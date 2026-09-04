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
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Modern Dashboard Activity displaying:
 * - Vehicle Speed with 2 decimal places
 * - Battery Percentage (SoC) with 2 decimal places
 * - Battery Temperature
 * - PRND Gear Position
 * - A/C Active / Inactive Status
 */
public class DashActivity extends CanzeActivity implements FieldListener, DebugListener {

    // 7EC / EVC PIDs
    private static final String SID_Speed_7EC          = "7ec.622003.24";
    private static final String SID_Speed_Free         = "5d7.0";
    private static final String SID_BatTemp_7EC        = "7ec.622001.24";
    private static final String SID_BatTemp_Free       = "42e.44";
    private static final String SID_Soc_7EC            = "7ec.622002.24";
    private static final String SID_Soc_Free           = "654.25";
    private static final String SID_Gear_7EC           = "7ec.622238.29";
    private static final String SID_Gear_Alt           = "7ec.622c04.29";
    private static final String SID_AcAuth_7EC         = "7ec.62332f.31";
    private static final String SID_AcReq_7EC          = "7ec.6233a2.31";
    private static final String SID_AcPower_7EC        = "7ec.6233a7.24";

    // Colors
    private static final int COLOR_CYAN    = Color.parseColor("#00E5FF");
    private static final int COLOR_GREEN   = Color.parseColor("#00E676");
    private static final int COLOR_AMBER   = Color.parseColor("#FFD600");
    private static final int COLOR_RED     = Color.parseColor("#FF5252");
    private static final int COLOR_MUTED   = Color.parseColor("#607D8B");
    private static final int COLOR_GEAR_INACTIVE = Color.parseColor("#4A6572");
    private static final int BG_GEAR_INACTIVE    = Color.parseColor("#141D2C");

    private boolean acActive = false;

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
    public void initListeners() {
        MainActivity.getInstance().setDebugListener(this);

        // Speed: 200 ms
        addField(SID_Speed_7EC, 200);
        addField(SID_Speed_Free, 100);

        // Battery Temp: 2000 ms
        addField(SID_BatTemp_7EC, 2000);
        addField(SID_BatTemp_Free, 2000);

        // Battery SoC: 1000 ms
        addField(SID_Soc_7EC, 1000);
        addField(SID_Soc_Free, 1000);

        // Gear: 300 ms
        addField(SID_Gear_7EC, 300);
        addField(SID_Gear_Alt, 300);

        // A/C Status: 1000 ms
        addField(SID_AcAuth_7EC, 1000);
        addField(SID_AcReq_7EC, 1000);
        addField(SID_AcPower_7EC, 1000);
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

        // Speed (2 decimal places)
        if (sid.equals(SID_Speed_7EC) || sid.equals(SID_Speed_Free)) {
            updateSpeed(val, field.getUnit());
            return;
        }

        // Battery Percentage (SoC - 2 decimal places)
        if (sid.equals(SID_Soc_7EC) || sid.equals(SID_Soc_Free)) {
            updateSoc(val);
            return;
        }

        // Battery Temperature
        if (sid.equals(SID_BatTemp_7EC) || sid.equals(SID_BatTemp_Free)) {
            updateBatteryTemp(val);
            return;
        }

        // Gear Level (PRND)
        if (sid.equals(SID_Gear_7EC) || sid.equals(SID_Gear_Alt)) {
            updateGear((int) Math.round(val));
            return;
        }

        // A/C Status
        if (sid.equals(SID_AcAuth_7EC) || sid.equals(SID_AcReq_7EC) || sid.equals(SID_AcPower_7EC)) {
            updateAc(sid, val);
        }
    }

    private void updateSpeed(double speedVal, String unit) {
        TextView textSpeed = findViewById(R.id.textSpeed);
        if (textSpeed != null) {
            textSpeed.setText(String.format(Locale.getDefault(), "%.2f", speedVal));
        }
        TextView textSpeedUnit = findViewById(R.id.textSpeedUnit);
        if (textSpeedUnit != null && unit != null && !unit.isEmpty()) {
            textSpeedUnit.setText(unit);
        }
    }

    private void updateSoc(double socVal) {
        // Normalise if raw was returned in 0..1 range
        if (socVal <= 1.0 && socVal > 0) socVal *= 100.0;
        TextView textSoc = findViewById(R.id.textSoc);
        if (textSoc != null) {
            textSoc.setText(String.format(Locale.getDefault(), "%.2f", socVal));
        }
        ProgressBar pb = findViewById(R.id.progressSoc);
        if (pb != null) {
            pb.setProgress((int) (Math.max(0, Math.min(100.0, socVal)) * 100));
        }
    }

    private void updateBatteryTemp(double tempVal) {
        TextView textBatTemp = findViewById(R.id.textBatTemp);
        if (textBatTemp != null) {
            textBatTemp.setText(String.format(Locale.getDefault(), "%.1f", tempVal));
            if (tempVal >= 45.0) {
                textBatTemp.setTextColor(COLOR_RED);
            } else if (tempVal >= 35.0) {
                textBatTemp.setTextColor(COLOR_AMBER);
            } else {
                textBatTemp.setTextColor(COLOR_CYAN);
            }
        }
    }

    private void updateGear(int gearCode) {
        // Reset all 4 buttons
        setGearStyle(R.id.gearP, false, 0, 0);
        setGearStyle(R.id.gearR, false, 0, 0);
        setGearStyle(R.id.gearN, false, 0, 0);
        setGearStyle(R.id.gearD, false, 0, 0);

        // EVC standard mapping: 1=P, 2=R, 3=N, 4=D
        switch (gearCode) {
            case 1:
                setGearStyle(R.id.gearP, true, COLOR_AMBER, Color.parseColor("#2E2500"));
                break;
            case 2:
                setGearStyle(R.id.gearR, true, COLOR_RED, Color.parseColor("#330D0D"));
                break;
            case 3:
                setGearStyle(R.id.gearN, true, COLOR_AMBER, Color.parseColor("#2E2500"));
                break;
            case 4:
                setGearStyle(R.id.gearD, true, COLOR_GREEN, Color.parseColor("#072B15"));
                break;
        }
    }

    private void setGearStyle(int viewId, boolean active, int textColor, int bgColor) {
        TextView tv = findViewById(viewId);
        if (tv == null) return;
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(10f * getResources().getDisplayMetrics().density);
        if (active) {
            gd.setColor(bgColor);
            gd.setStroke((int)(1.5f * getResources().getDisplayMetrics().density), textColor);
            tv.setTextColor(textColor);
        } else {
            gd.setColor(BG_GEAR_INACTIVE);
            gd.setStroke(0, Color.TRANSPARENT);
            tv.setTextColor(COLOR_GEAR_INACTIVE);
        }
        tv.setBackground(gd);
    }

    private void updateAc(String sid, double val) {
        if (sid.equals(SID_AcPower_7EC)) {
            acActive = (val > 50.0);
            TextView details = findViewById(R.id.textAcDetails);
            if (details != null) {
                details.setText(acActive ? String.format(Locale.getDefault(), "Compressor: %.0f W", val) : "Compressor: Idle");
            }
        } else if (sid.equals(SID_AcAuth_7EC) || sid.equals(SID_AcReq_7EC)) {
            if (val >= 0.5) acActive = true;
        }

        TextView textAcDot = findViewById(R.id.textAcDot);
        TextView textAcState = findViewById(R.id.textAcState);
        if (textAcDot != null) textAcDot.setTextColor(acActive ? COLOR_CYAN : COLOR_MUTED);
        if (textAcState != null) {
            textAcState.setText(acActive ? "ON" : "OFF");
            textAcState.setTextColor(acActive ? COLOR_CYAN : COLOR_MUTED);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_empty, menu);
        return true;
    }
}
