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
 * Removes the standing DC offset from a pulse train.
 *
 * A blowdown pulse train is strictly positive, so its mean is large. Feeding that straight into
 * a convolution or a saturator wastes headroom on an inaudible offset and makes the levelling
 * stage chase a constant. Subtracting a very low corner low pass leaves the audible content
 * untouched while pinning the mean at zero.
 */
public final class DcBlocker {

    private final LowPassFilter mean = new LowPassFilter();

    public void initialize(float cornerHz, float sampleRate) {
        mean.setCutoff(cornerHz, sampleRate);
    }

    public float f(float x) {
        return x - mean.f(x);
    }

    public void reset() {
        mean.reset();
    }
}
