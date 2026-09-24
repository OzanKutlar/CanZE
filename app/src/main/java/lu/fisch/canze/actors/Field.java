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

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * One value inside a frame. Other objects can register to be notified of updates.
 *
 * Threading: the poller notifies while the UI thread adds and removes listeners, so the listener
 * list is copy on write. Listeners receive one immutable snapshot per update.
 *
 * @author robertfisch
 */
public class Field {

    private static final double KM_PER_MILE = 1.609344;
    private static final int CONVERT_NONE = 0;
    private static final int CONVERT_DIVIDE = 1;   // unit starts with km, e.g. km/h
    private static final int CONVERT_MULTIPLY = 2; // unit ends with km, e.g. kWh/100km

    final CopyOnWriteArrayList<FieldListener> fieldListeners = new CopyOnWriteArrayList<>();

    private final String sid;
    private Frame frame;
    private short from;
    private short to;
    private int offset;
    private int decimals;
    private double resolution;
    private String unit;
    private String unitMiles;
    private int unitConversion;
    private String responseId;
    private short options; // see the options definitions in MainActivity
    private final String name;
    private final String list;
    private final String[] listItems;

    private volatile double value = Double.NaN;
    private volatile String strVal = "";

    private volatile long lastRequest;
    protected volatile int interval = Integer.MAX_VALUE;

    boolean virtual = false;

    /*
        The offset is applied BEFORE scaling.
        Request and response ids are hex strings without a leading 0x.
        The name is optional, for diagnostic printouts.
        The list is optional: semicolon separated descriptions of the values, 0 based.
     */
    public Field(String sid, Frame frame, short from, short to, double resolution, int decimals, int offset, String unit, String responseId, short options, String name, String list) {
        String rid = responseId == null ? "" : responseId.trim();
        this.sid = buildSid(sid, frame, rid, from);
        this.frame = frame;
        this.from = from;
        this.to = to;
        this.offset = offset;
        this.resolution = resolution;
        this.decimals = decimals;
        applyUnit(unit);
        this.responseId = rid;
        this.options = options;
        this.name = name;
        this.list = list;
        this.listItems = (list == null || list.isEmpty()) ? null : list.split(";");
        this.lastRequest = System.currentTimeMillis();
    }

    /** Snapshot for listeners: copies the current state, not the listeners. */
    private Field(Field source) {
        this.sid = source.sid;
        this.frame = source.frame;
        this.from = source.from;
        this.to = source.to;
        this.offset = source.offset;
        this.decimals = source.decimals;
        this.resolution = source.resolution;
        this.unit = source.unit;
        this.unitMiles = source.unitMiles;
        this.unitConversion = source.unitConversion;
        this.responseId = source.responseId;
        this.options = source.options;
        this.name = source.name;
        this.list = source.list;
        this.listItems = source.listItems;
        this.value = source.value;
        this.strVal = source.strVal;
        this.lastRequest = source.lastRequest;
        this.interval = source.interval;
        this.virtual = source.virtual;
    }

    private static String buildSid(String sid, Frame frame, String rid, short from) {
        if (sid != null && !sid.isEmpty()) return sid;
        if (frame == null) throw new IllegalArgumentException("a field without SID needs a frame");
        String hex = Integer.toHexString(frame.getId());
        String generated = rid.isEmpty() ? hex + "." + from : hex + "." + rid + "." + from;
        return generated.toLowerCase(Locale.US);
    }

    /** Works out the miles conversion once instead of on every getValue(). */
    private void applyUnit(String newUnit) {
        unit = newUnit == null ? "" : newUnit;
        String lower = unit.toLowerCase(Locale.US);
        if (lower.startsWith("km")) unitConversion = CONVERT_DIVIDE;
        else if (lower.endsWith("km")) unitConversion = CONVERT_MULTIPLY;
        else unitConversion = CONVERT_NONE;
        unitMiles = unit.replace("km", "mi");
    }

    @Override
    public String toString() {
        return getSID() + " : " + getName() + " [" + getUnit() + "]";
    }

    boolean isIsoTp() {
        return !responseId.isEmpty();
    }

    public String getSID() {
        return sid;
    }

    public String getPrintValue() {
        return getValue() + " " + getUnit();
    }

    public String getStringValue() {
        return strVal;
    }

    public String getListValue() {
        String[] items = listItems;
        if (items == null) return "";
        double v = value;
        if (Double.isNaN(v) || v < 0 || v >= items.length) return "";
        return items[(int) v];
    }

    /**
     * Scaled value. In miles mode km based units are converted, except for virtual fields whose
     * sources are already converted. NaN stays NaN: Math.round(NaN) is 0, which used to turn a
     * missing value into a real looking zero in miles mode.
     */
    public double getValue() {
        double val = (value - offset) * resolution;
        if (!MainActivity.milesMode || virtual || Double.isNaN(val)) return val;
        if (unitConversion == CONVERT_DIVIDE) return Math.round(val / KM_PER_MILE * 10.0) / 10.0;
        if (unitConversion == CONVERT_MULTIPLY) return Math.round(val * KM_PER_MILE * 10.0) / 10.0;
        return val;
    }

    public double getMax() {
        double val = (int) Math.pow(2, to - from + 1);
        return ((val - offset) * resolution);
    }

    public double getMin() {
        double val = 0;
        return ((val - offset) * resolution);
    }

    /* --------------------------------
     * Listeners management
     \ ------------------------------ */

    public void addListener(FieldListener fieldListener) {
        if (fieldListener == null) return;
        if (fieldListeners.addIfAbsent(fieldListener)) {
            // trigger an immediate update to pass the reference to this field
            fieldListener.onFieldUpdateEvent(this);
        }
    }

    public void removeListener(FieldListener fieldListener) {
        if (fieldListener != null) fieldListeners.remove(fieldListener);
    }

    /** One snapshot per update, shared by all listeners; a failing listener does not stop the rest. */
    private void notifyFieldListeners() {
        if (fieldListeners.isEmpty()) return;
        Field snapshot = new Field(this);
        for (FieldListener listener : fieldListeners) {
            try {
                listener.onFieldUpdateEvent(snapshot);
            } catch (RuntimeException e) {
                MainActivity.debug("Field " + sid + ": listener failed: " + e);
            }
        }
    }

    /* --------------------------------
     * Scheduling
     \ ------------------------------ */

    void updateLastRequest() {
        lastRequest = System.currentTimeMillis();
    }

    public long getLastRequest() {
        return lastRequest;
    }

    public boolean isDue(long referenceTime) {
        return lastRequest + interval < referenceTime;
    }

    public void setInterval(int interval) {
        this.interval = interval;
    }

    public int getInterval() {
        return interval;
    }

    /**
     * Whether this field's frame has been blacklisted after repeated failures. Derived from the
     * frame, so snapshots handed to listeners always report the current state.
     */
    public boolean isSkipped() {
        Frame f = frame;
        return f != null && f.isSkipped();
    }

    /** Push the current state to all listeners, e.g. when the field just became skipped. */
    public void notifyListeners() {
        notifyFieldListeners();
    }

    /* --------------------------------
     * Getters & setters
     \ ------------------------------ */

    public int getFrom() {
        return from;
    }

    public void setFrom(short from) {
        this.from = from;
    }

    public int getTo() {
        return to;
    }

    public void setTo(short to) {
        this.to = to;
    }

    public double getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public double getRawValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
        if (!Double.isNaN(value)) notifyFieldListeners();
    }

    public void setValue(String value) {
        this.strVal = value;
        notifyFieldListeners();
    }

    /** Sets a scaled value, inverting the scaling (and the miles conversion). */
    public void setCalculatedValue(double value) {
        double raw = value;
        if (MainActivity.milesMode && !virtual) {
            if (unitConversion == CONVERT_DIVIDE) raw = raw * KM_PER_MILE;
            else if (unitConversion == CONVERT_MULTIPLY) raw = raw / KM_PER_MILE;
        }
        setValue(raw / resolution + offset);
    }

    public int getId() {
        Frame f = frame;
        return f == null ? 0 : f.getId();
    }

    public String getHexId() {
        Frame f = frame;
        return f == null ? "" : f.getHexId();
    }

    public double getResolution() {
        return resolution;
    }

    public void setResolution(double resolution) {
        this.resolution = resolution;
    }

    public String getUnit() {
        return MainActivity.milesMode ? unitMiles : unit;
    }

    public void setUnit(String unit) {
        applyUnit(unit);
    }

    /** Request id: the response id with the first nibble lowered by 4 (0x62 becomes 0x22). */
    public String getRequestId() {
        if (responseId.isEmpty()) return "";
        char[] tmpChars = responseId.toCharArray();
        tmpChars[0] -= 0x04;
        return String.valueOf(tmpChars);
    }

    public String getResponseId() {
        return responseId;
    }

    public void setResponseId(String responseId) {
        this.responseId = responseId == null ? "" : responseId.trim();
    }

    public int getCar() {
        return (options & 0x0f);
    }

    public boolean isCar(int car) {
        return (options & car) == car;
    }

    public void setCar(int car) {
        options = (short) ((options & 0xfe0) + (car & 0x1f));
    }

    public int getFrequency() {
        Frame f = frame;
        return f == null ? 0 : f.getInterval();
    }

    public int getDecimals() {
        return decimals;
    }

    public void setDecimals(int decimals) {
        this.decimals = decimals;
    }

    public boolean isVirtual() {
        return virtual;
    }

    boolean isSigned() {
        return (this.options & MainActivity.FIELD_TYPE_MASK) == MainActivity.FIELD_TYPE_SIGNED;
    }

    public boolean isString() {
        return (this.options & MainActivity.FIELD_TYPE_MASK) == MainActivity.FIELD_TYPE_STRING;
    }

    public boolean isList() {
        return listItems != null;
    }

    public String getName() {
        return name;
    }

    public Frame getFrame() {
        return frame;
    }

    public void setFrame(Frame frame) {
        this.frame = frame;
    }
}
