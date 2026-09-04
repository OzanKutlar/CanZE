/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.
*/

package lu.fisch.canze.classes;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.Random;

import lu.fisch.canze.sound.CylinderPulse;
import lu.fisch.canze.sound.DcBlocker;
import lu.fisch.canze.sound.DelayLine;
import lu.fisch.canze.sound.DerivativeFilter;
import lu.fisch.canze.sound.ImpulseResponseFactory;
import lu.fisch.canze.sound.JitterFilter;
import lu.fisch.canze.sound.LevelingFilter;
import lu.fisch.canze.sound.LowPassFilter;
import lu.fisch.canze.sound.PartitionedConvolver;
import lu.fisch.canze.sound.SpeedObserver;

/**
 * Real time procedural cross plane V8 for an electric car.
 *
 * Two independent halves.
 *
 * The control half is EV specific. In this car the accelerator is not a throttle: the first part
 * of its travel trims regeneration at speed, and a light press at low speed commands enormous
 * torque. Pedal position therefore says almost nothing about how hard the drivetrain is working,
 * and it is ignored entirely while moving. Measured motor torque and road speed drive the sound
 * instead. The pedal only regains meaning when the car is stationary, where it becomes a rev
 * command against a virtual flywheel.
 *
 * The signal half is a port of the Angeliqe engine simulator synthesiser stage. A full gas
 * dynamics simulation is not viable on a phone, so the excitation is a synthesised cylinder
 * pulse train rather than solved cylinder pressure. Everything downstream of that is the real
 * chain: timing jitter, DC blocking, a derivative blend for the pulse edge, noise modulation,
 * convolution against an exhaust impulse response, and peak tracking level control.
 *
 * The convolution is uniformly partitioned in the frequency domain rather than direct form. The
 * output is identical; the cost is roughly two orders of magnitude lower, which is what makes
 * keeping it possible at all.
 *
 * Threading contract:
 *  - setInputs / setMuted / setMasterVolume may be called from any thread (volatile writes).
 *  - start() and stop() are synchronized and expected on the UI thread.
 *  - The AudioTrack is created, owned and released entirely inside the audio thread, so it can
 *    never be released underneath a blocking write().
 *  - Every buffer and filter is allocated in start(), never in the render loop.
 */
public class V8SoundEngine {

    private static final String TAG = "V8SoundEngine";

    public interface EngineListener {
        void onEngineStateChanged(float rpm, int gear);
    }

    private static final int SAMPLE_RATE = 44100;

    /**
     * Halved from the previous 1024 so the control layer runs at 86 Hz. Motor torque arrives at
     * roughly 20 Hz and the state machine has to be comfortably faster than its fastest input.
     */
    private static final int BUFFER_SIZE = 512;

    /** Convolution block. Must divide BUFFER_SIZE and be a power of two. */
    private static final int CONV_BLOCK = 256;

    /** About 93 ms of exhaust tail. Halve this first if a device cannot keep up. */
    private static final int IR_LENGTH = 4096;

    private static final String IR_ASSET = "v8_ir.wav";

    /**
     * Audio time represented by one buffer. Fixed by our own BUFFER_SIZE rather than by the
     * platform HAL, so the simulation advances identically on every device no matter how the
     * HAL paces write().
     */
    private static final float FRAME_DT = (float) BUFFER_SIZE / (float) SAMPLE_RATE; // 11.6 ms

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double CYCLE_RADIANS = CylinderPulse.CYCLE_RADIANS;

    // Virtual transmission. Nothing physical in the car corresponds to this, it exists purely so
    // the exhaust note sweeps and shifts the way a V8 does instead of tracking one motor rpm all
    // the way to 8000.
    private static final float[] GEAR_RATIOS = {3.45f, 2.15f, 1.52f, 1.12f, 0.86f, 0.68f};
    private static final float FINAL_DRIVE = 3.65f;
    private static final float BASE_IDLE_RPM = 780f;
    private static final float REDLINE_RPM = 6600f;

    // Real 5AM motor capability, used to normalise measured torque into an engine load.
    // Constant torque to the taper point, constant power (T proportional to 1/n) above it.
    private static final float MOTOR_PEAK_TORQUE_NM = 226f;
    private static final float MOTOR_TAPER_RPM = 3000f;

    /** Regeneration level treated as full overrun. */
    private static final float FULL_OVERRUN_NM = 80f;

    // The car is only treated as standing still when it is BOTH slow and not being driven.
    // Creep torque with the brake released clears this gate, so the engine picks up as the car
    // starts rolling even though the driver never touched the pedal.
    private static final float STATIONARY_SPEED_KMH = 2.5f;
    private static final float STATIONARY_TORQUE_NM = 5f;

    // Physical flywheel torque integration in neutral (dOmega/dt = T_net / J).
    private static final float NEUTRAL_REV_CEILING_RPM = 5200f;
    private static final float REV_DRIVE_ACCEL_RPM_S = 6800f;
    private static final float REV_NATURAL_DECEL_RPM_S = 1150f;

    // Rev limiter hysteresis ("bouncing off the limiter")
    private static final float LIMITER_CUT_DROP_RPM = 280f;
    private static final float LIMITER_CUT_DECEL_RPM_S = 4600f;
    private static final float LIMITER_CUT_MIN_TIME_S = 0.045f;

    // Minimum road speeds (km/h) required before upshifting into each gear [1->2 .. 5->6]
    private static final float[] MIN_UPSHIFT_SPEEDS = {0f, 28f, 50f, 70f, 88f, 99f};

    /** Minimum time between two shifts. Closes the 1<->2 hunting window. */
    private static final float SHIFT_LOCKOUT_S = 0.8f;

    // Cruise detection, in real units
    private static final float CRUISE_ACCEL_THRESHOLD = 1.2f; // km/h per second
    private static final float CRUISE_ENGAGE_S = 1.0f;
    private static final float CRUISE_RELEASE_S = 0.5f;

    // Oscillator rates in radians per second
    private static final double LOPE_RATE = 5.17;
    private static final double CRUISE_WANDER_RATE_1 = 3.66; // ~0.58 Hz
    private static final double CRUISE_WANDER_RATE_2 = 7.58; // ~1.21 Hz
    private static final double PHASE_WRAP = TWO_PI * 100.0;

    /**
     * Order of the sub bass reinforcement oscillator, relative to crank revolutions.
     *
     * The physically correct value is 4.0 (a four stroke V8 fires eight times per 720 degrees,
     * so four times per crank revolution). Do NOT "fix" this to 4.0. The oscillator is a pure
     * sine, and at 4.0 it lands exactly on the firing fundamental the pulse train already
     * produces (52 Hz at idle, 200 Hz at 3000 RPM), where a mathematically perfect tone tracking
     * engine speed reads as unmistakably synthetic. At 2.0 it sits at or below the high pass
     * corner and contributes felt weight without ever being audible as a tone.
     */
    private static final double SUB_BASS_ORDER = 2.0;

    /**
     * Acoustic propagation from the exhaust ports to the collector.
     *
     * BOTH BANKS ARE DELAYED BY THE SAME AMOUNT, so this is applied once to the collector sum
     * rather than twice before it. It is tempting to stagger the banks ("they carry different
     * cylinders, so they are different signals") but they are not: both are sums of the same
     * lobe shapes differing only in firing phase. Offsetting them adds that waveform to a
     * delayed copy of itself, which is a feedforward comb with notches at 44100/(2*offset) Hz
     * and odd multiples. Because those notches do not move with engine speed they stamp a fixed
     * metallic fingerprint over everything. A delay common to both banks is, correctly,
     * inaudible on its own, and only exists to place the pulse train correctly against the
     * convolved tail.
     */
    private static final int HEADER_DELAY = 64; // ~1.45 ms

    // Character controls. Interpolated by load between the idle and full load ends.
    private static final float DF_MIX_MIN = 0.10f;   // blend toward the pulse derivative
    private static final float DF_MIX_MAX = 0.42f;
    private static final float AIR_NOISE_MIN = 0.12f; // depth of noise amplitude modulation
    private static final float AIR_NOISE_MAX = 0.40f;
    private static final float CONV_MIN = 0.35f;      // convolution wet amount
    private static final float CONV_MAX = 0.60f;
    private static final float JITTER_MIN = 0.55f;    // timing irregularity, strongest at idle
    private static final float JITTER_MAX = 0.18f;

    // Decel pops.
    //
    // POP_RATE_HZ is a rate, converted to a per sample probability below. The previous version
    // compared a per sample random draw against a constant intended as a per frame probability,
    // which fired roughly 176 times a second and held the envelope permanently open. Combined
    // with an unfiltered noise source that produced continuous broadband static rather than
    // occasional pops.
    private static final float POP_RATE_HZ = 6.0f;
    private static final float POP_DECAY = 0.9981f;   // about 12 ms
    private static final float POP_LEVEL = 0.35f;
    private static final float POP_MIN_RPM = 1200f;   // no pops just above idle

    // Output stage, in normalised full scale units.
    private static final double PCM_FULL_SCALE = 31500.0;
    private static final double INTERNAL_DRIVE = 1.35;
    private static final double SOFT_KNEE = 0.8730;
    private static final double KNEE_WIDTH = 0.1111;
    private static final double PCM_HARD_CLAMP = 32000.0;

    /** Notify the gauge at ~10.8 Hz rather than at the 86 Hz control rate. */
    private static final int LISTENER_DIVIDER = 8;

    /** Base levelling target. Scaled down on overrun so quiet stays quiet. */
    private static final float LEVEL_TARGET = 0.70f;

    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;
    private volatile float masterVolume = 1.0f;

    private AssetManager assetManager = null;

    // Telemetry inputs
    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Control state
    private final SpeedObserver observer = new SpeedObserver();
    private float currentRpm = BASE_IDLE_RPM;
    private int currentGear = 1;
    private float currentThrottle = 0f;
    private float currentTorqueNm = 0f;
    private float currentSpeedKmH = 0f;
    private float cruiseTimer = 0f; // normalised 0..1
    private float shiftLockout = 0f;
    private float effectiveLoad = 0f;
    private float overrunAmount = 0f;
    private float downshiftBlip = 0f;
    private float targetShiftCut = 1.0f;
    private float smoothedShiftCut = 1.0f;
    private boolean isLimiterCut = false;
    private float limiterTimer = 0f;
    private float targetLimiterCut = 1.0f;
    private float smoothedLimiterCut = 1.0f;
    private int listenerCounter = 0;

    // Organic wander
    private float pedalWanderTarget = 0f;
    private float pedalWanderSmoothed = 0f;
    private float rpmWanderTarget = 0f;
    private float rpmWanderSmoothed = 0f;
    private double cruiseWanderPhase1 = 0.0;
    private double cruiseWanderPhase2 = 0.0;
    private double crankCycleFlutter = 1.0;

    // Oscillators
    private double crankPhase = 0.0;
    private double lopePhase = 0.0;
    private double subBassPhase = 0.0;

    // Signal chain, allocated in start()
    private final JitterFilter bank1Jitter = new JitterFilter();
    private final JitterFilter bank2Jitter = new JitterFilter();
    private final DcBlocker bank1Dc = new DcBlocker();
    private final DcBlocker bank2Dc = new DcBlocker();
    private final DerivativeFilter bank1Derivative = new DerivativeFilter();
    private final DerivativeFilter bank2Derivative = new DerivativeFilter();
    private final LowPassFilter bank1AirNoise = new LowPassFilter();
    private final LowPassFilter bank2AirNoise = new LowPassFilter();
    private final LowPassFilter intakeNoise = new LowPassFilter();
    private final LowPassFilter popFilter = new LowPassFilter();
    private final LowPassFilter antiAlias = new LowPassFilter();
    private final DelayLine collectorDelay = new DelayLine();
    private final LevelingFilter leveling = new LevelingFilter();

    private PartitionedConvolver convolver = null;
    private float[] dryBuffer = null;
    private float[] wetBuffer = null;

    private float popEnvelope = 0f;
    private static final float popProbabilityPerSample = POP_RATE_HZ / (float) SAMPLE_RATE;
    private float smoothedMasterVolume = 0f;

    private final Random rng = new Random();
    private EngineListener engineListener;

    public V8SoundEngine() {
    }

    /**
     * Preferred constructor. Supplying a context lets an impulse response asset override the
     * synthesised one; without it the synthesised exhaust is always used.
     *
     * @param context any context, only the application context is retained
     */
    public V8SoundEngine(Context context) {
        if (context != null) {
            this.assetManager = context.getApplicationContext().getAssets();
        }
    }

    public void setAssetManager(AssetManager assetManager) {
        this.assetManager = assetManager;
    }

    public void setEngineListener(EngineListener listener) {
        this.engineListener = listener;
    }

    public void setInputs(float speedKmH, float pedalPerc, float torqueNm) {
        if (Float.isNaN(speedKmH) || Float.isNaN(pedalPerc) || Float.isNaN(torqueNm)) return;
        this.targetSpeedKmH = Math.max(0f, speedKmH);
        this.targetPedalPerc = Math.max(0f, Math.min(100f, pedalPerc));
        this.targetTorqueNm = Math.max(-250f, Math.min(450f, torqueNm));
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setMasterVolume(float volume) {
        if (Float.isNaN(volume)) return;
        this.masterVolume = Math.max(0.0f, Math.min(3.0f, volume));
    }

    public float getMasterVolume() {
        return masterVolume;
    }

    public synchronized void start() {
        if (isRunning) return;

        try {
            buildSignalChain();
        } catch (Exception e) {
            Log.e(TAG, "could not build signal chain", e);
            return;
        }

        isRunning = true;
        smoothedMasterVolume = 0f;

        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                audioLoop();
            }
        }, "V8AudioSynthesizer");
        audioThread.start();
    }

    /**
     * Signals the audio thread to finish and waits for it. The AudioTrack is released by the
     * audio thread itself, so there is no window in which it can be freed while a write() is
     * still in flight.
     */
    public synchronized void stop() {
        isRunning = false;
        Thread thread = audioThread;
        audioThread = null;
        if (thread == null) return;
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------ chain set up */

    /**
     * Allocates every buffer and filter. Called from start() so the render loop never allocates,
     * which is what keeps the garbage collector out of the audio path.
     */
    private void buildSignalChain() {
        final float fs = SAMPLE_RATE;

        bank1Jitter.initialize(12, 3.0f, fs);
        bank2Jitter.initialize(12, 3.7f, fs);

        bank1Dc.initialize(12f, fs);
        bank2Dc.initialize(12f, fs);

        bank1Derivative.setGain(6f);
        bank2Derivative.setGain(6f);

        bank1AirNoise.setCutoff(1500f, fs);
        bank2AirNoise.setCutoff(1650f, fs);
        intakeNoise.setCutoff(420f, fs);

        // Band limits the pop into a thump. An exhaust bang has its energy in the low mids; a
        // full bandwidth burst reads as a spark or a click rather than as combustion.
        popFilter.setCutoff(900f, fs);

        // A real corner rather than the reference 0.45 * fs, which sits so close to Nyquist it
        // filters nothing. There is no engine content above this, and every noise source in the
        // chain benefits from losing its top octave.
        antiAlias.setCutoff(7500f, fs);

        collectorDelay.initialize(HEADER_DELAY + 8);
        collectorDelay.setDelay(HEADER_DELAY);

        leveling.reset();
        leveling.setTarget(LEVEL_TARGET);

        // The old 8.0 ceiling let the leveller recover the full amplitude drop of the overrun
        // combustion gate, which meant boosting the residual intake noise by the same factor.
        // 2.5 keeps idle audible without ever making the noise floor into the loudest thing.
        leveling.setRange(0.05f, 2.5f);

        final float[] ir = ImpulseResponseFactory.create(assetManager, IR_ASSET, IR_LENGTH, fs);
        convolver = new PartitionedConvolver(ir, CONV_BLOCK);

        dryBuffer = new float[BUFFER_SIZE];
        wetBuffer = new float[BUFFER_SIZE];

        observer.reset(targetSpeedKmH);
        currentSpeedKmH = targetSpeedKmH;
    }

    /* ---------------------------------------------------------------- audio */

    private void audioLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception e) {
            Log.w(TAG, "could not raise audio thread priority", e);
        }

        AudioTrack track = null;
        try {
            track = createTrack();
            if (track == null) return;
            track.play();
            renderLoop(track);
        } catch (Exception e) {
            Log.e(TAG, "audio loop aborted", e);
        } finally {
            releaseTrack(track);
            isRunning = false;
        }
    }

    private AudioTrack createTrack() {
        int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: " + minBuf);
            return null;
        }

        int bufSize = Math.max(minBuf, BUFFER_SIZE * 4);
        AudioTrack track;
        try {
            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
            );
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack construction failed", e);
            return null;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack did not initialise");
            releaseTrack(track);
            return null;
        }
        return track;
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try {
            track.stop();
        } catch (Exception ignored) {
        }
        try {
            track.release();
        } catch (Exception ignored) {
        }
    }

    private void renderLoop(AudioTrack track) {
        final short[] out = new short[BUFFER_SIZE];
        while (isRunning) {
            updateControl();
            renderExcitation(dryBuffer);
            applyConvolution(dryBuffer, wetBuffer);
            finishOutput(dryBuffer, wetBuffer, out);

            final int written = track.write(out, 0, BUFFER_SIZE);
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed: " + written);
                return;
            }
        }
    }

    /* ------------------------------------------------------------- synthesis */

    /**
     * Renders one buffer of dry excitation: the cylinder pulse train through jitter, DC removal,
     * derivative blending and noise modulation, plus the intake layer and sub bass.
     */
    private void renderExcitation(float[] dry) {
        final float load = effectiveLoad;
        final float overrun = overrunAmount;

        final double crankRadPerSample =
                ((currentRpm * crankCycleFlutter) / 60.0) * TWO_PI / SAMPLE_RATE;
        final double firingRadPerSample = crankRadPerSample * SUB_BASS_ORDER;

        final float combustion = 0.35f + load * 0.95f;
        final float mix = DF_MIX_MIN + load * (DF_MIX_MAX - DF_MIX_MIN);
        final float airNoise = AIR_NOISE_MIN + load * (AIR_NOISE_MAX - AIR_NOISE_MIN);
        final float intakeLevel = 0.0f; // (BYPASSED FOR TEST 3)
        final float subLevel = 0.35f + load * 0.25f;
        final float targetMaster = isMuted ? 0f : masterVolume;

        // Jitter is strongest at idle: a loping engine is audibly irregular, a loaded one is not.
        bank1Jitter.setScale(JITTER_MIN + load * (JITTER_MAX - JITTER_MIN));
        bank2Jitter.setScale(JITTER_MIN + load * (JITTER_MAX - JITTER_MIN));

        for (int i = 0; i < BUFFER_SIZE; i++) {
            crankPhase += crankRadPerSample;
            if (crankPhase >= CYCLE_RADIANS) {
                crankPhase -= CYCLE_RADIANS;
                // Cycle to cycle combustion variance
                crankCycleFlutter = 1.0 + (rng.nextDouble() - 0.5) * 0.007;
            }

            subBassPhase += firingRadPerSample;
            if (subBassPhase >= TWO_PI) subBassPhase -= TWO_PI;

            smoothedShiftCut += (targetShiftCut - smoothedShiftCut) * 0.008f;
            smoothedLimiterCut += (targetLimiterCut - smoothedLimiterCut) * 0.035f;
            smoothedMasterVolume += (targetMaster - smoothedMasterVolume) * 0.008f;

            // Under regeneration combustion largely stops. Collapsing the pulse amplitude while
            // leaving the pipe resonance intact is what produces overrun rather than silence.
            final float gate = smoothedShiftCut * smoothedLimiterCut * (1f - 0.75f * overrun);

            float b1 = (float) CylinderPulse.bankOne(crankPhase) * combustion * gate;
            float b2 = (float) CylinderPulse.bankTwo(crankPhase) * combustion * gate;

            b1 = shapeBank(b1, bank1Jitter, bank1Dc, bank1Derivative, bank1AirNoise, mix, airNoise);
            b2 = shapeBank(b2, bank2Jitter, bank2Dc, bank2Derivative, bank2AirNoise, mix, airNoise);

            final float collector = 0.5f * (b1 + b2);
            final float piped = collectorDelay.f(collector);

            // Decel pops: unburnt mixture igniting in a hot pipe on the overrun.
            //
            // The trigger probability is a rate divided by the sample rate, so POP_RATE_HZ means
            // what it says no matter what buffer or sample rate this runs at.
            popEnvelope *= POP_DECAY;
            if (overrun > 0.05f && currentRpm > POP_MIN_RPM
                    && rng.nextFloat() < overrun * popProbabilityPerSample) {
                popEnvelope = overrun * POP_LEVEL;
            }
            // Enveloped noise through a low pass, so each event is a band limited crack instead
            // of a burst of hiss.
            final float pop = popFilter.f(popEnvelope * (2f * rng.nextFloat() - 1f));

            final float intake = intakeNoise.f(2f * rng.nextFloat() - 1f) * intakeLevel;
            final float sub = (float) Math.sin(subBassPhase) * subLevel;

            dry[i] = piped + pop + intake + sub;
        }
    }

    /**
     * One bank of the engine simulator per channel chain.
     *
     * @param mix      weight of the derivative against the raw pulse, 0 is all pulse
     * @param airNoise depth of the noise amplitude modulation, 0 disables it
     */
    private float shapeBank(float x,
                            JitterFilter jitter,
                            DcBlocker dc,
                            DerivativeFilter derivative,
                            LowPassFilter noiseFilter,
                            float mix,
                            float airNoise) {
        final float jittered = jitter.f(x);
        final float centred = dc.f(jittered);
        final float slope = derivative.f(centred);

        final float blended = centred * (1f - mix) + slope * mix;

        final float noise = noiseFilter.f(2f * rng.nextFloat() - 1f);
        final float modulator = airNoise * noise + (1f - airNoise);

        return blended * modulator;
    }

    /**
     * Runs the buffer through the partitioned convolver in whole blocks. Output is time aligned
     * with the input, so summing the dry path against it introduces no comb filtering.
     */
    private void applyConvolution(float[] dry, float[] wet) {
        if (convolver == null) {
            System.arraycopy(dry, 0, wet, 0, BUFFER_SIZE);
            return;
        }
        for (int offset = 0; offset + CONV_BLOCK <= BUFFER_SIZE; offset += CONV_BLOCK) {
            convolveBlock(dry, wet, offset);
        }
    }

    /**
     * Copies one block in and out around the fixed size convolver call. CONV_BLOCK is small
     * enough that the two copies stay well inside budget, and it keeps the convolver interface
     * free of offsets.
     */
    private void convolveBlock(float[] dry, float[] wet, int offset) {
        System.arraycopy(dry, offset, blockScratchIn, 0, CONV_BLOCK);
        convolver.process(blockScratchIn, blockScratchOut);
        System.arraycopy(blockScratchOut, 0, wet, offset, CONV_BLOCK);
    }

    private final float[] blockScratchIn = new float[CONV_BLOCK];
    private final float[] blockScratchOut = new float[CONV_BLOCK];

    /**
     * Wet/dry blend, level control, saturation and conversion to PCM.
     */
    private void finishOutput(float[] dry, float[] wet, short[] out) {
        final float conv = CONV_MIN + effectiveLoad * (CONV_MAX - CONV_MIN);
        final float dryAmount = 1f - conv;

        // Overrun is meant to be quieter than full load. Holding a constant target told the
        // leveller to undo that, so it went looking for whatever was left to amplify, which on
        // the overrun is the intake noise floor.
        leveling.setTarget(LEVEL_TARGET * (1f - 0.45f * overrunAmount));

        for (int i = 0; i < BUFFER_SIZE; i++) {
            float v = conv * wet[i] + dryAmount * dry[i];

            v = leveling.f(v);
            v = antiAlias.f(v);

            double shaped = v * smoothedMasterVolume * INTERNAL_DRIVE;
            shaped = softKnee(shaped);

            double pcm = shaped * PCM_FULL_SCALE;
            if (pcm > PCM_HARD_CLAMP) pcm = PCM_HARD_CLAMP;
            if (pcm < -PCM_HARD_CLAMP) pcm = -PCM_HARD_CLAMP;

            out[i] = (short) pcm;
        }
    }

    /** Soft knee then hard clip, both at unity full scale. */
    private static double softKnee(double x) {
        if (x > SOFT_KNEE) {
            x = SOFT_KNEE + KNEE_WIDTH * Math.tanh((x - SOFT_KNEE) / KNEE_WIDTH);
        } else if (x < -SOFT_KNEE) {
            x = -SOFT_KNEE + KNEE_WIDTH * Math.tanh((x + SOFT_KNEE) / KNEE_WIDTH);
        }
        if (x > 1.0) return 1.0;
        if (x < -1.0) return -1.0;
        return x;
    }

    /* -------------------------------------------------------------- control */

    /**
     * Advances the control layer by exactly FRAME_DT of audio time, which is fixed by
     * BUFFER_SIZE and therefore identical on every device.
     */
    private void updateControl() {
        applyInputSmoothing();

        observer.predict(currentTorqueNm, FRAME_DT);
        observer.correct(targetSpeedKmH, FRAME_DT);
        currentSpeedKmH = observer.getSpeedKmH();

        updateLoadAndCruise();
        advanceWanderOscillators();

        if (!isStationary()) {
            runGearedDrive();
        } else if (currentThrottle < 0.05f) {
            runIdle();
        } else {
            runNeutralRev();
        }

        notifyListener();
    }

    private void applyInputSmoothing() {
        final float rawThrottle = targetPedalPerc / 100.0f;

        // Driver foot and linkage micro tremor
        if (rng.nextFloat() < 0.15f) {
            pedalWanderTarget = (rng.nextFloat() - 0.5f) * 0.012f;
        }
        pedalWanderSmoothed += (pedalWanderTarget - pedalWanderSmoothed) * 0.08f;
        final float tremor = (rawThrottle > 0.02f) ? pedalWanderSmoothed : 0.0f;
        final float throttleWithTremor = Math.max(0.0f, Math.min(1.0f, rawThrottle + tremor));

        final float throttleDelta = throttleWithTremor - currentThrottle;
        currentThrottle += throttleDelta * (throttleDelta > 0f ? 0.45f : 0.16f);

        final float torqueDelta = targetTorqueNm - currentTorqueNm;
        currentTorqueNm += torqueDelta * (torqueDelta > 0f ? 0.40f : 0.16f);

        downshiftBlip *= 0.90f;
        targetShiftCut += (1.0f - targetShiftCut) * 0.10f;
        if (shiftLockout > 0f) shiftLockout -= FRAME_DT;
    }

    /**
     * Cruise detection now uses the observer's acceleration, which is a real derivative at the
     * control rate rather than a differentiated one hertz integer.
     */
    private void updateLoadAndCruise() {
        final float accel = observer.getAccelKmHPerSec();

        final boolean cruising = Math.abs(accel) < CRUISE_ACCEL_THRESHOLD && currentSpeedKmH > 25.0f;
        if (cruising) {
            cruiseTimer = Math.min(1.0f, cruiseTimer + FRAME_DT / CRUISE_ENGAGE_S);
        } else {
            cruiseTimer = Math.max(0.0f, cruiseTimer - FRAME_DT / CRUISE_RELEASE_S);
        }

        effectiveLoad = computeLoad() * (1.0f - cruiseTimer * 0.45f);

        float overrun = -currentTorqueNm / FULL_OVERRUN_NM;
        if (overrun < 0f) overrun = 0f;
        if (overrun > 1f) overrun = 1f;
        overrunAmount = overrun;
    }

    /**
     * True when the car is genuinely at rest and nothing is driving the wheels. Requires low
     * torque as well as low speed, otherwise creeping away on motor torque alone would keep
     * reporting idle.
     */
    private boolean isStationary() {
        return currentSpeedKmH < STATIONARY_SPEED_KMH
                && Math.abs(currentTorqueNm) < STATIONARY_TORQUE_NM;
    }

    /**
     * @return motor torque available at the current motor speed, in Nm
     */
    private float availableTorqueNm() {
        final float motorRpm = SpeedObserver.motorRpm(currentSpeedKmH);
        if (motorRpm <= MOTOR_TAPER_RPM) return MOTOR_PEAK_TORQUE_NM;
        return MOTOR_PEAK_TORQUE_NM * MOTOR_TAPER_RPM / motorRpm;
    }

    /**
     * Engine load, 0..1.
     *
     * Standing still the pedal is the only meaningful signal, and it is what makes blipping the
     * throttle satisfying. Once moving the pedal is only a request whose meaning changes with
     * speed, so measured torque governs instead, normalised against what the motor can actually
     * deliver at this speed. Without that normalisation the same pedal position sounds strained
     * at high speed purely because field weakening has cut the available torque.
     *
     * Negative torque returns zero, letting the overrun path take over.
     */
    private float computeLoad() {
        if (isStationary()) return currentThrottle;
        final float available = availableTorqueNm();
        if (available <= 0f) return 0f;
        final float load = Math.max(0f, currentTorqueNm) / available;
        return Math.min(1.0f, load);
    }

    private void advanceWanderOscillators() {
        lopePhase += LOPE_RATE * FRAME_DT;
        cruiseWanderPhase1 += CRUISE_WANDER_RATE_1 * FRAME_DT;
        cruiseWanderPhase2 += CRUISE_WANDER_RATE_2 * FRAME_DT;
        if (lopePhase > PHASE_WRAP) lopePhase -= PHASE_WRAP;
        if (cruiseWanderPhase1 > PHASE_WRAP) cruiseWanderPhase1 -= PHASE_WRAP;
        if (cruiseWanderPhase2 > PHASE_WRAP) cruiseWanderPhase2 -= PHASE_WRAP;
    }

    private void runIdle() {
        currentGear = 0;
        isLimiterCut = false;
        targetLimiterCut = 1.0f;

        final float lope = (float) (Math.sin(lopePhase) * 25.0 + Math.cos(lopePhase * 0.65) * 18.0);
        final float targetIdle = BASE_IDLE_RPM + lope;
        currentRpm += (targetIdle - currentRpm) * 0.07f;
    }

    /**
     * Stationary revving. This is the only place the pedal drives the sound, because it is the
     * only situation in this car where pedal position and driver intent line up.
     */
    private void runNeutralRev() {
        currentGear = 0;

        final float throttle = currentThrottle;

        if (throttle > 0.04f) {
            if (currentRpm >= NEUTRAL_REV_CEILING_RPM) {
                isLimiterCut = true;
                limiterTimer = 0f;
            }

            if (isLimiterCut) {
                limiterTimer += FRAME_DT;
                targetLimiterCut = 0.20f;
                currentRpm -= LIMITER_CUT_DECEL_RPM_S * FRAME_DT;

                if (currentRpm <= NEUTRAL_REV_CEILING_RPM - LIMITER_CUT_DROP_RPM
                        && limiterTimer >= LIMITER_CUT_MIN_TIME_S) {
                    isLimiterCut = false;
                    targetLimiterCut = 1.0f;
                }
            } else {
                targetLimiterCut = 1.0f;

                final float effort = (float) Math.pow(throttle, 1.25);
                final float driveAccel = effort * REV_DRIVE_ACCEL_RPM_S;
                final float friction =
                        (currentRpm / NEUTRAL_REV_CEILING_RPM) * (REV_DRIVE_ACCEL_RPM_S * 0.18f);

                currentRpm += (driveAccel - friction) * FRAME_DT;
            }
        } else {
            isLimiterCut = false;
            targetLimiterCut = 1.0f;

            final float idleProximity =
                    Math.max(0.0f, Math.min(1.0f, (currentRpm - BASE_IDLE_RPM) / 650.0f));
            final float cushion = 0.50f + (idleProximity * 0.50f);
            final float decel = REV_NATURAL_DECEL_RPM_S
                    * (1.0f + (currentRpm / NEUTRAL_REV_CEILING_RPM) * 0.4f) * cushion;

            currentRpm -= decel * FRAME_DT;
        }

        if (currentRpm < BASE_IDLE_RPM) {
            currentRpm = BASE_IDLE_RPM;
        }
    }

    private void runGearedDrive() {
        final float wheelRpm =
                (currentSpeedKmH * 1000f) / (SpeedObserver.WHEEL_CIRCUMFERENCE_M * 60f);
        if (currentGear == 0) currentGear = 1;

        // Load delayed upshift schedule. Driven by measured torque rather than pedal, so gear
        // holding responds to what the car is actually doing.
        final float baseUpshift = 2400f - (cruiseTimer * 300f);
        final float aggression = (float) Math.pow(effectiveLoad, 1.15);
        float upshiftRpm = baseUpshift + (aggression * 3900f);
        if (upshiftRpm > REDLINE_RPM - 400f) upshiftRpm = REDLINE_RPM - 400f;

        evaluateShift(wheelRpm, upshiftRpm);

        final float converterSlip = 1.018f + (effectiveLoad * 0.024f);

        if (rng.nextFloat() < 0.12f) {
            rpmWanderTarget = (rng.nextFloat() - 0.5f) * 26.0f;
        }
        rpmWanderSmoothed += (rpmWanderTarget - rpmWanderSmoothed) * 0.03f;

        final float breathe = (float) (Math.sin(cruiseWanderPhase1) * 14.0
                + Math.cos(cruiseWanderPhase2) * 8.0);

        float target = (wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] * converterSlip)
                + downshiftBlip
                + breathe
                + rpmWanderSmoothed;

        if (target < BASE_IDLE_RPM) target = BASE_IDLE_RPM;

        if (target >= REDLINE_RPM || currentRpm >= REDLINE_RPM) {
            if (currentRpm >= REDLINE_RPM) {
                isLimiterCut = true;
                limiterTimer = 0f;
            }

            if (isLimiterCut) {
                limiterTimer += FRAME_DT;
                targetLimiterCut = 0.22f;
                currentRpm -= LIMITER_CUT_DECEL_RPM_S * FRAME_DT;
                if (currentRpm <= REDLINE_RPM - LIMITER_CUT_DROP_RPM
                        && limiterTimer >= LIMITER_CUT_MIN_TIME_S) {
                    isLimiterCut = false;
                    targetLimiterCut = 1.0f;
                }
            } else {
                targetLimiterCut = 1.0f;
                currentRpm += (REDLINE_RPM - currentRpm) * 0.18f;
            }
        } else {
            isLimiterCut = false;
            targetLimiterCut = 1.0f;
            currentRpm += (target - currentRpm) * 0.12f;
        }
    }

    private void evaluateShift(float wheelRpm, float upshiftRpm) {
        if (shiftLockout > 0f) return;

        final float gearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];
        final float minSpeedForNext =
                (currentGear < 6) ? MIN_UPSHIFT_SPEEDS[currentGear] : Float.MAX_VALUE;

        if (gearRpm > upshiftRpm && currentGear < 6 && currentSpeedKmH >= minSpeedForNext) {
            currentGear++;
            targetShiftCut = 0.82f;
            shiftLockout = SHIFT_LOCKOUT_S;
            return;
        }

        // Downshifts happen only while coasting or regenerating. In this car there is no power
        // downshift to model, and gating on load rather than pedal is what distinguishes a
        // released pedal at speed (regeneration) from a released pedal at rest (creep).
        final float downshiftRpm = 1500f;
        if (gearRpm < downshiftRpm && currentGear > 1 && effectiveLoad < 0.12f) {
            final float after = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
            if (after < REDLINE_RPM - 1200f) {
                currentGear--;
                downshiftBlip = 180f;
                shiftLockout = SHIFT_LOCKOUT_S;
            }
        }
    }

    /**
     * Fires at ~10.8 Hz. The gauge cannot show more than that anyway, and posting control rate
     * runnables to the UI looper competes with the OBD callbacks.
     */
    private void notifyListener() {
        final EngineListener listener = engineListener;
        if (listener == null) return;
        listenerCounter++;
        if (listenerCounter < LISTENER_DIVIDER) return;
        listenerCounter = 0;
        listener.onEngineStateChanged(currentRpm, currentGear);
    }
}
