/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package lu.fisch.canze.actors;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.classes.FieldLogger;

/**
 * The answer of a device to a frame request.
 *
 * For ISO-TP frames the frame is always a sub frame. On error the data part holds a readable
 * error text and is never decoded.
 *
 * Decoding: the payload is turned into bytes once, and every field reads its bits with shifts
 * (BitReader) instead of going through a string of '0' and '1' characters. The results are
 * identical to the old decoder, see BitReaderTest.
 *
 * @author robertfisch
 */
public class Message {

    /** no error at all */
    public static final int ERROR_NONE = 0;
    /** a dongle or bus level problem: says nothing about whether the PID exists */
    public static final int ERROR_OTHER = 1;
    /** the device did not answer within the timeout */
    public static final int ERROR_TIMEOUT = 2;
    /** the ECU explicitly refused the request (negative response) */
    public static final int ERROR_UNSUPPORTED = 3;

    private final Frame frame;
    private final String data;
    private final boolean error;
    private final int errorKind;

    /** Errors created this way are ERROR_OTHER and never cause a frame to be blacklisted. */
    public Message(Frame frame, String data, boolean error) {
        this(frame, data, error, error ? ERROR_OTHER : ERROR_NONE);
    }

    public Message(Frame frame, String data, boolean error, int errorKind) {
        if (frame == null) throw new IllegalArgumentException("a message needs a frame");
        if (MainActivity.isVerbose()) MainActivity.debug("Message.new.data:" + data);
        this.frame = frame;
        if (frame.isIsoTp() && data != null && data.startsWith("7f")) {
            // the ECU answered but refused: on a non standard car the strongest possible signal
            // that the PID simply does not exist
            this.error = true;
            this.errorKind = ERROR_UNSUPPORTED;
            this.data = "-E-Message.isotp.startswith7f";
            return;
        }
        this.data = data;
        this.error = error;
        this.errorKind = error ? errorKind : ERROR_NONE;
    }

    public int getErrorKind() {
        return errorKind;
    }

    /** @return true if this error indicates the PID itself is dead, not the dongle or bus */
    public boolean countsAsDeadPid() {
        return error && (errorKind == ERROR_TIMEOUT || errorKind == ERROR_UNSUPPORTED);
    }

    public String getData() {
        return (error || data == null) ? "" : data;
    }

    public Frame getFrame() {
        return frame;
    }

    public boolean isError() {
        return error;
    }

    public String getError() {
        return error ? data : "";
    }

    /** Pushes the frame's fields to the end of the queue without a value. */
    public void onMessageIncompleteEvent() {
        for (Field field : frame.getAllFields()) {
            field.updateLastRequest();
        }
    }

    /**
     * Updates every field defined for this frame. For an ISO-TP frame getFrame() is a sub frame,
     * so only the fields with the same response id are touched.
     */
    public void onMessageCompleteEvent() {
        if (error) return;
        byte[] bytes = BitReader.decodeHex(data);
        for (Field field : frame.getAllFields()) {
            decodeField(bytes, field);
        }
    }

    private void decodeField(byte[] bytes, Field field) {
        int from = field.getFrom();
        int to = field.getTo();
        // like before: a field reaching beyond the payload is left untouched
        if (!BitReader.fits(bytes, from, to)) return;
        try {
            if (field.isString()) {
                if (!decodeString(bytes, field, from, to)) return;
            } else if (!decodeNumber(bytes, field, from, to)) {
                return;
            }
            field.updateLastRequest();
        } catch (RuntimeException e) {
            MainActivity.debug("Message.decodeField: " + field.getSID() + ": " + e);
        }
    }

    /** @return false if the definition does not describe whole bytes */
    private boolean decodeString(byte[] bytes, Field field, int from, int to) {
        if ((to - from + 1) % 8 != 0) return false;
        String val = BitReader.readString(bytes, from, to);
        field.setValue(val);
        logField(field, val);
        return true;
    }

    /** @return false if the value cannot be represented, in which case the field is not updated */
    private boolean decodeNumber(byte[] bytes, Field field, int from, int to) {
        int width = to - from + 1;
        if (width > BitReader.MAX_WIDTH) {
            MainActivity.debug("Message: field " + field.getSID() + " is too wide (" + width + " bits)");
            return false;
        }
        long raw = BitReader.read(bytes, from, to);
        // any field of 5 bits or more with only ones set means: value not available
        if (width > 4 && BitReader.isAllOnes(raw, width)) {
            field.setValue(Double.NaN);
            logField(field, "NaN");
            return true;
        }
        long value = field.isSigned() ? BitReader.signExtend(raw, width) : raw;
        // values are ints, as they always were
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) return false;
        field.setValue((double) value);
        logField(field, String.valueOf(value));
        return true;
    }

    private static void logField(Field field, String value) {
        if (MainActivity.fieldLogMode) {
            FieldLogger.getInstance().log(field.getSID() + "," + value);
        }
    }
}
