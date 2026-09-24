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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import lu.fisch.canze.classes.Blacklist;

/**
 * Frame
 *
 * Id and response id never change, so the hex id and the RID are computed once: isSkipped() runs
 * for every scheduling candidate on every poll and used to format a new string each time. The
 * field lists are copy on write because the poller iterates them while a reload adds fields.
 */
public class Frame {

    private final int id;
    private final String responseId;
    private final boolean isoTp;
    private final Ecu sendingEcu;
    private final Frame containingFrame;
    private final String hexId;
    private final String rid;
    private volatile int interval; // in ms

    private final CopyOnWriteArrayList<Field> fields = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Field> queriedFields = new CopyOnWriteArrayList<>();

    /** number of consecutive failed requests for this frame */
    private int consecutiveFailures = 0;

    protected volatile long lastRequest = 0;

    public Frame(int id, int interval, Ecu sendingEcu, String responseId, Frame containingFrame) {
        this.id = id;
        this.interval = interval;
        this.sendingEcu = sendingEcu;
        this.responseId = responseId;
        this.containingFrame = containingFrame;
        String trimmed = responseId == null ? "" : responseId.trim();
        this.isoTp = !trimmed.isEmpty();
        this.hexId = String.format(Locale.US, "%03x", id);
        this.rid = (isoTp ? hexId + "." + trimmed : hexId).toLowerCase(Locale.US);
    }

    /* --------------------------------
     * Scheduling
     * ------------------------------ */

    public void updateLastRequest() {
        lastRequest = System.currentTimeMillis();
    }

    public long getLastRequest() {
        return lastRequest;
    }

    public boolean isDue(long referenceTime) {
        return lastRequest + interval < referenceTime;
    }

    /* --------------------------------
     * Failure tracking
     * ------------------------------ */

    /** @return the new number of consecutive failures */
    public synchronized int registerFailure() {
        if (consecutiveFailures < Integer.MAX_VALUE) {
            consecutiveFailures++;
        }
        return consecutiveFailures;
    }

    /** Record a successful request, clearing the failure streak. */
    public synchronized void registerSuccess() {
        consecutiveFailures = 0;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** @return true if this frame has been blacklisted and must not be polled */
    public boolean isSkipped() {
        return Blacklist.getInstance().contains(rid);
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public boolean isIsoTp() {
        return isoTp;
    }

    public int getId() {
        return id;
    }

    public String getRID() {
        return rid;
    }

    public String getHexId() {
        return hexId;
    }

    public Ecu getSendingEcu() {
        return sendingEcu;
    }

    public String getResponseId() {
        return responseId;
    }

    public List<Field> getAllFields() {
        return fields;
    }

    public void addField(Field field) {
        if (field != null) fields.add(field);
    }

    public List<Field> getQueriedFields() {
        return queriedFields;
    }

    public void addQueriedField(Field field) {
        if (field != null) queriedFields.add(field);
    }

    public void removeQueriedField(Field field) {
        queriedFields.remove(field);
    }

    public String getRequestId() {
        if (responseId == null || responseId.isEmpty()) return "";
        char[] tmpChars = responseId.toCharArray();
        tmpChars[0] -= 0x04;
        return String.valueOf(tmpChars);
    }

    public Frame getContainingFrame() {
        return containingFrame;
    }
}
