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

package lu.fisch.canze.devices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lu.fisch.canze.R;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.actors.Frame;
import lu.fisch.canze.actors.Message;
import lu.fisch.canze.actors.VirtualField;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.Blacklist;
import lu.fisch.canze.database.CanzeDataSource;

/**
 * An abstract device: manages the fields to request and the poller thread that requests them.
 *
 * Field lists:
 *  - applicationFields: always polled (e.g. speed for safe driving mode), with their interval
 *    remembered so it can be restored when a screen that wanted them faster goes away;
 *  - activityFieldsScheduled: polled when due, for the screen on display;
 *  - activityFieldsAsFastAsPossible: polled round robin whenever nothing else is due.
 * All three are guarded by the fields lock.
 *
 * Created by robertfisch on 07.09.2015.
 */
public abstract class Device {

    public static final int INTERVAL_ASAP = 0;      // follows frame rate
    public static final int INTERVAL_ASAPFAST = -1; // truly as fast as possible
    public static final int INTERVAL_ONCE = -2;     // one shot

    protected static final int TOUGHNESS_HARD = 0;    // hardest reset possible (ie atz)
    protected static final int TOUGHNESS_MEDIUM = 1;  // medium reset (i.e. atws)
    protected static final int TOUGHNESS_SOFT = 2;    // softest reset (i.e atd for ELM)
    protected static final int TOUGHNESS_NONE = 100;  // just clear error status

    /** how long stopAndJoin() waits for the poller before abandoning it */
    private static final long STOP_JOIN_TIMEOUT_MS = 3000;
    /** poller nap when there is nothing to do */
    private static final long IDLE_SLEEP_MS = 1000;
    /** poller nap when no field is due yet */
    private static final long NOT_DUE_SLEEP_MS = 200;

    private final double minIntervalMultiplicator = 1.3;
    private final double maxIntervalMultiplicator = 2.5;
    double intervalMultiplicator = minIntervalMultiplicator;
    private volatile boolean deviceIsInitialized = false;

    /** every field polled for any reason: the union of the three lists below */
    protected final ArrayList<Field> fields = new ArrayList<>();
    private final ArrayList<Field> activityFieldsScheduled = new ArrayList<>();
    private final ArrayList<Field> activityFieldsAsFastAsPossible = new ArrayList<>();
    private final ArrayList<Field> applicationFields = new ArrayList<>();
    /** interval each application field was registered with */
    private final HashMap<Field, Integer> applicationIntervals = new HashMap<>();

    private int activityFieldIndex = 0;

    /**
     * RIDs (e.g. 7ec.622001) of frames that answered correctly this session. Keyed by RID, not by
     * CAN id: with the CAN id, one working EVC PID shielded every dead EVC PID from the blacklist.
     */
    private final Set<String> provenFrames = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile boolean pollerActive = false;
    volatile Thread pollerThread;
    private final Object pollerLock = new Object();

    /** a descriptive problem set by initDevice, mainly useful when testing a new dongle */
    String lastInitProblem = "";

    /* ----------------------------------------------------------------
     * Poller lifecycle
     \ -------------------------------------------------------------- */

    /** Starts the poller if the link is up, stops it (bounded) if it is not. */
    public void initConnection() {
        MainActivity.debug("Device.initConnection: start");

        if (!BluetoothManager.getInstance().isConnected()) {
            MainActivity.debug("Device.initConnection: BT is not connected");
            stopAndJoin();
            return;
        }

        synchronized (pollerLock) {
            Thread current = pollerThread;
            if (current != null && current.isAlive() && isPollerActive()) {
                // the link is up and the poller is running: nothing to do
                return;
            }
            MainActivity.debug("Device.initConnection: starting new poller");
            setPollerActive(true);
            Thread poller = new Thread(new Runnable() {
                @Override
                public void run() {
                    runPoller();
                }
            }, "CanZE-poller");
            pollerThread = poller;
            poller.start();
        }
    }

    private void runPoller() {
        final Thread self = Thread.currentThread();
        try {
            // TOUGHNESS_NONE does basically nothing
            boolean ready = initDevice(deviceIsInitialized ? TOUGHNESS_NONE : TOUGHNESS_HARD);
            if (!ready) {
                handleInitFailure(self);
                return;
            }
            deviceIsInitialized = true;
            if (isCurrentPoller(self)) BluetoothManager.getInstance().publishReady();

            pollLoop(self);
            MainActivity.debug("Device.poller stopped");
        } catch (RuntimeException e) {
            MainActivity.debug("Device.poller: unexpected error, poller ends: " + e);
        } finally {
            clearPollerReference(self);
        }
    }

    /** Ends on a stop request, an interrupt, or when a newer poller has replaced this one. */
    private void pollLoop(Thread self) {
        while (isCurrentPoller(self)) {
            if (!hasWork() || !BluetoothManager.getInstance().isConnected()) {
                if (!sleepPoller(IDLE_SLEEP_MS)) return;
            } else {
                if (MainActivity.isVerbose()) MainActivity.debug("Device.poller: Doing next query");
                queryNextFilter();
            }
        }
    }

    private void handleInitFailure(Thread self) {
        MainActivity.debug("Device.poller: initDevice failed");
        deviceIsInitialized = false;
        if (!isCurrentPoller(self)) {
            MainActivity.debug("Device.poller: stop was requested, not restarting Bluetooth");
            return;
        }
        setPollerActive(false);
        MainActivity.debug("Device.poller: restarting Bluetooth");
        BluetoothManager.getInstance().publishConnecting();
        // drop the link and try again, serialized with every other stop/reload
        MainActivity.restartBluetoothAsync();
    }

    private boolean isCurrentPoller(Thread thread) {
        return isPollerActive() && pollerThread == thread && !thread.isInterrupted();
    }

    private boolean hasWork() {
        synchronized (fields) {
            return applicationFields.size() + activityFieldsScheduled.size() + activityFieldsAsFastAsPossible.size() > 0;
        }
    }

    /** @return false if the sleep was interrupted, i.e. the poller should stop */
    private boolean sleepPoller(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void dropDebug(String msg) {
        MainActivity main = MainActivity.getInstance();
        if (main != null) main.dropDebugMessage(msg);
    }

    private static void appendDebug(String msg) {
        MainActivity main = MainActivity.getInstance();
        if (main != null) main.appendDebugMessage(msg);
    }

    /* ----------------------------------------------------------------
     * Injected requests (poller stopped meanwhile)
     \ -------------------------------------------------------------- */

    public Message injectRequest(String sid) {
        Field field = Fields.getInstance().getBySID(sid);
        if (field == null) return null;
        return injectRequest(field.getFrame());
    }

    /** Stops the poller, requests one frame, and always restarts the poller. */
    public Message injectRequest(Frame frame) {
        if (frame == null) return null;
        stopAndJoin();
        try {
            return requestFrame(frame);
        } finally {
            initConnection();
        }
    }

    public Message injectRequests(Frame[] frames) {
        return injectRequests(frames, false, false);
    }

    /**
     * Stops the poller, requests the frames in order, and always restarts the poller (an early
     * return used to leave it stopped, freezing every screen).
     *
     * @return the last message, or null if a frame is missing or stopOnError hit an error
     */
    public Message injectRequests(Frame[] frames, boolean stopOnError, boolean callOnMessageComplete) {
        if (frames == null) return null;
        stopAndJoin();
        try {
            Message message = null;
            for (Frame frame : frames) {
                if (frame == null) return null;
                message = requestFrame(frame);
                if (stopOnError && message.isError()) return null;
                if (callOnMessageComplete) {
                    if (!message.isError()) {
                        message.onMessageCompleteEvent();
                    } else {
                        message.onMessageIncompleteEvent();
                    }
                }
            }
            return message;
        } finally {
            initConnection();
        }
    }

    /* ----------------------------------------------------------------
     * Polling
     \ -------------------------------------------------------------- */

    private void queryNextFilter() {
        if (!hasWork()) return;
        try {
            Field field = getNextField();
            if (field == null) {
                sleepPoller(NOT_DUE_SLEEP_MS);
                return;
            }

            dropDebug(field.getSID());
            final Frame frame = field.getFrame();

            Message message = requestFrame(frame);
            if (!message.isError()) {
                appendDebug("ok");
                handleFrameSuccess(field, frame, message);
                return;
            }

            // one plain retry, unless we are being asked to stop
            if (!isCurrentPoller(Thread.currentThread())) {
                message.onMessageIncompleteEvent();
                return;
            }
            appendDebug("...");
            message = requestFrame(frame);
            if (!message.isError()) {
                appendDebug("ok");
                handleFrameSuccess(field, frame, message);
                return;
            }

            appendDebug("fail");
            // mark the fields as updated so the frame goes to the back of the queue
            message.onMessageIncompleteEvent();

            if (message.countsAsDeadPid() && blacklistIfDead(frame)) {
                // the dongle and bus are fine, this PID just does not answer on this car:
                // re-initialising here is what used to starve the values that do work
                return;
            }

            // reset, but only if we are not asked to stop
            if (isCurrentPoller(Thread.currentThread()) && BluetoothManager.getInstance().isConnected()) {
                MainActivity.debug("Device.queryNextFilter: Re-initializing");
                deviceIsInitialized = false;
                deviceIsInitialized = initDevice(TOUGHNESS_MEDIUM, 2);
            }
        } catch (RuntimeException e) {
            MainActivity.debug("Device.queryNextFilter: " + e);
        }
    }

    private void handleFrameSuccess(Field field, Frame frame, Message message) {
        frame.registerSuccess();
        provenFrames.add(frame.getRID());
        // updates every field of this frame
        message.onMessageCompleteEvent();
        if (field.getInterval() == INTERVAL_ONCE) {
            removeActivityField(field);
        }
    }

    /**
     * @param frame the frame that just failed
     * @return true if the frame is now blacklisted, so no device reset is warranted
     */
    private boolean blacklistIfDead(Frame frame) {
        if (frame == null) return false;

        // A frame that already answered this session is not an unimplemented PID: repeated
        // failures mean the transport desynced, which a re-initialisation cures.
        if (provenFrames.contains(frame.getRID())) {
            frame.registerSuccess();
            MainActivity.debug("Device.blacklistIfDead: refusing to blacklist proven frame " + frame.getRID());
            return false;
        }

        if (frame.registerFailure() < Blacklist.FAILURE_THRESHOLD) return false;

        // zero the counter, so clearing the blacklist gives every frame fresh chances
        frame.registerSuccess();

        if (!Blacklist.getInstance().add(frame)) {
            return true; // already on the list
        }

        MainActivity.debug("Device.blacklistIfDead: no longer requesting " + frame.getRID());
        MainActivity.toast(MainActivity.getStringSingle(R.string.toast_FieldSkipped) + " " + frame.getRID());

        // let every affected field push a final update, so the UI can turn it red
        for (Field deadField : frame.getAllFields()) {
            deadField.notifyListeners();
        }
        return true;
    }

    private static long dueTime(Field field) {
        return field.getLastRequest() + (long) field.getInterval();
    }

    /**
     * The pollable field that is due first, without allocating. Due times are compared as longs:
     * the old comparator cast the difference to int, which overflowed for fields at
     * Integer.MAX_VALUE and could let a never due field hide every other one.
     */
    private Field getNextDueField(ArrayList<Field> candidates, long referenceTime) {
        Field best = null;
        long bestDue = Long.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Field candidate = candidates.get(i);
            if (candidate == null || candidate.isSkipped()) continue;
            long due = dueTime(candidate);
            if (best == null || due < bestDue) {
                best = candidate;
                bestDue = due;
            }
        }
        return best != null && best.isDue(referenceTime) ? best : null;
    }

    private Field getNextField() {
        long referenceTime = System.currentTimeMillis();
        boolean verbose = MainActivity.isVerbose();

        synchronized (fields) {
            Field field = getNextDueField(applicationFields, referenceTime);
            if (field != null) {
                if (verbose) MainActivity.debug("Device.getNextField: applicationFields, " + field.getSID());
                return field;
            }

            field = getNextDueField(activityFieldsScheduled, referenceTime);
            if (field != null) {
                if (verbose) MainActivity.debug("Device.getNextField: activityFieldsScheduled, " + field.getSID());
                return field;
            }

            // bounded: at most one full pass, so an entirely blacklisted list cannot spin here
            int candidateCount = activityFieldsAsFastAsPossible.size();
            for (int i = 0; i < candidateCount; i++) {
                activityFieldIndex = (activityFieldIndex + 1) % candidateCount;
                Field candidate = activityFieldsAsFastAsPossible.get(activityFieldIndex);
                if (candidate == null || candidate.isSkipped()) continue;
                if (verbose) MainActivity.debug("Device.getNextField: activityFieldsAsFastAsPossible, " + candidate.getSID());
                return candidate;
            }

            if (verbose) {
                MainActivity.debug("Device.getNextField: empty:" + applicationFields.size() + " / " + activityFieldsScheduled.size() + " / " + candidateCount);
            }
            return null;
        }
    }

    public void join() throws InterruptedException {
        Thread poller = pollerThread;
        if (poller != null)
            poller.join();
    }

    /* ----------------------------------------------------------------
     * Field registration
     \ -------------------------------------------------------------- */

    /**
     * Drops every screen specific field. Fields that were only polled for a screen go back to
     * "never due", and application fields get their own interval back.
     */
    public void clearFields() {
        MainActivity.debug("Device.clearFields: start");
        synchronized (fields) {
            releaseActivityIntervals(activityFieldsScheduled);
            releaseActivityIntervals(activityFieldsAsFastAsPossible);
            activityFieldsScheduled.clear();
            activityFieldsAsFastAsPossible.clear();
            fields.clear();
            fields.addAll(applicationFields);
            for (Map.Entry<Field, Integer> entry : applicationIntervals.entrySet()) {
                entry.getKey().setInterval(entry.getValue());
            }
        }
    }

    private void releaseActivityIntervals(ArrayList<Field> list) {
        for (Field field : list) {
            if (!applicationIntervals.containsKey(field)) field.setInterval(Integer.MAX_VALUE);
        }
    }

    /** One request returns every field of a frame, so fields are compared by frame. */
    private static boolean sameFrame(Field a, Field b) {
        return a.getId() == b.getId() && a.getResponseId().equals(b.getResponseId());
    }

    private static Field findSameFrame(ArrayList<Field> list, Field field) {
        for (int i = 0; i < list.size(); i++) {
            Field candidate = list.get(i);
            if (sameFrame(candidate, field)) return candidate;
        }
        return null;
    }

    private boolean containsField(Field field) {
        return findSameFrame(fields, field) != null;
    }

    private boolean containsApplicationField(Field field) {
        return findSameFrame(applicationFields, field) != null;
    }

    private boolean containsActivityFieldScheduled(Field field) {
        return findSameFrame(activityFieldsScheduled, field) != null;
    }

    private boolean containsActivityFieldAsFastAsPossible(Field field) {
        return findSameFrame(activityFieldsAsFastAsPossible, field) != null;
    }

    /** Registers a field to be polled flat out (INTERVAL_ASAPFAST). */
    private void addActivityFieldAsap(final Field field) {
        // already present listeners are not registered twice
        field.addListener(CanzeDataSource.getInstance());

        if (field.isVirtual()) {
            // poll the real fields a virtual field depends on
            for (Field realField : ((VirtualField) field).getFields()) {
                addActivityFieldAsap(realField);
            }
            return;
        }

        synchronized (fields) {
            if (!containsField(field)) fields.add(field);
            if (containsActivityFieldAsFastAsPossible(field)) return;
            activityFieldsAsFastAsPossible.add(field);
            // the frame is polled flat out now, scheduled entries for it are redundant
            for (int i = activityFieldsScheduled.size() - 1; i >= 0; i--) {
                if (sameFrame(activityFieldsScheduled.get(i), field)) activityFieldsScheduled.remove(i);
            }
        }
    }

    /**
     * interval > 0: every interval ms
     * INTERVAL_ASAP (0): the frame's own repeat interval (relevant for free frames)
     * INTERVAL_ASAPFAST (-1): as fast as possible. Use sparingly
     * INTERVAL_ONCE (-2): once, removed after the first successful answer (e.g. tester present)
     */
    public void addActivityField(final Field field, int interval) {
        if (field == null) return;
        if (interval == INTERVAL_ASAPFAST) {
            addActivityFieldAsap(field);
            return;
        }
        if (interval == INTERVAL_ASAP) {
            Frame frame = field.getFrame();
            if (frame == null) return;
            interval = frame.getInterval();
        } else if (interval < 0 && interval != INTERVAL_ONCE) {
            return; // unknown code
        }

        // already present listeners are not registered twice
        field.addListener(CanzeDataSource.getInstance());

        if (field.isVirtual()) {
            VirtualField virtualField = (VirtualField) field;
            int count = Math.max(1, virtualField.getFields().size());
            // spread the dependencies over the interval
            int realInterval = interval > 0 ? (int) Math.min(Integer.MAX_VALUE, (long) interval * count) : interval;
            for (Field realField : virtualField.getFields()) {
                addActivityField(realField, realInterval);
            }
            return;
        }

        synchronized (fields) {
            addScheduledLocked(field, interval);
        }
    }

    private void addScheduledLocked(Field field, int interval) {
        if (!containsField(field)) {
            fields.add(field);
            activityFieldsScheduled.add(field);
            field.setInterval(interval);
            return;
        }
        // the frame is already polled
        if (containsActivityFieldAsFastAsPossible(field)) return; // flat out already
        if (interval == INTERVAL_ONCE) return; // the next regular poll serves the one shot

        Field scheduled = findSameFrame(activityFieldsScheduled, field);
        if (scheduled != null) {
            // the smallest interval wins. It used to be set on the new field, which is not the
            // one being polled, so asking for a faster rate had no effect. A one shot entry is
            // upgraded, otherwise its removal would stop the regular polling of the frame.
            if (scheduled.getInterval() == INTERVAL_ONCE || interval < scheduled.getInterval()) {
                scheduled.setInterval(interval);
            }
            return;
        }

        // so far only polled for the application (e.g. safe driving speed)
        activityFieldsScheduled.add(field);
        field.setInterval(applicationIntervals.containsKey(field) ? Math.min(field.getInterval(), interval) : interval);
    }

    public void removeActivityField(final Field field) {
        if (field == null) return;
        synchronized (fields) {
            if (!activityFieldsScheduled.remove(field)) return;
            if (containsApplicationField(field) || containsActivityFieldAsFastAsPossible(field)) {
                // still polled for another reason: give an application field its interval back
                Integer applicationInterval = applicationIntervals.get(field);
                if (applicationInterval != null) field.setInterval(applicationInterval);
                return;
            }
            fields.remove(field);
            field.setInterval(Integer.MAX_VALUE);
            field.removeListener(CanzeDataSource.getInstance());
        }
    }

    /**
     * Registers a field that is polled regardless of the screen on display. Before, a field whose
     * frame a screen already polled was silently not registered, and vanished with that screen.
     */
    public void addApplicationField(final Field field, int interval) {
        if (field == null) return;
        // already present listeners are not registered twice
        field.addListener(CanzeDataSource.getInstance());

        if (field.isVirtual()) {
            VirtualField virtualField = (VirtualField) field;
            int count = Math.max(1, virtualField.getFields().size());
            int realInterval = (int) Math.min(Integer.MAX_VALUE, (long) interval * count);
            for (Field realField : virtualField.getFields()) {
                addApplicationField(realField, realInterval);
            }
            return;
        }

        synchronized (fields) {
            Field representative = findSameFrame(applicationFields, field);
            if (representative == null) {
                representative = field;
                applicationFields.add(field);
                if (!fields.contains(field)) fields.add(field);
            }
            Integer previous = applicationIntervals.get(representative);
            int merged = previous == null ? interval : Math.min(previous, interval);
            applicationIntervals.put(representative, merged);
            boolean alsoForScreen = activityFieldsScheduled.contains(representative)
                    || activityFieldsAsFastAsPossible.contains(representative);
            representative.setInterval(alsoForScreen ? Math.min(representative.getInterval(), merged) : merged);
        }
    }

    public void removeApplicationField(final Field field) {
        if (field == null) return;
        synchronized (fields) {
            applicationIntervals.remove(field);
            if (!applicationFields.remove(field)) return;
            if (!containsActivityFieldScheduled(field) && !containsActivityFieldAsFastAsPossible(field)) {
                fields.remove(field);
                field.setInterval(Integer.MAX_VALUE);
                field.removeListener(CanzeDataSource.getInstance());
            }
        }
        // the real fields of a virtual field are not removed: another one may still need them
    }

    /* ----------------------------------------------------------------
     * Link lifecycle
     \ -------------------------------------------------------------- */

    /** Called for every freshly established Bluetooth link. */
    public void init(boolean reset) {
        // the dongle may still be doing whatever it did when the old link dropped
        deviceIsInitialized = false;

        initConnection();

        if (reset) {
            clearFields();
            MainActivity.debug("Device.init: done, reset");
        } else
            MainActivity.debug("Device.init: done, noreset");
    }

    /**
     * Stops the poller thread and waits (bounded) for it to finish. Interrupts the poller so
     * blocking waits inside the device end quickly.
     */
    public void stopAndJoin() {
        MainActivity.debug("Device.stopAndJoin: start");
        final Thread poller;
        synchronized (pollerLock) {
            setPollerActive(false);
            poller = pollerThread;
        }

        if (poller == null || !poller.isAlive()) {
            MainActivity.debug("Device.stopAndJoin: pollerThread is null");
            clearPollerReference(poller);
            return;
        }
        if (poller == Thread.currentThread()) {
            // joining ourselves would dead-lock; the loop ends on its own now
            MainActivity.debug("Device.stopAndJoin: called from the poller itself, not joining");
            return;
        }

        poller.interrupt();
        try {
            poller.join(STOP_JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            MainActivity.debug("Device.stopAndJoin: interrupted while waiting for the poller");
        }
        if (poller.isAlive()) {
            // no longer the registered poller, so it exits at its next loop check
            MainActivity.debug("Device.stopAndJoin: poller did not stop within " + STOP_JOIN_TIMEOUT_MS + " ms, abandoning it");
        }
        clearPollerReference(poller);
        MainActivity.debug("Device.stopAndJoin: poller stopped");
    }

    private void clearPollerReference(Thread poller) {
        synchronized (pollerLock) {
            if (poller != null && pollerThread == poller) pollerThread = null;
        }
    }

    boolean isPollerActive() {
        return pollerActive;
    }

    void setPollerActive(boolean pollerActive) {
        this.pollerActive = pollerActive;
    }

    /* ----------------------------------------------------------------
     * Requests
     \ -------------------------------------------------------------- */

    /** Requests a frame, ISO-TP or free, and adapts the free frame timeout to the outcome. */
    public Message requestFrame(Frame frame) {
        Message msg = frame.isIsoTp() ? requestIsoTpFrame(frame) : requestFreeFrame(frame);

        if (msg.isError()) {
            MainActivity.debug("Device.requestframe: " + frame.getRID() + " returned error " + msg.getError());
            // an empty answer means the timeout is too low: increase it
            if (intervalMultiplicator < maxIntervalMultiplicator) {
                intervalMultiplicator += 0.1;
            }
        } else {
            if (MainActivity.isVerbose()) {
                MainActivity.debug("Device.requestframe: request for " + frame.getRID() + " returned data " + msg.getData());
            }
            // recover slowly after good answers
            if (intervalMultiplicator > minIntervalMultiplicator) {
                intervalMultiplicator -= 0.01;
            }
        }
        return msg;
    }

    public abstract Message requestFreeFrame(Frame frame);

    public abstract Message requestIsoTpFrame(Frame frame);

    public abstract boolean initDevice(int toughness);

    protected abstract boolean initDevice(int toughness, int retries);

    public String getLastInitProblem() {
        return lastInitProblem;
    }
}
