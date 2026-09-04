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

/**
 * Schroeder all pass, used to smear the comb resonances when synthesising the impulse response.
 *
 * Without this the synthesised pipe is a stack of four clean resonant peaks, which reads as a
 * tuned tube rather than an exhaust system. The all pass leaves the magnitude response alone and
 * scrambles the phase, which is exactly the diffusion a real muffler provides.
 */
public final class AllPassFilter {

    private final float[] buffer;
    private final int length;
    private int index = 0;
    private float g = 0.5f;

    public AllPassFilter(int delaySamples) {
        if (delaySamples < 1) delaySamples = 1;
        this.length = delaySamples;
        this.buffer = new float[delaySamples];
    }

    public void setG(float g) {
        if (g < -0.95f) g = -0.95f;
        if (g > 0.95f) g = 0.95f;
        this.g = g;
    }

    public float f(float x) {
        final float buffered = buffer[index];
        final float out = -g * x + buffered;

        float stored = x + g * buffered;
        if (stored > -1e-25f && stored < 1e-25f) stored = 0f;
        buffer[index] = stored;

        index++;
        if (index >= length) index = 0;

        return out;
    }
}
