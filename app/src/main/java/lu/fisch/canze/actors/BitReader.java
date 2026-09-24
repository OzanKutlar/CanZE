/*
    CanZE
    Take a closer look at your ZE car

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.
*/

package lu.fisch.canze.actors;

/**
 * Bit level access to CAN payloads, MSB first: bit 0 is the top bit of the first byte.
 *
 * Pure Java with no Android dependency, so it is unit tested on the JVM. Replaces the old
 * approach of turning every payload into a string of '0' and '1' characters.
 */
public final class BitReader {

    /** widest field read() handles; wider values would not survive the sign handling */
    public static final int MAX_WIDTH = 62;

    private BitReader() {
    }

    /**
     * Decodes hex pairs. Like the old decoder, a pair that is not valid hex is skipped and a
     * dangling last character is ignored.
     */
    public static byte[] decodeHex(String hex) {
        if (hex == null) return new byte[0];
        int pairs = hex.length() / 2;
        byte[] out = new byte[pairs];
        int n = 0;
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) continue;
            out[n++] = (byte) ((hi << 4) | lo);
        }
        if (n == pairs) return out;
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    public static int bitLength(byte[] bytes) {
        return bytes == null ? 0 : bytes.length * 8;
    }

    /** @return true if bits from..to (inclusive) exist in the payload */
    public static boolean fits(byte[] bytes, int from, int to) {
        return from >= 0 && to >= from && to < bitLength(bytes);
    }

    /** @return bits from..to (inclusive) as an unsigned value */
    public static long read(byte[] bytes, int from, int to) {
        if (!fits(bytes, from, to) || to - from + 1 > MAX_WIDTH) {
            throw new IllegalArgumentException("bits " + from + ".." + to + " not readable from " + bitLength(bytes) + " bits");
        }
        long value = 0L;
        for (int i = from; i <= to; i++) {
            value = (value << 1) | ((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        }
        return value;
    }

    /** @return true if every one of the width bits is set, the marker for an unavailable value */
    public static boolean isAllOnes(long raw, int width) {
        return width > 0 && width <= MAX_WIDTH && raw == (1L << width) - 1L;
    }

    /** Interprets raw as a two's complement number of the given width. */
    public static long signExtend(long raw, int width) {
        if (width <= 0 || width > MAX_WIDTH) throw new IllegalArgumentException("width " + width);
        return ((raw >> (width - 1)) & 1L) != 0 ? raw - (1L << width) : raw;
    }

    /** Reads whole bytes as characters. The width must be a multiple of 8. */
    public static String readString(byte[] bytes, int from, int to) {
        if (!fits(bytes, from, to) || (to - from + 1) % 8 != 0) {
            throw new IllegalArgumentException("bits " + from + ".." + to + " are not a whole number of bytes");
        }
        StringBuilder text = new StringBuilder((to - from + 1) / 8);
        for (int i = from; i <= to; i += 8) {
            text.append((char) read(bytes, i, i + 7));
        }
        return text.toString();
    }
}
