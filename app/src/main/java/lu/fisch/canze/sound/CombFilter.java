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
 * Damped feedback comb, ported from the engine simulator feedback comb filter with an added
 * one pole in the feedback path so the tail loses its highs the way a real pipe does.
 *
 * Used only to synthesise the impulse response at start up, never in the per sample audio path,
 * so an exact modulo ring is fine here.
 */
public final class CombFilter {

    private final float[] buffer;
    private final int length;
    private int index = 0;
    private float store = 0f;

    private float feedback = 0.8f;
    private float damping = 0.35f;

    /**
     * @param delaySamples loop length in samples, must be positive
     */
    public CombFilter(int delaySamples) {
        if (delaySamples < 1) delaySamples = 1;
        this.length = delaySamples;
        this.buffer = new float[delaySamples];
    }

    /**
     * @param feedback loop gain, clamped below unity so the filter can never run away
     */
    public void setFeedback(float feedback) {
        if (feedback < 0f) feedback = 0f;
        if (feedback > 0.995f) feedback = 0.995f;
        this.feedback = feedback;
    }

    public void setDamping(float damping) {
        if (damping < 0f) damping = 0f;
        if (damping > 0.95f) damping = 0.95f;
        this.damping = damping;
    }

    public float f(float x) {
        final float y = buffer[index];

        store = y * (1f - damping) + store * damping;
        if (store > -1e-25f && store < 1e-25f) store = 0f;

        buffer[index] = x + feedback * store;

        index++;
        if (index >= length) index = 0;

        return y;
    }
}
