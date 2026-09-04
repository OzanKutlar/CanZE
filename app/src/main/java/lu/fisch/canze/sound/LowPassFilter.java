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
 * One pole RC low pass. Port of the engine simulator low_pass_filter fast path.
 *
 * Not thread safe: one instance per signal path, owned by the audio thread.
 */
public final class LowPassFilter {

    private float y = 0f;
    private float alpha = 1f;

    /**
     * @param cutoffHz   corner frequency, values at or below zero pass the signal through
     * @param sampleRate audio sample rate in Hz
     */
    public void setCutoff(float cutoffHz, float sampleRate) {
        if (cutoffHz <= 0f || sampleRate <= 0f) {
            alpha = 1f;
            return;
        }
        final float rc = 1f / (2f * (float) Math.PI * cutoffHz);
        final float dt = 1f / sampleRate;
        float a = dt / (rc + dt);
        if (a > 1f) a = 1f;
        if (a < 0f) a = 0f;
        alpha = a;
    }

    public float f(float x) {
        y += alpha * (x - y);
        // Denormals in a recursive path cost far more than the branch that avoids them.
        if (y > -1e-25f && y < 1e-25f) y = 0f;
        return y;
    }

    public void reset() {
        y = 0f;
    }
}
