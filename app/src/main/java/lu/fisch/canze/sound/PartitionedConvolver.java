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
 * Uniformly partitioned overlap save convolution with a frequency domain delay line.
 *
 * The engine simulator convolves in the time domain, walking every tap for every output sample.
 * At the impulse response lengths that actually sound like an exhaust system that is hundreds of
 * millions of multiply accumulates per second, which is not viable in Java on a phone.
 *
 * Partitioning gives mathematically identical output for a small fraction of the work. The
 * impulse response is cut into blocks, each transformed once at construction. Every audio block
 * costs one forward transform, one inverse transform, and a complex multiply accumulate across
 * the stored partitions.
 *
 * Output is time aligned with the input block, so a dry path summed against this introduces no
 * comb filtering.
 *
 * Allocates only in the constructor.
 */
public final class PartitionedConvolver {

    private final int blockSize;
    private final int fftSize;
    private final int partitions;

    private final RealFFT fft;

    private final float[][] irRe;
    private final float[][] irIm;
    private final float[][] fdlRe;
    private final float[][] fdlIm;

    private final float[] workRe;
    private final float[] workIm;
    private final float[] accRe;
    private final float[] accIm;
    private final float[] previousBlock;

    private int fdlIndex = 0;
    private final boolean bypass;

    /**
     * @param impulseResponse taps, may be null or empty in which case the convolver passes through
     * @param blockSize       processing block in samples, must be a power of two
     */
    public PartitionedConvolver(float[] impulseResponse, int blockSize) {
        if (blockSize < 2 || (blockSize & (blockSize - 1)) != 0) {
            throw new IllegalArgumentException("block size must be a power of two: " + blockSize);
        }

        this.blockSize = blockSize;
        this.fftSize = blockSize * 2;

        if (impulseResponse == null || impulseResponse.length == 0) {
            this.bypass = true;
            this.partitions = 0;
            this.fft = null;
            this.irRe = null;
            this.irIm = null;
            this.fdlRe = null;
            this.fdlIm = null;
            this.workRe = null;
            this.workIm = null;
            this.accRe = null;
            this.accIm = null;
            this.previousBlock = null;
            return;
        }

        this.bypass = false;
        this.partitions = (impulseResponse.length + blockSize - 1) / blockSize;
        this.fft = new RealFFT(fftSize);

        this.irRe = new float[partitions][fftSize];
        this.irIm = new float[partitions][fftSize];
        this.fdlRe = new float[partitions][fftSize];
        this.fdlIm = new float[partitions][fftSize];

        this.workRe = new float[fftSize];
        this.workIm = new float[fftSize];
        this.accRe = new float[fftSize];
        this.accIm = new float[fftSize];
        this.previousBlock = new float[blockSize];

        for (int p = 0; p < partitions; p++) {
            final int offset = p * blockSize;
            final float[] re = irRe[p];
            final float[] im = irIm[p];
            for (int i = 0; i < blockSize; i++) {
                final int src = offset + i;
                re[i] = (src < impulseResponse.length) ? impulseResponse[src] : 0f;
                im[i] = 0f;
            }
            for (int i = blockSize; i < fftSize; i++) {
                re[i] = 0f;
                im[i] = 0f;
            }
            fft.forward(re, im);
        }
    }

    public boolean isBypass() {
        return bypass;
    }

    /**
     * Convolves exactly one block.
     *
     * @param in  input samples, length must be at least blockSize
     * @param out output samples, length must be at least blockSize, may alias in
     */
    public void process(float[] in, float[] out) {
        if (in == null || out == null) return;
        if (in.length < blockSize || out.length < blockSize) return;

        if (bypass) {
            System.arraycopy(in, 0, out, 0, blockSize);
            return;
        }

        // Overlap save input frame: the previous block followed by the current one.
        for (int i = 0; i < blockSize; i++) {
            workRe[i] = previousBlock[i];
            workIm[i] = 0f;
        }
        for (int i = 0; i < blockSize; i++) {
            workRe[blockSize + i] = in[i];
            workIm[blockSize + i] = 0f;
        }

        fft.forward(workRe, workIm);

        System.arraycopy(workRe, 0, fdlRe[fdlIndex], 0, fftSize);
        System.arraycopy(workIm, 0, fdlIm[fdlIndex], 0, fftSize);

        for (int i = 0; i < fftSize; i++) {
            accRe[i] = 0f;
            accIm[i] = 0f;
        }

        for (int p = 0; p < partitions; p++) {
            int idx = fdlIndex - p;
            if (idx < 0) idx += partitions;

            final float[] xr = fdlRe[idx];
            final float[] xi = fdlIm[idx];
            final float[] hr = irRe[p];
            final float[] hi = irIm[p];

            for (int i = 0; i < fftSize; i++) {
                final float ar = xr[i];
                final float ai = xi[i];
                final float br = hr[i];
                final float bi = hi[i];
                accRe[i] += ar * br - ai * bi;
                accIm[i] += ar * bi + ai * br;
            }
        }

        fft.inverse(accRe, accIm);

        // The first half is circular wrap around and is discarded.
        for (int i = 0; i < blockSize; i++) {
            out[i] = accRe[blockSize + i];
        }

        System.arraycopy(in, 0, previousBlock, 0, blockSize);

        fdlIndex++;
        if (fdlIndex >= partitions) fdlIndex = 0;
    }
}
