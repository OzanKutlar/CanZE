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
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

import java.text.DecimalFormatSymbols;
import java.util.concurrent.atomic.AtomicBoolean;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Utils;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

/**
 * Fullscreen "car dashboard": speed, state of charge and gear in three stacked bands.
 *
 * Polling follows MotorActivity / BatteryFullscreenActivity:
 *  - speed (5d7.0) is INTERVAL_ASAPFAST, so the ELM stays locked on the 5D7 filter and
 *    every spare slot is a speed read;
 *  - SoC (42e.0) has a short scheduled interval, so it pre-empts speed as soon as it is due;
 *  - gear only exists as an ISO-TP PID, so it is polled slowly: every poll costs a
 *    diagnostic round trip plus a filter re-arm for the next speed read.
 *
 * Rendering: poller callbacks only write volatile ints and queue at most one UI render.
 * The render compares against what is on screen and only calls setText on a change.
 * Values that have not been refreshed for a few seconds are dimmed.
 *
 * Without a link and without live data, a "Start demo" button runs a scripted drive cycle.
 */
public class HudActivity extends CanzeActivity implements FieldListener, DebugListener {

    // ------------------------------------------------------------------ polling

    private static final String SID_SPEED = "5d7.0";
    private static final String SID_SOC = "42e.0";
    private static final String SID_GEAR = "7ec.622238.29";
    private static final int SOC_INTERVAL_MS = 200;
    private static final int GEAR_INTERVAL_MS = 2000;

    // ------------------------------------------------------------------ values

    private static final int NO_VALUE = Integer.MIN_VALUE;
    private static final int MAX_SPEED = 999;
    private static final int MAX_CACHED_SPEED = 300;
    private static final String DASH = "\u2013";
    private static final int GEAR_PARK = 1;
    private static final int GEAR_REVERSE = 2;
    private static final int GEAR_NEUTRAL = 3;
    private static final int GEAR_DRIVE = 4;
    private static final String[] GEAR_LETTERS = {DASH, "P", "R", "N", "D"};
    private static final String[] SPEED_TEXT = buildSpeedText();

    private static final long STALE_NANOS = 3000000000L;
    private static final long REAL_DATA_GRACE_NANOS = 3000000000L;
    private static final long TICK_MS = 50L;

    // ------------------------------------------------------------------ text fitting

    private static final float REFERENCE_TEXT_PX = 100f;
    private static final float HEIGHT_FILL = 0.8f;
    private static final float WIDTH_FILL = 0.92f;
    private static final String SPEED_SAMPLE = "188";
    private static final String GEAR_SAMPLE = "D";

    // ------------------------------------------------------------------ colours

    private static final int COLOR_SPEED = Color.parseColor("#FFFFFF");
    private static final int COLOR_SOC_OK = Color.parseColor("#00E676");
    private static final int COLOR_SOC_MID = Color.parseColor("#FFD600");
    private static final int COLOR_SOC_LOW = Color.parseColor("#FF5252");
    private static final int COLOR_GEAR_DRIVE = Color.parseColor("#00E676");
    private static final int COLOR_GEAR_REVERSE = Color.parseColor("#FF5252");
    private static final int COLOR_GEAR_HOLD = Color.parseColor("#FFD600");
    private static final int COLOR_STALE = Color.parseColor("#4A5B73");
    private static final int SOC_LOW_HUNDREDTHS = 1500;
    private static final int SOC_MID_HUNDREDTHS = 3000;

    // ------------------------------------------------------------------ demo

    private static final double DEMO_CYCLE_S = 40.0;
    private static final double DEMO_CRUISE_KMH = 50.0;
    private static final double DEMO_REVERSE_KMH = 5.0;
    private static final double DEMO_START_SOC = 78.0;
    private static final double DEMO_DRAIN_PER_KMH_S = 0.0006; // %/s per km/h
    private static final double SOC_QUANTUM = 0.02;            // resolution of 42e.0

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean renderPending = new AtomicBoolean(false);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final StringBuilder socText = new StringBuilder(8);

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            renderPending.set(false);
            render(System.nanoTime());
        }
    };
    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            onTick();
            uiHandler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable fitTask = new Runnable() {
        @Override
        public void run() {
            fitTextSizes();
        }
    };

    // Written by the poller thread (live data) or the main thread (demo).
    private volatile int speedValue = NO_VALUE;
    private volatile int socHundredths = NO_VALUE;
    private volatile int gearCode = NO_VALUE;
    private volatile long speedUpdatedNanos;
    private volatile long socUpdatedNanos;
    private volatile long gearUpdatedNanos;
    private volatile long lastRealSampleNanos;

    // Main thread only.
    private TextView speedView;
    private TextView socView;
    private TextView gearView;
    private View demoButton;
    private View demoBadge;
    private int shownSpeed = NO_VALUE;
    private int shownSoc = NO_VALUE;
    private int shownGear = NO_VALUE;
    private char decimalSeparator = '.';
    private boolean demoActive;
    private boolean linkErrorReported;
    private double demoTime;
    private double demoSoc = DEMO_START_SOC;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hud);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideActionBar();
        decimalSeparator = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        bindViews();
        wireDemoControls();
        watchLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        uiHandler.removeCallbacks(tickTask);
        uiHandler.post(tickTask);
    }

    @Override
    protected void onPause() {
        uiHandler.removeCallbacks(tickTask);
        uiHandler.removeCallbacks(fitTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    private void hideActionBar() {
        ActionBar bar = getSupportActionBar();
        if (bar != null) bar.hide();
    }

    private void enterImmersive() {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------ views

    private void bindViews() {
        speedView = findViewById(R.id.hudSpeed);
        socView = findViewById(R.id.hudSoc);
        gearView = findViewById(R.id.hudGear);
        demoButton = findViewById(R.id.hudDemoButton);
        demoBadge = findViewById(R.id.hudDemoBadge);
        TextView speedUnit = findViewById(R.id.hudSpeedUnit);
        speedUnit.setText(MainActivity.milesMode ? R.string.hud_unit_mph : R.string.hud_unit_kmh);
        applyTabularDigits();
    }

    /** Keeps digits the same width so numbers do not shift sideways as they change. */
    private void applyTabularDigits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        speedView.setFontFeatureSettings("tnum");
        socView.setFontFeatureSettings("tnum");
    }

    private void wireDemoControls() {
        demoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDemo();
            }
        });
        demoBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopDemo(true);
            }
        });
    }

    /** Text sizes are fitted once per size change, never per update. */
    private void watchLayout() {
        findViewById(R.id.hudBands).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                boolean sameSize = (right - left) == (oldRight - oldLeft)
                        && (bottom - top) == (oldBottom - oldTop);
                if (sameSize) return;
                uiHandler.removeCallbacks(fitTask);
                uiHandler.post(fitTask);
            }
        });
    }

    private void fitTextSizes() {
        if (speedView == null) return;
        String socSample = "100" + decimalSeparator + "00";
        float shared = Math.min(fitSize(speedView, SPEED_SAMPLE), fitSize(socView, socSample));
        applySize(speedView, shared);
        applySize(socView, shared);
        applySize(gearView, fitSize(gearView, GEAR_SAMPLE));
    }

    private float fitSize(TextView view, String sample) {
        int w = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int h = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (w <= 0 || h <= 0) return 0f;
        measurePaint.set(view.getPaint());
        measurePaint.setTextSize(REFERENCE_TEXT_PX);
        float sampleWidth = measurePaint.measureText(sample);
        float byHeight = h * HEIGHT_FILL;
        if (sampleWidth <= 0f) return byHeight;
        return Math.min(byHeight, REFERENCE_TEXT_PX * w * WIDTH_FILL / sampleWidth);
    }

    private static void applySize(TextView view, float px) {
        if (px <= 0f) return;
        if (Math.abs(view.getTextSize() - px) < 0.5f) return;
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    // ------------------------------------------------------------------ fields

    @Override
    protected void initListeners() {
        addField(SID_SPEED, Device.INTERVAL_ASAPFAST);
        addField(SID_SOC, SOC_INTERVAL_MS);
        addField(SID_GEAR, GEAR_INTERVAL_MS);
    }

    /** Runs on the poller thread. Only writes volatile state and queues one render. */
    @Override
    public void onFieldUpdateEvent(final Field field) {
        if (field == null) return;
        String sid = field.getSID();
        double value = field.getValue();
        if (sid == null || Double.isNaN(value)) return;
        long now = System.nanoTime();
        if (SID_SPEED.equalsIgnoreCase(sid)) {
            speedValue = toDisplaySpeed(value);
            speedUpdatedNanos = now;
        } else if (SID_SOC.equalsIgnoreCase(sid)) {
            socHundredths = toHundredths(value);
            socUpdatedNanos = now;
        } else if (SID_GEAR.equalsIgnoreCase(sid)) {
            acceptGear((int) Math.round(value));
            gearUpdatedNanos = now;
        } else {
            return;
        }
        lastRealSampleNanos = now;
        requestRender();
    }

    /** 0 (transient) and unknown codes keep the last stable gear, avoiding flicker in shifts. */
    private void acceptGear(int code) {
        if (code >= GEAR_PARK && code <= GEAR_DRIVE) gearCode = code;
    }

    private void requestRender() {
        if (renderPending.compareAndSet(false, true)) uiHandler.post(renderTask);
    }

    private static int toDisplaySpeed(double kmh) {
        double shown = Math.abs(Utils.kmOrMiles(kmh));
        return (int) Math.min(MAX_SPEED, Math.round(shown));
    }

    private static int toHundredths(double soc) {
        double clamped = Math.max(0.0, Math.min(100.0, soc));
        return (int) Math.round(clamped * 100.0);
    }

    // ------------------------------------------------------------------ rendering

    private void render(long now) {
        if (speedView == null) return;
        renderSpeed(now);
        renderSoc(now);
        renderGear(now);
    }

    private void renderSpeed(long now) {
        int value = speedValue;
        if (value != shownSpeed) {
            shownSpeed = value;
            speedView.setText(speedText(value));
        }
        setColorIfChanged(speedView, isStale(speedUpdatedNanos, now) ? COLOR_STALE : COLOR_SPEED);
    }

    private void renderSoc(long now) {
        int value = socHundredths;
        if (value != shownSoc) {
            shownSoc = value;
            socView.setText(socText(value));
        }
        setColorIfChanged(socView, isStale(socUpdatedNanos, now) ? COLOR_STALE : socColor(value));
    }

    private void renderGear(long now) {
        int code = gearCode;
        if (code != shownGear) {
            shownGear = code;
            gearView.setText(gearText(code));
        }
        setColorIfChanged(gearView, isStale(gearUpdatedNanos, now) ? COLOR_STALE : gearColor(code));
    }

    private static String speedText(int value) {
        if (value == NO_VALUE) return DASH;
        if (value >= 0 && value <= MAX_CACHED_SPEED) return SPEED_TEXT[value];
        return Integer.toString(value);
    }

    private CharSequence socText(int hundredths) {
        if (hundredths == NO_VALUE) return DASH;
        int fraction = hundredths % 100;
        socText.setLength(0);
        socText.append(hundredths / 100).append(decimalSeparator);
        if (fraction < 10) socText.append('0');
        socText.append(fraction);
        return socText;
    }

    private static String gearText(int code) {
        if (code < 0 || code >= GEAR_LETTERS.length) return DASH;
        return GEAR_LETTERS[code];
    }

    private static int socColor(int hundredths) {
        if (hundredths == NO_VALUE) return COLOR_STALE;
        if (hundredths < SOC_LOW_HUNDREDTHS) return COLOR_SOC_LOW;
        if (hundredths < SOC_MID_HUNDREDTHS) return COLOR_SOC_MID;
        return COLOR_SOC_OK;
    }

    private static int gearColor(int code) {
        if (code == GEAR_DRIVE) return COLOR_GEAR_DRIVE;
        if (code == GEAR_REVERSE) return COLOR_GEAR_REVERSE;
        if (code == GEAR_PARK || code == GEAR_NEUTRAL) return COLOR_GEAR_HOLD;
        return COLOR_STALE;
    }

    private static boolean isStale(long updatedNanos, long now) {
        return updatedNanos == 0L || now - updatedNanos > STALE_NANOS;
    }

    private static void setColorIfChanged(TextView view, int color) {
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
    }

    private static void setVisibleIfChanged(View view, boolean visible) {
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != wanted) view.setVisibility(wanted);
    }

    private static String[] buildSpeedText() {
        String[] texts = new String[MAX_CACHED_SPEED + 1];
        for (int i = 0; i <= MAX_CACHED_SPEED; i++) texts[i] = Integer.toString(i);
        return texts;
    }

    // ------------------------------------------------------------------ demo mode

    /** Main-thread tick: drives the demo, the demo controls and stale dimming. */
    private void onTick() {
        long now = System.nanoTime();
        if (demoActive) {
            if (hasRecentRealData(now)) {
                stopDemo(false);
            } else {
                advanceDemo(TICK_MS / 1000.0, now);
            }
        }
        updateDemoControls(now);
        render(now);
    }

    private void updateDemoControls(long now) {
        if (demoButton == null || demoBadge == null) return;
        boolean offer = !demoActive && !hasRecentRealData(now) && !isLinkConnected();
        setVisibleIfChanged(demoButton, offer);
        setVisibleIfChanged(demoBadge, demoActive);
    }

    private boolean hasRecentRealData(long now) {
        long last = lastRealSampleNanos;
        return last != 0L && now - last <= REAL_DATA_GRACE_NANOS;
    }

    private boolean isLinkConnected() {
        try {
            return BluetoothManager.getInstance().isConnected();
        } catch (RuntimeException e) {
            if (!linkErrorReported) {
                linkErrorReported = true;
                appendDebugMessage("HUD: cannot query link state: " + e.getMessage());
            }
            return false;
        }
    }

    private void startDemo() {
        if (demoActive || hasRecentRealData(System.nanoTime())) return;
        demoActive = true;
        demoTime = 0.0;
        demoSoc = DEMO_START_SOC;
    }

    /** @param clearValues true when the user stops the demo; false when live data took over. */
    private void stopDemo(boolean clearValues) {
        if (!demoActive) return;
        demoActive = false;
        if (!clearValues) return;
        speedValue = NO_VALUE;
        socHundredths = NO_VALUE;
        gearCode = NO_VALUE;
        speedUpdatedNanos = 0L;
        socUpdatedNanos = 0L;
        gearUpdatedNanos = 0L;
    }

    private void advanceDemo(double dtSeconds, long now) {
        demoTime = (demoTime + dtSeconds) % DEMO_CYCLE_S;
        double speed = demoSpeedAt(demoTime);
        demoSoc = Math.max(0.0, demoSoc - DEMO_DRAIN_PER_KMH_S * speed * dtSeconds);
        // Quantise like the real 42e.0 field so the display behaves exactly as in the car.
        double quantised = Math.floor(demoSoc / SOC_QUANTUM) * SOC_QUANTUM;
        speedValue = toDisplaySpeed(speed);
        socHundredths = toHundredths(quantised);
        gearCode = demoGearAt(demoTime);
        speedUpdatedNanos = now;
        socUpdatedNanos = now;
        gearUpdatedNanos = now;
    }

    /** Scripted 40 s cycle: park, pull away, cruise, brake, reverse, park. */
    private static double demoSpeedAt(double t) {
        if (t < 4.0) return 0.0;
        if (t < 14.0) return DEMO_CRUISE_KMH * easeInOut((t - 4.0) / 10.0);
        if (t < 24.0) return DEMO_CRUISE_KMH + 1.5 * Math.sin((t - 14.0) * 0.9);
        if (t < 30.0) return DEMO_CRUISE_KMH * (1.0 - easeInOut((t - 24.0) / 6.0));
        if (t < 33.0) return 0.0;
        if (t < 37.0) return DEMO_REVERSE_KMH * Math.sin(Math.PI * (t - 33.0) / 4.0);
        return 0.0;
    }

    private static int demoGearAt(double t) {
        if (t < 3.0) return GEAR_PARK;
        if (t < 31.0) return GEAR_DRIVE;
        if (t < 32.0) return GEAR_NEUTRAL;
        if (t < 37.5) return GEAR_REVERSE;
        return GEAR_PARK;
    }

    private static double easeInOut(double x) {
        double c = Math.max(0.0, Math.min(1.0, x));
        return c * c * (3.0 - 2.0 * c);
    }
}
