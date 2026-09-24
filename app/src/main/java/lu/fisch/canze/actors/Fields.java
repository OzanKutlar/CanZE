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
import java.util.Locale;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.interfaces.VirtualFieldAction;

/**
 * All known fields.
 *
 * Threading: a load builds a new list and map and publishes them in one step, so readers on
 * other threads (poller, database restore, draw threads) always see a complete set, never a
 * half cleared one. Published collections are never modified afterwards; a later add() copies.
 *
 * @author robertfisch
 */
public class Fields {

    private static final int FIELD_SID          = 0; // hex, no leading 0x
    private static final int FIELD_ID           = 1; // hex, no leading 0x
    private static final int FIELD_FROM         = 2; // decimal
    private static final int FIELD_TO           = 3; // decimal
    private static final int FIELD_RESOLUTION   = 4; // double
    private static final int FIELD_OFFSET       = 5; // double
    private static final int FIELD_DECIMALS     = 6; // decimal
    private static final int FIELD_UNIT         = 7;
    private static final int FIELD_REQUEST_ID   = 8; // hex, no leading 0x
    private static final int FIELD_RESPONSE_ID  = 9; // hex, no leading 0x
    private static final int FIELD_OPTIONS      = 10; // hex, no leading 0x
    private static final int FIELD_NAME         = 11; // used for diagnostic ISO-TP printouts
    private static final int FIELD_LIST         = 12; // same

    public static final int TOAST_NONE          = 0;
    public static final int TOAST_DEVICE        = 1;
    public static final int TOAST_ALL           = 2;

    private static Fields instance = null;

    private volatile ArrayList<Field> fields = new ArrayList<>();
    private volatile HashMap<String, Field> fieldsBySid = new HashMap<>();

    /** only set while load() runs */
    private ArrayList<Field> building = null;
    private HashMap<String, Field> buildingBySid = null;

    private double runningUsage = 0;
    private double realRangeReference = Double.NaN;
    private static long start = System.currentTimeMillis();

    private Fields() {
        // filled by load(), once the car is known
    }

    public static boolean initialised() {
        return (instance == null);
    }

    public static synchronized Fields getInstance() {
        if (instance == null) instance = new Fields();
        return instance;
    }

    private static String key(String sid) {
        return sid.toLowerCase(Locale.US);
    }

    /* --------------------------------
     * Virtual fields
     \ ------------------------------ */

    private void addVirtualFields() {
        addVirtualFieldUsage();
        addVirtualFieldUsageLpf();
        addVirtualFieldFrictionTorque();
        addVirtualFieldFrictionPower();
        addVirtualFieldDcPower();
        addVirtualFieldHeaterSetpoint();
        addVirtualFieldRealRange();
        addVirtualFieldRealDelta();
        addVirtualFieldRealDeltaNoReset();
    }

    private void addVirtualFieldUsage() {
        final String SID_EVC_TractionBatteryVoltage = "7ec.623203.24";  // unit = V
        final String SID_EVC_TractionBatteryCurrent = "7ec.623204.24";  // unit = A
        final String SID_RealSpeed = "5d7.0";                           // unit = km/h

        addVirtualFieldCommon("6100", "kWh/100km", SID_EVC_TractionBatteryVoltage + ";" + SID_EVC_TractionBatteryCurrent + ";" + SID_RealSpeed, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double realSpeed = dependantFields.get(SID_RealSpeed).getValue();
                if (realSpeed < 0 || realSpeed > 150) return Double.NaN;
                if (realSpeed < 5) return 0;
                double dcVolt = dependantFields.get(SID_EVC_TractionBatteryVoltage).getValue();
                double dcCur = dependantFields.get(SID_EVC_TractionBatteryCurrent).getValue();
                if (dcVolt < 300 || dcVolt > 450 || dcCur < -200 || dcCur > 100) return Double.NaN;
                double dcPwr = dcVolt * dcCur / 1000.0;
                double usage = -(Math.round(1000.0 * dcPwr / realSpeed) / 10.0);
                if (usage < -150) return -150;
                else if (usage > 150) return 150;
                else return usage;
            }
        });
    }

    private void addVirtualFieldFrictionTorque() {
        final String SID_DriverBrakeWheel_Torque_Request = "130.44";
        final String SID_ElecBrakeWheelsTorqueApplied = "1f8.28";

        addVirtualFieldCommon("6101", "Nm", SID_DriverBrakeWheel_Torque_Request + ";" + SID_ElecBrakeWheelsTorqueApplied, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                return dependantFields.get(SID_DriverBrakeWheel_Torque_Request).getValue() - dependantFields.get(SID_ElecBrakeWheelsTorqueApplied).getValue();
            }
        });
    }

    private void addVirtualFieldFrictionPower() {
        final String SID_DriverBrakeWheel_Torque_Request = "130.44";
        final String SID_ElecBrakeWheelsTorqueApplied = "1f8.28";
        final String SID_ElecEngineRPM = "1f8.40";

        addVirtualFieldCommon("6102", "kW", SID_DriverBrakeWheel_Torque_Request + ";" + SID_ElecBrakeWheelsTorqueApplied + ";" + SID_ElecEngineRPM, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                return (dependantFields.get(SID_DriverBrakeWheel_Torque_Request).getValue() - dependantFields.get(SID_ElecBrakeWheelsTorqueApplied).getValue()) * dependantFields.get(SID_ElecEngineRPM).getValue() / MainActivity.reduction;
            }
        });
    }

    private void addVirtualFieldDcPower() {
        final String SID_TractionBatteryVoltage = "7ec.623203.24";
        final String SID_TractionBatteryCurrent = "7ec.623204.24";

        addVirtualFieldCommon("6103", "kW", SID_TractionBatteryVoltage + ";" + SID_TractionBatteryCurrent, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                return dependantFields.get(SID_TractionBatteryVoltage).getValue() * dependantFields.get(SID_TractionBatteryCurrent).getValue() / 1000;
            }
        });
    }

    private void addVirtualFieldUsageLpf() {
        // the averaging runs on the real time between samples, capped at 1 s
        final String SID_VirtualUsage = "800.6100.24";

        addVirtualFieldCommon("6104", "kWh/100km", SID_VirtualUsage, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double value = dependantFields.get(SID_VirtualUsage).getValue();
                if (!Double.isNaN(value)) {
                    long now = System.currentTimeMillis();
                    long since = now - start;
                    if (since > 1000) since = 1000;
                    start = now;
                    double factor = since * 0.00005; // 0.05 per second
                    runningUsage = runningUsage * (1 - factor) + value * factor;
                }
                return runningUsage;
            }
        });
    }

    private void addVirtualFieldHeaterSetpoint() {
        final String SID_VirtualUsage = "699.8";

        addVirtualFieldCommon("6105", "°C", SID_VirtualUsage, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double value = dependantFields.get(SID_VirtualUsage).getValue();
                if (value == 0) {
                    return Double.NaN;
                } else if (value == 4) {
                    return -10.0;
                } else if (value == 5) {
                    return 40.0;
                }
                return value;
            }
        });
    }

    /** @return true if no range estimate was stored for 15 minutes (served from memory) */
    private static boolean rangeReferenceExpired(String sidRangeEstimate) {
        long lastInsertedTime = CanzeDataSource.getInstance().getLastTime(sidRangeEstimate);
        return System.currentTimeMillis() - lastInsertedTime > 15 * 60 * 1000;
    }

    private void addVirtualFieldRealRange() {
        final String SID_EVC_Odometer = "7ec.622006.24";
        final String SID_RangeEstimate = "654.42";

        if (Double.isNaN(realRangeReference))
            realRangeReference = CanzeDataSource.getInstance().getLast(SID_RangeEstimate);

        addVirtualFieldCommon("6106", "km", SID_EVC_Odometer + ";" + SID_RangeEstimate, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double odo = dependantFields.get(SID_EVC_Odometer).getValue();
                double gom = dependantFields.get(SID_RangeEstimate).getValue();
                if (rangeReferenceExpired(SID_RangeEstimate) || Double.isNaN(realRangeReference)) {
                    if (!Double.isNaN(gom) && !Double.isNaN(odo)) {
                        realRangeReference = odo + gom;
                    }
                }
                if (Double.isNaN(realRangeReference)) {
                    return Double.NaN;
                }
                return realRangeReference - odo;
            }
        });
    }

    private void addVirtualFieldRealDelta() {
        final String SID_EVC_Odometer = "7ec.622006.24";
        final String SID_RangeEstimate = "654.42";

        if (Double.isNaN(realRangeReference)) {
            realRangeReference = CanzeDataSource.getInstance().getLast(SID_RangeEstimate);
        }

        addVirtualFieldCommon("6107", "km", SID_EVC_Odometer + ";" + SID_RangeEstimate, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double odo = dependantFields.get(SID_EVC_Odometer).getValue();
                double gom = dependantFields.get(SID_RangeEstimate).getValue();
                if (rangeReferenceExpired(SID_RangeEstimate) || Double.isNaN(realRangeReference)) {
                    if (!Double.isNaN(gom) && !Double.isNaN(odo)) {
                        realRangeReference = odo + gom;
                    }
                }
                if (Double.isNaN(realRangeReference)) {
                    return Double.NaN;
                }
                double delta = realRangeReference - odo - gom;
                if (delta > 12.0 || delta < -12.0) {
                    realRangeReference = odo + gom;
                    delta = 0.0;
                }
                return delta;
            }
        });
    }

    private void addVirtualFieldRealDeltaNoReset() {
        final String SID_EVC_Odometer = "7ec.622006.24";
        final String SID_RangeEstimate = "654.42";

        if (Double.isNaN(realRangeReference)) {
            realRangeReference = CanzeDataSource.getInstance().getLast(SID_RangeEstimate);
        }

        addVirtualFieldCommon("6108", "km", SID_EVC_Odometer + ";" + SID_RangeEstimate, new VirtualFieldAction() {
            @Override
            public double updateValue(HashMap<String, Field> dependantFields) {
                double odo = dependantFields.get(SID_EVC_Odometer).getValue();
                double gom = dependantFields.get(SID_RangeEstimate).getValue();
                if (rangeReferenceExpired(SID_RangeEstimate) || Double.isNaN(realRangeReference)) {
                    if (!Double.isNaN(gom) && !Double.isNaN(odo)) {
                        realRangeReference = odo + gom;
                    }
                }
                if (Double.isNaN(realRangeReference)) {
                    return Double.NaN;
                }
                return realRangeReference - odo - gom;
            }
        });
    }

    private void addVirtualFieldCommon(String virtualId, String unit, String dependantIds, VirtualFieldAction virtualFieldAction) {
        Frame frame = Frames.getInstance().getById(0x800);
        if (frame == null) {
            MainActivity.debug("Fields: virtual frame 800 missing, skipping virtual field " + virtualId);
            return;
        }
        HashMap<String, Field> dependantFields = new HashMap<>();
        for (String idStr : dependantIds.split(";")) {
            Field field = lookupForLoad(idStr);
            // a dependency missing for this car: the virtual field cannot exist
            if (field == null) return;
            dependantFields.put(idStr, field);
        }
        VirtualField virtualField = new VirtualField(virtualId, dependantFields, unit, virtualFieldAction);
        // a virtual field is always ISO-TP, so it needs a subframe
        Frame subFrame = Frames.getInstance().getById(0x800, virtualField.getResponseId());
        if (subFrame == null) {
            subFrame = new Frame(frame.getId(), frame.getInterval(), frame.getSendingEcu(), virtualField.getResponseId(), frame);
            Frames.getInstance().add(subFrame);
        }
        subFrame.addField(virtualField);
        virtualField.setFrame(subFrame);
        addInternal(virtualField);
    }

    /* --------------------------------
     * Loading
     \ ------------------------------ */

    private void fillOneLine(String line) {
        if (line.contains("#")) line = line.substring(0, line.indexOf('#'));
        String[] tokens = line.split(",");
        if (tokens.length <= FIELD_OPTIONS) return;

        int frameId;
        short options;
        try {
            frameId = Integer.parseInt(tokens[FIELD_ID].trim(), 16);
            options = Short.parseShort(tokens[FIELD_OPTIONS].trim(), 16);
        } catch (NumberFormatException e) {
            // one bad line used to abort the whole load
            MainActivity.debug("Fields: malformed line skipped: " + line);
            return;
        }

        Frame frame = Frames.getInstance().getById(frameId);
        if (frame == null) {
            if (MainActivity.isVerbose()) MainActivity.debug("frame does not exist:" + tokens[FIELD_ID].trim());
            return;
        }
        // only fields for the selected car
        if ((options & MainActivity.car) == 0) return;

        if (MainActivity.isVerbose()) {
            MainActivity.debug(tokens[FIELD_SID] + " " + tokens[FIELD_ID] + "." + tokens[FIELD_FROM] + "." + tokens[FIELD_RESPONSE_ID]);
        }
        try {
            Field field = new Field(
                    tokens[FIELD_SID].trim(),
                    frame,
                    Short.parseShort(tokens[FIELD_FROM].trim()),
                    Short.parseShort(tokens[FIELD_TO].trim()),
                    Double.parseDouble(tokens[FIELD_RESOLUTION].trim()),
                    Integer.parseInt(tokens[FIELD_DECIMALS].trim()),
                    Integer.parseInt(tokens[FIELD_OFFSET].trim()),
                    tokens[FIELD_UNIT].trim(),
                    tokens[FIELD_RESPONSE_ID].trim(),
                    options,
                    (tokens.length > FIELD_NAME) ? tokens[FIELD_NAME] : "",
                    (tokens.length > FIELD_LIST) ? tokens[FIELD_LIST] : ""
            );

            // Each frame keeps its fields, so an incoming message updates them all at once. An
            // ISO-TP frame is only a skeleton: its definition depends on the response id, so a
            // subframe is created per response id.
            if (field.isIsoTp()) {
                Frame subFrame = Frames.getInstance().getById(frameId, field.getResponseId());
                if (subFrame == null) {
                    subFrame = new Frame(frame.getId(), frame.getInterval(), frame.getSendingEcu(), field.getResponseId(), frame);
                    Frames.getInstance().add(subFrame);
                }
                subFrame.addField(field);
                field.setFrame(subFrame);
            } else {
                frame.addField(field);
            }
            addInternal(field);
        } catch (RuntimeException e) {
            MainActivity.debug("Fields: could not create field from: " + line + " (" + e + ")");
        }
    }

    private void fillFromAsset(String assetName) {
        AssetLoadHelper assetLoadHelper = new AssetLoadHelper(MainActivity.getInstance());
        BufferedReader bufferedReader = assetLoadHelper.getBufferedReaderFromAsset(assetName);
        if (bufferedReader == null) {
            MainActivity.toast(-100, "Can't access asset " + assetName);
            return;
        }
        try {
            String line;
            while ((line = bufferedReader.readLine()) != null)
                fillOneLine(line);
        } catch (IOException e) {
            MainActivity.debug("Fields: could not read " + assetName + ": " + e.getMessage());
        } finally {
            try {
                bufferedReader.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void load() {
        load("");
    }

    public void load(String assetName) {
        synchronized (this) {
            building = new ArrayList<>();
            buildingBySid = new HashMap<>();
            try {
                if (assetName == null || assetName.isEmpty()) {
                    fillFromAsset("_Fields.csv");
                    addVirtualFields();
                } else {
                    fillFromAsset(assetName);
                    if (assetName.startsWith("VFC")) {
                        addVirtualFields();
                    }
                }
                // publish the complete set in one step
                fieldsBySid = buildingBySid;
                fields = building;
            } finally {
                building = null;
                buildingBySid = null;
            }
        }
        // registers the application wide fields (e.g. speed for safe driving mode)
        MainActivity main = MainActivity.getInstance();
        if (main != null) main.registerApplicationFields();
    }

    private Field lookupForLoad(String sid) {
        if (buildingBySid != null) return buildingBySid.get(key(sid));
        return getBySID(sid);
    }

    /** Caller holds the lock, or is inside load(). */
    private void addInternal(Field field) {
        String k = key(field.getSID());
        if (building != null) {
            building.add(field);
            buildingBySid.put(k, field);
            return;
        }
        ArrayList<Field> nextList = new ArrayList<>(fields);
        nextList.add(field);
        HashMap<String, Field> nextMap = new HashMap<>(fieldsBySid);
        nextMap.put(k, field);
        fieldsBySid = nextMap;
        fields = nextList;
    }

    /* --------------------------------
     * Access
     \ ------------------------------ */

    public Field getBySID(String sid) {
        if (sid == null) return null;
        HashMap<String, Field> map = fieldsBySid;
        Field field = map.get(sid);
        return field != null ? field : map.get(key(sid));
    }

    public int size() {
        return fields.size();
    }

    public Field get(int index) {
        ArrayList<Field> snapshot = fields;
        if (index < 0 || index >= snapshot.size()) return null;
        return snapshot.get(index);
    }

    public Object[] toArray() {
        return fields.toArray();
    }

    public synchronized void add(Field field) {
        if (field == null) return;
        addInternal(field);
    }

    public void clearAllFields() {
        for (Field field : fields) {
            field.setValue(0);
        }
    }

    /** @return the current set; do not modify it */
    public ArrayList<Field> getAllFields() {
        return fields;
    }
}
