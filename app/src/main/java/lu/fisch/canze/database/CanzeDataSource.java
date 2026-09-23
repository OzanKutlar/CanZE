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

package lu.fisch.canze.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lu.fisch.canze.actors.Field;
import lu.fisch.canze.classes.TimePoint;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Short term history of field values, used to seed the graphs.
 *
 * Threading: insert() is called on the poller thread for every field update, so it never touches
 * SQLite itself. Points are queued and written once per FLUSH_DELAY_MS in a single transaction by
 * a dedicated writer thread. The newest point per SID is also kept in memory, so getLast() and
 * getLastTime() cost nothing for any SID seen this session.
 */
public class CanzeDataSource implements FieldListener {

    private static final String TAG = "CanZE-DB";
    private static final String TABLE = "data";

    /** how much history is kept */
    private static final long LIMIT_MS = 60L * 60L * 1000L; // 1 h
    /** points are coalesced for this long before being written */
    private static final long FLUSH_DELAY_MS = 1000L;
    private static final int MAX_POINTS_PER_TRANSACTION = 500;
    private static final int MAX_TRANSACTIONS_PER_FLUSH = 20;

    /** marks a failed lookup, which must not be cached as "no data" */
    private static final TimePoint QUERY_FAILED = new TimePoint(Long.MIN_VALUE, Double.NaN);

    /*
     * Singleton stuff
     */
    private static CanzeDataSource instance = null;

    public static synchronized CanzeDataSource getInstance() {
        if (instance == null) throw new Error("Must call at least once with given context!");
        return instance;
    }

    public static synchronized CanzeDataSource getInstance(Context context) {
        if (instance == null) instance = new CanzeDataSource(context.getApplicationContext());
        return instance;
    }

    private final CanzeOpenHelper dbHelper;
    private volatile SQLiteDatabase database;

    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentLinkedQueue<PendingPoint> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // cleared first, so points queued during this flush schedule the next one
            flushScheduled.set(false);
            flushPending();
        }
    };

    /** newest point per SID: the per second maximum, i.e. exactly what the table holds */
    private final ConcurrentHashMap<String, TimePoint> lasts = new ConcurrentHashMap<>();
    /** SIDs known to have no stored point, so they are not queried again */
    private final Set<String> missing = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private CanzeDataSource(Context context) {
        dbHelper = new CanzeOpenHelper(context);
    }

    /* --------------------------------
     * Lifecycle
     \ ------------------------------ */

    /** @return true if the database is usable */
    public boolean open() {
        try {
            database = dbHelper.getWritableDatabase();
            return true;
        } catch (SQLException e) {
            Log.e(TAG, "could not open the database", e);
            database = null;
            return false;
        }
    }

    /** Writes what is still queued, then closes. Runs on the writer thread. */
    public void close() {
        submit("close", new Runnable() {
            @Override
            public void run() {
                flushPending();
                database = null;
                dbHelper.close();
            }
        });
    }

    public void reinit() {
        clear();
    }

    /** Drops all history, queued points and cached values. */
    public void clear() {
        pending.clear();
        lasts.clear();
        missing.clear();
        SQLiteDatabase db = database;
        if (db == null) return;
        try {
            dbHelper.reinit(db);
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "could not clear the database", e);
        }
    }

    /** Removes history older than LIMIT_MS. Blocking, prefer cleanUpAsync(). */
    public void cleanUp() {
        SQLiteDatabase db = database;
        if (db == null) return;
        long limit = System.currentTimeMillis() - LIMIT_MS;
        try {
            int removed = db.delete(TABLE, "moment<?", new String[]{String.valueOf(limit)});
            Log.d(TAG, "cleanUp removed " + removed + " rows");
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "cleanUp failed", e);
        }
    }

    /** Removes old history on the writer thread, serialized with the pending writes. */
    public void cleanUpAsync() {
        submit("cleanUp", new Runnable() {
            @Override
            public void run() {
                cleanUp();
            }
        });
    }

    /* --------------------------------
     * Writing
     \ ------------------------------ */

    /** Queues the field's current value. Cheap enough for the poller thread. */
    public void insert(Field field) {
        if (field == null) return;
        String sid = field.getSID();
        double value = field.getValue();
        if (sid == null || Double.isNaN(value)) return;

        // with fast dongles fields arrive far quicker than useful, keep one point per second
        long moment = (System.currentTimeMillis() / 1000L) * 1000L;
        PendingPoint point = recordLast(sid, moment, value);
        if (point == null) return;
        pending.add(point);
        scheduleFlush();
    }

    /**
     * Keeps the per second maximum. Returns what has to be written, or null if the point does
     * not change the stored data.
     */
    private synchronized PendingPoint recordLast(String sid, long moment, double value) {
        missing.remove(sid);
        TimePoint last = lasts.get(sid);
        if (last == null || last.date != moment) {
            lasts.put(sid, new TimePoint(moment, value));
            return new PendingPoint(sid, moment, value, false);
        }
        if (value <= last.value) return null;
        lasts.put(sid, new TimePoint(moment, value));
        return new PendingPoint(sid, moment, value, true);
    }

    public void delete(String sid, long moment) {
        SQLiteDatabase db = database;
        if (db == null || sid == null) return;
        try {
            db.delete(TABLE, "sid=? AND moment=?", new String[]{sid, String.valueOf(moment)});
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "delete failed for " + sid, e);
        }
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return;
        try {
            writer.schedule(flushTask, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flushScheduled.set(false);
            Log.w(TAG, "flush rejected", e);
        }
    }

    private void flushPending() {
        SQLiteDatabase db = database;
        if (db == null) {
            pending.clear();
            return;
        }
        // bounded: at most MAX_TRANSACTIONS_PER_FLUSH batches, the rest goes to the next flush
        for (int i = 0; i < MAX_TRANSACTIONS_PER_FLUSH && !pending.isEmpty(); i++) {
            if (!writeBatch(db)) return;
        }
        if (!pending.isEmpty()) scheduleFlush();
    }

    /** @return false if the database is failing; the queue is then dropped to avoid thrashing */
    private boolean writeBatch(SQLiteDatabase db) {
        try {
            db.beginTransaction();
            try {
                for (int i = 0; i < MAX_POINTS_PER_TRANSACTION; i++) {
                    PendingPoint point = pending.poll();
                    if (point == null) break;
                    writePoint(db, point);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "could not write history, dropping queued points", e);
            pending.clear();
            return false;
        }
    }

    private void writePoint(SQLiteDatabase db, PendingPoint point) {
        if (point.replace) {
            db.delete(TABLE, "sid=? AND moment=?", new String[]{point.sid, String.valueOf(point.moment)});
        }
        ContentValues values = new ContentValues(3);
        values.put("sid", point.sid);
        values.put("moment", point.moment);
        values.put("value", point.value);
        db.insert(TABLE, null, values);
    }

    private void submit(String name, Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, name + " rejected", e);
        }
    }

    /* --------------------------------
     * Reading
     \ ------------------------------ */

    public double getLast(String sid) {
        TimePoint point = lastPoint(sid);
        return point == null ? Double.NaN : point.value;
    }

    public long getLastTime(String sid) {
        TimePoint point = lastPoint(sid);
        return point == null ? -1 : point.date;
    }

    public double getMax(String sid) {
        return aggregate(sid, "MAX");
    }

    public double getMin(String sid) {
        return aggregate(sid, "MIN");
    }

    /** @return the retained history of a SID, oldest first */
    public ArrayList<TimePoint> getData(String sid) {
        ArrayList<TimePoint> data = new ArrayList<>();
        SQLiteDatabase db = database;
        if (db == null || sid == null) return data;
        long since = System.currentTimeMillis() - LIMIT_MS;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT moment, value FROM data WHERE sid=? AND moment>=? ORDER BY moment ASC",
                    new String[]{sid, String.valueOf(since)});
            while (c.moveToNext()) {
                data.add(new TimePoint(c.getLong(0), c.getDouble(1)));
            }
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "getData failed for " + sid, e);
        } finally {
            if (c != null) c.close();
        }
        return data;
    }

    private TimePoint lastPoint(String sid) {
        if (sid == null) return null;
        TimePoint cached = lasts.get(sid);
        if (cached != null) return cached;
        if (missing.contains(sid)) return null;

        TimePoint stored = queryLast(sid);
        if (stored == QUERY_FAILED) return null;
        if (stored == null) {
            missing.add(sid);
            return null;
        }
        TimePoint raced = lasts.putIfAbsent(sid, stored);
        return raced != null ? raced : stored;
    }

    /** @return the newest stored point, null if there is none, QUERY_FAILED on error */
    private TimePoint queryLast(String sid) {
        SQLiteDatabase db = database;
        if (db == null) return QUERY_FAILED;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT moment, value FROM data WHERE sid=? ORDER BY moment DESC LIMIT 1",
                    new String[]{sid});
            if (!c.moveToFirst()) return null;
            return new TimePoint(c.getLong(0), c.getDouble(1));
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, "getLast failed for " + sid, e);
            return QUERY_FAILED;
        } finally {
            if (c != null) c.close();
        }
    }

    /** @param function MIN or MAX, never user input */
    private double aggregate(String sid, String function) {
        SQLiteDatabase db = database;
        if (db == null || sid == null) return Double.NaN;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT " + function + "(value) FROM data WHERE sid=?", new String[]{sid});
            if (!c.moveToFirst() || c.isNull(0)) return Double.NaN;
            return c.getDouble(0);
        } catch (SQLException | IllegalStateException e) {
            Log.w(TAG, function + " failed for " + sid, e);
            return Double.NaN;
        } finally {
            if (c != null) c.close();
        }
    }

    /*
     * FieldListener implementation
     */
    @Override
    public void onFieldUpdateEvent(Field field) {
        insert(field);
    }

    private static final class PendingPoint {
        final String sid;
        final long moment;
        final double value;
        /** true if an earlier point of the same second has to be replaced */
        final boolean replace;

        PendingPoint(String sid, long moment, double value, boolean replace) {
            this.sid = sid;
            this.moment = moment;
            this.value = value;
            this.replace = replace;
        }
    }
}
