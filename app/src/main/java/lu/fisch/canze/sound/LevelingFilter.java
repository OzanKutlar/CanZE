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
 * Peak tracking automatic gain, ported from the engine simulator levelling filter.
 *
 * The synthesised signal swings enormously between idle and redline, and between coasting and
 * full load, because the pulse count and pulse energy both scale with the engine state. A fixed
 * output gain would either clip at load or vanish at idle. Tracking a decaying peak and dividing
 * by it keeps the perceived level steady without a conventional compressor.
 *
 * The two time constants are deliberately slower than the reference so the gain does not audibly
 * pump on individual combustion pulses.
 */
public final class LevelingFilter {

    private static final float PEAK_DECAY = 0.99985f;   // about 100 ms at 44.1 kHz
    private static final float GAIN_SMOOTH = 0.001f;    // about 23 ms at 44.1 kHz

    private float peak = 1f;
    private float attenuation = 1f;

    private float target = 0.75f;
    private float minLevel = 0.05f;
    private float maxLevel = 8.0f;

    public void setTarget(float target) {
        if (target > 0f) this.target = target;
    }

    public void setRange(float minLevel, float maxLevel) {
        if (minLevel > 0f) this.minLevel = minLevel;
        if (maxLevel > minLevel) this.maxLevel = maxLevel;
    }

    public float getAttenuation() {
        return attenuation;
    }

    public void reset() {
        peak = 1f;
        attenuation = 1f;
    }

    public float f(float x) {
        peak *= PEAK_DECAY;

        final float magnitude = Math.abs(x);
        if (magnitude > peak) peak = magnitude;
        if (peak < 1e-6f) return 0f;

        float wanted = target / peak;
        if (wanted < minLevel) wanted = minLevel;
        else if (wanted > maxLevel) wanted = maxLevel;

        attenuation += (wanted - attenuation) * GAIN_SMOOTH;

        return x * attenuation;
    }
}
