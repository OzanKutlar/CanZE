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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import lu.fisch.canze.activities.MainActivity;

/**
 * Frames
 *
 * Lookups go through a map keyed by id and response id instead of a linear scan (every ISO-TP
 * field definition looked up its frame, which made loading quadratic). A reload builds a new list
 * and swaps it in under the lock, so other threads never see a half filled list.
 */
public class Frames {

    private static final int FRAME_ID = 0;              // hex, no leading 0x
    private static final int FRAME_INTERVAL_ZOE = 1;    // decimal
    private static final int FRAME_INTERVAL_FLUKAN = 2; // decimal
    private static final int FRAME_ECU = 3;

    private static Frames instance = null;

    private final Object lock = new Object();
    private ArrayList<Frame> frames = new ArrayList<>();
    private HashMap<String, Frame> byKey = new HashMap<>();

    private Frames() {
        load();
    }

    public static synchronized Frames getInstance() {
        if (instance == null) instance = new Frames();
        return instance;
    }

    private static String key(int id, String responseId) {
        return responseId == null ? id + "#" : id + "=" + responseId;
    }

    private void fillOneLine(String line, ArrayList<Frame> list, HashMap<String, Frame> map) {
        if (line.contains("#")) line = line.substring(0, line.indexOf('#'));
        String[] tokens = line.split(",");
        if (tokens.length != 4) return;

        Ecu ecu = Ecus.getInstance().getByMnemonic(tokens[FRAME_ECU].trim());
        if (ecu == null) {
            MainActivity.debug("Ecu does not exist:" + tokens[FRAME_ECU].trim());
            return;
        }

        int frameId;
        int interval;
        try {
            frameId = Integer.parseInt(tokens[FRAME_ID].trim(), 16);
            // every ZOE variant uses the ZOE column; before, only Q210 and R240 did
            int column = MainActivity.isZOE() ? FRAME_INTERVAL_ZOE : FRAME_INTERVAL_FLUKAN;
            interval = Integer.parseInt(tokens[column].trim(), 10);
        } catch (NumberFormatException e) {
            MainActivity.debug("Frames: malformed line skipped: " + line);
            return;
        }

        String k = key(frameId, null);
        Frame existing = map.get(k);
        if (existing != null) {
            existing.setInterval(interval);
            return;
        }
        Frame frame = new Frame(frameId, interval, ecu, null, null);
        list.add(frame);
        map.put(k, frame);
    }

    private void fillFromAsset(String assetName, ArrayList<Frame> list, HashMap<String, Frame> map) {
        AssetLoadHelper assetLoadHelper = new AssetLoadHelper(MainActivity.getInstance());
        BufferedReader bufferedReader = assetLoadHelper.getBufferedReaderFromAsset(assetName);
        if (bufferedReader == null) {
            MainActivity.toast(-100, "Can't access asset " + assetName);
            return;
        }
        try {
            String line;
            while ((line = bufferedReader.readLine()) != null)
                fillOneLine(line, list, map);
        } catch (IOException e) {
            MainActivity.debug("Frames: could not read " + assetName + ": " + e.getMessage());
        } finally {
            try {
                bufferedReader.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void publish(ArrayList<Frame> list, HashMap<String, Frame> map) {
        synchronized (lock) {
            frames = list;
            byKey = map;
        }
    }

    public void load() {
        load("");
    }

    public void load(String assetName) {
        ArrayList<Frame> list = new ArrayList<>();
        HashMap<String, Frame> map = new HashMap<>();
        String name = (assetName == null || assetName.isEmpty()) ? "_Frames.csv" : assetName;
        fillFromAsset(name, list, map);
        publish(list, map);
    }

    public void load(Ecu ecu) {
        if (ecu == null) return;
        if (ecu.getFromId() == 0x801) {
            // the Free Frame Computer: load all free frames
            load("FFC_Frames.csv");
            return;
        }
        // just the diagnostic frame; subframes are created for each field
        ArrayList<Frame> list = new ArrayList<>();
        HashMap<String, Frame> map = new HashMap<>();
        Frame frame = new Frame(ecu.getFromId(), 0, ecu, null, null);
        list.add(frame);
        map.put(key(frame.getId(), null), frame);
        publish(list, map);
    }

    /** Adds a frame; a different frame with the same id and response id is replaced. */
    public void add(Frame frame) {
        if (frame == null) return;
        String k = key(frame.getId(), frame.getResponseId());
        synchronized (lock) {
            Frame existing = byKey.get(k);
            if (existing == frame) return;
            if (existing != null) frames.remove(existing);
            frames.add(frame);
            byKey.put(k, frame);
        }
    }

    /** @return the frame at that position, or null if out of range */
    public Frame get(int position) {
        synchronized (lock) {
            if (position < 0 || position >= frames.size()) return null;
            return frames.get(position);
        }
    }

    public int size() {
        synchronized (lock) {
            return frames.size();
        }
    }

    /** @return the free frame (no response id) with this id */
    public Frame getById(int id) {
        synchronized (lock) {
            return byKey.get(key(id, null));
        }
    }

    public Frame getById(int id, String responseId) {
        if (responseId == null) return null;
        synchronized (lock) {
            return byKey.get(key(id, responseId));
        }
    }

    /** @return a snapshot copy, safe to iterate */
    public ArrayList<Frame> getAllFrames() {
        synchronized (lock) {
            return new ArrayList<>(frames);
        }
    }
}
