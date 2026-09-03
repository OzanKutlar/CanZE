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
import java.util.Random;

/**
 * High-performance real-time procedural cross-plane V8 engine sound synthesizer
 * and torque-responsive virtual transmission.
 *
 * Based on acoustic principles from engine-sim:
 * - Physical exhaust blowdown shockwave pulse generation (dP/dt finite-difference radiation)
 * - Physical dual-bank delay lines for asymmetric crossplane phase interference (1-8-4-3-6-5-7-2)
 * - Resonant muffler chamber modeling
 * - Torque-coupled transmission holding, kickdown, and deceleration overrun crackles
 * - Full dynamic-range audio pipeline (3x+ loudness with transparent soft-knee saturation)
 */
public class V8SoundEngine {

    public interface EngineListener {
        void onEngineStateChanged(float rpm, int gear);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 1024;

    // 4-Stroke Crank Cycle (720 degrees = 4 * PI radians)
    private static final double CYCLE_RADIANS = 4.0 * Math.PI;

    // Cross-plane V8 firing order: 1-8-4-3-6-5-7-2
    // Bank 1 (Left):  Cyl 1 (0°),   Cyl 3 (270°), Cyl 5 (450°), Cyl 7 (540°)
    // Bank 2 (Right): Cyl 8 (90°),  Cyl 4 (180°), Cyl 6 (360°), Cyl 2 (630°)
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

    // Transmission gear ratios (1st to 6th)
    private static final float[] GEAR_RATIOS = {3.60f, 2.20f, 1.50f, 1.10f, 0.85f, 0.68f};
    private static final float FINAL_DRIVE = 3.85f;
    private static final float WHEEL_CIRCUMFERENCE_M = 1.95f;
    private static final float BASE_IDLE_RPM = 800f;
    private static final float REDLINE_RPM = 6800f;

    // Acoustic delay lines for left/right header propagation delay
    private static final int HEADER_DELAY_SAMPLES = 88; // ~2.0 ms sound propagation delay
    private final double[] bank1DelayLine = new double[HEADER_DELAY_SAMPLES];
    private final double[] bank2DelayLine = new double[HEADER_DELAY_SAMPLES];
    private int delayWriteIdx = 0;

    private AudioTrack audioTrack;
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
    private float effectiveLoad = 0f;
    private float downshiftBlip = 0f;
    private float targetShiftCut = 1.0f;
    private float smoothedShiftCut = 1.0f;

    // Overrun state
    private float overrunTimer = 0f;
    private float prevThrottle = 0f;
    private final Random rng = new Random();

    // Oscillators & acoustics
    private double crankPhase = 0.0;
    private double lopePhase1 = 0.0;
    private double lopePhase2 = 0.0;
    private double intakePhase = 0.0;
    private final double[] cylinderJitter = new double[8];

    // Acoustic differentiator & filter memory
    private double prevBank1 = 0.0;
    private double prevBank2 = 0.0;
    private double resonatorY1 = 0.0;
    private double resonatorY2 = 0.0;
    private double highPassIn = 0.0;
    private double highPassOut = 0.0;

    private EngineListener engineListener;

    public V8SoundEngine() {
        for (int i = 0; i < 8; i++) {
            cylinderJitter[i] = 1.0 + (rng.nextDouble() - 0.5) * 0.08;
        }
    }

    public void setEngineListener(EngineListener listener) {
        this.engineListener = listener;
    }

    public void setInputs(float speedKmH, float pedalPerc, float torqueNm) {
        this.targetSpeedKmH = Math.max(0f, speedKmH);
        this.targetPedalPerc = Math.max(0f, Math.min(100f, pedalPerc));
        // signed torque: negative = regen braking, positive = motor pull
        this.targetTorqueNm = Math.max(-250f, Math.min(450f, torqueNm));
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public void setMasterVolume(float volume) {
        this.masterVolume = Math.max(0.0f, Math.min(3.0f, volume));
    }

    public float getMasterVolume() {
        return masterVolume;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        int bufSize = Math.max(minBuf, BUFFER_SIZE * 4);

        audioTrack = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STREAM
        );
        try {
            audioTrack.setStereoVolume(1.0f, 1.0f);
        } catch (Exception ignored) {
        }

        audioTrack.play();

        audioThread = new Thread(new Runnable() {
            @Override
            public void run() {
                audioLoop();
            }
        }, "V8AudioSynthesizer");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    public synchronized void stop() {
        isRunning = false;
        if (audioThread != null) {
            try {
                audioThread.join(400);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }

    private void audioLoop() {
        short[] buffer = new short[BUFFER_SIZE];
        float smoothedVolume = 0.0f;

        while (isRunning) {
            updateTransmission();

            float throttle = currentThrottle;
            float rpm = currentRpm;
            float torque = currentTorqueNm;
            float load = effectiveLoad;

            // Crankshaft rotation speed in radians per sample
            double crankRadPerSample = (rpm / 60.0) * (2.0 * Math.PI) / SAMPLE_RATE;

            // Acoustic overall volume target (elevated baseline for strong presence)
            float targetVolume;
            if (isMuted) {
                targetVolume = 0.0f;
            } else {
                // Base idle/cruise volume + load enhancement
                targetVolume = 0.85f + (load * 0.45f);
                if (torque < -10f) {
                    // Heavy regen deceleration volume boost
                    float regenFactor = Math.min(1.0f, -torque / 140.0f);
                    targetVolume = Math.max(targetVolume, 0.88f + regenFactor * 0.40f);
                }
            }

            // Helmholtz induction roar frequency (~90 Hz - 220 Hz)
            double intakeFreq = 85.0 + (rpm * 0.022);
            double intakeInc = (2.0 * Math.PI * intakeFreq) / SAMPLE_RATE;

            // 2nd-order muffler chamber resonator coefficients (~140 Hz body resonance)
            double resFreq = 135.0 + (rpm * 0.015);
            double w0 = 2.0 * Math.PI * resFreq / SAMPLE_RATE;
            double cosW = Math.cos(w0);
            double sinW = Math.sin(w0);
            double alpha = sinW / (2.0 * 2.2); // Q factor ~2.2 for deep throaty resonance
            double b0 = alpha;
            double a0 = 1.0 + alpha;
            double a1 = -2.0 * cosW;
            double a2 = 1.0 - alpha;
            double normB0 = b0 / a0;
            double normA1 = a1 / a0;
            double normA2 = a2 / a0;

            for (int i = 0; i < BUFFER_SIZE; i++) {
                // Advance crank angle across 720 degree cycle
                crankPhase += crankRadPerSample;
                if (crankPhase >= CYCLE_RADIANS) {
                    crankPhase -= CYCLE_RADIANS;
                    // Periodic micro-jitter update per engine cycle to keep sound organic
                    for (int j = 0; j < 8; j++) {
                        cylinderJitter[j] = 1.0 + (rng.nextDouble() - 0.5) * 0.10;
                    }
                }

                intakePhase += intakeInc;
                if (intakePhase >= 2.0 * Math.PI) {
                    intakePhase -= 2.0 * Math.PI;
                }

                smoothedVolume += (targetVolume - smoothedVolume) * 0.005f;
                smoothedShiftCut += (targetShiftCut - smoothedShiftCut) * 0.008f;

                if (smoothedVolume < 0.001f) {
                    buffer[i] = 0;
                    continue;
                }

                // 1. Synthesize individual cylinder exhaust blowdown pressures
                double bank1Raw = 0.0;
                double bank2Raw = 0.0;

                // Bank 1 (Left): Cylinders 1, 3, 5, 7
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[0], cylinderJitter[0], load);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[2], cylinderJitter[2], load);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[4], cylinderJitter[4], load);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[6], cylinderJitter[6], load);

                // Bank 2 (Right): Cylinders 8, 4, 6, 2
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[7], cylinderJitter[7], load);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[3], cylinderJitter[3], load);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[5], cylinderJitter[5], load);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[1], cylinderJitter[1], load);

                // Deceleration overrun crackles and pops
                if (overrunTimer > 0.01f && rng.nextFloat() < 0.0028f) {
                    double popIntensity = 1.4 + rng.nextDouble() * 1.6;
                    if (rng.nextBoolean()) {
                        bank1Raw += popIntensity;
                    } else {
                        bank2Raw += popIntensity;
                    }
                }

                // 2. Header tube delay lines (propagation travel to collector)
                bank1DelayLine[delayWriteIdx] = bank1Raw;
                bank2DelayLine[delayWriteIdx] = bank2Raw;
                delayWriteIdx = (delayWriteIdx + 1) % HEADER_DELAY_SAMPLES;
                double bank1Delayed = bank1DelayLine[delayWriteIdx];
                double bank2Delayed = bank2DelayLine[delayWriteIdx];

                // 3. Acoustic Differentiation (dP/dt finite-difference)
                // engine-sim discovery: far-field radiated sound is proportional to time derivative of pressure
                double bank1Deriv = (bank1Delayed - prevBank1) * 3.8;
                double bank2Deriv = (bank2Delayed - prevBank2) * 3.8;
                prevBank1 = bank1Delayed;
                prevBank2 = bank2Delayed;

                // 4. Crossplane X-Pipe Crossover (70% direct + 30% opposing bank crossover)
                double exhaustLeft = bank1Delayed + (bank1Deriv * 0.45) + (bank2Delayed * 0.30);
                double exhaustRight = bank2Delayed + (bank2Deriv * 0.45) + (bank1Delayed * 0.30);
                double combinedExhaust = (exhaustLeft + exhaustRight) * 0.55;

                // 5. Tuned Muffler Resonant Cavity (adds deep low-end chest thump)
                double resonatorSample = (normB0 * combinedExhaust) - (normA1 * resonatorY1) - (normA2 * resonatorY2);
                resonatorY2 = resonatorY1;
                resonatorY1 = resonatorSample;

                double acousticMix = combinedExhaust + (resonatorSample * 1.35);

                // 6. Intake Roar on throttle / high load
                if (load > 0.08f) {
                    float intakeScale = (load - 0.08f) / 0.92f;
                    double intakeRoar = Math.sin(intakePhase) * (intakeScale * 0.38)
                            + Math.sin(intakePhase * 0.5) * (intakeScale * 0.22);
                    acousticMix += intakeRoar;
                }

                // 7. Subsonic High-Pass Filter (~30 Hz cutoff)
                // Eliminates DC offset and ultra-low subsonic cone oscillation
                double hpAlpha = 0.9955;
                highPassOut = hpAlpha * (highPassOut + acousticMix - highPassIn);
                highPassIn = acousticMix;
                double cleanAcoustic = highPassOut;

                // 8. Output Amplifier with Warm Drive & Transparent Limiter
                // Drive factor brings quiet passages up while preserving transient punch
                double drive = 1.55 + (load * 0.85);
                double driven = cleanAcoustic * drive;

                // Master gain tuned to fully exploit 16-bit PCM (peaking near +/- 30,000)
                double outputGain = driven * smoothedVolume * smoothedShiftCut * masterVolume * 34000.0;

                // Smooth soft-knee saturation curve (never harsh-clips or farts)
                double saturatedSample;
                if (outputGain > 26000.0) {
                    saturatedSample = 26000.0 + 5000.0 * Math.tanh((outputGain - 26000.0) / 5000.0);
                } else if (outputGain < -26000.0) {
                    saturatedSample = -26000.0 + 5000.0 * Math.tanh((outputGain + 26000.0) / 5000.0);
                } else {
                    saturatedSample = outputGain;
                }

                // Hard safety clamp
                if (saturatedSample > 31500.0) saturatedSample = 31500.0;
                if (saturatedSample < -31500.0) saturatedSample = -31500.0;

                buffer[i] = (short) saturatedSample;
            }

            if (audioTrack != null && isRunning) {
                audioTrack.write(buffer, 0, BUFFER_SIZE);
            }
        }
    }

    /**
     * Evaluates cylinder blowdown pressure wave with physical asymmetric curve:
     * rapid sonic rise when exhaust valve cracks open, followed by exponential expansion decay.
     */
    private double evaluateCylinderPulse(double currentCrankAngle, double firingAngle, double jitter, float load) {
        double delta = currentCrankAngle - firingAngle;
        if (delta < 0.0) delta += CYCLE_RADIANS;
        if (delta >= CYCLE_RADIANS) delta -= CYCLE_RADIANS;

        // Exhaust valve opening duration: ~135 degrees of crank rotation
        double duration = 135.0 * Math.PI / 180.0;
        if (delta < duration) {
            double x = delta / duration;
            // Asymmetric pulse: fast rise, exponential decay
            double pulseShape = Math.sin(Math.pow(x, 0.45) * Math.PI) * Math.exp(-2.6 * x);
            double amplitude = (0.65 + load * 0.75) * jitter;
            return pulseShape * amplitude;
        }
        return 0.0;
    }

    /**
     * Virtual transmission and engine load state machine.
     * Computes virtual gear, mechanical RPM, kickdown, and overrun burble.
     */
    private void updateTransmission() {
        float speed = targetSpeedKmH;
        float throttle = targetPedalPerc / 100.0f;
        float torque = targetTorqueNm;

        // Responsive smoothing for inputs
        currentThrottle += (throttle - currentThrottle) * 0.22f;
        currentTorqueNm += (torque - currentTorqueNm) * 0.24f;
        currentSpeedKmH += (speed - currentSpeedKmH) * 0.20f;
        downshiftBlip *= 0.84f;
        targetShiftCut += (1.0f - targetShiftCut) * 0.18f;

        // Compute Effective Engine Load:
        // Blends driver pedal demand with physical EV motor torque.
        // Resolves the high-speed field-weakening drop-off so sound remains loud & aggressive.
        float torqueNorm = (currentTorqueNm > 0f) ? Math.min(1.0f, currentTorqueNm / 200.0f) : 0f;
        effectiveLoad = Math.max(currentThrottle, (currentThrottle * 0.60f + torqueNorm * 0.40f));

        // Deceleration overrun detector: rapid throttle drop with vehicle moving or in regen
        float throttleDrop = prevThrottle - currentThrottle;
        if (throttleDrop > 0.18f && currentSpeedKmH > 15f && currentThrottle < 0.10f) {
            overrunTimer = 1.4f; // 1.4 seconds of burble and crackles
        }
        if (overrunTimer > 0f) {
            overrunTimer -= 0.02f; // decay per frame update
        }
        prevThrottle = currentThrottle;

        // Idle cam lope oscillators
        lopePhase1 += 0.14;
        lopePhase2 += 0.08;
        if (lopePhase1 > 2.0 * Math.PI * 50.0) lopePhase1 -= 2.0 * Math.PI * 50.0;
        if (lopePhase2 > 2.0 * Math.PI * 50.0) lopePhase2 -= 2.0 * Math.PI * 50.0;

        if (currentSpeedKmH < 2.5f && currentThrottle < 0.05f) {
            // Stationary Idle: Natural crossplane lope
            currentGear = 0;
            float lopeOffset = (float) (Math.sin(lopePhase1) * 28.0 + Math.cos(lopePhase2) * 18.0);
            float targetIdle = BASE_IDLE_RPM + lopeOffset;
            currentRpm += (targetIdle - currentRpm) * 0.15f;
        } else if (currentSpeedKmH < 3.5f && currentThrottle >= 0.05f) {
            // Stationary Neutral Revving: Instant rev response
            currentGear = 0;
            float revTarget = BASE_IDLE_RPM + (currentThrottle * (REDLINE_RPM - BASE_IDLE_RPM) * 0.94f);
            currentRpm += (revTarget - currentRpm) * 0.22f;
        } else {
            // Moving: Mechanically locked gear RPM with torque-responsive shift points
            float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);
            if (currentGear == 0) currentGear = 1;

            // Dynamic shift schedule based on effective load
            // Under heavy wheel torque or high pedal, gears are held longer
            float upshiftRpm = 2200f + (effectiveLoad * 4300f);
            float downshiftRpm = 1350f + (effectiveLoad * 1800f);

            // Kickdown: sudden pedal stomp or high wheel torque downshifts for passing power
            boolean isKickdown = (currentThrottle > 0.68f || (currentTorqueNm > 170f && currentSpeedKmH > 15f));
            if (isKickdown && currentGear > 2) {
                float potentialLowerRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
                if (potentialLowerRpm < 5800f) {
                    currentGear--;
                    downshiftBlip = 500f;
                }
            }

            float rawGearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];

            // Upshift / Downshift evaluation
            if (rawGearRpm > upshiftRpm && currentGear < 6) {
                currentGear++;
                targetShiftCut = 0.82f; // Simulated transmission shift cut
            } else if (rawGearRpm < downshiftRpm && currentGear > 1 && !isKickdown) {
                currentGear--;
                downshiftBlip = 300f;
            }

            float lockedRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] + downshiftBlip;
            if (lockedRpm < BASE_IDLE_RPM) lockedRpm = BASE_IDLE_RPM;
            if (lockedRpm > REDLINE_RPM) lockedRpm = REDLINE_RPM;

            currentRpm += (lockedRpm - currentRpm) * 0.26f;
        }

        if (engineListener != null) {
            engineListener.onEngineStateChanged(currentRpm, currentGear);
        }
    }
}
