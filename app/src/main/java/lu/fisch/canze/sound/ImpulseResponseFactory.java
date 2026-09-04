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

import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Produces the impulse response fed to the partitioned convolver.
 *
 * Two sources, in priority order:
 *  1. A mono 16 bit PCM WAV at the given asset path, if one is present and parses.
 *  2. A synthesised exhaust system, rendered once by pushing a unit impulse through a comb and
 *     all pass network whose loop lengths correspond to real pipe lengths.
 *
 * Synthesising means the feature works with no bundled audio and stays tunable from code, while
 * the asset path lets a real recording take over later without touching anything else.
 */
public final class ImpulseResponseFactory {

    private static final String TAG = "ImpulseResponse";

    private static final float SPEED_OF_SOUND_M_S = 343f;

    // Round trip pipe lengths in metres. Mutually non harmonic on purpose: integer ratios would
    // stack their resonances into a single pitched tone instead of a broadband body.
    private static final float[] PIPE_LENGTHS_M = {0.83f, 1.27f, 1.71f, 2.39f};
    private static final float[] PIPE_FEEDBACK = {0.84f, 0.81f, 0.78f, 0.74f};

    private static final int[] ALLPASS_DELAYS = {221, 75};

    private ImpulseResponseFactory() {
    }

    /**
     * @param assets     asset manager, may be null in which case synthesis is used directly
     * @param assetName  path within assets, for example v8_ir.wav
     * @param length     desired impulse response length in samples
     * @param sampleRate audio sample rate in Hz
     * @return an impulse response, never null
     */
    public static float[] create(AssetManager assets, String assetName, int length, float sampleRate) {
        final float[] loaded = loadWav(assets, assetName, length);
        if (loaded != null) {
            Log.i(TAG, "using impulse response asset: " + assetName + " (" + loaded.length + " taps)");
            return normalise(loaded);
        }
        return normalise(synthesise(length, sampleRate));
    }

    /**
     * Renders a unit impulse through the pipe network.
     *
     * @param length     taps to render, must be positive
     * @param sampleRate audio sample rate in Hz
     */
    public static float[] synthesise(int length, float sampleRate) {
        if (length < 1) length = 1;

        final CombFilter[] combs = new CombFilter[PIPE_LENGTHS_M.length];
        for (int i = 0; i < combs.length; i++) {
            int delay = Math.round(PIPE_LENGTHS_M[i] / SPEED_OF_SOUND_M_S * sampleRate);
            if (delay < 2) delay = 2;
            combs[i] = new CombFilter(delay);
            combs[i].setFeedback(PIPE_FEEDBACK[i]);
            combs[i].setDamping(0.34f);
        }

        final AllPassFilter[] allPasses = new AllPassFilter[ALLPASS_DELAYS.length];
        for (int i = 0; i < allPasses.length; i++) {
            allPasses[i] = new AllPassFilter(ALLPASS_DELAYS[i]);
            allPasses[i].setG(0.5f);
        }

        final float[] ir = new float[length];
        final float invLength = 1f / length;

        for (int i = 0; i < length; i++) {
            final float x = (i == 0) ? 1f : 0f;

            float y = 0f;
            for (CombFilter comb : combs) {
                y += comb.f(x);
            }
            y *= 0.25f;

            for (AllPassFilter allPass : allPasses) {
                y = allPass.f(y);
            }

            // Forces the tail to zero at the end of the buffer. Truncating a still ringing
            // response would put a step at the last tap, which convolves as a broadband click.
            final float envelope = (float) Math.exp(-5.0 * i * invLength);
            ir[i] = y * envelope;
        }

        return ir;
    }

    /**
     * Scales to unit energy so swapping impulse responses does not change perceived loudness.
     */
    private static float[] normalise(float[] ir) {
        if (ir == null || ir.length == 0) return new float[]{1f};

        double energy = 0.0;
        for (float v : ir) {
            energy += (double) v * v;
        }
        if (energy <= 0.0) return new float[]{1f};

        final float scale = (float) (1.0 / Math.sqrt(energy));
        for (int i = 0; i < ir.length; i++) {
            ir[i] *= scale;
        }
        return ir;
    }

    /**
     * Minimal RIFF reader. Accepts mono or stereo 16 bit PCM and takes the first channel.
     *
     * @return taps, or null if the asset is missing or not in a form we accept
     */
    private static float[] loadWav(AssetManager assets, String assetName, int maxSamples) {
        if (assets == null || assetName == null || maxSamples < 1) return null;

        InputStream stream = null;
        try {
            stream = assets.open(assetName);

            final byte[] raw = readFully(stream, 4 * 1024 * 1024);
            if (raw.length < 44) {
                Log.w(TAG, "impulse response asset too short to be a WAV");
                return null;
            }

            if (raw[0] != 'R' || raw[1] != 'I' || raw[2] != 'F' || raw[3] != 'F'
                    || raw[8] != 'W' || raw[9] != 'A' || raw[10] != 'V' || raw[11] != 'E') {
                Log.w(TAG, "impulse response asset is not RIFF/WAVE");
                return null;
            }

            int channels = 0;
            int bits = 0;
            int dataOffset = -1;
            int dataLength = 0;

            int p = 12;
            // Bounded: every iteration advances p by at least 8 bytes.
            while (p + 8 <= raw.length) {
                final int chunkId = readInt32(raw, p);
                final int chunkSize = readInt32LE(raw, p + 4);
                if (chunkSize < 0) break;

                final int body = p + 8;

                if (chunkId == 0x666D7420 && body + 16 <= raw.length) { // "fmt "
                    final int format = readInt16LE(raw, body);
                    channels = readInt16LE(raw, body + 2);
                    bits = readInt16LE(raw, body + 14);
                    if (format != 1) {
                        Log.w(TAG, "impulse response asset is not uncompressed PCM");
                        return null;
                    }
                } else if (chunkId == 0x64617461) { // "data"
                    dataOffset = body;
                    dataLength = Math.min(chunkSize, raw.length - body);
                    break;
                }

                p = body + chunkSize + (chunkSize & 1);
            }

            if (dataOffset < 0 || dataLength <= 0 || channels < 1 || bits != 16) {
                Log.w(TAG, "impulse response asset must be 16 bit PCM with a data chunk");
                return null;
            }

            final int frameBytes = 2 * channels;
            int frames = dataLength / frameBytes;
            if (frames > maxSamples) frames = maxSamples;
            if (frames < 1) return null;

            final float[] ir = new float[frames];
            for (int i = 0; i < frames; i++) {
                final int at = dataOffset + i * frameBytes;
                final int lo = raw[at] & 0xFF;
                final int hi = raw[at + 1];
                ir[i] = ((hi << 8) | lo) / 32768f;
            }
            return ir;

        } catch (IOException e) {
            // A missing asset is the normal case, not a failure. Synthesis takes over.
            Log.i(TAG, "no impulse response asset, synthesising instead");
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "impulse response asset could not be parsed", e);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] readFully(InputStream stream, int limit) throws IOException {
        final byte[] chunk = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        int total = 0;
        // Bounded by limit: a stream that never ends still terminates this loop.
        while (total < limit) {
            final int read = stream.read(chunk);
            if (read < 0) break;
            out.write(chunk, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    private static int readInt32(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int readInt32LE(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }

    private static int readInt16LE(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }
}
