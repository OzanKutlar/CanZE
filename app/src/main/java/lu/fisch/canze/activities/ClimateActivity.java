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
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Modern Climate (HVAC / CLIM) Activity.
 */
public class ClimateActivity extends CanzeActivity implements FieldListener, DebugListener {

    // 6121 PIDs
    private static final String SID_EvapTemp           = "764.6121.16";
    private static final String SID_InCarTemp_24       = "764.6121.24";
    private static final String SID_InCarTemp_26       = "764.6121.26";
    private static final String SID_InCarTemp_8        = "764.6121.8";
    private static final String SID_RHumidity_32       = "764.6121.32";
    private static final String SID_RHumidity_36       = "764.6121.36";
    private static final String SID_RHumidity_16       = "764.6121.16";
    private static final String SID_RightSunshine_21   = "764.6121.72";
    private static final String SID_LeftSunshine_21    = "764.6121.80";
    private static final String SID_BlowerSpeed        = "764.6121.88";
    private static final String SID_BlowerReq          = "764.6121.96";
    private static final String SID_Vbatt              = "764.6121.104";

    // 6122 PIDs
    private static final String SID_NightDashLights    = "764.6122.104";
    private static final String SID_RightSunshine_22   = "764.6122.112";
    private static final String SID_LeftSunshine_22    = "764.6122.120";
    private static final String SID_ExternalTemp_22    = "764.6122.128";
    private static final String SID_ExternalTemp_43    = "764.6143.110";
    private static final String SID_ExternalTemp_67    = "764.6167.40";
    private static final String SID_ACCompAuth         = "764.6122.136";
    private static final String SID_ACPressure_22      = "764.6122.144";
    private static final String SID_ACPressure_43      = "764.6143.134";
    private static final String SID_MotorTemp          = "764.6122.152";

    // 6127 PIDs
    private static final String SID_DistFlapReq        = "764.6127.17";
    private static final String SID_RecircFlapReq      = "764.6127.41";
    private static final String SID_DistFlapPos        = "764.6127.65";
    private static final String SID_RecircFlapPos      = "764.6127.89";

    private static final int COLOR_CYAN  = Color.parseColor("#00E5FF");
    private static final int COLOR_GREEN = Color.parseColor("#00E676");
    private static final int COLOR_AMBER = Color.parseColor("#FFD600");
    private static final int COLOR_MUTED = Color.parseColor("#607D8B");

    private double reqDistFlap = Double.NaN;
    private double posDistFlap = Double.NaN;
    private double reqRecircFlap = Double.NaN;
    private double posRecircFlap = Double.NaN;
    private double leftSunshine = Double.NaN;
    private double rightSunshine = Double.NaN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_climate);
        animateEntrance();
    }

    private void animateEntrance() {
        final int[] cardIds = {
                R.id.card_hero_climate,
                R.id.card_blower_system,
                R.id.card_flaps_system,
                R.id.card_compressor_sensors
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

        // Request 2121 fields
        addField(SID_EvapTemp, 2500);
        addField(SID_InCarTemp_24, 2500);
        addField(SID_InCarTemp_26, 2500);
        addField(SID_InCarTemp_8, 2500);
        addField(SID_RHumidity_32, 3000);
        addField(SID_RHumidity_36, 3000);
        addField(SID_RHumidity_16, 3000);
        addField(SID_RightSunshine_21, 3000);
        addField(SID_LeftSunshine_21, 3000);
        addField(SID_BlowerSpeed, 1500);
        addField(SID_BlowerReq, 1500);
        addField(SID_Vbatt, 2000);

        // Request 2122 & fallback fields
        addField(SID_NightDashLights, 3000);
        addField(SID_RightSunshine_22, 3000);
        addField(SID_LeftSunshine_22, 3000);
        addField(SID_ExternalTemp_22, 3000);
        addField(SID_ExternalTemp_43, 3000);
        addField(SID_ExternalTemp_67, 3000);
        addField(SID_ACCompAuth, 2000);
        addField(SID_ACPressure_22, 2000);
        addField(SID_ACPressure_43, 2000);
        addField(SID_MotorTemp, 3000);

        // Request 2127 flap fields
        addField(SID_DistFlapReq, 2000);
        addField(SID_RecircFlapReq, 2000);
        addField(SID_DistFlapPos, 2000);
        addField(SID_RecircFlapPos, 2000);
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

        // In-Car Temp
        if (sid.equals(SID_InCarTemp_24) || sid.equals(SID_InCarTemp_26) || sid.equals(SID_InCarTemp_8)) {
            setText(R.id.textInCarTemp, String.format(Locale.getDefault(), "%.1f", val));
            return;
        }

        // Evaporator Temp
        if (sid.equals(SID_EvapTemp)) {
            setText(R.id.textEvapTempSub, String.format(Locale.getDefault(), "Evaporator: %.1f°C", val));
            return;
        }

        // Relative Humidity
        if (sid.equals(SID_RHumidity_32) || sid.equals(SID_RHumidity_36) || sid.equals(SID_RHumidity_16)) {
            setText(R.id.textHumiditySub, String.format(Locale.getDefault(), "Humidity: %.0f%%", val));
            return;
        }

        // External Temp
        if (sid.equals(SID_ExternalTemp_22) || sid.equals(SID_ExternalTemp_43) || sid.equals(SID_ExternalTemp_67)) {
            setText(R.id.textExternalTemp, String.format(Locale.getDefault(), "%.1f", val));
            return;
        }

        // Fan Speed & Request
        if (sid.equals(SID_BlowerSpeed)) {
            setText(R.id.textFanSpeed, String.format(Locale.getDefault(), "Speed: %.0f rpm", val));
            return;
        }
        if (sid.equals(SID_BlowerReq)) {
            int progress = (int) Math.max(0, Math.min(100, val));
            setText(R.id.textFanReq, String.format(Locale.getDefault(), "%d%%", progress));
            ProgressBar pb = findViewById(R.id.progressFan);
            if (pb != null) pb.setProgress(progress);
            return;
        }

        // 12V Voltage
        if (sid.equals(SID_Vbatt)) {
            setText(R.id.text12V, String.format(Locale.getDefault(), "%.1f V", val));
            return;
        }

        // Night Dash Lights
        if (sid.equals(SID_NightDashLights)) {
            setText(R.id.textNightLights, String.format(Locale.getDefault(), "%.0f%%", val));
            return;
        }

        // Flaps: Distribution
        if (sid.equals(SID_DistFlapReq)) {
            reqDistFlap = val;
            updateDistFlapUI();
            return;
        }
        if (sid.equals(SID_DistFlapPos)) {
            posDistFlap = val;
            updateDistFlapUI();
            return;
        }

        // Flaps: Recirculation
        if (sid.equals(SID_RecircFlapReq)) {
            reqRecircFlap = val;
            updateRecircFlapUI();
            return;
        }
        if (sid.equals(SID_RecircFlapPos)) {
            posRecircFlap = val;
            updateRecircFlapUI();
            return;
        }

        // AC Compressor Auth
        if (sid.equals(SID_ACCompAuth)) {
            boolean authorized = val >= 0.5;
            TextView tv = findViewById(R.id.textAcCompAuth);
            if (tv != null) {
                tv.setText(authorized ? "COMP: AUTHORIZED" : "COMP: OFF");
                tv.setTextColor(authorized ? COLOR_GREEN : COLOR_MUTED);
            }
            return;
        }

        // AC Pressure
        if (sid.equals(SID_ACPressure_22) || sid.equals(SID_ACPressure_43)) {
            setText(R.id.textAcPressure, String.format(Locale.getDefault(), "%.1f bar", val));
            return;
        }

        // Motor Temp
        if (sid.equals(SID_MotorTemp)) {
            setText(R.id.textMotorTemp, String.format(Locale.getDefault(), "%.0f °C", val));
            return;
        }

        // Sunshine Sensors
        if (sid.equals(SID_LeftSunshine_21) || sid.equals(SID_LeftSunshine_22)) {
            leftSunshine = val;
            updateSunshineUI();
            return;
        }
        if (sid.equals(SID_RightSunshine_21) || sid.equals(SID_RightSunshine_22)) {
            rightSunshine = val;
            updateSunshineUI();
        }
    }

    private void updateDistFlapUI() {
        String pStr = Double.isNaN(posDistFlap) ? "--" : String.format(Locale.getDefault(), "%.0f", posDistFlap);
        String rStr = Double.isNaN(reqDistFlap) ? "--" : String.format(Locale.getDefault(), "%.0f", reqDistFlap);
        setText(R.id.textDistPos, String.format("Pos: %s%% (Req: %s%%)", pStr, rStr));
        if (!Double.isNaN(posDistFlap)) {
            ProgressBar pb = findViewById(R.id.progressDistFlap);
            if (pb != null) pb.setProgress((int) Math.max(0, Math.min(100, posDistFlap)));
        }
    }

    private void updateRecircFlapUI() {
        String pStr = Double.isNaN(posRecircFlap) ? "--" : String.format(Locale.getDefault(), "%.0f", posRecircFlap);
        String rStr = Double.isNaN(reqRecircFlap) ? "--" : String.format(Locale.getDefault(), "%.0f", reqRecircFlap);
        setText(R.id.textRecircPos, String.format("Pos: %s%% (Req: %s%%)", pStr, rStr));
        if (!Double.isNaN(posRecircFlap)) {
            ProgressBar pb = findViewById(R.id.progressRecircFlap);
            if (pb != null) pb.setProgress((int) Math.max(0, Math.min(100, posRecircFlap)));
        }
    }

    private void updateSunshineUI() {
        String lStr = Double.isNaN(leftSunshine) ? "--" : String.format(Locale.getDefault(), "%.0f", leftSunshine);
        String rStr = Double.isNaN(rightSunshine) ? "--" : String.format(Locale.getDefault(), "%.0f", rightSunshine);
        setText(R.id.textSunshine, String.format("%s / %s W", lStr, rStr));
    }

    private void setText(int viewId, String text) {
        TextView tv = findViewById(viewId);
        if (tv != null && text != null) tv.setText(text);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_empty, menu);
        return true;
    }
}
