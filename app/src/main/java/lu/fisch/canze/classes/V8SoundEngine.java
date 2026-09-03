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
 * Real-time procedural V8 engine sound synthesizer and virtual transmission engine.
 * Synthesizes cross-plane V8 exhaust pulses, harmonic bank resonance, intake roar,
 * and deceleration overrun burble using low-latency AudioTrack streaming.
 */
public class V8SoundEngine {

    public interface EngineListener {
        void onEngineStateChanged(float rpm, int gear);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 1024;

    // Gearbox ratios (1st to 6th)
    private static final float[] GEAR_RATIOS = {3.60f, 2.20f, 1.50f, 1.10f, 0.85f, 0.68f};
    private static final float FINAL_DRIVE = 3.8f;
    private static final float WHEEL_CIRCUMFERENCE_M = 1.95f; // Fluence/Zoe wheel
    private static final float BASE_IDLE_RPM = 875f;
    private static final float REDLINE_RPM = 6800f;

    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;

    // Input state (thread-safe, signed torque)
    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Current smoothed simulation state
    private float currentRpm = BASE_IDLE_RPM;
    private int currentGear = 1;
    private float currentThrottle = 0f;
    private float currentTorqueNm = 0f;
    private float currentSpeedKmH = 0f;
    private float downshiftBlip = 0f;

    // Idle cam lope oscillator phases
    private double lopePhase1 = 0.0;
    private double lopePhase2 = 0.0;

    private EngineListener engineListener;
    private final Random random = new Random();

    public V8SoundEngine() {
    }

    public void setEngineListener(EngineListener listener) {
        this.engineListener = listener;
    }

    public void setInputs(float speedKmH, float pedalPerc, float torqueNm) {
        this.targetSpeedKmH = Math.max(0f, speedKmH);
        this.targetPedalPerc = Math.max(0f, Math.min(100f, pedalPerc));
        // signed torque: negative = regenerative braking / deceleration
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
        double phase = 0.0;
        float smoothedVolume = 0.0f;

        while (isRunning) {
            updateTransmission();

            float throttle = currentThrottle;
            float rpm = currentRpm;
            float torque = currentTorqueNm;

            // Fundamental crank frequency & firing frequency
            // V8 4-stroke = 4 firings per crank rev
            double fCrank = rpm / 60.0;
            double fFire = fCrank * 4.0;
            double phaseInc = (2.0 * Math.PI * fFire) / SAMPLE_RATE;

            // Engine torque is the main driver of volume and acoustic power:
            // - Negative torque (regen braking): moderate volume with hollow compression tone
            // - Positive torque: scales volume and saturation from quiet cruise to thunderous roar
            float targetVolume;
            if (isMuted) {
                targetVolume = 0.0f;
            } else if (torque < 0f) {
                // Regenerative braking volume based on deceleration strength
                float regenFactor = Math.min(1.0f, -torque / 120.0f);
                targetVolume = 0.35f + (regenFactor * 0.35f);
            } else {
                // Positive torque drive
                float loadFactor = Math.min(1.0f, torque / 180.0f);
                targetVolume = 0.28f + (loadFactor * 0.52f) + (throttle * 0.20f);
            }

            // Deceleration overrun / exhaust crackle trigger:
            // Happens during regen braking (negative torque) or sudden throttle lift-off
            boolean isDecelRegen = (torque < -10f && rpm > 1400f);
            float regenCrackleIntensity = isDecelRegen ? Math.min(1.0f, (-torque) / 100.0f) : 0f;

            for (int i = 0; i < BUFFER_SIZE; i++) {
                phase += phaseInc;
                if (phase > 2.0 * Math.PI * 1000.0) {
                    phase -= 2.0 * Math.PI * 1000.0;
                }

                // Smooth volume transitions to avoid audio clicks
                smoothedVolume += (targetVolume - smoothedVolume) * 0.003f;
                if (smoothedVolume < 0.001f) {
                    buffer[i] = 0;
                    continue;
                }

                // Cross-plane V8 multi-harmonic wave model
                // 1. Primary firing pulse
                double wave = Math.sin(phase);
                // 2. Second harmonic (cylinder head resonance)
                wave += 0.45 * Math.sin(phase * 2.0 + 0.3);
                // 3. Sub-harmonic (cross-plane bank lope / uneven burble at half firing rate)
                wave += 0.65 * Math.sin(phase * 0.5 + 0.8);
                // 4. Low rumble (quarter firing rate = single cylinder chug)
                wave += 0.35 * Math.sin(phase * 0.25 + 1.2);
                // 5. Higher octave bite
                wave += 0.20 * Math.sin(phase * 3.0 + 0.5);

                // If in regenerative braking, add hollow transmission compression harmonics
                if (isDecelRegen) {
                    wave += 0.30 * Math.sin(phase * 1.5 + 0.4);
                }

                // Non-linear soft saturation driven predominantly by torque load
                double drive;
                if (torque < 0f) {
                    // Lean backpressure saturation during regen
                    drive = 1.0 + (regenCrackleIntensity * 0.8);
                } else {
                    // Positive torque creates thick combustion distortion
                    drive = 1.0 + (torque / 160.0 * 1.8) + (throttle * 0.8);
                }
                double saturated = Math.tanh(wave * drive * 0.85);

                // Throttle intake rush
                if (throttle > 0.05f && torque >= 0f) {
                    double airNoise = (random.nextDouble() * 2.0 - 1.0) * (throttle * 0.16);
                    saturated += airNoise;
                }

                // Regenerative deceleration exhaust burble / crackle / pops
                if (isDecelRegen) {
                    // Pop frequency scales with negative torque strength
                    float popChance = 0.012f + (regenCrackleIntensity * 0.028f);
                    if (random.nextFloat() < popChance) {
                        double pop = (random.nextDouble() * 2.0 - 1.0) * (0.6 + regenCrackleIntensity * 0.7);
                        saturated += pop;
                    }
                }

                double finalSample = saturated * smoothedVolume * 30000.0;
                if (finalSample > 32767.0) finalSample = 32767.0;
                if (finalSample < -32768.0) finalSample = -32768.0;

                buffer[i] = (short) finalSample;
            }

            if (audioTrack != null && isRunning) {
                audioTrack.write(buffer, 0, BUFFER_SIZE);
            }
        }
    }

    /**
     * Virtual transmission state machine.
     * Computes gear and smooths engine RPM from speed and throttle inputs.
     */
    private void updateTransmission() {
        float speed = targetSpeedKmH;
        float throttle = targetPedalPerc / 100.0f;
        float torque = targetTorqueNm;

        // Smooth inputs
        currentThrottle += (throttle - currentThrottle) * 0.15f;
        currentTorqueNm += (torque - currentTorqueNm) * 0.18f;
        currentSpeedKmH += (speed - currentSpeedKmH) * 0.15f;
        downshiftBlip *= 0.82f; // Decay kickdown blip

        // Advance idle cam lope oscillators
        lopePhase1 += 0.14; // ~2.2 Hz wobble
        lopePhase2 += 0.08; // ~1.2 Hz wobble
        if (lopePhase1 > 2.0 * Math.PI * 100.0) lopePhase1 -= 2.0 * Math.PI * 100.0;
        if (lopePhase2 > 2.0 * Math.PI * 100.0) lopePhase2 -= 2.0 * Math.PI * 100.0;

        if (currentSpeedKmH < 2.0f && currentThrottle < 0.06f) {
            // Stationary Idle: Natural cross-plane lope between 850 and 900 RPM
            currentGear = 1;
            float lopeOffset = (float) (Math.sin(lopePhase1) * 18.0 + Math.cos(lopePhase2) * 12.0 + (random.nextFloat() - 0.5f) * 6.0);
            float targetIdle = BASE_IDLE_RPM + lopeOffset; // bobs around 850 - 900
            currentRpm += (targetIdle - currentRpm) * 0.15f;
        } else if (currentSpeedKmH < 3.0f && currentThrottle >= 0.06f) {
            // Stationary Neutral Revving: Smooth progressive revving based on pedal
            currentGear = 1;
            float revTarget = BASE_IDLE_RPM + (currentThrottle * (REDLINE_RPM - BASE_IDLE_RPM) * 0.88f);
            currentRpm += (revTarget - currentRpm) * 0.10f;
        } else {
            // Moving: Mechanically locked gear RPM with progressive shift logic
            float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);

            // Progressive, continuous shift points across 0% to 100% pedal:
            // - Light throttle (cruising): shifts early at 2,200 RPM
            // - Medium throttle: shifts around 3,500 - 4,500 RPM
            // - Full throttle (wide-open): holds gears up to 6,400 RPM
            float upshiftRpm = 2200f + (currentThrottle * 4200f);
            float downshiftRpm = 1350f + (currentThrottle * 1800f);

            // Kickdown check: sudden hard throttle drops down 1 or 2 gears
            boolean isKickdown = (currentThrottle > 0.70f);
            if (isKickdown && currentGear > 2) {
                float potentialLowerRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 2];
                if (potentialLowerRpm < 5800f) {
                    currentGear--; // Kickdown!
                    downshiftBlip = 450f; // Throttle blip
                }
            }

            float rawGearRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];

            // Upshift if RPM exceeds shift point
            if (rawGearRpm > upshiftRpm && currentGear < 6) {
                currentGear++;
            } else if (rawGearRpm < downshiftRpm && currentGear > 1 && !isKickdown) {
                currentGear--;
            }

            // Calculate mechanical gear RPM
            float lockedRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1] + downshiftBlip;

            // Clutch slip at very low speeds (< 12 km/h in 1st gear) so engine doesn't stall below idle
            if (lockedRpm < BASE_IDLE_RPM) {
                lockedRpm = BASE_IDLE_RPM;
            }
            if (lockedRpm > REDLINE_RPM) {
                lockedRpm = REDLINE_RPM;
            }

            // Smooth RPM tracking (high responsiveness to vehicle speed)
            currentRpm += (lockedRpm - currentRpm) * 0.22f;
        }

        if (engineListener != null) {
            int displayGear = (currentSpeedKmH < 2.0f && currentThrottle < 0.06f) ? 0 : currentGear;
            engineListener.onEngineStateChanged(currentRpm, displayGear);
        }
    }
}
