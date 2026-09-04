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
 * In place radix 2 Cooley Tukey FFT over split real and imaginary arrays.
 *
 * Real input is handled by zeroing the imaginary array rather than by a packed real transform.
 * The packed form would halve the cost, but at the sizes used here (512 points, twice per 256
 * sample block) the transform is already a small fraction of the audio budget, and the plain
 * complex version has far fewer ways to be subtly wrong.
 *
 * Allocates only in the constructor. Nothing here allocates on the audio thread.
 */
public final class RealFFT {

    private final int n;
    private final int[] reverse;
    private final float[] cosTable;
    private final float[] sinTable;

    /**
     * @param n transform length, must be a power of two and at least 2
     */
    public RealFFT(int n) {
        if (n < 2 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT length must be a power of two: " + n);
        }
        this.n = n;

        int bits = 0;
        while ((1 << bits) < n) bits++;

        reverse = new int[n];
        for (int i = 0; i < n; i++) {
            int r = 0;
            for (int b = 0; b < bits; b++) {
                if ((i & (1 << b)) != 0) r |= 1 << (bits - 1 - b);
            }
            reverse[i] = r;
        }

        cosTable = new float[n];
        sinTable = new float[n];
        for (int k = 0; k < n; k++) {
            final double angle = 2.0 * Math.PI * k / n;
            cosTable[k] = (float) Math.cos(angle);
            sinTable[k] = (float) Math.sin(angle);
        }
    }

    public int size() {
        return n;
    }

    public void forward(float[] re, float[] im) {
        transform(re, im, false);
    }

    /** Inverse transform, including the 1/n scaling. */
    public void inverse(float[] re, float[] im) {
        transform(re, im, true);
    }

    private void transform(float[] re, float[] im, boolean inverseTransform) {
        if (re == null || im == null || re.length < n || im.length < n) {
            throw new IllegalArgumentException("FFT buffers too small");
        }

        for (int i = 0; i < n; i++) {
            final int j = reverse[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            final int half = len >> 1;
            final int step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0; j < half; j++) {
                    final int k = j * step;
                    final float wr = cosTable[k];
                    final float wi = inverseTransform ? sinTable[k] : -sinTable[k];

                    final int a = i + j;
                    final int b = a + half;

                    final float xr = re[b] * wr - im[b] * wi;
                    final float xi = re[b] * wi + im[b] * wr;

                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }

        if (inverseTransform) {
            final float scale = 1f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= scale;
                im[i] *= scale;
            }
        }
    }
}
