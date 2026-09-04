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
 * First difference of the input, which is what puts the crack on the leading edge of a
 * combustion pulse.
 *
 * The engine simulator divides the difference by the sample period, producing values in the tens
 * of thousands that are then scaled back down by the blend weight. Here the 1/dt is folded into
 * a plain gain instead, so the output stays in the same numeric range as the dry signal and the
 * blend weight means what it looks like it means.
 */
public final class DerivativeFilter {

    private float previous = 0f;
    private float gain = 8f;

    public void setGain(float gain) {
        this.gain = gain;
    }

    public float f(float x) {
        final float d = x - previous;
        previous = x;
        return d * gain;
    }

    public void reset() {
        previous = 0f;
    }
}
