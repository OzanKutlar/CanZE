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

package lu.fisch.canze.classes;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import lu.fisch.canze.actors.Frame;

/**
 * Holds the set of frames (identified by their RID) that repeatedly failed to answer.
 * Blacklisted frames are no longer polled by the Device poller thread, and are marked
 * red in the user interface. The list is persisted so that a non standard car does not
 * have to re-learn its dead PIDs on every app start.
 *
 * There is deliberately no automatic expiry: the list is only ever cleared by the user
 * from the settings screen.
 */
public class Blacklist {

    public interface ChangeListener {
        void onBlacklistChanged();
    }

    /** number of consecutive failures before a frame is blacklisted */
    public static final int FAILURE_THRESHOLD = 3;

    private static final String PREFERENCE_KEY = "blacklistedFrames";

    private static Blacklist instance = null;

    private final Set<String> rids = Collections.synchronizedSet(new HashSet<String>());

    private SharedPreferences preferences = null;
    private ChangeListener changeListener = null;

    private Blacklist() {
    }

    public static synchronized Blacklist getInstance() {
        if (instance == null) {
            instance = new Blacklist();
        }
        return instance;
    }

    /* --------------------------------
     * Persistence
     \ ------------------------------ */

    /**
     * Bind the blacklist to a preferences file and load its previous content.
     *
     * @param preferences the shared preferences to persist into, may be null
     */
    public void load(SharedPreferences preferences) {
        if (preferences == null) return;

        this.preferences = preferences;
        Set<String> stored = preferences.getStringSet(PREFERENCE_KEY, null);

        synchronized (rids) {
            rids.clear();
            if (stored != null) {
                rids.addAll(stored);
            }
        }
        fireChanged();
    }

    private void save() {
        if (preferences == null) return;

        // never hand the live set to SharedPreferences, it is not defensively copied
        // on every API level and would be mutated underneath the framework
        Set<String> copy;
        synchronized (rids) {
            copy = new HashSet<>(rids);
        }

        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet(PREFERENCE_KEY, copy);
        editor.apply();
    }

    /* --------------------------------
     * Content
     \ ------------------------------ */

    public boolean contains(String rid) {
        if (rid == null) return false;
        return rids.contains(rid);
    }

    /**
     * @param frame the frame to blacklist
     * @return true if the frame was newly added, false if it was already known or invalid
     */
    public boolean add(Frame frame) {
        if (frame == null) return false;

        String rid = frame.getRID();
        if (rid == null || rid.trim().isEmpty()) return false;

        boolean added;
        synchronized (rids) {
            added = rids.add(rid);
        }

        if (added) {
            save();
            fireChanged();
        }
        return added;
    }

    /**
     * @return the number of entries that were removed
     */
    public int clear() {
        int count;
        synchronized (rids) {
            count = rids.size();
            rids.clear();
        }
        save();
        fireChanged();
        return count;
    }

    public int size() {
        return rids.size();
    }

    /**
     * @return a sorted copy of the blacklisted RIDs, safe to iterate
     */
    public Set<String> getAll() {
        synchronized (rids) {
            return new TreeSet<>(rids);
        }
    }

    /* --------------------------------
     * Change notification
     \ ------------------------------ */

    public void setChangeListener(ChangeListener listener) {
        this.changeListener = listener;
    }

    /**
     * Only clears the listener if it is still the given one. This avoids an activity
     * that is being paused from stealing the registration of the activity that just
     * resumed on top of it.
     */
    public void clearChangeListener(ChangeListener listener) {
        if (this.changeListener == listener) {
            this.changeListener = null;
        }
    }

    private void fireChanged() {
        ChangeListener listener = changeListener;
        if (listener != null) {
            listener.onBlacklistChanged();
        }
    }
}
