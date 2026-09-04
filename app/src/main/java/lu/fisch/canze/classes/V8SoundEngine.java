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

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
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

    // Balanced 7-speed transmission with smooth 1.3-1.5x steps, cruising at ~1500-2500 RPM
    private static final float[] GEAR_RATIOS = {4.85f, 3.15f, 2.10f, 1.45f, 1.05f, 0.80f, 0.62f};
    private static final float FINAL_DRIVE = 3.65f;
    private static final float REDLINE_RPM = 6600f;

    private static final float MOTOR_PEAK_TORQUE_NM = 226f;
    private static final float MOTOR_TAPER_RPM = 3000f;
    // Retuned from 80. Full lift-off regen in the vehicle model is 55 Nm, so the old divisor
    // meant the overrun signal never reached the top of its own range and the decel pops and
    // fuelling cut were effectively switched off during the one manoeuvre they exist for.
    private static final float FULL_OVERRUN_NM = 55f;
    private static final float STATIONARY_SPEED_KMH = 2.5f;
    private static final float STATIONARY_TORQUE_NM = 5f;

    // Overrun and coast handling
    private static final float COAST_UPSHIFT_BLOCK = 0.05f;
    private static final float COAST_THROTTLE_CLOSED = 0.04f;
    private static final float COAST_OVERRUN_SPEED_KMH = 8f;
    private static final float COAST_OVERRUN_FLOOR = 0.45f;
    private static final float COAST_DOWNSHIFT_RPM = 1150f;
    private static final float COMBUSTION_OVERRUN_CUT = 0.90f;
    private static final float BLIP_RATIO_GAIN = 0.55f;
    private static final float BLIP_MAX_RPM = 650f;
    private static final float OVERRUN_SLEW = 0.30f;
    private static final float DRIVE_SLEW = 0.12f;
    private static final float LAUNCH_SLEW = 0.28f;

    // Defaults for user-tunable parameters
    public static final float DEFAULT_IDLE_RPM = 780f;
    public static final float DEFAULT_STALL_FLASH_RPM = 2100f;
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
    private static final float[] MIN_UPSHIFT_SPEEDS = {0f, 12f, 30f, 48f, 68f, 88f, 108f};
    // Clean hysteresis downshift speeds: 3.5-6 km/h buffer below upshifts prevents gear hunting
    private static final float[] MIN_DOWNSHIFT_SPEEDS = {0f, 8.5f, 24f, 42f, 62f, 82f, 102f};
    private static final float SHIFT_LOCKOUT_UP_S = 0.70f;
    private static final float SHIFT_LOCKOUT_DOWN_S = 0.45f;

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

    // Output buffer sizing. getMinBufferSize() reports BYTES, so all of this is in bytes.
    // 512 frames * 2 bytes * 6 buffers = 6144 bytes, roughly 70 ms of headroom. The previous
    // BUFFER_SIZE * 4 was only ~23 ms and sat right on the edge of underrunning, which is what
    // made other apps' playback glitch.
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int TARGET_BUFFER_COUNT = 6;

    // Shutdown ramp. The fade must finish before the state machine reaches ENGINE_OFF.
    private static final float STOP_FADE_FRACTION = 0.30f;
    private static final float MIN_STOPPING_RPM = 60f;
    private static final int SILENCE_RAMP_SAMPLES = 441; // ~10 ms at 44.1 kHz

    // Render thread parking while the ignition is off.
    private static final long PARK_POLL_MS = 50L;
    private static final int PARK_MAX_ITERATIONS = 1200; // bounded: ~60 s per outer pass
    private static final long FADE_OUT_POLL_MS = 10L;
    private static final int FADE_OUT_MAX_POLLS = 6;     // bounded: 60 ms grace on stop()

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

    // Last PCM value actually emitted, used to ramp to silence without a discontinuity.
    // Volatile because stop() polls it from the caller's thread.
    private volatile float lastPcm = 0f;
    private int silenceRampPos = 0;
    private volatile boolean fadeOutRequested = false;

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
                // Clear the chain BEFORE publishing the new state. A parked render thread wakes
                // on engineState != ENGINE_OFF, so assigning it last avoids waking into a
                // half-reset signal chain.
                resetForStart();
                engineState = ENGINE_RUNNING;
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

    /**
     * Returns the synthesizer to a clean, click-free state. Without this a restart inherits the
     * previous run's filter memory and smoothed volume, which produces an audible thump.
     */
    private void resetForStart() {
        stateTimer = 0f;
        currentRpm = 0f; // Begin smooth spin-up to 1000 RPM
        stopBaseRpm = 0f;
        smoothedMasterVolume = 0f;
        popEnvelope = 0f;
        lastPcm = 0f;
        silenceRampPos = 0;

        // Drivetrain state. Without this a restart inherits the previous run's gear, overrun
        // and learned observer bias, so the first second after ignition is computed from a
        // speed estimate carried over from however the last run happened to end.
        currentGear = 0;
        effectiveLoad = 0f;
        overrunAmount = 0f;
        downshiftBlip = 0f;
        shiftLockout = 0f;
        cruiseTimer = 0f;
        currentThrottle = 0f;
        currentTorqueNm = 0f;
        observer.reset(targetSpeedKmH);
        currentSpeedKmH = targetSpeedKmH;

        bank1Dc.reset();
        bank2Dc.reset();
        bank1Derivative.reset();
        bank2Derivative.reset();
        antiAlias.reset();
        popFilter.reset();

        leveling.reset();
        leveling.setTarget(levelTarget);
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
        final Thread thread = audioThread;
        if (thread == null) {
            isRunning = false;
            return;
        }

        // Ask the render loop to ramp to silence first, so leaving the screen mid-note does not
        // truncate the waveform at a non-zero sample.
        fadeOutRequested = true;
        waitForFadeOut();

        isRunning = false;
        audioThread = null;
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        fadeOutRequested = false;
    }

    /**
     * Bounded wait for the render thread to reach a zero output sample. Worst case 60 ms.
     */
    private void waitForFadeOut() {
        for (int i = 0; i < FADE_OUT_MAX_POLLS; i++) {
            if (!isRunning) return;
            if (lastPcm == 0f) return;
            try {
                Thread.sleep(FADE_OUT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
            // THREAD_PRIORITY_AUDIO, not URGENT_AUDIO. Urgent is intended for the system's own
            // mixer threads; a procedural synth running FFTs should not outrank them.
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
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
        final int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBuf <= 0) return null;

        final int desiredBytes = BUFFER_SIZE * BYTES_PER_SAMPLE * TARGET_BUFFER_COUNT;
        final int bufSize = Math.max(minBuf, desiredBytes);

        AudioTrack track = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track = createTrackModern(bufSize);
        }
        if (track == null) {
            track = createTrackLegacy(bufSize);
        }
        if (track == null) return null;

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            releaseTrack(track);
            return null;
        }
        return track;
    }

    /**
     * API 21+ path. Kept in its own method so AudioAttributes is never resolved on older
     * runtimes, which matters because minSdkVersion is 15.
     *
     * USAGE_MEDIA / CONTENT_TYPE_MUSIC is the direct equivalent of the legacy STREAM_MUSIC, so
     * routing and volume behaviour are unchanged. Low latency mode is deliberately NOT requested:
     * it asks for a smaller buffer and more HAL pressure, the opposite of what we want here.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private AudioTrack createTrackModern(int bufSize) {
        try {
            final AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            final AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            return new AudioTrack(
                    attributes,
                    format,
                    bufSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
        } catch (Exception e) {
            Log.w(TAG, "modern AudioTrack unavailable, falling back to legacy", e);
            return null;
        }
    }

    private AudioTrack createTrackLegacy(int bufSize) {
        try {
            return new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                    AudioTrack.MODE_STREAM
            );
        } catch (Exception e) {
            Log.e(TAG, "could not create AudioTrack", e);
            return null;
        }
    }

    private void releaseTrack(AudioTrack track) {
        if (track == null) return;
        try { track.stop(); } catch (Exception ignored) {}
        try { track.release(); } catch (Exception ignored) {}
    }

    private void renderLoop(AudioTrack track) {
        final short[] out = new short[BUFFER_SIZE];
        boolean playing = true;

        while (isRunning) {
            // With the ignition off there is nothing to synthesise. Previously the loop still ran
            // the full DSP chain and a blocking write every 11.6 ms just to emit silence, which
            // starved the system mixer and glitched other apps' playback.
            if (isSilent()) {
                if (playing) {
                    pauseTrack(track);
                    playing = false;
                }
                if (!parkUntilActive()) return;
                continue;
            }

            if (!playing) {
                if (!resumeTrack(track)) return;
                playing = true;
            }

            updateControl();
            renderExcitation(dryBuffer, directBuffer);
            applyConvolution(dryBuffer, wetBuffer);
            finishOutput(dryBuffer, wetBuffer, directBuffer, out);

            final int written = track.write(out, 0, BUFFER_SIZE);
            if (written < 0) return;
        }
    }

    /**
     * Safe to park only once the engine is off AND the output has actually settled at zero,
     * so we never park mid-waveform.
     */
    private boolean isSilent() {
        return engineState == ENGINE_OFF && lastPcm == 0f;
    }

    /**
     * Bounded poll until the engine restarts or the thread is asked to exit.
     *
     * @return true to continue rendering, false to exit the render loop
     */
    private boolean parkUntilActive() {
        for (int i = 0; i < PARK_MAX_ITERATIONS; i++) {
            if (!isRunning) return false;
            if (engineState != ENGINE_OFF) return true;
            try {
                Thread.sleep(PARK_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isRunning;
    }

    private void pauseTrack(AudioTrack track) {
        try {
            track.pause();
            track.flush();
        } catch (Exception e) {
            Log.w(TAG, "could not pause audio track", e);
        }
    }

    private boolean resumeTrack(AudioTrack track) {
        try {
            track.play();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not resume audio track", e);
            return false;
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
            final float tailStart = 1.0f - STOP_FADE_FRACTION;
            float tail = (progress > tailStart)
                    ? (1.0f - progress) / STOP_FADE_FRACTION
                    : 1.0f;
            if (tail < 0f) tail = 0f;
            combustion = 0.40f * tail;
        }

        final float mix = edgeBiteIdle + load * (edgeBiteLoad - edgeBiteIdle);
        final float airNoise = AIR_NOISE_MIN + load * (airNoiseMax - AIR_NOISE_MIN);
        final float subLevel = (engineState == ENGINE_RUNNING) ? (subBassLevel + load * 0.25f) : 0f;
        final float targetMaster = (isMuted || fadeOutRequested) ? 0f : masterVolume;

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

            final float gate =
                    smoothedShiftCut * smoothedLimiterCut * (1f - COMBUSTION_OVERRUN_CUT * overrun);

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
        // Skip the 4096 tap FFT entirely when there is no excitation to convolve.
        if (engineState == ENGINE_OFF) {
            Arrays.fill(wet, 0f);
            return;
        }
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
            emitSilence(out);
            return;
        }

        final float conv = CONV_MIN + effectiveLoad * (exhaustDepth - CONV_MIN);
        final float dryAmount = 1f - conv;

        // Keep leveling target steady during shutdown so AGC does not collapse volume
        leveling.setTarget(levelTarget * (1f - 0.45f * overrunAmount));

        final float stopMaster = computeStopMaster();

        double pcm = 0.0;
        for (int i = 0; i < BUFFER_SIZE; i++) {
            float exhaust = conv * (wet[i] * 2.2f) + dryAmount * dry[i];
            float v = exhaust + direct[i];

            v = leveling.f(v);
            v = antiAlias.f(v);

            double shaped = v * smoothedMasterVolume * INTERNAL_DRIVE * stopMaster;
            shaped = softKnee(shaped);

            pcm = shaped * PCM_FULL_SCALE;
            if (pcm > PCM_HARD_CLAMP) pcm = PCM_HARD_CLAMP;
            if (pcm < -PCM_HARD_CLAMP) pcm = -PCM_HARD_CLAMP;

            out[i] = (short) pcm;
        }

        lastPcm = (float) pcm;
        silenceRampPos = 0;
    }

    /**
     * Raised cosine fade across the final STOP_FADE_FRACTION of the wind-down. Smooth in the
     * first derivative at both ends, unlike the previous linear ramp.
     */
    private float computeStopMaster() {
        if (engineState != ENGINE_STOPPING) return 1.0f;

        final float fadeStart = STOP_TIME_S * (1f - STOP_FADE_FRACTION);
        if (stateTimer <= fadeStart) return 1.0f;

        float remain = (STOP_TIME_S - stateTimer) / (STOP_TIME_S * STOP_FADE_FRACTION);
        if (remain < 0f) remain = 0f;
        if (remain > 1f) remain = 1f;
        return 0.5f * (1f - (float) Math.cos(remain * Math.PI));
    }

    /**
     * Ramps the last emitted sample down to zero rather than cutting to silence outright.
     * This is a backstop guaranteeing no discontinuity on ANY path into ENGINE_OFF, including
     * an abrupt stop() while the engine is still running.
     */
    private void emitSilence(short[] out) {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            if (lastPcm == 0f || silenceRampPos >= SILENCE_RAMP_SAMPLES) {
                out[i] = (short) 0;
                lastPcm = 0f;
                continue;
            }

            final float t = (float) silenceRampPos / (float) SILENCE_RAMP_SAMPLES;
            final float gain = 0.5f * (1f + (float) Math.cos(t * Math.PI));
            out[i] = (short) (lastPcm * gain);
            silenceRampPos++;
        }

        if (silenceRampPos >= SILENCE_RAMP_SAMPLES) {
            lastPcm = 0f;
            silenceRampPos = 0;
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

        final float progress = Math.min(1.0f, stateTimer / STOP_TIME_S);
        // Natural flywheel spin-down curve
        final float decay = (float) Math.pow(1.0f - progress, 1.6);
        currentRpm = stopBaseRpm * decay;

        // Floor the revs so the crank phase keeps advancing through the tail instead of stalling.
        if (currentRpm < MIN_STOPPING_RPM) currentRpm = MIN_STOPPING_RPM;

        // Terminate on elapsed time ONLY. The old "|| currentRpm <= 20f" condition fired at about
        // t=2.01s while the amplitude fade did not finish until t=2.2s, so the state flipped to
        // ENGINE_OFF with the master gain still around 0.57 and the next buffer was hard silence.
        // That step discontinuity was the audible cut-off.
        if (progress >= 1.0f) {
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

        // Falling edges were roughly three times slower than rising ones, which is backwards:
        // a throttle plate slams shut faster than it opens and regen onset is near instant.
        // The old 0.16f meant the engine kept sounding loaded for about 200 ms after a lift.
        final float throttleDelta = throttleWithTremor - currentThrottle;
        currentThrottle += throttleDelta * (throttleDelta > 0f ? 0.45f : 0.34f);

        final float torqueDelta = targetTorqueNm - currentTorqueNm;
        currentTorqueNm += torqueDelta * (torqueDelta > 0f ? 0.40f : 0.34f);

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

        // A closed throttle at speed IS overrun, whatever the torque channel happens to report.
        // Keying this purely off negative torque meant coasting at near-zero torque produced no
        // engine braking character at all, when a shut throttle is exactly what defines it.
        if (currentThrottle < COAST_THROTTLE_CLOSED && currentSpeedKmH > COAST_OVERRUN_SPEED_KMH
                && overrun < COAST_OVERRUN_FLOOR) {
            overrun = COAST_OVERRUN_FLOOR;
        }

        if (overrun > 1f) overrun = 1f;
        overrunAmount = overrun;
    }

    /**
     * Regen torque cannot hold a car stationary, so a negative reading must not veto the handoff
     * to idle. Taking the absolute value here kept runGearedDrive() alive down to walking pace
     * and turned the entry into the idle lope into a step rather than a settle.
     */
    private boolean isStationary() {
        return currentSpeedKmH < STATIONARY_SPEED_KMH
                && currentTorqueNm < STATIONARY_TORQUE_NM;
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

        // High-stall launch flare: holds revs high (~3000 RPM) at 0-5 km/h, then descends smoothly
        final float flareSpeedProgress = Math.min(1.0f, currentSpeedKmH / Math.max(12f, slipFadeKmh * 0.75f));
        final float flareDecay = 0.5f * (1.0f + (float) Math.cos(flareSpeedProgress * Math.PI));
        final float launchLoad = Math.min(1.0f, (float) Math.sqrt(effectiveLoad) * 1.15f);
        final float launchSlip = launchLoad * (2250f) * flareDecay;
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
            // Fast-attack slew rate (0.28f) on launch tip-in so revs snap to ~3000 RPM in ~150ms
            final boolean isLaunchSurge = currentSpeedKmH < 14f && target > currentRpm && effectiveLoad > 0.15f;
            float slewRate = isLaunchSurge ? LAUNCH_SLEW : DRIVE_SLEW;
            // On a closed throttle the crank is mechanically locked to the wheels through the
            // driveline, so revs cannot lag road speed the way they can under power.
            if (overrunAmount > COAST_UPSHIFT_BLOCK) {
                slewRate = DRIVE_SLEW + (OVERRUN_SLEW - DRIVE_SLEW) * overrunAmount;
            }
            currentRpm += (target - currentRpm) * slewRate;
        }
    }

    private void evaluateShift(float wheelRpm, float upshiftRpm) {
        if (shiftLockout > 0f) return;

        final float gearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];
        final int maxGear = GEAR_RATIOS.length;
        final float minSpeedForNext =
                (currentGear < maxGear) ? MIN_UPSHIFT_SPEEDS[currentGear] : Float.MAX_VALUE;

        // A trailing throttle must never climb the box. computeLoad() clamps negative torque to
        // zero, so lifting collapsed effectiveLoad and dropped upshiftRpm to its 2200 floor while
        // gearRpm was still up at cruising revs. Lifting at 90 km/h in 4th fired three upshifts
        // in about 1.5 s, each stamping a shift cut into the audio, purely because of the lift.
        final boolean driving = overrunAmount < COAST_UPSHIFT_BLOCK;

        if (driving && gearRpm > upshiftRpm && currentGear < maxGear
                && currentSpeedKmH >= minSpeedForNext) {
            currentGear++;
            targetShiftCut = 0.60f;
            shiftLockout = SHIFT_LOCKOUT_UP_S;
            return;
        }

        // Downshifts on speed thresholds with deadband hysteresis, plus a revs trigger
        if (currentGear > 1) {
            final float downshiftSpeed = MIN_DOWNSHIFT_SPEEDS[currentGear - 1];
            final float currentRatio = GEAR_RATIOS[currentGear - 1];
            final float nextGearRatio = GEAR_RATIOS[currentGear - 2];
            final float rpmAfter = wheelRpm * FINAL_DRIVE * nextGearRatio;

            // 1. Coasting / Braking downshift: only when speed actually drops below the lower band threshold
            final boolean coastDown = currentSpeedKmH < downshiftSpeed && effectiveLoad < 0.28f;
            // 2. Revs decaying toward idle on a closed throttle. The step back up in engine speed
            //    as each gear is taken is most of what deceleration actually sounds like, and the
            //    speed thresholds alone were too coarse to produce it.
            final boolean coastRpm =
                    overrunAmount > COAST_UPSHIFT_BLOCK && gearRpm < COAST_DOWNSHIFT_RPM;
            // 3. Power kickdown: only in 3rd gear or higher (never kick down into 1st while driving in 2nd)
            final boolean kickdown = currentGear > 2 && effectiveLoad > 0.50f && currentSpeedKmH < downshiftSpeed * 1.15f && rpmAfter < 4200f;

            if ((coastDown || coastRpm || kickdown) && rpmAfter < REDLINE_RPM - 1000f) {
                currentGear--;
                // Rev match the blip to the ratio step being taken rather than a flat constant,
                // so a short step produces a small blip and a tall one a large blip.
                float blip = gearRpm * ((nextGearRatio / currentRatio) - 1f) * BLIP_RATIO_GAIN;
                if (kickdown) blip *= 1.35f;
                if (blip < 0f) blip = 0f;
                if (blip > BLIP_MAX_RPM) blip = BLIP_MAX_RPM;
                downshiftBlip = blip;
                shiftLockout = SHIFT_LOCKOUT_DOWN_S;
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
