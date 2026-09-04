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
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Powertrain (PEB) readout.
 *
 * All identifiers below are the SIDs generated automatically by the Field
 * constructor when the leading column of _Fields.csv is empty, i.e.
 * hexFrameId + "." + responseId + "." + fromBit, lowercased.
 */
public class PebActivity extends CanzeActivity implements FieldListener, DebugListener {

    private static final String SID_Odometer      = "77e.623008.28";
    private static final String SID_12V           = "77e.623012.24";
    private static final String SID_OnboardVolt   = "77e.62300d.24";
    private static final String SID_HvCurrent     = "77e.62301d.24";
    private static final String SID_InverterTemp  = "77e.62302b.24";
    private static final String SID_RotorTemp     = "77e.623035.24";
    private static final String SID_Ignition      = "77e.62300b.31";
    private static final String SID_IgnitionAlt   = "77e.62300c.31";

    private static final int COLOR_CYAN  = Color.parseColor("#00E5FF");
    private static final int COLOR_GREEN = Color.parseColor("#00E676");
    private static final int COLOR_AMBER = Color.parseColor("#FFD600");
    private static final int COLOR_RED   = Color.parseColor("#FF5252");
    private static final int COLOR_MUTED = Color.parseColor("#607D8B");

    /** How long to wait for PID 0B before registering 0C instead. */
    private static final long IGNITION_FALLBACK_MS = 6000L;
    /** Above this the temperature readouts turn red. */
    private static final double TEMP_WARN_C = 80.0;

    private static final int INTERVAL_ODOMETER = 5000;
    private static final int INTERVAL_FAST     = 1000;
    private static final int INTERVAL_SLOW     = 2000;

    private final Handler fallbackHandler = new Handler(Looper.getMainLooper());

    private boolean ignitionSeen  = false;
    private boolean fallbackArmed = false;

    /**
     * Fires exactly once. If the primary ignition PID has stayed silent we add
     * the alternate one. Guarded on both flags so a late primary answer or a
     * second invocation cannot register the field twice.
     */
    private final Runnable ignitionFallback = new Runnable() {
        @Override
        public void run() {
            if (ignitionSeen || fallbackArmed) return;
            fallbackArmed = true;
            addField(SID_IgnitionAlt, INTERVAL_SLOW);
            MainActivity.debug("PebActivity: ignition 0B silent, falling back to 0C");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_peb);
        animateEntrance();
    }

    private void animateEntrance() {
        final int[] cardIds = {
                R.id.card_peb_odo,
                R.id.card_peb_volts,
                R.id.card_peb_temps,
                R.id.card_peb_current,
                R.id.card_peb_ignition
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

        addField(SID_Odometer, INTERVAL_ODOMETER);
        addField(SID_12V, INTERVAL_FAST);
        addField(SID_OnboardVolt, INTERVAL_FAST);
        addField(SID_HvCurrent, INTERVAL_FAST);
        addField(SID_InverterTemp, INTERVAL_SLOW);
        addField(SID_RotorTemp, INTERVAL_SLOW);
        addField(SID_Ignition, INTERVAL_SLOW);

        fallbackHandler.removeCallbacks(ignitionFallback);
        fallbackHandler.postDelayed(ignitionFallback, IGNITION_FALLBACK_MS);
    }

    @Override
    protected void onDestroy() {
        fallbackHandler.removeCallbacks(ignitionFallback);
        super.onDestroy();
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

    /** Routes one field update to its card. Runs on the UI thread only. */
    private void dispatch(Field field) {
        final String sid = field.getSID();
        if (sid == null) return;

        final double val = field.getValue();

        if (sid.equals(SID_Ignition) || sid.equals(SID_IgnitionAlt)) {
            if (Double.isNaN(val)) return;
            ignitionSeen = true;
            updateIgnition(val >= 0.5, sid.equals(SID_IgnitionAlt) ? "PID 30 0C" : "PID 30 0B");
            return;
        }

        if (Double.isNaN(val)) return;

        if (sid.equals(SID_Odometer)) {
            setValue(R.id.textOdo, String.format(Locale.getDefault(), "%.0f", val), COLOR_AMBER);
            setText(R.id.textOdoUnit, field.getUnit());
            return;
        }
        if (sid.equals(SID_12V)) {
            setValue(R.id.text12V, String.format(Locale.getDefault(), "%.2f", val), COLOR_CYAN);
            return;
        }
        if (sid.equals(SID_OnboardVolt)) {
            setValue(R.id.textOnboardV, String.format(Locale.getDefault(), "%.2f", val), COLOR_CYAN);
            return;
        }
        if (sid.equals(SID_HvCurrent)) {
            updateHvCurrent(val);
            return;
        }
        if (sid.equals(SID_InverterTemp)) {
            updateTemperature(R.id.textInverterTemp, val);
            return;
        }
        if (sid.equals(SID_RotorTemp)) {
            updateTemperature(R.id.textRotorTemp, val);
        }
    }

    /** Negative current means the pack is being fed, so it gets the regen colour. */
    private void updateHvCurrent(double amps) {
        setValue(R.id.textHvCurrent,
                String.format(Locale.getDefault(), "%.2f", amps),
                amps < 0 ? COLOR_GREEN : COLOR_CYAN);
        setText(R.id.textHvCurrentSub, amps < 0 ? "Regenerating" : "Drawing");
    }

    private void updateTemperature(int viewId, double celsius) {
        setValue(viewId,
                String.format(Locale.getDefault(), "%.1f", celsius),
                celsius > TEMP_WARN_C ? COLOR_RED : COLOR_AMBER);
    }

    private void updateIgnition(boolean on, String source) {
        TextView dot = findViewById(R.id.textIgnitionDot);
        if (dot != null) dot.setTextColor(on ? COLOR_GREEN : COLOR_MUTED);

        TextView state = findViewById(R.id.textIgnitionState);
        if (state != null) {
            state.setText(on ? "ON" : "OFF");
            state.setTextColor(on ? COLOR_GREEN : COLOR_MUTED);
        }

        setText(R.id.textIgnitionSource, source);
    }

    private void setValue(int viewId, String text, int color) {
        TextView tv = findViewById(viewId);
        if (tv == null) return;
        tv.setText(text);
        tv.setTextColor(color);
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
