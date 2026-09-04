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

package lu.fisch.canze.sound;

import java.util.Random;

/**
 * Reads the signal back through a slowly wandering fractional delay.
 *
 * This is the engine simulator jitter idea: rather than adding noise to the amplitude, the read
 * position itself is modulated by low passed noise. The result is timing irregularity between
 * combustion events, which is the difference between an engine and a metronome. Amplitude noise
 * alone cannot produce it.
 */
public final class JitterFilter {

    private final LowPassFilter noiseFilter = new LowPassFilter();
    private final Random rng = new Random();

    private float[] history = null;
    private int mask = 0;
    private int write = 0;
    private int maxJitter = 0;
    private float scale = 0f;

    /**
     * @param maxJitterSamples largest read offset in samples, must be positive
     * @param cutoffHz         how fast the offset is allowed to wander
     * @param sampleRate       audio sample rate in Hz
     */
    public void initialize(int maxJitterSamples, float cutoffHz, float sampleRate) {
        if (maxJitterSamples < 1) maxJitterSamples = 1;

        int n = 8;
        while (n < maxJitterSamples + 4) n <<= 1;

        history = new float[n];
        mask = n - 1;
        write = 0;
        maxJitter = maxJitterSamples;
        noiseFilter.setCutoff(cutoffHz, sampleRate);
    }

    /**
     * @param scale 0 disables jitter entirely, 1 uses the full configured range
     */
    public void setScale(float scale) {
        if (scale < 0f) scale = 0f;
        if (scale > 1f) scale = 1f;
        this.scale = scale;
    }

    public float f(float x) {
        if (history == null) return x;

        history[write] = x;

        final float noise = 2f * rng.nextFloat() - 1f;
        final float wander = noiseFilter.f(noise);

        float d = (wander * 0.5f + 0.5f) * scale * maxJitter;
        if (d < 0f) d = 0f;
        if (d > maxJitter) d = maxJitter;

        final int whole = (int) d;
        final float frac = d - whole;

        final int a = (write - whole) & mask;
        final int b = (write - whole - 1) & mask;
        final float out = history[a] * (1f - frac) + history[b] * frac;

        write = (write + 1) & mask;
        return out;
    }
}
