/*
    CanZE
    Take a peek into your car's inner workings

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

package lu.fisch.canze.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Modern Dashboard Activity.
 * Displays:
 *  - Vehicle speed (whole km/h, passive frame 5D7)
 *  - Battery percentage (SoC, 2 decimal places)
 *  - Battery temperature (°C)
 *  - Gear position (PRND)
 *  - A/C status (On / Off)
 */
public class DashActivity extends CanzeActivity implements FieldListener, DebugListener {

    // Passive SIDs (broadcast frames, cheap to read)
    // User SoC, the same field the Battery screen shows. Passive CAN frame 42E,
    // 13 bits at resolution 0.02 with 2 decimals, so the two-decimal display is
    // native to the field. Deliberately NOT the EVC PID 622002, whose rows in
    // _Fields.csv carry conflicting scaling.
    private static final String SID_UserSoC     = "42e.0";
    // Diagnostic SIDs from 7EC (EVC), each one an ISO-TP round trip
    private static final String SID_BatTemp     = "7ec.622001.24";
    private static final String SID_Gear        = "7ec.622238.29";
    private static final String SID_AcAuth      = "7ec.62332f.31";
    private static final String SID_AcReq       = "7ec.6233a2.31";
    private static final String SID_AcPwr       = "7ec.6233a7.24";

    // Passive CAN speed (frame 5D7, whole km/h). The EVC diagnostic speed PID was
    // dropped: it cost an ISO-TP round trip for decimals that are not needed here.
    private static final String SID_SpeedPassive = "5d7.0";

    // Polling. SoC is the headline value on this screen, so it follows the pattern in
    // MotorActivity: registered as INTERVAL_ASAPFAST, the ELM327 stays on the 42E
    // filter and spends every spare slot on SoC. All other fields are scheduled and
    // always pre-empt the ASAP field when they fall due.
    private static final int INTERVAL_SPEED_MS   = 300;   // passive 5D7, ~3 Hz
    private static final int INTERVAL_GEAR_MS    = 1500;  // ISO-TP
    private static final int INTERVAL_AC_MS      = 3000;  // ISO-TP, three PIDs
    private static final int INTERVAL_BATTEMP_MS = 5000;  // ISO-TP, changes slowly

    private static final int COLOR_CYAN    = Color.parseColor("#00E5FF");
    private static final int COLOR_GREEN   = Color.parseColor("#00E676");
    private static final int COLOR_AMBER   = Color.parseColor("#FFD600");
    private static final int COLOR_RED     = Color.parseColor("#FF5252");
    private static final int COLOR_MUTED   = Color.parseColor("#607D8B");
    private static final int COLOR_GEAR_OFF= Color.parseColor("#4A5B73");

    private boolean acAuthorized = false;
    private boolean acRequested  = false;
    private double  acPower      = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dash);
        animateEntrance();
    }

    private void animateEntrance() {
        final int[] cardIds = {
                R.id.card_dash_speed,
                R.id.card_dash_gear,
                R.id.card_dash_battery,
                R.id.card_dash_ac
        };
        long delay = 60;
        for (int id : cardIds) {
            final View v = findViewById(id);
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(30f);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(350)
                    .setStartDelay(delay)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            delay += 60;
        }
    }

    @Override
    protected void initListeners() {
        MainActivity.getInstance().setDebugListener(this);

        addField(SID_UserSoC, Device.INTERVAL_ASAPFAST);
        addField(SID_SpeedPassive, INTERVAL_SPEED_MS);
        addField(SID_Gear, INTERVAL_GEAR_MS);
        addField(SID_AcAuth, INTERVAL_AC_MS);
        addField(SID_AcReq, INTERVAL_AC_MS);
        addField(SID_AcPwr, INTERVAL_AC_MS);
        addField(SID_BatTemp, INTERVAL_BATTEMP_MS);
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        if (field == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                dispatch(field);
            }
        });
    }

    private void dispatch(Field field) {
        final String sid = field.getSID();
        if (sid == null) return;
        final double val = field.getValue();
        if (Double.isNaN(val)) return;

        // Speed (whole km/h, the native resolution of 5d7.0)
        if (sid.equals(SID_SpeedPassive)) {
            TextView tvSpeed = findViewById(R.id.textSpeed);
            if (tvSpeed != null) {
                tvSpeed.setText(String.format(Locale.getDefault(), "%d", Math.round(val)));
            }
            TextView tvUnit = findViewById(R.id.textSpeedUnit);
            if (tvUnit != null) {
                tvUnit.setText(field.getUnit());
            }
            return;
        }

        // Battery Percentage (user SoC, 2 decimal places)
        if (sid.equals(SID_UserSoC)) {
            TextView tvSoc = findViewById(R.id.textSoc);
            if (tvSoc != null) {
                tvSoc.setText(String.format(Locale.getDefault(), "%.2f", val));
            }
            return;
        }

        // Battery Temperature (°C)
        if (sid.equals(SID_BatTemp)) {
            TextView tvTemp = findViewById(R.id.textBatTemp);
            if (tvTemp != null) {
                tvTemp.setText(String.format(Locale.getDefault(), "%.1f", val));
                if (val > 45) {
                    tvTemp.setTextColor(COLOR_RED);
                } else if (val > 35) {
                    tvTemp.setTextColor(COLOR_AMBER);
                } else {
                    tvTemp.setTextColor(COLOR_CYAN);
                }
            }
            return;
        }

        // Transmission Gear (PRND)
        if (sid.equals(SID_Gear)) {
            updateGearUI((int) Math.round(val));
            return;
        }

        // A/C Status
        if (sid.equals(SID_AcAuth)) {
            acAuthorized = (val >= 0.5);
            updateAcUI();
            return;
        }
        if (sid.equals(SID_AcReq)) {
            acRequested = (val >= 0.5);
            updateAcUI();
            return;
        }
        if (sid.equals(SID_AcPwr)) {
            acPower = val;
            updateAcUI();
        }
    }

    /**
     * 0: Transient / Neutral
     * 1: Park (P)
     * 2: Reverse (R)
     * 3: Neutral (N)
     * 4: Drive (D)
     */
    private void updateGearUI(int gearCode) {
        TextView p = findViewById(R.id.gearP);
        TextView r = findViewById(R.id.gearR);
        TextView n = findViewById(R.id.gearN);
        TextView d = findViewById(R.id.gearD);
        if (p == null || r == null || n == null || d == null) return;

        // Reset all
        p.setTextColor(COLOR_GEAR_OFF);
        p.setBackgroundColor(Color.parseColor("#1A2436"));
        r.setTextColor(COLOR_GEAR_OFF);
        r.setBackgroundColor(Color.parseColor("#1A2436"));
        n.setTextColor(COLOR_GEAR_OFF);
        n.setBackgroundColor(Color.parseColor("#1A2436"));
        d.setTextColor(COLOR_GEAR_OFF);
        d.setBackgroundColor(Color.parseColor("#1A2436"));

        switch (gearCode) {
            case 1: // P
                p.setTextColor(COLOR_AMBER);
                p.setBackgroundColor(Color.parseColor("#2A3326"));
                break;
            case 2: // R
                r.setTextColor(COLOR_RED);
                r.setBackgroundColor(Color.parseColor("#361E26"));
                break;
            case 3: // N
                n.setTextColor(COLOR_AMBER);
                n.setBackgroundColor(Color.parseColor("#2A3326"));
                break;
            case 4: // D
                d.setTextColor(COLOR_GREEN);
                d.setBackgroundColor(Color.parseColor("#16362C"));
                break;
        }
    }

    private void updateAcUI() {
        boolean isOn = acAuthorized || acRequested || (acPower > 25);
        TextView tvDot = findViewById(R.id.textAcDot);
        TextView tvState = findViewById(R.id.textAcState);
        TextView tvSub = findViewById(R.id.textAcSub);

        if (tvDot != null) {
            tvDot.setTextColor(isOn ? COLOR_CYAN : COLOR_MUTED);
        }
        if (tvState != null) {
            tvState.setText(isOn ? "ACTIVE" : "OFF");
            tvState.setTextColor(isOn ? COLOR_CYAN : COLOR_MUTED);
        }
        if (tvSub != null) {
            if (acPower > 0) {
                tvSub.setText(String.format(Locale.getDefault(), "Power: %.0f W", acPower));
            } else {
                tvSub.setText(isOn ? "Compressor running" : "Compressor idle");
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_empty, menu);
        return true;
    }
}
