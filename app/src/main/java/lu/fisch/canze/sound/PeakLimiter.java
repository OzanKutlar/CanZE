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
 * This sits underneath the levelling filter rather than replacing it, because the two solve
 * different problems and want opposite time constants. The leveller corrects long term loudness
 * and must be slower than the musical gesture it sits under, otherwise it hunts for the noise
 * floor whenever the signal legitimately gets quiet. That slowness leaves it unable to catch a
 * sudden resonant build up, which then reaches the hard clamp and clips into a full harmonic
 * stack.
 *
 * The limiter covers exactly that gap: it does nothing at all below the ceiling, and above it
 * pulls the gain down within a couple of milliseconds.
 */
public final class PeakLimiter {

    private float envelope = 0f;
    private float ceiling = 0.9f;
    private float attack = 1f;
    private float release = 0.001f;

    /**
     * @param sampleRate audio sample rate in Hz, must be positive
     * @param ceiling    level above which gain reduction begins, must be positive
     * @param attackMs   how quickly the envelope rises toward a louder peak
     * @param releaseMs  how quickly gain is restored once the peak passes
     */
    public void initialize(float sampleRate, float ceiling, float attackMs, float releaseMs) {
        if (sampleRate <= 0f) return;
        if (ceiling > 0f) this.ceiling = ceiling;
        this.attack = coefficient(attackMs, sampleRate);
        this.release = coefficient(releaseMs, sampleRate);
    }

    private static float coefficient(float milliseconds, float sampleRate) {
        if (milliseconds <= 0f) return 1f;
        final double samples = milliseconds * 0.001 * sampleRate;
        if (samples < 1.0) return 1f;
        return (float) (1.0 - Math.exp(-1.0 / samples));
    }

    public void reset() {
        envelope = 0f;
    }

    public float f(float x) {
        final float magnitude = Math.abs(x);

        if (magnitude > envelope) {
            envelope += (magnitude - envelope) * attack;
        } else {
            envelope += (magnitude - envelope) * release;
        }
        if (envelope < 1e-20f) envelope = 0f;

        if (envelope <= ceiling) return x;
        return x * (ceiling / envelope);
    }
}
