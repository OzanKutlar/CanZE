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
 * Fast attack, slow release peak limiter.
 *
 * This sits downstream of the levelling filter and does a different job. The leveller is
 * deliberately slow, because automatic gain that reacts faster than the musical gesture under it
 * will always chase the noise floor. That slowness leaves it unable to catch short resonant
 * spikes, and without something in front of the output stage those spikes reach the clamp, where
 * they read as grainy crackle rather than as loudness.
 *
 * So the two stages are split by time constant: the leveller handles level over hundreds of
 * milliseconds, this handles peaks over a few. Attack is instantaneous because a limiter that
 * lets the first spike through has not limited anything.
 */
public final class PeakLimiter {

    private static final float RELEASE = 0.9999f; // about 220 ms at 44.1 kHz

    private float envelope = 0f;
    private float threshold = 0.9f;

    /**
     * @param threshold level above which gain reduction begins, must be positive
     */
    public void setThreshold(float threshold) {
        if (threshold > 0f) this.threshold = threshold;
    }

    public void reset() {
        envelope = 0f;
    }

    /** @return current gain reduction, 1 meaning none. Useful for diagnostics. */
    public float getGain() {
        return (envelope > threshold) ? threshold / envelope : 1f;
    }

    public float f(float x) {
        final float magnitude = Math.abs(x);

        if (magnitude > envelope) {
            envelope = magnitude;
        } else {
            envelope *= RELEASE;
            if (envelope < 1e-20f) envelope = 0f;
        }

        if (envelope <= threshold) return x;
        return x * (threshold / envelope);
    }
}
