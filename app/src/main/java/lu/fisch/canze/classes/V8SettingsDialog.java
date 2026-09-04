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
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
*/

package lu.fisch.canze.classes;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Code-built settings panel for the procedural V8 sound engine.
 * Provides persistent live sliders with a one-click Reset to Defaults button.
 */
public final class V8SettingsDialog {

    private static final String PREFS_NAME = "lu.fisch.canze.settings";
    public static final String KEY_VOLUME = "v8_master_volume";
    public static final String KEY_IDLE = "v8_idle_rpm";
    public static final String KEY_STALL_FLASH = "v8_stall_flash_rpm";
    public static final String KEY_SLIP_FADE = "v8_slip_fade_kmh";
    public static final String KEY_UPSHIFT_BASE = "v8_upshift_base_rpm";
    public static final String KEY_EDGE_IDLE = "v8_edge_bite_idle";
    public static final String KEY_EDGE_LOAD = "v8_edge_bite_load";
    public static final String KEY_AIR_NOISE = "v8_air_noise";
    public static final String KEY_EXHAUST_DEPTH = "v8_exhaust_depth";
    public static final String KEY_IDLE_ROUGH = "v8_idle_roughness";
    public static final String KEY_SUB_BASS = "v8_sub_bass";
    public static final String KEY_POP_RATE = "v8_pop_rate";
    public static final String KEY_LEVEL_TARGET = "v8_level_target";

    private interface Setter {
        void apply(float value);
    }

    private static final class Param {
        final String key;
        final String label;
        final String unit;
        final float min;
        final float max;
        final float def;
        final int decimals;
        final boolean isInt;
        final Setter setter;
        SeekBar seekBar;
        TextView valueView;

        Param(String key, String label, String unit, float min, float max, float def,
              int decimals, boolean isInt, Setter setter) {
            this.key = key;
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.max = max;
            this.def = def;
            this.decimals = decimals;
            this.isInt = isInt;
            this.setter = setter;
        }

        float load(SharedPreferences prefs) {
            if (isInt) {
                return prefs.getInt(key, Math.round(def));
            }
            return prefs.getFloat(key, def);
        }

        void save(SharedPreferences prefs, float val) {
            SharedPreferences.Editor editor = prefs.edit();
            if (isInt) {
                editor.putInt(key, Math.round(val));
            } else {
                editor.putFloat(key, val);
            }
            editor.apply();
        }

        String format(float val) {
            String fmt = decimals == 0 ? "%.0f" : (decimals == 1 ? "%.1f" : "%.2f");
            return String.format(Locale.getDefault(), fmt, val) + unit;
        }
    }

    private V8SettingsDialog() {}

    private static List<Param> createParams(final V8SoundEngine engine) {
        List<Param> list = new ArrayList<>();

        list.add(new Param(KEY_VOLUME, "Master Volume", " %", 0f, 150f, 100f, 0, true, new Setter() {
            @Override
            public void apply(float v) { engine.setMasterVolume(v / 100f); }
        }));

        list.add(new Param(KEY_IDLE, "Base Idle Speed", " RPM", 600f, 1100f, V8SoundEngine.DEFAULT_IDLE_RPM, 0, false, new Setter() {
            @Override
            public void apply(float v) { engine.setIdleRpm(v); }
        }));

        list.add(new Param(KEY_STALL_FLASH, "Launch Stall Flash", " RPM", 800f, 2800f, V8SoundEngine.DEFAULT_STALL_FLASH_RPM, 0, false, new Setter() {
            @Override
            public void apply(float v) { engine.setStallFlashRpm(v); }
        }));

        list.add(new Param(KEY_SLIP_FADE, "Launch Flare Fade Speed", " km/h", 10f, 40f, V8SoundEngine.DEFAULT_SLIP_FADE_KMH, 0, false, new Setter() {
            @Override
            public void apply(float v) { engine.setSlipFadeKmh(v); }
        }));

        list.add(new Param(KEY_UPSHIFT_BASE, "Base Upshift RPM", " RPM", 1800f, 3200f, V8SoundEngine.DEFAULT_UPSHIFT_BASE_RPM, 0, false, new Setter() {
            @Override
            public void apply(float v) { engine.setUpshiftBaseRpm(v); }
        }));

        list.add(new Param(KEY_EDGE_IDLE, "Combustion Bite (Idle)", "", 0f, 0.30f, V8SoundEngine.DEFAULT_EDGE_BITE_IDLE, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setEdgeBiteIdle(v); }
        }));

        list.add(new Param(KEY_EDGE_LOAD, "Combustion Snap (Load)", "", 0f, 0.30f, V8SoundEngine.DEFAULT_EDGE_BITE_LOAD, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setEdgeBiteLoad(v); }
        }));

        list.add(new Param(KEY_AIR_NOISE, "Intake Air Flutter", "", 0f, 0.20f, V8SoundEngine.DEFAULT_AIR_NOISE, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setAirNoise(v); }
        }));

        list.add(new Param(KEY_EXHAUST_DEPTH, "Exhaust Pipe Depth", "", 0.20f, 0.80f, V8SoundEngine.DEFAULT_EXHAUST_DEPTH, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setExhaustDepth(v); }
        }));

        list.add(new Param(KEY_IDLE_ROUGH, "Idle Cam Lope Roughness", "", 0f, 0.80f, V8SoundEngine.DEFAULT_IDLE_ROUGHNESS, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setIdleRoughness(v); }
        }));

        list.add(new Param(KEY_SUB_BASS, "Sub-Bass Order Rumble", "", 0f, 0.80f, V8SoundEngine.DEFAULT_SUB_BASS, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setSubBassLevel(v); }
        }));

        list.add(new Param(KEY_POP_RATE, "Decel Overrun Pop Rate", " Hz", 0f, 15f, V8SoundEngine.DEFAULT_POP_RATE, 1, false, new Setter() {
            @Override
            public void apply(float v) { engine.setPopRate(v); }
        }));

        list.add(new Param(KEY_LEVEL_TARGET, "Audio Level Target", "", 0.30f, 1.00f, V8SoundEngine.DEFAULT_LEVEL_TARGET, 2, false, new Setter() {
            @Override
            public void apply(float v) { engine.setLevelTarget(v); }
        }));

        return list;
    }

    /**
     * Applies all saved preferences to the sound engine during initialisation.
     */
    public static void applySaved(Context context, V8SoundEngine engine) {
        if (context == null || engine == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<Param> params = createParams(engine);
        for (Param p : params) {
            p.setter.apply(p.load(prefs));
        }
    }

    /**
     * Displays the settings modal dialog with live controls.
     */
    public static void show(final Context context, final V8SoundEngine engine) {
        if (context == null || engine == null) return;

        final SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final List<Param> params = createParams(engine);
        final Dialog dialog = new Dialog(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101721);
        root.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));

        // Title bar
        TextView title = new TextView(context);
        title.setText("⚙ V8 SOUND TUNING");
        title.setTextColor(0xFF00E5FF);
        title.setTextSize(18f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(context, 12));
        root.addView(title);

        // Scrollable list of parameters
        ScrollView scroll = new ScrollView(context);
        LinearLayout listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        for (final Param p : params) {
            listLayout.addView(buildRow(context, prefs, p));
        }
        scroll.addView(listLayout);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(scroll, scrollParams);

        // Bottom action buttons (Reset + Close)
        LinearLayout buttonBar = new LinearLayout(context);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(0, dp(context, 12), 0, 0);

        Button btnReset = new Button(context);
        btnReset.setText("↺ RESET TO DEFAULTS");
        btnReset.setTextColor(0xFFFF5252);
        btnReset.setAllCaps(false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences.Editor editor = prefs.edit();
                for (Param p : params) {
                    editor.remove(p.key);
                }
                editor.apply();

                for (Param p : params) {
                    p.setter.apply(p.def);
                    if (p.seekBar != null) {
                        p.seekBar.setProgress(valueToProgress(p, p.def));
                    }
                    if (p.valueView != null) {
                        p.valueView.setText(p.format(p.def));
                    }
                }
            }
        });

        Button btnClose = new Button(context);
        btnClose.setText("DONE");
        btnClose.setTextColor(0xFF00E5FF);
        btnClose.setAllCaps(false);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        buttonBar.addView(btnReset, btnLp);
        buttonBar.addView(btnClose, btnLp);
        root.addView(buttonBar);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (context.getResources().getDisplayMetrics().heightPixels * 0.85));
        }
        dialog.show();
    }

    private static View buildRow(Context context, final SharedPreferences prefs, final Param p) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(context, 6), 0, dp(context, 6));

        LinearLayout labelRow = new LinearLayout(context);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView lbl = new TextView(context);
        lbl.setText(p.label);
        lbl.setTextColor(0xFFCFD8DC);
        lbl.setTextSize(13f);

        final TextView valTxt = new TextView(context);
        valTxt.setTextColor(0xFFFFD600);
        valTxt.setTextSize(13f);
        valTxt.setGravity(Gravity.END);

        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelRow.addView(lbl, l1);
        labelRow.addView(valTxt, l2);
        row.addView(labelRow);

        SeekBar sb = new SeekBar(context);
        sb.setMax(1000);
        float initial = Math.max(p.min, Math.min(p.max, p.load(prefs)));
        sb.setProgress(valueToProgress(p, initial));
        valTxt.setText(p.format(initial));

        p.seekBar = sb;
        p.valueView = valTxt;

        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float currentVal = progressToValue(p, progress);
                valTxt.setText(p.format(currentVal));
                if (fromUser) {
                    p.save(prefs, currentVal);
                    p.setter.apply(currentVal);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        row.addView(sb);
        return row;
    }

    private static int valueToProgress(Param p, float val) {
        return Math.round(((val - p.min) / (p.max - p.min)) * 1000f);
    }

    private static float progressToValue(Param p, int progress) {
        return p.min + (progress / 1000f) * (p.max - p.min);
    }

    private static int dp(Context ctx, int dps) {
        return (int) (dps * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
