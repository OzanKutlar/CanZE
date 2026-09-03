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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.Random;

/**
 * Deep-rumble real-time procedural cross-plane V8 engine sound synthesizer.
 *
 * Generates low-end acoustic blowdown waves (50 - 250 Hz), crossplane bank offset burble
 * (firing order 1-8-4-3-6-5-7-2), asymmetric header delay lines feeding an X-pipe crossover,
 * muffler cavity acoustics, and a torque-coupled six speed virtual transmission.
 *
 * Threading contract:
 *  - setInputs / setMuted / setMasterVolume may be called from any thread (volatile writes).
 *  - start() and stop() are synchronized and expected on the UI thread.
 *  - The AudioTrack is created, owned and released entirely inside the audio thread, so it can
 *    never be released underneath a blocking write().
 */
public class V8SoundEngine {

    private static final String TAG = "V8SoundEngine";

    public interface EngineListener {
        void onEngineStateChanged(float rpm, int gear);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 1024;

    /**
     * Audio time represented by one buffer. Fixed by our own BUFFER_SIZE rather than by the
     * platform HAL, so the simulation advances identically on every device no matter how the
     * HAL paces write().
     */
    private static final float FRAME_DT = (float) BUFFER_SIZE / (float) SAMPLE_RATE; // 23.22 ms

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double CYCLE_RADIANS = 4.0 * Math.PI; // 720 degrees

    // Cross-plane V8 firing order: 1-8-4-3-6-5-7-2
    // Bank 1 (Left):  Cyl 1 (0deg),  Cyl 3 (270deg), Cyl 5 (450deg), Cyl 7 (540deg)
    // Bank 2 (Right): Cyl 8 (90deg), Cyl 4 (180deg), Cyl 6 (360deg), Cyl 2 (630deg)
    private static final double[] CYLINDER_FIRING_ANGLES = {
            0.0 * Math.PI / 180.0,   // Cyl 1 (Bank 1)
            630.0 * Math.PI / 180.0, // Cyl 2 (Bank 2)
            270.0 * Math.PI / 180.0, // Cyl 3 (Bank 1)
            180.0 * Math.PI / 180.0, // Cyl 4 (Bank 2)
            450.0 * Math.PI / 180.0, // Cyl 5 (Bank 1)
            360.0 * Math.PI / 180.0, // Cyl 6 (Bank 2)
            540.0 * Math.PI / 180.0, // Cyl 7 (Bank 1)
            90.0 * Math.PI / 180.0   // Cyl 8 (Bank 2)
    };

    // Transmission gear ratios (1st to 6th) - tuned for authentic V8 muscle spacing
    private static final float[] GEAR_RATIOS = {3.45f, 2.15f, 1.52f, 1.12f, 0.86f, 0.68f};
    private static final float FINAL_DRIVE = 3.65f;
    private static final float WHEEL_CIRCUMFERENCE_M = 1.95f;
    private static final float BASE_IDLE_RPM = 780f;
    private static final float REDLINE_RPM = 6600f;

    // Minimum road speeds (km/h) required before upshifting into each gear [1->2 .. 5->6]
    private static final float[] MIN_UPSHIFT_SPEEDS = {0f, 28f, 50f, 70f, 88f, 99f};

    /** Minimum time between two shifts. Closes the 1<->2 hunting window. */
    private static final float SHIFT_LOCKOUT_S = 0.8f;

    // Cruise detection, in real units rather than per-buffer deltas
    private static final float CRUISE_ACCEL_THRESHOLD = 1.2f; // km/h per second
    private static final float CRUISE_ENGAGE_S = 1.0f;
    private static final float CRUISE_RELEASE_S = 0.5f;

    // Oscillator rates in radians per second (were per-buffer increments)
    private static final double LOPE_RATE = 5.17;
    private static final double CRUISE_WANDER_RATE_1 = 3.66; // ~0.58 Hz
    private static final double CRUISE_WANDER_RATE_2 = 7.58; // ~1.21 Hz
    private static final double PHASE_WRAP = TWO_PI * 100.0; // multiples of 0.65 stay continuous

    /**
     * Sub-bass oscillator order. Kept at 2.0 to place the sub-harmonic deep in the
     * 25 - 60 Hz chest-thumping bass pocket without high-pitched drone.
     */
    private static final double SUB_BASS_ORDER = 2.0;

    // Acoustic propagation delay lines (~1.45 ms buffer)
    private static final int DELAY_SAMPLES = 64;
    private final double[] bank1Delay = new double[DELAY_SAMPLES];
    private final double[] bank2Delay = new double[DELAY_SAMPLES];
    private int delayIdx = 0;

    // Precomputed blowdown pulse shape: sin^2(pi*x) * exp(-0.85x) over 170 degrees of crank.
    // Evaluating this analytically cost ~353k sin+exp calls per second.
    private static final int PULSE_TABLE_SIZE = 2048;
    private static final double PULSE_DURATION_RAD = 170.0 * Math.PI / 180.0;
    private static final double[] PULSE_TABLE = new double[PULSE_TABLE_SIZE + 1];

    static {
        for (int i = 0; i <= PULSE_TABLE_SIZE; i++) {
            double x = (double) i / (double) PULSE_TABLE_SIZE;
            double s = Math.sin(x * Math.PI);
            PULSE_TABLE[i] = s * s * Math.exp(-0.85 * x);
        }
    }

    private static final double PCM_HARD_CLAMP = 31500.0;

    /** Notify the gauge at ~10.8 Hz instead of ~43 Hz. */
    private static final int LISTENER_DIVIDER = 4;

    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;
    private volatile float masterVolume = 1.0f;

    // Telemetry inputs
    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Dynamic simulation state
    private float currentRpm = BASE_IDLE_RPM;
    private int currentGear = 1;
    private float currentThrottle = 0f;
    private float currentTorqueNm = 0f;
    private float currentSpeedKmH = 0f;
    private float prevSpeedKmH = 0f;
    private float accelKmHPerSec = 0f;
    private float cruiseTimer = 0f; // normalised 0..1
    private float shiftLockout = 0f;
    private float effectiveLoad = 0f;
    private float downshiftBlip = 0f;
    private float targetShiftCut = 1.0f;
    private float smoothedShiftCut = 1.0f;
    private int listenerCounter = 0;

    // Organic wander & micro-noise state
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

    // Acoustic filter states
    private double bank1Lp = 0.0;
    private double bank2Lp = 0.0;
    private double mufflerLp = 0.0;
    private double bodyResonator = 0.0;
    private double hpIn = 0.0;
    private double hpOut = 0.0;

    // Output smoothing
    private float smoothedVolume = 0f;
    private float smoothedMasterVolume = 0f;

    private final Random rng = new Random();
    private EngineListener engineListener;

    public V8SoundEngine() {
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
        isRunning = true;

        smoothedVolume = 0f;
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
        short[] buffer = new short[BUFFER_SIZE];
        while (isRunning) {
            updateTransmission();
            renderBuffer(buffer);
            int written = track.write(buffer, 0, BUFFER_SIZE);
            if (written < 0) {
                Log.e(TAG, "AudioTrack.write failed: " + written);
                return;
            }
        }
    }

    private void renderBuffer(short[] buffer) {
        final float rpm = currentRpm;
        final float load = effectiveLoad;
        final float torque = currentTorqueNm;

        final double crankRadPerSample =
                ((rpm * crankCycleFlutter) / 60.0) * TWO_PI / SAMPLE_RATE;
        final double firingRadPerSample = crankRadPerSample * SUB_BASS_ORDER;

        float targetVolume;
        if (isMuted) {
            targetVolume = 0.0f;
        } else {
            targetVolume = 0.85f + (load * 0.40f);
            if (torque < -10f) {
                // Pronounced engine braking compression sound during regen
                targetVolume = Math.max(targetVolume, 0.90f);
            }
        }
        final float targetMaster = isMuted ? 0.0f : masterVolume;

        final double pulseAmplitude = 0.70 + (load * 0.75);
        final double subBassAmplitude = 0.35 + (load * 0.25);
        final double drive = 1.35 + (load * 1.65);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            crankPhase += crankRadPerSample;
            if (crankPhase >= CYCLE_RADIANS) {
                crankPhase -= CYCLE_RADIANS;
                // Cylinder to cylinder combustion variance: micro-flutter per 720 degree cycle
                crankCycleFlutter = 1.0 + (rng.nextDouble() - 0.5) * 0.007;
            }

            subBassPhase += firingRadPerSample;
            if (subBassPhase >= TWO_PI) subBassPhase -= TWO_PI;

            smoothedVolume += (targetVolume - smoothedVolume) * 0.005f;
            smoothedShiftCut += (targetShiftCut - smoothedShiftCut) * 0.008f;
            smoothedMasterVolume += (targetMaster - smoothedMasterVolume) * 0.008f;

            // 1. Smooth acoustic blowdown pressure lobes, summed per bank
            double bank1Raw = pulse(crankPhase, CYLINDER_FIRING_ANGLES[0])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[2])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[4])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[6]);
            double bank2Raw = pulse(crankPhase, CYLINDER_FIRING_ANGLES[7])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[3])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[5])
                    + pulse(crankPhase, CYLINDER_FIRING_ANGLES[1]);
            bank1Raw *= pulseAmplitude;
            bank2Raw *= pulseAmplitude;

            // 2. Primary header tube acoustic propagation delay
            bank1Delay[delayIdx] = bank1Raw;
            bank2Delay[delayIdx] = bank2Raw;
            delayIdx = (delayIdx + 1) % DELAY_SAMPLES;
            double b1Delayed = bank1Delay[delayIdx];
            double b2Delayed = bank2Delay[delayIdx];

            // 3. Exhaust manifold acoustic low-pass (rolls off harsh content above ~950 Hz)
            bank1Lp += (b1Delayed - bank1Lp) * 0.14;
            bank2Lp += (b2Delayed - bank2Lp) * 0.14;

            // 4. X-Pipe Crossover (75% direct bank + 25% crossover balance)
            double leftPipe = bank1Lp * 0.75 + bank2Lp * 0.25;
            double rightPipe = bank2Lp * 0.75 + bank1Lp * 0.25;
            double exhaustMix = (leftPipe + rightPipe) * 0.50;

            // 5. Muffler cavity resonance
            bodyResonator += (exhaustMix - bodyResonator) * 0.035;
            mufflerLp += (exhaustMix - mufflerLp) * 0.09;
            double deepTone = (exhaustMix * 0.70) + (bodyResonator * 0.60) + (mufflerLp * 0.40);

            // 6. Deep sub-bass foundation (25 - 60 Hz)
            double composite = deepTone + Math.sin(subBassPhase) * subBassAmplitude;

            // 7. Subsonic high-pass (~22 Hz), removes DC without touching audible bass
            hpOut = 0.9965 * (hpOut + composite - hpIn);
            hpIn = composite;

            // 8. Warm tube style saturation: rich 2nd/3rd harmonics, no high pitched buzz
            double driven = hpOut * drive;
            double warmed = driven / (1.0 + Math.abs(driven) * 0.38);

            // 9. Master gain with transparent soft-knee limiter (preserves deep rumble without harsh clipping)
            double masterGain = warmed * smoothedVolume * smoothedShiftCut * smoothedMasterVolume * 115000.0;

            double out;
            if (masterGain > 27500.0) {
                out = 27500.0 + 3500.0 * Math.tanh((masterGain - 27500.0) / 3500.0);
            } else if (masterGain < -27500.0) {
                out = -27500.0 + 3500.0 * Math.tanh((masterGain + 27500.0) / 3500.0);
            } else {
                out = masterGain;
            }

            if (out > PCM_HARD_CLAMP) out = PCM_HARD_CLAMP;
            if (out < -PCM_HARD_CLAMP) out = -PCM_HARD_CLAMP;

            buffer[i] = (short) out;
        }
    }

    /**
     * Table lookup of the blowdown pressure lobe with linear interpolation. The shape has zero
     * derivative at both boundaries, which is what keeps it free of high pitched buzz.
     */
    private static double pulse(double crankAngle, double firingAngle) {
        double delta = crankAngle - firingAngle;
        if (delta < 0.0) delta += CYCLE_RADIANS;
        if (delta >= PULSE_DURATION_RAD) return 0.0;

        double pos = (delta / PULSE_DURATION_RAD) * PULSE_TABLE_SIZE;
        int idx = (int) pos;
        if (idx < 0) return 0.0;
        if (idx >= PULSE_TABLE_SIZE) return PULSE_TABLE[PULSE_TABLE_SIZE];
        double frac = pos - idx;
        return PULSE_TABLE[idx] + (PULSE_TABLE[idx + 1] - PULSE_TABLE[idx]) * frac;
    }

    /* -------------------------------------------------------- transmission */

    /**
     * Virtual transmission and load state machine. Advances by exactly FRAME_DT of audio time
     * per call, which is fixed by BUFFER_SIZE and therefore identical on every device.
     */
    private void updateTransmission() {
        applyInputSmoothing();
        updateAccelerationAndCruise();
        advanceWanderOscillators();

        if (currentSpeedKmH < 2.5f && currentThrottle < 0.05f) {
            runIdle();
        } else if (currentSpeedKmH < 3.5f && currentThrottle >= 0.05f) {
            runNeutralRev();
        } else {
            runGearedDrive();
        }

        notifyListener();
    }

    private void applyInputSmoothing() {
        float rawThrottle = targetPedalPerc / 100.0f;

        // Organic driver foot / throttle plate micro-tremor
        if (rng.nextFloat() < 0.15f) {
            pedalWanderTarget = (rng.nextFloat() - 0.5f) * 0.012f;
        }
        pedalWanderSmoothed += (pedalWanderTarget - pedalWanderSmoothed) * 0.08f;
        float tremor = (rawThrottle > 0.02f) ? pedalWanderSmoothed : 0.0f;
        float throttleWithTremor = Math.max(0.0f, Math.min(1.0f, rawThrottle + tremor));

        // Fast attack, smooth release: react on the very first packet when the pedal goes down,
        // decay gently on lift off to simulate flywheel inertia.
        float throttleDelta = throttleWithTremor - currentThrottle;
        currentThrottle += throttleDelta * (throttleDelta > 0f ? 0.65f : 0.22f);

        float torqueDelta = targetTorqueNm - currentTorqueNm;
        currentTorqueNm += torqueDelta * (torqueDelta > 0f ? 0.55f : 0.22f);

        // Speed now arrives at ~1 Hz as an integer, so interpolate slowly enough that the steps
        // do not stair-step the RPM.
        currentSpeedKmH += (targetSpeedKmH - currentSpeedKmH) * 0.045f;

        downshiftBlip *= 0.84f;
        targetShiftCut += (1.0f - targetShiftCut) * 0.16f;
        if (shiftLockout > 0f) shiftLockout -= FRAME_DT;
    }

    private void updateAccelerationAndCruise() {
        // km/h per SECOND. This used to be a per-buffer delta compared against 0.12, i.e. an
        // effective 5.2 km/h/s threshold that the test drive cleared by only about 20%.
        float rawAccel = (currentSpeedKmH - prevSpeedKmH) / FRAME_DT;
        prevSpeedKmH = currentSpeedKmH;
        accelKmHPerSec += (rawAccel - accelKmHPerSec) * 0.02f;

        boolean isCruising = Math.abs(accelKmHPerSec) < CRUISE_ACCEL_THRESHOLD
                && currentSpeedKmH > 25.0f;
        if (isCruising) {
            cruiseTimer = Math.min(1.0f, cruiseTimer + FRAME_DT / CRUISE_ENGAGE_S);
        } else {
            cruiseTimer = Math.max(0.0f, cruiseTimer - FRAME_DT / CRUISE_RELEASE_S);
        }

        float torqueNorm = (currentTorqueNm > 0f) ? Math.min(1.0f, currentTorqueNm / 180.0f) : 0f;
        float rawLoad = Math.max(currentThrottle, (currentThrottle * 0.55f + torqueNorm * 0.45f));
        effectiveLoad = rawLoad * (1.0f - cruiseTimer * 0.45f);
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
        float lopeOffset = (float) (Math.sin(lopePhase) * 25.0 + Math.cos(lopePhase * 0.65) * 18.0);
        float targetIdle = BASE_IDLE_RPM + lopeOffset;
        currentRpm += (targetIdle - currentRpm) * 0.14f;
    }

    private void runNeutralRev() {
        currentGear = 0;
        float lopeFlutter = (float) (Math.sin(cruiseWanderPhase1) * 22.0);
        float revTarget = BASE_IDLE_RPM
                + (currentThrottle * (REDLINE_RPM - BASE_IDLE_RPM) * 0.92f)
                + lopeFlutter;
        currentRpm += (revTarget - currentRpm) * 0.20f;
    }

    private void runGearedDrive() {
        float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);
        if (currentGear == 0) currentGear = 1;

        // While accelerating shift late for a strong pull; on steady cruise relax into overdrive.
        float upshiftRpm = (2300f - cruiseTimer * 400f) + (effectiveLoad * 3700f);
        float downshiftRpm = 1250f + (effectiveLoad * 1400f);

        boolean isKickdown = (currentThrottle > 0.68f
                || (currentTorqueNm > 175f && currentSpeedKmH > 15f));

        if (isKickdown && currentGear > 2 && shiftLockout <= 0f) {
            float potentialLowerRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
            if (potentialLowerRpm < 5800f) {
                currentGear--;
                downshiftBlip = 480f;
                cruiseTimer = 0f;
                shiftLockout = SHIFT_LOCKOUT_S;
            }
        }

        evaluateShift(wheelRpm, upshiftRpm, downshiftRpm, isKickdown);

        // Torque converter fluid coupling slips 1.8% to 4.2% with load
        float converterSlip = 1.018f + (effectiveLoad * 0.024f);

        if (rng.nextFloat() < 0.12f) {
            rpmWanderTarget = (rng.nextFloat() - 0.5f) * 26.0f;
        }
        rpmWanderSmoothed += (rpmWanderTarget - rpmWanderSmoothed) * 0.06f;
        float harmonicBreathe = (float) (Math.sin(cruiseWanderPhase1) * 14.0
                + Math.cos(cruiseWanderPhase2) * 8.0);

        float targetDynamicRpm = (wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] * converterSlip)
                + downshiftBlip
                + harmonicBreathe
                + rpmWanderSmoothed;

        if (targetDynamicRpm < BASE_IDLE_RPM) targetDynamicRpm = BASE_IDLE_RPM;
        if (targetDynamicRpm > REDLINE_RPM) targetDynamicRpm = REDLINE_RPM;

        currentRpm += (targetDynamicRpm - currentRpm) * 0.22f;
    }

    private void evaluateShift(float wheelRpm, float upshiftRpm, float downshiftRpm, boolean isKickdown) {
        if (shiftLockout > 0f) return;

        float rawGearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];
        float minSpeedForNextGear = (currentGear < 6) ? MIN_UPSHIFT_SPEEDS[currentGear] : Float.MAX_VALUE;

        if (rawGearRpm > upshiftRpm && currentGear < 6 && currentSpeedKmH >= minSpeedForNextGear) {
            currentGear++;
            targetShiftCut = 0.82f;
            shiftLockout = SHIFT_LOCKOUT_S;
            return;
        }

        if (rawGearRpm < downshiftRpm && currentGear > 1 && !isKickdown) {
            // Hysteresis guard: never drop into a gear that would immediately upshift again.
            // The 1<->2 pair has almost no natural margin (ratio spread 1.60 vs a shift window
            // of ~1.58), so without this it hunts.
            float rpmAfterDownshift = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
            if (rpmAfterDownshift < upshiftRpm * 0.92f) {
                currentGear--;
                downshiftBlip = 260f;
                shiftLockout = SHIFT_LOCKOUT_S;
            }
        }
    }

    /**
     * Fires at ~10.8 Hz. The gauge cannot show more than that anyway, and posting 43 runnables
     * per second to the UI looper competes with the OBD callbacks we just moved off it.
     */
    private void notifyListener() {
        EngineListener listener = engineListener;
        if (listener == null) return;
        listenerCounter++;
        if (listenerCounter < LISTENER_DIVIDER) return;
        listenerCounter = 0;
        listener.onEngineStateChanged(currentRpm, currentGear);
    }
}
