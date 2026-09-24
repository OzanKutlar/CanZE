/*
    CanZE
    Take a closer look at your ZE car

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.
*/

package lu.fisch.canze.classes;

import android.os.Environment;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lu.fisch.canze.activities.MainActivity;

/**
 * Appends time stamped lines to a log file on one background thread.
 *
 * The file is opened once and kept open; the old loggers opened and closed it for every line, on
 * whatever thread logged (often the poller). The queue is bounded: if storage cannot keep up,
 * lines are dropped rather than growing memory. Problems are reported to logcat only, never
 * through MainActivity.debug(), which would log straight back into this writer.
 */
final class AsyncLineWriter {

    private static final String TAG = "CanZE-Log";
    private static final int MAX_QUEUED_LINES = 10000;
    private static final long RETRY_AFTER_FAILURE_MS = 30000L;
    private static final String FALLBACK_PATTERN = "yyyy-MM-dd-HH-mm-ss";

    private final String filePrefix;
    private final int fileDateRes;
    private final int lineDateRes;
    private final String lineJoiner;
    private final String header;
    private final ThreadPoolExecutor executor;

    // writer thread only
    private BufferedWriter writer = null;
    private SimpleDateFormat lineFormat = null;
    private long retryAt = 0L;

    /**
     * @param fileDateRes string resource with the date pattern of the file name
     * @param lineDateRes string resource with the date pattern of each line
     * @param lineJoiner  text between the time stamp and the line
     * @param header      first line of a new file, or null
     */
    AsyncLineWriter(final String threadName, String filePrefix, int fileDateRes, int lineDateRes, String lineJoiner, String header) {
        this.filePrefix = filePrefix;
        this.fileDateRes = fileDateRes;
        this.lineDateRes = lineDateRes;
        this.lineJoiner = lineJoiner;
        this.header = header;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(MAX_QUEUED_LINES),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, threadName);
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    /** Queues a line. Never blocks the caller. */
    void log(final String text) {
        final long at = System.currentTimeMillis();
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    writeLine(at, text);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "log line rejected", e);
        }
    }

    private void writeLine(long at, String text) {
        if (writer == null && !open(at)) return;
        try {
            writer.write(lineFormat.format(new Date(at)));
            writer.write(lineJoiner);
            writer.write(text == null ? "null" : text);
            writer.write('\n');
            // flush once the burst is written, not after every line
            if (executor.getQueue().isEmpty()) writer.flush();
        } catch (IOException e) {
            Log.w(TAG, "could not write the " + filePrefix + " log", e);
            closeQuietly();
            retryAt = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
        }
    }

    private boolean open(long at) {
        if (System.currentTimeMillis() < retryAt) return false;
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                throw new IOException("external storage not mounted");
            }
            File dir = new File(Environment.getExternalStorageDirectory(), "CanZE");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File file = new File(dir, filePrefix + "-" + format(fileDateRes).format(new Date(at)) + ".log");
            writer = new BufferedWriter(new FileWriter(file, true));
            if (header != null) {
                writer.write(header);
                writer.write('\n');
            }
            lineFormat = format(lineDateRes);
            return true;
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "could not open the " + filePrefix + " log, retrying later", e);
            closeQuietly();
            retryAt = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
            return false;
        }
    }

    private static SimpleDateFormat format(int patternRes) {
        String pattern = MainActivity.getStringSingle(patternRes);
        if (pattern == null || pattern.isEmpty()) pattern = FALLBACK_PATTERN;
        try {
            return new SimpleDateFormat(pattern, Locale.getDefault());
        } catch (IllegalArgumentException e) {
            return new SimpleDateFormat(FALLBACK_PATTERN, Locale.getDefault());
        }
    }

    private void closeQuietly() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            Log.w(TAG, "could not close the " + filePrefix + " log", e);
        }
        writer = null;
    }
}
