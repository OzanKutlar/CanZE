/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
*/

package lu.fisch.canze.classes;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.Arrays;
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
 */
public class V8SoundEngine {

    private static final String TAG = "V8SoundEngine";

    public interface EngineListener {
        void onEngineStateChanged(float rpm, int gear);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 512;
    private static final int CONV_BLOCK = 256;
    private static final int IR_LENGTH = 4096;
    private static final String IR_ASSET = "v8_ir.wav";

    private static final float FRAME_DT = (float) BUFFER_SIZE / (float) SAMPLE_RATE; // 11.6 ms
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double CYCLE_RADIANS = CylinderPulse.CYCLE_RADIANS;

    // Ignition state machine
    public static final int ENGINE_OFF = 0;
    public static final int ENGINE_RUNNING = 1;
    public static final int ENGINE_STOPPING = 2;

    private static final float STOP_TIME_S = 2.2f; // Audible 2.2s flywheel wind-down duration

    // Virtual manual transmission gear ratios
    private static final float[] GEAR_RATIOS = {3.80f, 2.60f, 1.75f, 1.25f, 0.95f, 0.72f};
    private static final float FINAL_DRIVE = 3.65f;
    private static final float REDLINE_RPM = 6600f;

    private static final float MOTOR_PEAK_TORQUE_NM = 226f;
    private static final float MOTOR_TAPER_RPM = 3000f;
    private static final float FULL_OVERRUN_NM = 80f;
    private static final float STATIONARY_SPEED_KMH = 2.5f;
    private static final float STATIONARY_TORQUE_NM = 5f;

    // Defaults for user-tunable parameters
    public static final float DEFAULT_IDLE_RPM = 780f;
    public static final float DEFAULT_STALL_FLASH_RPM = 1850f;
    public static final float DEFAULT_SLIP_FADE_KMH = 24f;
    public static final float DEFAULT_UPSHIFT_BASE_RPM = 2200f;
    public static final float DEFAULT_EDGE_BITE_IDLE = 0.04f;
    public static final float DEFAULT_EDGE_BITE_LOAD = 0.14f;
    public static final float DEFAULT_AIR_NOISE = 0.09f;
    public static final float DEFAULT_EXHAUST_DEPTH = 0.55f;
    public static final float DEFAULT_IDLE_ROUGHNESS = 0.40f;
    public static final float DEFAULT_SUB_BASS = 0.30f;
    public static final float DEFAULT_POP_RATE = 6.0f;
    public static final float DEFAULT_LEVEL_TARGET = 0.70f;

    private static final float NEUTRAL_REV_CEILING_RPM = 5200f;
    private static final float REV_DRIVE_ACCEL_RPM_S = 6800f;
    private static final float REV_NATURAL_DECEL_RPM_S = 1150f;
    private static final float LIMITER_CUT_DROP_RPM = 280f;
    private static final float LIMITER_CUT_DECEL_RPM_S = 4600f;
    private static final float LIMITER_CUT_MIN_TIME_S = 0.045f;
    private static final float[] MIN_UPSHIFT_SPEEDS = {0f, 12f, 30f, 50f, 70f, 90f};
    private static final float SHIFT_LOCKOUT_S = 0.8f;

    private static final float CRUISE_ACCEL_THRESHOLD = 1.2f;
    private static final float CRUISE_ENGAGE_S = 1.0f;
    private static final float CRUISE_RELEASE_S = 0.5f;
    private static final double LOPE_RATE = 5.17;
    private static final double CRUISE_WANDER_RATE_1 = 3.66;
    private static final double CRUISE_WANDER_RATE_2 = 7.58;
    private static final double PHASE_WRAP = TWO_PI * 100.0;
    private static final double SUB_BASS_ORDER = 2.0;
    private static final int HEADER_DELAY = 64;

    private static final float AIR_NOISE_MIN = 0.03f;
    private static final float CONV_MIN = 0.30f;
    private static final float JITTER_MAX = 0.12f;
    private static final float POP_DECAY = 0.9981f;
    private static final float POP_LEVEL = 0.35f;
    private static final float POP_MIN_RPM = 1200f;

    private static final double PCM_FULL_SCALE = 31500.0;
    private static final double INTERNAL_DRIVE = 1.35;
    private static final double SOFT_KNEE = 0.8730;
    private static final double KNEE_WIDTH = 0.1111;
    private static final double PCM_HARD_CLAMP = 32000.0;
    private static final int LISTENER_DIVIDER = 8;

    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;
    private volatile float masterVolume = 1.0f;
    private AssetManager assetManager = null;

    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Tunable fields
    private volatile float idleRpm = DEFAULT_IDLE_RPM;
    private volatile float stallFlashRpm = DEFAULT_STALL_FLASH_RPM;
    private volatile float slipFadeKmh = DEFAULT_SLIP_FADE_KMH;
    private volatile float upshiftBaseRpm = DEFAULT_UPSHIFT_BASE_RPM;
    private volatile float edgeBiteIdle = DEFAULT_EDGE_BITE_IDLE;
    private volatile float edgeBiteLoad = DEFAULT_EDGE_BITE_LOAD;
    private volatile float airNoiseMax = DEFAULT_AIR_NOISE;
    private volatile float exhaustDepth = DEFAULT_EXHAUST_DEPTH;
    private volatile float idleRoughness = DEFAULT_IDLE_ROUGHNESS;
    private volatile float subBassLevel = DEFAULT_SUB_BASS;
    private volatile float popRateHz = DEFAULT_POP_RATE;
    private volatile float levelTarget = DEFAULT_LEVEL_TARGET;

    // Ignition state
    private volatile int engineState = ENGINE_OFF;
    private float stateTimer = 0f;
    private float stopBaseRpm = 0f;

    // Control state
    private final SpeedObserver observer = new SpeedObserver();
    private float currentRpm = 0f;
    private int currentGear = 0;
    private float currentThrottle = 0f;
    private float currentTorqueNm = 0f;
    private float currentSpeedKmH = 0f;
    private float cruiseTimer = 0f;
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

    private float pedalWanderTarget = 0f;
    private float pedalWanderSmoothed = 0f;
    private float rpmWanderTarget = 0f;
    private float rpmWanderSmoothed = 0f;
    private double cruiseWanderPhase1 = 0.0;
    private double cruiseWanderPhase2 = 0.0;
    private double crankCycleFlutter = 1.0;

    private double crankPhase = 0.0;
    private double lopePhase = 0.0;
    private double subBassPhase = 0.0;

    private final JitterFilter bank1Jitter = new JitterFilter();
    private final JitterFilter bank2Jitter = new JitterFilter();
    private final DcBlocker bank1Dc = new DcBlocker();
    private final DcBlocker bank2Dc = new DcBlocker();
    private final DerivativeFilter bank1Derivative = new DerivativeFilter();
    private final DerivativeFilter bank2Derivative = new DerivativeFilter();
    private final LowPassFilter bank1AirNoise = new LowPassFilter();
    private final LowPassFilter bank2AirNoise = new LowPassFilter();
    private final LowPassFilter popFilter = new LowPassFilter();
    private final LowPassFilter antiAlias = new LowPassFilter();
    private final DelayLine collectorDelay = new DelayLine();
    private final LevelingFilter leveling = new LevelingFilter();

    private PartitionedConvolver convolver = null;
    private float[] dryBuffer = null;
    private float[] wetBuffer = null;
    private float[] directBuffer = null;

    private float popEnvelope = 0f;
    private float smoothedMasterVolume = 0f;

    private final Random rng = new Random();
    private EngineListener engineListener;

    public V8SoundEngine() {}

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

    /* ------------------------------------------------------------ ignition */

    public void setIgnition(boolean on) {
        if (on) {
            if (engineState == ENGINE_OFF || engineState == ENGINE_STOPPING) {
                engineState = ENGINE_RUNNING;
                stateTimer = 0f;
                currentRpm = 0f; // Begin smooth spin-up to 1000 RPM
            }
        } else {
            if (engineState == ENGINE_RUNNING) {
                engineState = ENGINE_STOPPING;
                stateTimer = 0f;
                stopBaseRpm = currentRpm;
            }
        }
    }

    public int getEngineState() {
        return engineState;
    }

    /* ------------------------------------------------------ tuning setters */

    public void setIdleRpm(float v) {
        idleRpm = clamp(v, 600f, 1100f);
    }

    public void setStallFlashRpm(float v) {
        stallFlashRpm = clamp(v, 800f, 2800f);
    }

    public void setSlipFadeKmh(float v) {
        slipFadeKmh = clamp(v, 10f, 40f);
    }

    public void setUpshiftBaseRpm(float v) {
        upshiftBaseRpm = clamp(v, 1800f, 3200f);
    }

    public void setEdgeBiteIdle(float v) {
        edgeBiteIdle = clamp(v, 0f, 0.30f);
    }

    public void setEdgeBiteLoad(float v) {
        edgeBiteLoad = clamp(v, 0f, 0.30f);
    }

    public void setAirNoise(float v) {
        airNoiseMax = clamp(v, 0f, 0.20f);
    }

    public void setExhaustDepth(float v) {
        exhaustDepth = clamp(v, 0.20f, 0.80f);
    }

    public void setIdleRoughness(float v) {
        idleRoughness = clamp(v, 0f, 0.80f);
    }

    public void setSubBassLevel(float v) {
        subBassLevel = clamp(v, 0f, 0.80f);
    }

    public void setPopRate(float v) {
        popRateHz = clamp(v, 0f, 15f);
    }

    public void setLevelTarget(float v) {
        levelTarget = clamp(v, 0.30f, 1.0f);
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    /* --------------------------------------------------------- lifecycle */

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

    private void buildSignalChain() {
        final float fs = SAMPLE_RATE;

        bank1Jitter.initialize(12, 3.0f, fs);
        bank2Jitter.initialize(12, 3.7f, fs);

        bank1Dc.initialize(12f, fs);
        bank2Dc.initialize(12f, fs);

        bank1Derivative.setGain(2.2f);
        bank2Derivative.setGain(2.2f);

        bank1AirNoise.setCutoff(1200f, fs);
        bank2AirNoise.setCutoff(1350f, fs);

        popFilter.setCutoff(900f, fs);
        antiAlias.setCutoff(7500f, fs);

        collectorDelay.initialize(HEADER_DELAY + 8);
        collectorDelay.setDelay(HEADER_DELAY);

        leveling.reset();
        leveling.setTarget(levelTarget);
        leveling.setRange(0.05f, 2.5f);

        final float[] ir = ImpulseResponseFactory.create(assetManager, IR_ASSET, IR_LENGTH, fs);
        convolver = new PartitionedConvolver(ir, CONV_BLOCK);

        dryBuffer = new float[BUFFER_SIZE];
        wetBuffer = new float[BUFFER_SIZE];
        directBuffer = new float[BUFFER_SIZE];

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
        if (minBuf <= 0) return null;

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
            return null;
        }

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            releaseTrack(track);
            return null;
        }
        return track;
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try { track.stop(); } catch (Exception ignored) {}
        try { track.release(); } catch (Exception ignored) {}
    }

    private void renderLoop(AudioTrack track) {
        final short[] out = new short[BUFFER_SIZE];
        while (isRunning) {
            updateControl();
            renderExcitation(dryBuffer, directBuffer);
            applyConvolution(dryBuffer, wetBuffer);
            finishOutput(dryBuffer, wetBuffer, directBuffer, out);

            final int written = track.write(out, 0, BUFFER_SIZE);
            if (written < 0) return;
        }
    }

    /* ------------------------------------------------------------- synthesis */

    private void renderExcitation(float[] dry, float[] direct) {
        if (engineState == ENGINE_OFF) {
            Arrays.fill(dry, 0f);
            Arrays.fill(direct, 0f);
            return;
        }

        final float load = effectiveLoad;
        final float overrun = overrunAmount;

        final double crankRadPerSample =
                ((currentRpm * crankCycleFlutter) / 60.0) * TWO_PI / SAMPLE_RATE;
        final double firingRadPerSample = crankRadPerSample * SUB_BASS_ORDER;

        // Fuelling combustion curve: keep pulses audible during shutdown so rev wind-down is clearly heard
        float combustion = 0f;
        if (engineState == ENGINE_RUNNING) {
            float startGain = Math.min(1.0f, stateTimer / 0.45f);
            combustion = (0.40f + load * 0.90f) * startGain;
        } else if (engineState == ENGINE_STOPPING) {
            // Keep combustion at natural idle strength, gently tapering only in the final 15%
            float progress = Math.min(1.0f, stateTimer / STOP_TIME_S);
            float tail = (progress > 0.85f) ? (1.0f - progress) / 0.15f : 1.0f;
            combustion = 0.40f * tail;
        }

        final float mix = edgeBiteIdle + load * (edgeBiteLoad - edgeBiteIdle);
        final float airNoise = AIR_NOISE_MIN + load * (airNoiseMax - AIR_NOISE_MIN);
        final float subLevel = (engineState == ENGINE_RUNNING) ? (subBassLevel + load * 0.25f) : 0f;
        final float targetMaster = isMuted ? 0f : masterVolume;

        final float jitterNow = idleRoughness + load * (JITTER_MAX - idleRoughness);
        bank1Jitter.setScale(jitterNow);
        bank2Jitter.setScale(jitterNow);

        for (int i = 0; i < BUFFER_SIZE; i++) {
            crankPhase += crankRadPerSample;
            if (crankPhase >= CYCLE_RADIANS) {
                crankPhase -= CYCLE_RADIANS;
                crankCycleFlutter = 1.0 + (rng.nextDouble() - 0.5) * 0.007;
            }

            subBassPhase += firingRadPerSample;
            if (subBassPhase >= TWO_PI) subBassPhase -= TWO_PI;

            smoothedShiftCut += (targetShiftCut - smoothedShiftCut) * 0.008f;
            smoothedLimiterCut += (targetLimiterCut - smoothedLimiterCut) * 0.035f;
            smoothedMasterVolume += (targetMaster - smoothedMasterVolume) * 0.008f;

            final float gate = smoothedShiftCut * smoothedLimiterCut * (1f - 0.75f * overrun);

            float b1 = (float) CylinderPulse.bankOne(crankPhase) * combustion * gate;
            float b2 = (float) CylinderPulse.bankTwo(crankPhase) * combustion * gate;

            b1 = shapeBank(b1, bank1Jitter, bank1Dc, bank1Derivative, bank1AirNoise, mix, airNoise);
            b2 = shapeBank(b2, bank2Jitter, bank2Dc, bank2Derivative, bank2AirNoise, mix, airNoise);

            final float collector = 0.5f * (b1 + b2);
            final float piped = collectorDelay.f(collector);

            // Decel overrun pops
            popEnvelope *= POP_DECAY;
            if (engineState == ENGINE_RUNNING && overrun > 0.05f && currentRpm > POP_MIN_RPM
                    && rng.nextFloat() < overrun * popRateHz / (float) SAMPLE_RATE) {
                popEnvelope = overrun * POP_LEVEL;
            }
            final float pop = popFilter.f(popEnvelope * (2f * rng.nextFloat() - 1f));
            final float sub = (float) Math.sin(subBassPhase) * subLevel;

            dry[i] = piped;
            direct[i] = pop + sub;
        }
    }

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
        final float noise = noiseFilter.f(2f * rng.nextFloat() - 1f);
        final float modulator = airNoise * noise + (1f - airNoise);
        return slope * mix + (centred * modulator) * (1f - mix);
    }

    private void applyConvolution(float[] dry, float[] wet) {
        if (convolver == null) {
            System.arraycopy(dry, 0, wet, 0, BUFFER_SIZE);
            return;
        }
        for (int offset = 0; offset + CONV_BLOCK <= BUFFER_SIZE; offset += CONV_BLOCK) {
            convolveBlock(dry, wet, offset);
        }
    }

    private void convolveBlock(float[] dry, float[] wet, int offset) {
        System.arraycopy(dry, offset, blockScratchIn, 0, CONV_BLOCK);
        convolver.process(blockScratchIn, blockScratchOut);
        System.arraycopy(blockScratchOut, 0, wet, offset, CONV_BLOCK);
    }

    private final float[] blockScratchIn = new float[CONV_BLOCK];
    private final float[] blockScratchOut = new float[CONV_BLOCK];

    private void finishOutput(float[] dry, float[] wet, float[] direct, short[] out) {
        if (engineState == ENGINE_OFF) {
            Arrays.fill(out, (short) 0);
            return;
        }

        final float conv = CONV_MIN + effectiveLoad * (exhaustDepth - CONV_MIN);
        final float dryAmount = 1f - conv;

        // Keep leveling target steady during shutdown so AGC does not collapse volume
        leveling.setTarget(levelTarget * (1f - 0.45f * overrunAmount));

        // Master fade only in the final 15% of stopping for clean zero-crossing
        float stopMaster = 1.0f;
        if (engineState == ENGINE_STOPPING && stateTimer > STOP_TIME_S * 0.85f) {
            stopMaster = Math.max(0f, (STOP_TIME_S - stateTimer) / (STOP_TIME_S * 0.15f));
        }

        for (int i = 0; i < BUFFER_SIZE; i++) {
            float exhaust = conv * (wet[i] * 2.2f) + dryAmount * dry[i];
            float v = exhaust + direct[i];

            v = leveling.f(v);
            v = antiAlias.f(v);

            double shaped = v * smoothedMasterVolume * INTERNAL_DRIVE * stopMaster;
            shaped = softKnee(shaped);

            double pcm = shaped * PCM_FULL_SCALE;
            if (pcm > PCM_HARD_CLAMP) pcm = PCM_HARD_CLAMP;
            if (pcm < -PCM_HARD_CLAMP) pcm = -PCM_HARD_CLAMP;

            out[i] = (short) pcm;
        }
    }

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

    private void updateControl() {
        switch (engineState) {
            case ENGINE_OFF:
                currentRpm = 0f;
                currentGear = 0;
                notifyListener();
                return;
            case ENGINE_STOPPING:
                runStopping();
                notifyListener();
                return;
            default:
                break;
        }

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

    private void runStopping() {
        stateTimer += FRAME_DT;
        currentGear = 0;

        float progress = Math.min(1.0f, stateTimer / STOP_TIME_S);
        // Natural flywheel spin-down curve
        float decay = (float) Math.pow(1.0f - progress, 1.6);
        currentRpm = stopBaseRpm * decay;

        if (progress >= 1.0f || currentRpm <= 20f) {
            currentRpm = 0f;
            stopBaseRpm = 0f;
            engineState = ENGINE_OFF;
        }
    }

    private void applyInputSmoothing() {
        final float rawThrottle = targetPedalPerc / 100.0f;

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
        targetShiftCut += (1.0f - targetShiftCut) * 0.055f;
        if (shiftLockout > 0f) shiftLockout -= FRAME_DT;
    }

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

    private boolean isStationary() {
        return currentSpeedKmH < STATIONARY_SPEED_KMH
                && Math.abs(currentTorqueNm) < STATIONARY_TORQUE_NM;
    }

    private float availableTorqueNm() {
        final float motorRpm = SpeedObserver.motorRpm(currentSpeedKmH);
        if (motorRpm <= MOTOR_TAPER_RPM) return MOTOR_PEAK_TORQUE_NM;
        return MOTOR_PEAK_TORQUE_NM * MOTOR_TAPER_RPM / motorRpm;
    }

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
        stateTimer += FRAME_DT;

        final float lope = (float) (Math.sin(lopePhase) * 25.0 + Math.cos(lopePhase * 0.65) * 18.0);

        // Startup sequence: revs sweep smoothly from 0 -> 1000 RPM and settle on idle
        if (stateTimer < 1.3f) {
            float progress = Math.min(1.0f, stateTimer / 1.05f);
            float startCurve = (float) (1.0 - Math.pow(1.0 - progress, 2.0));
            float startTarget = startCurve * 1000f;
            currentRpm += (startTarget - currentRpm) * 0.16f;
        } else {
            final float targetIdle = idleRpm + lope;
            currentRpm += (targetIdle - currentRpm) * 0.07f;
        }
    }

    private void runNeutralRev() {
        currentGear = 0;
        stateTimer += FRAME_DT;
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
                    Math.max(0.0f, Math.min(1.0f, (currentRpm - idleRpm) / 650.0f));
            final float cushion = 0.50f + (idleProximity * 0.50f);
            final float decel = REV_NATURAL_DECEL_RPM_S
                    * (1.0f + (currentRpm / NEUTRAL_REV_CEILING_RPM) * 0.4f) * cushion;

            currentRpm -= decel * FRAME_DT;
        }

        if (currentRpm < idleRpm) {
            currentRpm = idleRpm;
        }
    }

    private void runGearedDrive() {
        stateTimer += FRAME_DT;
        final float wheelRpm =
                (currentSpeedKmH * 1000f) / (SpeedObserver.WHEEL_CIRCUMFERENCE_M * 60f);
        if (currentGear == 0) currentGear = 1;

        final float baseUpshift = upshiftBaseRpm - (cruiseTimer * 300f);
        final float aggression = (float) Math.pow(effectiveLoad, 1.15);
        float upshiftRpm = baseUpshift + (aggression * 2300f);
        if (upshiftRpm > 4500f) upshiftRpm = 4500f;

        evaluateShift(wheelRpm, upshiftRpm);

        final float slipFade = Math.max(0f, 1f - (currentSpeedKmH / slipFadeKmh));
        final float slipCurve = slipFade * slipFade;
        final float launchSlip = (float) Math.pow(effectiveLoad, 1.25) * stallFlashRpm * slipCurve;
        final float converterSlip = 1.018f + (effectiveLoad * 0.024f);

        if (rng.nextFloat() < 0.12f) {
            rpmWanderTarget = (rng.nextFloat() - 0.5f) * 26.0f;
        }
        rpmWanderSmoothed += (rpmWanderTarget - rpmWanderSmoothed) * 0.03f;

        final float breathe = (float) (Math.sin(cruiseWanderPhase1) * 14.0
                + Math.cos(cruiseWanderPhase2) * 8.0);

        final float gearedRpm = (wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] * converterSlip)
                + downshiftBlip
                + breathe
                + rpmWanderSmoothed;

        float target = Math.max(gearedRpm, idleRpm + launchSlip);
        if (target < idleRpm) target = idleRpm;

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
            targetShiftCut = 0.60f;
            shiftLockout = SHIFT_LOCKOUT_S;
            return;
        }

        final float downshiftRpm = 1500f;
        if (gearRpm < downshiftRpm && currentGear > 1 && effectiveLoad < 0.12f) {
            final float after = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
            if (after < REDLINE_RPM - 1200f) {
                currentGear--;
                downshiftBlip = 260f;
                shiftLockout = SHIFT_LOCKOUT_S;
            }
        }
    }

    private void notifyListener() {
        final EngineListener listener = engineListener;
        if (listener == null) return;
        listenerCounter++;
        if (listenerCounter < LISTENER_DIVIDER) return;
        listenerCounter = 0;
        listener.onEngineStateChanged(currentRpm, currentGear);
    }
}
