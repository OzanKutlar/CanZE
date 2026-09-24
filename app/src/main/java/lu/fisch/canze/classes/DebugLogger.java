package lu.fisch.canze.classes;

import lu.fisch.canze.R;

/**
 * Debug log on external storage, written on a background thread through one open file.
 *
 * Created by Chris Mattheis on 03/11/15.
 */
public class DebugLogger {

    private static final DebugLogger instance = new DebugLogger();

    private final AsyncLineWriter writer = new AsyncLineWriter(
            "CanZE-debug-log", "debug", R.string.format_YMDHMSs, R.string.format_YMDHMSs, ": ", null);

    private DebugLogger() {
    }

    public static DebugLogger getInstance() {
        return instance;
    }

    /** Appends a time stamped line. Never blocks the caller. */
    public void log(String text) {
        writer.log(text);
    }
}
