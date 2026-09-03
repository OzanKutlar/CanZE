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
 * Deep-rumble real-time procedural cross-plane V8 engine sound synthesizer.
 * Generates authentic low-end acoustic blowdown waves (50 - 250 Hz),
 * crossplane bank offset burble (1-8-4-3-6-5-7-2), low-pass muffler cavity acoustics,
 * and torque-coupled multi-gear transmission dynamics.
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

    // Transmission gear ratios (1st to 6th) - tuned for authentic V8 muscle spacing
    private static final float[] GEAR_RATIOS = {3.45f, 2.15f, 1.52f, 1.12f, 0.86f, 0.68f};
    private static final float FINAL_DRIVE = 3.65f;
    private static final float WHEEL_CIRCUMFERENCE_M = 1.95f;
    private static final float BASE_IDLE_RPM = 780f;
    private static final float REDLINE_RPM = 6600f;

    // Minimum road speeds (km/h) required before upshifting into each gear [1->2, 2->3, 3->4, 4->5, 5->6]
    // Guarantees 6th gear only engages at ~100 km/h and holds 3rd/4th gear longer
    private static final float[] MIN_UPSHIFT_SPEEDS = {0f, 28f, 50f, 70f, 88f, 99f};

    // Acoustic delay lines for header length propagation
    private static final int DELAY_SAMPLES = 64; // ~1.5 ms sound travel delay
    private final double[] bank1Delay = new double[DELAY_SAMPLES];
    private final double[] bank2Delay = new double[DELAY_SAMPLES];
    private int delayIdx = 0;

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
    private float prevSpeedKmH = 0f;
    private float vehicleAccel = 0f;
    private float cruiseTimer = 0f;
    private float effectiveLoad = 0f;
    private float downshiftBlip = 0f;
    private float targetShiftCut = 1.0f;
    private float smoothedShiftCut = 1.0f;

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

    // Acoustic filter states (smooth muffler integration)
    private double bank1Lp = 0.0;
    private double bank2Lp = 0.0;
    private double mufflerLp = 0.0;
    private double bodyResonator = 0.0;
    private double hpIn = 0.0;
    private double hpOut = 0.0;

    private final Random rng = new Random();
    private EngineListener engineListener;

    public V8SoundEngine() {
    }

    public void setEngineListener(EngineListener listener) {
        this.engineListener = listener;
    }

    public void setInputs(float speedKmH, float pedalPerc, float torqueNm) {
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
        float smoothedMasterVolume = 0.0f;

        while (isRunning) {
            updateTransmission();

            float rpm = currentRpm;
            float load = effectiveLoad;
            float torque = currentTorqueNm;

            // Angular speed of crankshaft in radians per sample with organic cycle flutter
            double crankRadPerSample = ((rpm * crankCycleFlutter) / 60.0) * (2.0 * Math.PI) / SAMPLE_RATE;

            // Sub-bass reinforcement oscillator (tuned to engine firing cadence)
            // 4 cylinder fires per rev = 4th order harmonic of crank revs
            double firingOrderRadPerSample = crankRadPerSample * 2.0;

            // Target internal dynamic volume (load and regen modulation)
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

            float targetMaster = isMuted ? 0.0f : masterVolume;

            for (int i = 0; i < BUFFER_SIZE; i++) {
                // Advance crank phase across 720 degree cycle (4*PI)
                crankPhase += crankRadPerSample;
                if (crankPhase >= CYCLE_RADIANS) {
                    crankPhase -= CYCLE_RADIANS;
                    // Cylinder combustion variance: minute micro-flutter per 720° engine cycle (±0.35%)
                    crankCycleFlutter = 1.0 + (rng.nextDouble() - 0.5) * 0.007;
                }

                subBassPhase += firingOrderRadPerSample;
                if (subBassPhase >= 2.0 * Math.PI) {
                    subBassPhase -= 2.0 * Math.PI;
                }

                smoothedVolume += (targetVolume - smoothedVolume) * 0.005f;
                smoothedShiftCut += (targetShiftCut - smoothedShiftCut) * 0.008f;
                smoothedMasterVolume += (targetMaster - smoothedMasterVolume) * 0.008f;

                if (smoothedVolume < 0.001f || smoothedMasterVolume < 0.001f) {
                    buffer[i] = 0;
                    continue;
                }

                // 1. Synthesize smooth acoustic pressure waves for each cylinder
                double bank1Raw = 0.0;
                double bank2Raw = 0.0;

                // Bank 1 (Left): Cylinders 1, 3, 5, 7
                bank1Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[0], load);
                bank1Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[2], load);
                bank1Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[4], load);
                bank1Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[6], load);

                // Bank 2 (Right): Cylinders 8, 4, 6, 2
                bank2Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[7], load);
                bank2Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[3], load);
                bank2Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[5], load);
                bank2Raw += evaluateSmoothPulse(crankPhase, CYLINDER_FIRING_ANGLES[1], load);

                // 2. Primary header tube acoustic propagation delay
                bank1Delay[delayIdx] = bank1Raw;
                bank2Delay[delayIdx] = bank2Raw;
                delayIdx = (delayIdx + 1) % DELAY_SAMPLES;
                double bank1Delayed = bank1Delay[delayIdx];
                double bank2Delayed = bank2Delay[delayIdx];

                // 3. Exhaust manifold acoustic low-pass filter (rolls off harsh highs above 950 Hz)
                bank1Lp += (bank1Delayed - bank1Lp) * 0.14;
                bank2Lp += (bank2Delayed - bank2Lp) * 0.14;

                // 4. X-Pipe Crossover (75% direct bank + 25% crossover balance)
                // Preserves the distinct asymmetric crossplane rumble without cancelling
                double leftPipe = bank1Lp * 0.75 + bank2Lp * 0.25;
                double rightPipe = bank2Lp * 0.75 + bank1Lp * 0.25;
                double exhaustMix = (leftPipe + rightPipe) * 0.50;

                // 5. Deep Muffler Cavity Resonator (creates that chest-thumping muscle-car bass)
                bodyResonator += (exhaustMix - bodyResonator) * 0.035;
                mufflerLp += (exhaustMix - mufflerLp) * 0.09;
                double deepTone = (exhaustMix * 0.70) + (bodyResonator * 0.60) + (mufflerLp * 0.40);

                // 6. Sub-bass acoustic foundation (gives authentic low V8 body at 50-120 Hz)
                double subBass = Math.sin(subBassPhase) * (0.35 + load * 0.25);
                double compositeSignal = deepTone + subBass;

                // 7. Subsonic High-Pass Filter (~22 Hz cutoff)
                // Removes inaudible DC offset while retaining 100% of audible low bass
                double hpAlpha = 0.9965;
                hpOut = hpAlpha * (hpOut + compositeSignal - hpIn);
                hpIn = compositeSignal;
                double cleanSignal = hpOut;

                // 8. Warm Tube-Style Saturation (Deep Growl under load)
                // Produces rich 2nd/3rd harmonics without high-pitched buzz
                double drive = 1.35 + (load * 1.65);
                double driven = cleanSignal * drive;
                double warmedSignal = driven / (1.0 + Math.abs(driven) * 0.38);

                // 9. Master Volume Scaling (3.8x louder baseline at 100% volume setting)
                // Driven by smoothedMasterVolume so slider responds smoothly & immediately
                double masterGain = warmedSignal * smoothedVolume * smoothedShiftCut * smoothedMasterVolume * 115000.0;

                // Transparent soft-knee limiter (preserves deep rumble without harsh clipping)
                double finalSample;
                if (masterGain > 27500.0) {
                    finalSample = 27500.0 + 3500.0 * Math.tanh((masterGain - 27500.0) / 3500.0);
                } else if (masterGain < -27500.0) {
                    finalSample = -27500.0 + 3500.0 * Math.tanh((masterGain + 27500.0) / 3500.0);
                } else {
                    finalSample = masterGain;
                }

                if (finalSample > 31500.0) finalSample = 31500.0;
                if (finalSample < -31500.0) finalSample = -31500.0;

                buffer[i] = (short) finalSample;
            }

            if (audioTrack != null && isRunning) {
                audioTrack.write(buffer, 0, BUFFER_SIZE);
            }
        }
    }

    /**
     * Evaluates a smooth acoustic blowdown pressure lobe.
     * Uses a raised-cosine (sin^2) shape over ~170 degrees of crank rotation,
     * ensuring zero derivative at wave boundaries (completely eliminates high-pitched buzz).
     */
    private double evaluateSmoothPulse(double currentCrankAngle, double firingAngle, float load) {
        double delta = currentCrankAngle - firingAngle;
        if (delta < 0.0) delta += CYCLE_RADIANS;
        if (delta >= CYCLE_RADIANS) delta -= CYCLE_RADIANS;

        // Exhaust blowdown duration: 170 degrees of crank rotation
        double duration = 170.0 * Math.PI / 180.0;
        if (delta < duration) {
            double x = delta / duration;
            // Raised cosine pulse (perfectly smooth, pure low-end energy)
            double s = Math.sin(x * Math.PI);
            double pulse = s * s * Math.exp(-0.85 * x);
            double amplitude = 0.70 + (load * 0.75);
            return pulse * amplitude;
        }
        return 0.0;
    }

    /**
     * Virtual transmission and load state machine.
     * Coordinates gear ratios, torque converter slip, organic RPM wander, and idle lope.
     */
    private void updateTransmission() {
        float speed = targetSpeedKmH;
        float rawThrottle = targetPedalPerc / 100.0f;
        float torque = targetTorqueNm;

        // 1. Organic driver foot / throttle plate micro-tremor (±0.6% wander)
        if (rng.nextFloat() < 0.15f) {
            pedalWanderTarget = (rng.nextFloat() - 0.5f) * 0.012f;
        }
        pedalWanderSmoothed += (pedalWanderTarget - pedalWanderSmoothed) * 0.08f;
        float throttleWithTremor = Math.max(0.0f, Math.min(1.0f, rawThrottle + (rawThrottle > 0.02f ? pedalWanderSmoothed : 0.0f)));

        // Fast-attack, smooth-release throttle response:
        // When pressing the pedal down, react immediately (0.65f attack) so sound begins on the very first packet.
        // When releasing the pedal, decay smoothly (0.22f release) to simulate natural engine inertia.
        float throttleDelta = throttleWithTremor - currentThrottle;
        float throttleRate = (throttleDelta > 0f) ? 0.65f : 0.22f;
        currentThrottle += throttleDelta * throttleRate;

        // Torque fast attack on load demand
        float torqueDelta = torque - currentTorqueNm;
        float torqueRate = (torqueDelta > 0f) ? 0.55f : 0.22f;
        currentTorqueNm += torqueDelta * torqueRate;

        currentSpeedKmH += (speed - currentSpeedKmH) * 0.20f;
        downshiftBlip *= 0.84f;
        targetShiftCut += (1.0f - targetShiftCut) * 0.16f;

        // Calculate vehicle acceleration (km/h per frame update)
        vehicleAccel = currentSpeedKmH - prevSpeedKmH;
        prevSpeedKmH = currentSpeedKmH;

        // Detect steady-state cruise vs acceleration
        boolean isCruising = (Math.abs(vehicleAccel) < 0.12f && currentSpeedKmH > 25.0f);
        if (isCruising) {
            cruiseTimer = Math.min(2.0f, cruiseTimer + 0.05f);
        } else {
            cruiseTimer = Math.max(0.0f, cruiseTimer - 0.10f);
        }

        // Effective Engine Load: blends pedal demand with motor pull
        // Relaxes load significantly during steady cruise so engine doesn't roar
        float torqueNorm = (currentTorqueNm > 0f) ? Math.min(1.0f, currentTorqueNm / 180.0f) : 0f;
        float rawLoad = Math.max(currentThrottle, (currentThrottle * 0.55f + torqueNorm * 0.45f));
        float cruiseLoadFactor = 1.0f - (cruiseTimer / 2.0f * 0.45f); // Drops acoustic load by up to 45% during cruise
        effectiveLoad = rawLoad * cruiseLoadFactor;

        // Advance idle cam lope oscillator
        lopePhase += 0.12;
        if (lopePhase > 2.0 * Math.PI * 100.0) lopePhase -= 2.0 * Math.PI * 100.0;

        // Advance dual cruising organic breathe oscillators (0.65 Hz & 1.35 Hz)
        cruiseWanderPhase1 += 0.085;
        cruiseWanderPhase2 += 0.176;
        if (cruiseWanderPhase1 > 2.0 * Math.PI * 100.0) cruiseWanderPhase1 -= 2.0 * Math.PI * 100.0;
        if (cruiseWanderPhase2 > 2.0 * Math.PI * 100.0) cruiseWanderPhase2 -= 2.0 * Math.PI * 100.0;

        if (currentSpeedKmH < 2.5f && currentThrottle < 0.05f) {
            // Stationary Idle: Natural crossplane lope
            currentGear = 0;
            float lopeOffset = (float) (Math.sin(lopePhase) * 25.0 + Math.cos(lopePhase * 0.65) * 18.0);
            float targetIdle = BASE_IDLE_RPM + lopeOffset;
            currentRpm += (targetIdle - currentRpm) * 0.14f;
        } else if (currentSpeedKmH < 3.5f && currentThrottle >= 0.05f) {
            // Stationary Neutral Revving: Instantaneous rev response with subtle lope flutter
            currentGear = 0;
            float lopeFlutter = (float) (Math.sin(cruiseWanderPhase1) * 22.0);
            float revTarget = BASE_IDLE_RPM + (currentThrottle * (REDLINE_RPM - BASE_IDLE_RPM) * 0.92f) + lopeFlutter;
            currentRpm += (revTarget - currentRpm) * 0.20f;
        } else {
            // Moving: Mechanically geared RPM with torque converter fluid coupling
            float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);
            if (currentGear == 0) currentGear = 1;

            // Progressive shift points based on effective load and cruise state:
            // - While accelerating: shifts later (2,400 - 5,600 RPM) for strong gear pull
            // - While cruising at steady speed: relaxes to ~1,800 - 2,100 RPM
            float baseUpshift = 2300f - (cruiseTimer / 2.0f * 400f);
            float upshiftRpm = baseUpshift + (effectiveLoad * 3700f);
            float downshiftRpm = 1250f + (effectiveLoad * 1400f);

            // Kickdown on sudden throttle stomp or strong wheel torque
            boolean isKickdown = (currentThrottle > 0.68f || (currentTorqueNm > 175f && currentSpeedKmH > 15f));
            if (isKickdown && currentGear > 2) {
                float potentialLowerRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
                if (potentialLowerRpm < 5800f) {
                    currentGear--;
                    downshiftBlip = 480f;
                    cruiseTimer = 0f;
                }
            }

            float rawGearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];
            float minSpeedForNextGear = (currentGear < 6) ? MIN_UPSHIFT_SPEEDS[currentGear] : 999f;

            // Shift evaluation with speed gates & subtle transmission acoustic dip
            if (rawGearRpm > upshiftRpm && currentGear < 6 && currentSpeedKmH >= minSpeedForNextGear) {
                currentGear++;
                targetShiftCut = 0.82f;
            } else if (rawGearRpm < downshiftRpm && currentGear > 1 && !isKickdown) {
                currentGear--;
                downshiftBlip = 260f;
            }

            // 2. Torque converter slip: fluid coupling slips 1.8% to 4.2% based on engine load
            float converterSlip = 1.018f + (effectiveLoad * 0.024f);

            // 3. Low-frequency cruising RPM breathe (±18 to ±32 RPM wander)
            // Eliminates the static mathematical drone during steady cruise control
            if (rng.nextFloat() < 0.12f) {
                rpmWanderTarget = (rng.nextFloat() - 0.5f) * 26.0f;
            }
            rpmWanderSmoothed += (rpmWanderTarget - rpmWanderSmoothed) * 0.06f;

            float harmonicBreathe = (float) (Math.sin(cruiseWanderPhase1) * 14.0 + Math.cos(cruiseWanderPhase2) * 8.0);
            float organicRpmOffset = harmonicBreathe + rpmWanderSmoothed;

            float targetDynamicRpm = (wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] * converterSlip)
                    + downshiftBlip
                    + organicRpmOffset;

            if (targetDynamicRpm < BASE_IDLE_RPM) targetDynamicRpm = BASE_IDLE_RPM;
            if (targetDynamicRpm > REDLINE_RPM) targetDynamicRpm = REDLINE_RPM;

            currentRpm += (targetDynamicRpm - currentRpm) * 0.22f;
        }

        if (engineListener != null) {
            engineListener.onEngineStateChanged(currentRpm, currentGear);
        }
    }
}
