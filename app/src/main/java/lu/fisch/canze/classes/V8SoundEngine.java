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
    private static final float IDLE_RPM = 800f;
    private static final float REDLINE_RPM = 6800f;

    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;

    // Input state (thread-safe)
    private volatile float targetSpeedKmH = 0f;
    private volatile float targetPedalPerc = 0f;
    private volatile float targetTorqueNm = 0f;

    // Current smoothed simulation state
    private float currentRpm = IDLE_RPM;
    private int currentGear = 1;
    private float currentThrottle = 0f;
    private float lastThrottle = 0f;
    private float currentSpeedKmH = 0f;

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
        this.targetTorqueNm = Math.max(0f, torqueNm);
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
            float torque = targetTorqueNm;

            // Fundamental crank frequency & firing frequency
            // V8 4-stroke = 4 firings per crank rev
            double fCrank = rpm / 60.0;
            double fFire = fCrank * 4.0;
            double phaseInc = (2.0 * Math.PI * fFire) / SAMPLE_RATE;

            float targetVolume = isMuted ? 0.0f : Math.min(1.0f, 0.28f + (throttle * 0.52f) + (torque / 350f * 0.20f));

            // Deceleration overrun / crackle check
            boolean isOverrun = (lastThrottle > 0.35f && throttle < 0.12f && rpm > 2600f);
            lastThrottle = throttle;

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
                // 5. Higher octave bite / exhaust rush
                wave += 0.20 * Math.sin(phase * 3.0 + 0.5);

                // Non-linear soft saturation (cubic overdrive gives throaty roar)
                double drive = 1.0 + (throttle * 2.4) + (torque / 250.0 * 0.8);
                double saturated = Math.tanh(wave * drive * 0.85);

                // Throttle intake air rush (high-frequency noise)
                if (throttle > 0.05f) {
                    double airNoise = (random.nextDouble() * 2.0 - 1.0) * (throttle * 0.18);
                    saturated += airNoise;
                }

                // Deceleration exhaust pop / burble
                if (isOverrun && random.nextFloat() < 0.008f) {
                    saturated += (random.nextDouble() * 2.0 - 1.0) * 0.75;
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

        // Smooth inputs
        currentThrottle += (throttle - currentThrottle) * 0.12f;
        currentSpeedKmH += (speed - currentSpeedKmH) * 0.15f;

        float computedRpm;

        if (currentSpeedKmH < 1.0f) {
            // Stationary: Idle or Neutral Revving
            currentGear = 1;
            float freeRevTarget = IDLE_RPM + (currentThrottle * (REDLINE_RPM - IDLE_RPM) * 0.85f);
            currentRpm += (freeRevTarget - currentRpm) * 0.08f;
        } else {
            // Moving: Calculate gear and speed-matched RPM
            float wheelRpm = (currentSpeedKmH * 1000f) / (WHEEL_CIRCUMFERENCE_M * 60f);

            // Automatic shift point calculation based on throttle aggression
            float upshiftRpm = 3400f + (currentThrottle * 2600f); // 3400 to 6000 RPM
            float downshiftRpm = 1700f + (currentThrottle * 1000f); // 1700 to 2700 RPM

            float potentialRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];

            if (potentialRpm > upshiftRpm && currentGear < 6) {
                currentGear++;
            } else if (potentialRpm < downshiftRpm && currentGear > 1) {
                currentGear--;
            }

            float gearMatchedRpm = wheelRpm * FINAL_DRIVE * GEAR_RATIOS[currentGear - 1];
            if (gearMatchedRpm < IDLE_RPM) gearMatchedRpm = IDLE_RPM;
            if (gearMatchedRpm > REDLINE_RPM) gearMatchedRpm = REDLINE_RPM;

            currentRpm += (gearMatchedRpm - currentRpm) * 0.15f;
        }

        if (engineListener != null) {
            engineListener.onEngineStateChanged(currentRpm, currentSpeedKmH < 1.0f && currentThrottle < 0.05f ? 0 : currentGear);
        }
    }
}
