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

import android.content.Context;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * Schema history:
 *  1 - data table with an index on sid only
 *  2 - composite (sid, moment) index, which every query filters and orders by
 */
public class CanzeOpenHelper extends SQLiteOpenHelper {
    private static final String TAG = "CanZE-DB";
    private static final int DATABASE_VERSION = 2;
    private static final String DATABASE_NAME = "lu.fisch.canze.db";

    CanzeOpenHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS data (sid TEXT NOT NULL, moment INTEGER NOT NULL, value REAL NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS indexSidMoment ON data (sid, moment)");
        } catch (SQLException e) {
            Log.e(TAG, "could not create the schema", e);
        }
    }

    /**
     * Version 1 databases never got trimmed (the cleanup query was never executed), so they are
     * typically very large. The table only holds short term graph history, so rebuilding it is
     * both cheaper and safer than migrating it.
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "upgrading database " + oldVersion + " -> " + newVersion + ", history is rebuilt");
        reinit(db);
    }

    /** Installing an older build must not crash on the newer schema. */
    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "downgrading database " + oldVersion + " -> " + newVersion + ", history is rebuilt");
        reinit(db);
    }

    public void clear(SQLiteDatabase db) {
        if (db == null) return;
        try {
            // dropping the table drops its indexes as well
            db.execSQL("DROP TABLE IF EXISTS data");
        } catch (SQLException e) {
            Log.e(TAG, "could not drop the data table", e);
        }
    }

    public void reinit(SQLiteDatabase db) {
        clear(db);
        onCreate(db);
    }
}
