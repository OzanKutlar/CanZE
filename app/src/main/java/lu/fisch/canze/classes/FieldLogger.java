package lu.fisch.canze.classes;

import lu.fisch.canze.R;

/**
 * CSV log of every decoded field value (time,SID,value), written on a background thread through
 * one open file. The header is now written as is; it used to get a time stamp prepended.
 *
 * Created by Chris Mattheis on 03/11/15.
 */
public class FieldLogger {

    private static final FieldLogger instance = new FieldLogger();

    private final AsyncLineWriter writer = new AsyncLineWriter(
            "CanZE-field-log", "field", R.string.format_YMDHMS, R.string.format_YMDHMS, ",", "time,SID,value");

    private FieldLogger() {
    }

    public static FieldLogger getInstance() {
        return instance;
    }

    /** Appends a time stamped line. Never blocks the caller. */
    public void log(String text) {
        writer.log(text);
    }
}
