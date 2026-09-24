package lu.fisch.canze.actors;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BitReaderTest {

    @Test
    public void decodesHexPairs() {
        assertArrayEquals(new byte[]{0x61, (byte) 0x80, 0x0a}, BitReader.decodeHex("61800a"));
        assertArrayEquals(new byte[]{0x12}, BitReader.decodeHex("zz12"));
        assertArrayEquals(new byte[]{0x12}, BitReader.decodeHex("123"));
        assertArrayEquals(new byte[0], BitReader.decodeHex(null));
    }

    @Test
    public void readsBitRanges() {
        byte[] b = BitReader.decodeHex("61800a");
        assertEquals(6, BitReader.read(b, 0, 3));
        assertEquals(1, BitReader.read(b, 4, 7));
        assertEquals(10, BitReader.read(b, 16, 23));
        assertEquals(0xbcd, BitReader.read(BitReader.decodeHex("abcd"), 4, 15));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBitsBeyondThePayload() {
        BitReader.read(BitReader.decodeHex("ff"), 4, 8);
    }

    @Test
    public void handlesSignAndUnavailable() {
        assertTrue(BitReader.isAllOnes(0x1f, 5));
        assertFalse(BitReader.isAllOnes(0x1e, 5));
        assertEquals(-2, BitReader.signExtend(0xfe, 8));
        assertEquals(127, BitReader.signExtend(0x7f, 8));
        assertEquals(-2048, BitReader.signExtend(0x800, 12));
    }

    @Test
    public void readsStrings() {
        assertEquals("AB", BitReader.readString(BitReader.decodeHex("4142"), 0, 15));
    }

    @Test
    public void matchesLegacyDecoderOnRandomFrames() {
        Random random = new Random(42);
        for (int n = 0; n < 2000; n++) {
            int bytes = 1 + random.nextInt(16);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < bytes; i++) hex.append(String.format("%02x", random.nextInt(256)));
            int bits = bytes * 8;
            int width = 1 + random.nextInt(Math.min(32, bits));
            int from = random.nextInt(bits - width + 1);
            int to = from + width - 1;
            boolean signed = random.nextBoolean();
            String data = hex.toString();
            assertEquals(data + " " + from + ".." + to + " signed=" + signed,
                    legacy(data, from, to, signed), modern(data, from, to, signed));
        }
    }

    /** The decoding path Message uses now. null means the field is not updated. */
    private static Double modern(String data, int from, int to, boolean signed) {
        byte[] b = BitReader.decodeHex(data);
        int width = to - from + 1;
        if (!BitReader.fits(b, from, to) || width > BitReader.MAX_WIDTH) return null;
        long raw = BitReader.read(b, from, to);
        if (width > 4 && BitReader.isAllOnes(raw, width)) return Double.NaN;
        long value = signed ? BitReader.signExtend(raw, width) : raw;
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) return null;
        return (double) value;
    }

    /** Verbatim copy of the old string based decoder. null means the field is not updated. */
    private static Double legacy(String data, int from, int to, boolean signed) {
        StringBuilder bin = new StringBuilder();
        for (int i = 0; i < data.length(); i += 2) {
            try {
                bin.append(String.format("%8s", Integer.toBinaryString(Integer.parseInt(data.substring(i, i + 2), 16) & 0xFF)).replace(' ', '0'));
            } catch (Exception e) {
                // skipped, as before
            }
        }
        if (bin.length() < to) return null;
        try {
            String s = bin.substring(from, to + 1);
            if (s.length() <= 4 || s.contains("0")) {
                int val;
                if (signed && s.startsWith("1")) {
                    val = Integer.parseInt("-" + s.replace('0', 'q').replace('1', '0').replace('q', '1'), 2) - 1;
                } else {
                    val = Integer.parseInt("0" + s, 2);
                }
                return (double) val;
            }
            return Double.NaN;
        } catch (Exception e) {
            return null;
        }
    }
}
