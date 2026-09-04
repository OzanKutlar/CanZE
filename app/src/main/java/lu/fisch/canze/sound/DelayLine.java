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
 * Fixed integer delay on a power of two ring, modelling acoustic time of flight down a pipe.
 */
public final class DelayLine {

    private float[] buffer = null;
    private int mask = 0;
    private int write = 0;
    private int delay = 0;

    public void initialize(int maxDelaySamples) {
        if (maxDelaySamples < 1) maxDelaySamples = 1;

        int n = 8;
        while (n < maxDelaySamples + 2) n <<= 1;

        buffer = new float[n];
        mask = n - 1;
        write = 0;
        delay = 0;
    }

    public void setDelay(int samples) {
        if (buffer == null) return;
        if (samples < 0) samples = 0;
        if (samples > mask - 1) samples = mask - 1;
        delay = samples;
    }

    public float f(float x) {
        if (buffer == null) return x;
        buffer[write] = x;
        final int read = (write - delay) & mask;
        write = (write + 1) & mask;
        return buffer[read];
    }
}
