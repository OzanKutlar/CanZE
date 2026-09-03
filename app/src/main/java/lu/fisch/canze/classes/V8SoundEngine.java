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
 * Real-time procedural cross-plane V8 engine sound synthesizer and virtual transmission.
 * Implements physical dual-bank exhaust pulse timing (1-8-4-3-6-5-7-2),
 * X-pipe acoustic crossover, Helmholtz intake roar, and overrun burble during regen braking.
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
    // Odd cylinders = Left Bank (1, 3, 5, 7), Even cylinders = Right Bank (2, 4, 6, 8)
    // Firing angles in radians across 720 degree cycle:
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
    private static final float BASE_IDLE_RPM = 820f;
    private static final float REDLINE_RPM = 6800f;

    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;

    // Telemetry inputs (thread-safe, signed torque)
    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Dynamic simulation state
    private float currentRpm = BASE_IDLE_RPM;
    private int currentGear = 1;
    private float currentThrottle = 0f;
    private float currentTorqueNm = 0f;
    private float currentSpeedKmH = 0f;
    private float downshiftBlip = 0f;
    private float shiftTorqueCut = 1.0f;

    // Oscillators & acoustics
    private double crankPhase = 0.0;
    private double lopePhase1 = 0.0;
    private double lopePhase2 = 0.0;
    private double intakePhase = 0.0;
    private double regenModPhase = 0.0;

    // Acoustic resonator filter memory
    private double bank1Filter = 0.0;
    private double bank2Filter = 0.0;
    private double exhaustBodyFilter = 0.0;

    private final Random random = new Random();
    private EngineListener engineListener;

    public V8SoundEngine() {
    }

    public void setEngineListener(EngineListener listener) {
        this.engineListener = listener;
    }

    public void setInputs(float speedKmH, float pedalPerc, float torqueNm) {
        this.targetSpeedKmH = Math.max(0f, speedKmH);
        this.targetPedalPerc = Math.max(0f, Math.min(100f, pedalPerc));
        // signed torque: negative = regen braking, positive = motor pull
        this.targetTorqueNm = Math.max(-200f, Math.min(350f, torqueNm));
    }

    public void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    public boolean isMuted() {
        return isMuted;
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

            // Crankshaft rotation angular velocity in radians per sample
            // 1 crank rev = 2 * PI radians, RPM = revs per min
            double crankRadPerSample = (rpm / 60.0) * (2.0 * Math.PI) / SAMPLE_RATE;

            // Target master volume based on torque and throttle
            float targetVolume;
            if (isMuted) {
                targetVolume = 0.0f;
            } else if (torque < -5f) {
                // Regenerative braking: deep hollow compression tone
                float regenFactor = Math.min(1.0f, -torque / 140.0f);
                targetVolume = 0.32f + (regenFactor * 0.36f);
            } else {
                // Driving power: scaling with torque pull and throttle opening
                float loadFactor = Math.min(1.0f, Math.max(0f, torque) / 180.0f);
                targetVolume = 0.26f + (loadFactor * 0.54f) + (throttle * 0.20f);
            }

            // Deceleration regenerative braking detection
            boolean isDecelRegen = (torque < -10f && rpm > 1100f);
            float regenIntensity = isDecelRegen ? Math.min(1.0f, (-torque) / 130.0f) : 0f;
            double regenModInc = (2.0 * Math.PI * (7.0 + regenIntensity * 8.0)) / SAMPLE_RATE;

            // Helmholtz induction frequency (around 80 - 150 Hz with RPM)
            double intakeFreq = 70.0 + (rpm * 0.025);
            double intakeInc = (2.0 * Math.PI * intakeFreq) / SAMPLE_RATE;

            for (int i = 0; i < BUFFER_SIZE; i++) {
                // Advance crank phase across 720 degree (CYCLE_RADIANS) cycle
                crankPhase += crankRadPerSample;
                if (crankPhase >= CYCLE_RADIANS) {
                    crankPhase -= CYCLE_RADIANS;
                }

                intakePhase += intakeInc;
                if (intakePhase >= 2.0 * Math.PI) {
                    intakePhase -= 2.0 * Math.PI;
                }

                regenModPhase += regenModInc;
                if (regenModPhase >= 2.0 * Math.PI) {
                    regenModPhase -= 2.0 * Math.PI;
                }

                // Smooth volume changes to prevent clicks
                smoothedVolume += (targetVolume - smoothedVolume) * 0.004f;
                if (smoothedVolume < 0.001f) {
                    buffer[i] = 0;
                    continue;
                }

                // Synthesize individual cylinder exhaust blowdown pulses
                double bank1Raw = 0.0;
                double bank2Raw = 0.0;

                // Cylinders 1, 3, 5, 7 -> Left Bank
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[0]);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[2]);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[4]);
                bank1Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[6]);

                // Cylinders 8, 4, 6, 2 -> Right Bank
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[1]);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[3]);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[5]);
                bank2Raw += evaluateCylinderPulse(crankPhase, CYLINDER_FIRING_ANGLES[7]);

                // Acoustic collector manifold low-pass resonators
                bank1Filter += (bank1Raw - bank1Filter) * 0.28;
                bank2Filter += (bank2Raw - bank2Filter) * 0.28;

                // Exhaust X-Pipe crossover: 75% primary bank + 25% crossover balance
                double leftExhaust = bank1Filter * 0.75 + bank2Filter * 0.25;
                double rightExhaust = bank2Filter * 0.75 + bank1Filter * 0.25;
                double exhaustMix = (leftExhaust + rightExhaust) * 0.5;

                // Exhaust pipe resonance body filter
                exhaustBodyFilter += (exhaustMix - exhaustBodyFilter) * 0.16;
                double exhaustSignal = exhaustMix + (exhaustBodyFilter * 0.45);

                // Deep sub-octave rumble for high displacement feel (5.0L - 6.2L V8 body)
                double subRumble = Math.sin(crankPhase * 0.5) * 0.35 + Math.sin(crankPhase * 0.25) * 0.22;
                exhaustSignal += subRumble;

                // Throttle body Helmholtz induction roar (throaty intake growl on load)
                if (throttle > 0.05f && torque >= 0f) {
                    double intakeRoar = Math.sin(intakePhase) * (throttle * 0.42);
                    exhaustSignal += intakeRoar;
                }

                // Regenerative braking overrun burble & deceleration pops
                if (isDecelRegen) {
                    double compressionTone = Math.sin(crankPhase * 1.5) * 0.30;
                    double burbleMod = 1.0 + (regenIntensity * 0.40 * Math.sin(regenModPhase));
                    exhaustSignal = (exhaustSignal * burbleMod) + compressionTone;

                    // Occasional overrun crackle on sharp throttle lift-off
                    if (throttle < 0.05f && random.nextFloat() < (0.003f * regenIntensity)) {
                        exhaustSignal += (random.nextFloat() * 1.6 - 0.8);
                    }
                }

                // Warm non-linear tube/combustion saturation (cubic / tanh soft overdrive)
                double drive = 1.0 + (Math.max(0f, torque) / 130.0 * 1.5) + (throttle * 0.6);
                double saturated = Math.tanh(exhaustSignal * drive * 0.85);

                // Output amplitude scaling with smooth ceiling limiting
                double finalSample = saturated * smoothedVolume * shiftTorqueCut * 28000.0;
                if (finalSample > 32000.0) finalSample = 32000.0;
                if (finalSample < -32000.0) finalSample = -32000.0;

                buffer[i] = (short) finalSample;
            }

            if (audioTrack != null && isRunning) {
                audioTrack.write(buffer, 0, BUFFER_SIZE);
            }
        }
    }

    /**
     * Evaluates an individual cylinder's blowdown pressure pulse as a function of crank angle.
     */
    private double evaluateCylinderPulse(double currentCrankAngle, double firingAngle) {
        double delta = currentCrankAngle - firingAngle;
        if (delta < 0.0) delta += CYCLE_RADIANS;
        if (delta >= CYCLE_RADIANS) delta -= CYCLE_RADIANS;

        // Exhaust valve blowdown duration is approx 150 degrees of crank rotation (0.833 * PI rad)
        double duration = 150.0 * Math.PI / 180.0;
        if (delta < duration) {
            double x = delta / duration;
            // Asymmetric sharp rise, exponential pressure decay curve
            return Math.sin(x * Math.PI) * Math.exp(-2.2 * x);
        }
        return 0.0;
    }

    /**
     * Virtual transmission state machine.
     * Computes virtual gear, mechanical RPM, downshift kickdown, and idle cam lope.
     */
    private void updateTransmission() {
        float speed = targetSpeedKmH;
        float throttle = targetPedalPerc / 100.0f;
        float torque = targetTorqueNm;

        // Smooth inputs
        currentThrottle += (throttle - currentThrottle) * 0.16f;
        currentTorqueNm += (torque - currentTorqueNm) * 0.18f;
        currentSpeedKmH += (speed - currentSpeedKmH) * 0.15f;
        downshiftBlip *= 0.82f;
        shiftTorqueCut += (1.0f - shiftTorqueCut) * 0.15f;

        // Advance idle cam lope wobble oscillators
        lopePhase1 += 0.14;
        lopePhase2 += 0.08;
        if (lopePhase1 > 2.0 * Math.PI * 50.0) lopePhase1 -= 2.0 * Math.PI * 50.0;
        if (lopePhase2 > 2.0 * Math.PI * 50.0) lopePhase2 -= 2.0 * Math.PI * 50.0;

        if (currentSpeedKmH < 2.0f && currentThrottle < 0.06f) {
            // Stationary Idle: Natural cross-plane lope between 780 and 860 RPM
            currentGear = 0;
            float lopeOffset = (float) (Math.sin(lopePhase1) * 26.0 + Math.cos(lopePhase2) * 16.0);
            float targetIdle = BASE_IDLE_RPM + lopeOffset;
            currentRpm += (targetIdle - currentRpm) * 0.14f;
        } else if (currentSpeedKmH < 3.0f && currentThrottle >= 0.06f) {
            // Stationary Neutral Revving: Instantaneous rev response up to redline
            currentGear = 0;
            float revTarget = BASE_IDLE_RPM + (currentThrottle * (REDLINE_RPM - BASE_IDLE_RPM) * 0.92f);
            currentRpm += (revTarget - currentRpm) * 0.18f;
        } else {
            // Moving: Mechanically locked gear RPM with progressive shift schedule
            float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);
            if (currentGear == 0) currentGear = 1;

            // Dynamic shift points:
            // - Light throttle cruise: shifts early (2,100 RPM)
            // - Hard throttle pull: holds gear up to 6,300 RPM
            float upshiftRpm = 2100f + (currentThrottle * 4200f);
            float downshiftRpm = 1300f + (currentThrottle * 1700f);

            // Kickdown check: sudden hard throttle drops 1 or 2 gears
            boolean isKickdown = (currentThrottle > 0.72f);
            if (isKickdown && currentGear > 2) {
                float potentialLowerRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
                if (potentialLowerRpm < 5700f) {
                    currentGear--;
                    downshiftBlip = 450f;
                }
            }

            float rawGearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];

            // Shift logic with momentary torque cut for realistic shift feeling
            if (rawGearRpm > upshiftRpm && currentGear < 6) {
                currentGear++;
                shiftTorqueCut = 0.55f; // Gear change drop
            } else if (rawGearRpm < downshiftRpm && currentGear > 1 && !isKickdown) {
                currentGear--;
                downshiftBlip = 300f;
            }

            float lockedRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] + downshiftBlip;
            if (lockedRpm < BASE_IDLE_RPM) lockedRpm = BASE_IDLE_RPM;
            if (lockedRpm > REDLINE_RPM) lockedRpm = REDLINE_RPM;

            currentRpm += (lockedRpm - currentRpm) * 0.24f;
        }

        if (engineListener != null) {
            engineListener.onEngineStateChanged(currentRpm, currentGear);
        }
    }
}
