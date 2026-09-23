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
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;

import java.text.DecimalFormatSymbols;
import java.util.concurrent.atomic.AtomicBoolean;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.ChargeSession;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.widgets.HoldProgressFrame;

/**
 * Fullscreen charging dashboard: state of charge, charging power, and a split bottom band
 * with the session time (left, plus time to full) and the energy added (right).
 *
 * Polling never leaves passive frames:
 *  - SoC (42e.0) is INTERVAL_ASAPFAST, so the ELM stays locked on the 42E filter;
 *  - charging power (42e.56) shares frame 42E and arrives with every SoC packet;
 *  - plug state (654.2) is polled slowly and time to full (654.32) rides along on 654.
 *
 * The session lives in ChargeSession and is persisted. It resets automatically when the
 * plug goes from disconnected to connected, and manually by holding the time panel.
 * Rendering follows HudActivity: volatile state, one coalesced render, change-only setText.
 */
public class ChargingHudActivity extends CanzeActivity implements FieldListener, DebugListener {

    // ------------------------------------------------------------------ polling

    private static final String SID_SOC = "42e.0";
    private static final String SID_POWER = "42e.56";
    private static final String SID_PLUG = "654.2";
    private static final String SID_TIME_TO_FULL = "654.32";
    private static final String[] SIBLING_SIDS = {SID_POWER, SID_TIME_TO_FULL};
    private static final int PLUG_INTERVAL_MS = 2000;
    private static final String PREFS_NAME = "lu.fisch.canze.charging_hud";

    // ------------------------------------------------------------------ values

    private static final int NO_VALUE = Integer.MIN_VALUE;
    private static final int PLUG_UNKNOWN = -1;
    private static final int PLUG_OUT = 0;
    private static final int PLUG_IN = 1;
    private static final int MAX_TTF_MINUTES = 1022;
    private static final double MAX_POWER_KW = 999.9;
    private static final long[] POW10 = {1L, 10L, 100L, 1000L};

    private static final long STALE_NANOS = 3000000000L;
    private static final long REAL_DATA_GRACE_NANOS = 3000000000L;
    private static final long TICK_MS = 50L;
    private static final long SAVE_INTERVAL_MS = 10000L;

    // ------------------------------------------------------------------ text fitting

    private static final float REFERENCE_TEXT_PX = 100f;
    private static final float TOP_HEIGHT_FILL = 0.8f;
    private static final float BOTTOM_HEIGHT_FILL = 0.5f;
    private static final float WIDTH_FILL = 0.9f;
    private static final String TIME_SAMPLE = "88:88:88";

    // ------------------------------------------------------------------ colours

    private static final int COLOR_SOC_OK = Color.parseColor("#00E676");
    private static final int COLOR_SOC_MID = Color.parseColor("#FFD600");
    private static final int COLOR_SOC_LOW = Color.parseColor("#FF5252");
    private static final int COLOR_POWER = Color.parseColor("#00E5FF");
    private static final int COLOR_TIME = Color.parseColor("#FFFFFF");
    private static final int COLOR_ENERGY = Color.parseColor("#00E676");
    private static final int COLOR_ENERGY_ESTIMATED = Color.parseColor("#FFD600");
    private static final int COLOR_STALE = Color.parseColor("#4A5B73");
    private static final int SOC_LOW_HUNDREDTHS = 1500;
    private static final int SOC_MID_HUNDREDTHS = 3000;

    // ------------------------------------------------------------------ demo

    private static final double DEMO_START_SOC = 35.0;
    private static final double DEMO_PEAK_KW = 45.0;
    private static final double DEMO_MIN_KW = 3.0;
    private static final double DEMO_RAMP_S = 8.0;
    private static final double DEMO_TAPER_SOC = 80.0;
    private static final double DEMO_CAPACITY_KWH = 52.0;
    private static final double SOC_QUANTUM = 0.02;   // resolution of 42e.0
    private static final double POWER_QUANTUM = 0.3;  // resolution of 42e.56

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean renderPending = new AtomicBoolean(false);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final StringBuilder scratch = new StringBuilder(16);
    private final Field[] siblingFields = new Field[SIBLING_SIDS.length];
    private final ChargeSession demoSession = new ChargeSession(null);

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            renderPending.set(false);
            render(System.nanoTime(), System.currentTimeMillis());
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
    private volatile ChargeSession realSession;
    private volatile int socHundredths = NO_VALUE;
    private volatile int powerTenths = NO_VALUE;
    private volatile int ttfMinutes = NO_VALUE;
    private volatile int plugState = PLUG_UNKNOWN;
    private volatile long socUpdatedNanos;
    private volatile long powerUpdatedNanos;
    private volatile long lastRealSampleNanos;
    private volatile boolean demoActive;

    // Main thread only.
    private TextView socView;
    private TextView powerView;
    private TextView timeView;
    private TextView energyView;
    private TextView ttfView;
    private HoldProgressFrame timePanel;
    private View demoButton;
    private View demoBadge;
    private int shownSoc = NO_VALUE;
    private int shownPower = NO_VALUE;
    private int shownTtf = NO_VALUE;
    private long shownSeconds = -1L;
    private long shownEnergy = -1L;
    private boolean shownEstimated;
    private int shownTimeLength;
    private int shownEnergyLength;
    private char decimalSeparator = '.';
    private String socSample = "100.00";
    private String powerSample = "88.8";
    private String energySample = "~888.888";
    private long lastSaveMs;
    private boolean linkErrorReported;
    private double demoTime;
    private double demoSoc = DEMO_START_SOC;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charging_hud);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideActionBar();
        initSamples();
        ChargeSession session = new ChargeSession(getSharedPreferences(PREFS_NAME, MODE_PRIVATE));
        session.load(System.currentTimeMillis());
        realSession = session;
        bindViews();
        wireControls();
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
        detachSiblingListeners();
        saveRealSession(System.currentTimeMillis());
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        detachSiblingListeners();
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

    private void initSamples() {
        decimalSeparator = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        socSample = "100" + decimalSeparator + "00";
        powerSample = "88" + decimalSeparator + "8";
        energySample = "~888" + decimalSeparator + "888";
    }

    private void bindViews() {
        socView = findViewById(R.id.chudSoc);
        powerView = findViewById(R.id.chudPower);
        timeView = findViewById(R.id.chudTime);
        energyView = findViewById(R.id.chudEnergy);
        ttfView = findViewById(R.id.chudTimeToFull);
        timePanel = findViewById(R.id.chudTimePanel);
        demoButton = findViewById(R.id.chudDemoButton);
        demoBadge = findViewById(R.id.chudDemoBadge);
        applyTabularDigits();
    }

    /** Keeps digits the same width so numbers do not shift sideways as they change. */
    private void applyTabularDigits() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        socView.setFontFeatureSettings("tnum");
        powerView.setFontFeatureSettings("tnum");
        timeView.setFontFeatureSettings("tnum");
        energyView.setFontFeatureSettings("tnum");
    }

    private void wireControls() {
        timePanel.setOnHoldCompleteListener(new HoldProgressFrame.OnHoldCompleteListener() {
            @Override
            public void onHoldComplete() {
                resetActiveSession();
            }
        });
        timePanel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(ChargingHudActivity.this, R.string.charging_hud_hold_hint, Toast.LENGTH_SHORT).show();
            }
        });
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
        findViewById(R.id.chudBands).addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                boolean sameSize = (right - left) == (oldRight - oldLeft)
                        && (bottom - top) == (oldBottom - oldTop);
                if (!sameSize) scheduleFit();
            }
        });
    }

    private void scheduleFit() {
        uiHandler.removeCallbacks(fitTask);
        uiHandler.post(fitTask);
    }

    private void fitTextSizes() {
        if (socView == null) return;
        float top = Math.min(fitSize(socView, socSample, TOP_HEIGHT_FILL),
                fitSize(powerView, powerSample, TOP_HEIGHT_FILL));
        applySize(socView, top);
        applySize(powerView, top);
        float bottom = Math.min(fitSize(timeView, TIME_SAMPLE, BOTTOM_HEIGHT_FILL),
                fitSize(energyView, energySample, BOTTOM_HEIGHT_FILL));
        applySize(timeView, bottom);
        applySize(energyView, bottom);
    }

    /** Fits the wider of the sample and the current text, so e.g. 100+ hours still fit. */
    private float fitSize(TextView view, String sample, float heightFill) {
        int w = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
        int h = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (w <= 0 || h <= 0) return 0f;
        measurePaint.set(view.getPaint());
        measurePaint.setTextSize(REFERENCE_TEXT_PX);
        float textWidth = Math.max(measurePaint.measureText(sample),
                measurePaint.measureText(view.getText().toString()));
        float byHeight = h * heightFill;
        if (textWidth <= 0f) return byHeight;
        return Math.min(byHeight, REFERENCE_TEXT_PX * w * WIDTH_FILL / textWidth);
    }

    private static void applySize(TextView view, float px) {
        if (px <= 0f) return;
        if (Math.abs(view.getTextSize() - px) < 0.5f) return;
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    // ------------------------------------------------------------------ fields

    @Override
    protected void initListeners() {
        addField(SID_SOC, Device.INTERVAL_ASAPFAST);
        addField(SID_PLUG, PLUG_INTERVAL_MS);
        attachSiblingListeners();
    }

    /** 42e.56 and 654.32 share frames with polled fields, so they arrive for free. */
    private void attachSiblingListeners() {
        synchronized (siblingFields) {
            for (int i = 0; i < SIBLING_SIDS.length; i++) {
                if (siblingFields[i] != null) continue;
                Field field = Fields.getInstance().getBySID(SIBLING_SIDS[i]);
                if (field == null) {
                    appendDebugMessage("Charging HUD: field " + SIBLING_SIDS[i] + " not available");
                    continue;
                }
                field.addListener(this);
                siblingFields[i] = field;
            }
        }
    }

    private void detachSiblingListeners() {
        synchronized (siblingFields) {
            for (int i = 0; i < siblingFields.length; i++) {
                Field field = siblingFields[i];
                if (field == null) continue;
                field.removeListener(this);
                siblingFields[i] = null;
            }
        }
    }

    /** Runs on the poller thread. Only writes volatile state, feeds the session, queues one render. */
    @Override
    public void onFieldUpdateEvent(final Field field) {
        ChargeSession session = realSession;
        if (field == null || session == null) return;
        String sid = field.getSID();
        double value = field.getValue();
        if (sid == null || Double.isNaN(value)) return;
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        if (SID_SOC.equalsIgnoreCase(sid)) {
            socHundredths = toHundredths(value);
            socUpdatedNanos = nowNanos;
            session.onSoc(value, nowMs);
        } else if (SID_POWER.equalsIgnoreCase(sid)) {
            powerTenths = toTenths(value);
            powerUpdatedNanos = nowNanos;
            session.onPower(value, nowMs);
        } else if (SID_PLUG.equalsIgnoreCase(sid)) {
            onPlug(value >= 0.5, nowMs, session);
        } else if (SID_TIME_TO_FULL.equalsIgnoreCase(sid)) {
            ttfMinutes = isValidTtf(value) ? (int) Math.round(value) : NO_VALUE;
        } else {
            return;
        }
        lastRealSampleNanos = nowNanos;
        requestRender();
    }

    /** A disconnected-to-connected transition starts a new session. */
    private void onPlug(boolean connected, long nowMs, ChargeSession session) {
        int previous = plugState;
        plugState = connected ? PLUG_IN : PLUG_OUT;
        if (previous == PLUG_OUT && connected) {
            session.reset(nowMs);
            appendDebugMessage("Charging HUD: plug connected, session reset");
        }
    }

    private void requestRender() {
        if (renderPending.compareAndSet(false, true)) uiHandler.post(renderTask);
    }

    private static boolean isValidTtf(double minutes) {
        return minutes >= 1.0 && minutes <= MAX_TTF_MINUTES;
    }

    private static int toHundredths(double soc) {
        double clamped = Math.max(0.0, Math.min(100.0, soc));
        return (int) Math.round(clamped * 100.0);
    }

    private static int toTenths(double kw) {
        double clamped = Math.max(0.0, Math.min(MAX_POWER_KW, kw));
        return (int) Math.round(clamped * 10.0);
    }

    // ------------------------------------------------------------------ session

    private ChargeSession activeSession() {
        return demoActive ? demoSession : realSession;
    }

    private void resetActiveSession() {
        long nowMs = System.currentTimeMillis();
        ChargeSession session = activeSession();
        if (session == null) return;
        session.reset(nowMs);
        if (!demoActive) lastSaveMs = nowMs;
        render(System.nanoTime(), nowMs);
    }

    private void saveRealSession(long nowMs) {
        ChargeSession session = realSession;
        if (session == null) return;
        session.save();
        lastSaveMs = nowMs;
    }

    private void maybeSave(long nowMs) {
        if (nowMs - lastSaveMs >= SAVE_INTERVAL_MS || nowMs < lastSaveMs) saveRealSession(nowMs);
    }

    // ------------------------------------------------------------------ rendering

    private void render(long nowNanos, long nowMs) {
        if (socView == null) return;
        renderSoc(nowNanos);
        renderPower(nowNanos);
        ChargeSession session = activeSession();
        if (session != null) {
            renderTime(session, nowMs);
            renderEnergy(session);
        }
        renderTimeToFull();
    }

    private void renderSoc(long nowNanos) {
        int value = socHundredths;
        if (value != shownSoc) {
            shownSoc = value;
            socView.setText(value == NO_VALUE ? getString(R.string.hud_no_value) : formatScaled(value, 2, false));
        }
        setColorIfChanged(socView, isStale(socUpdatedNanos, nowNanos) ? COLOR_STALE : socColor(value));
    }

    private void renderPower(long nowNanos) {
        int value = powerTenths;
        if (value != shownPower) {
            shownPower = value;
            powerView.setText(value == NO_VALUE ? getString(R.string.hud_no_value) : formatScaled(value, 1, false));
        }
        boolean stale = value == NO_VALUE || isStale(powerUpdatedNanos, nowNanos);
        setColorIfChanged(powerView, stale ? COLOR_STALE : COLOR_POWER);
    }

    private void renderTime(ChargeSession session, long nowMs) {
        long seconds = session.getElapsedMs(nowMs) / 1000L;
        if (seconds == shownSeconds) return;
        shownSeconds = seconds;
        CharSequence text = formatHms(seconds);
        int length = text.length();
        timeView.setText(text);
        setColorIfChanged(timeView, COLOR_TIME);
        if (length != shownTimeLength) {
            shownTimeLength = length;
            scheduleFit();
        }
    }

    private void renderEnergy(ChargeSession session) {
        long thousandths = Math.round(Math.max(0.0, session.getEnergyKwh()) * 1000.0);
        boolean estimated = session.isEstimated();
        if (thousandths == shownEnergy && estimated == shownEstimated) return;
        shownEnergy = thousandths;
        shownEstimated = estimated;
        CharSequence text = formatScaled(thousandths, 3, estimated);
        int length = text.length();
        energyView.setText(text);
        setColorIfChanged(energyView, estimated ? COLOR_ENERGY_ESTIMATED : COLOR_ENERGY);
        if (length != shownEnergyLength) {
            shownEnergyLength = length;
            scheduleFit();
        }
    }

    private void renderTimeToFull() {
        int minutes = ttfMinutes;
        if (minutes == shownTtf) return;
        shownTtf = minutes;
        if (minutes == NO_VALUE) {
            setVisibleIfChanged(ttfView, false);
            return;
        }
        ttfView.setText(getString(R.string.charging_hud_full_in, formatHm(minutes)));
        setVisibleIfChanged(ttfView, true);
    }

    /** Fixed-point value (e.g. hundredths) with an optional "~" prefix, into the reused builder. */
    private CharSequence formatScaled(long value, int decimals, boolean approx) {
        long divisor = POW10[Math.max(0, Math.min(POW10.length - 1, decimals))];
        long safe = Math.max(0L, value);
        scratch.setLength(0);
        if (approx) scratch.append('~');
        scratch.append(safe / divisor);
        if (divisor == 1L) return scratch;
        long fraction = safe % divisor;
        scratch.append(decimalSeparator);
        for (long p = divisor / 10L; p > 1L && fraction < p; p /= 10L) scratch.append('0');
        scratch.append(fraction);
        return scratch;
    }

    private CharSequence formatHms(long totalSeconds) {
        long safe = Math.max(0L, totalSeconds);
        scratch.setLength(0);
        scratch.append(safe / 3600L).append(':');
        appendTwoDigits((safe / 60L) % 60L);
        scratch.append(':');
        appendTwoDigits(safe % 60L);
        return scratch;
    }

    private String formatHm(int minutes) {
        scratch.setLength(0);
        scratch.append(minutes / 60).append(':');
        appendTwoDigits(minutes % 60);
        return scratch.toString();
    }

    private void appendTwoDigits(long value) {
        if (value < 10L) scratch.append('0');
        scratch.append(value);
    }

    private static int socColor(int hundredths) {
        if (hundredths == NO_VALUE) return COLOR_STALE;
        if (hundredths < SOC_LOW_HUNDREDTHS) return COLOR_SOC_LOW;
        if (hundredths < SOC_MID_HUNDREDTHS) return COLOR_SOC_MID;
        return COLOR_SOC_OK;
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

    // ------------------------------------------------------------------ tick and demo

    /** Main-thread tick: drives the demo, the demo controls, periodic saving and the clock. */
    private void onTick() {
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        if (demoActive) {
            if (hasRecentRealData(nowNanos)) {
                stopDemo(false);
            } else {
                advanceDemo(TICK_MS / 1000.0, nowNanos, nowMs);
            }
        }
        updateDemoControls(nowNanos);
        maybeSave(nowMs);
        render(nowNanos, nowMs);
    }

    private void updateDemoControls(long nowNanos) {
        if (demoButton == null || demoBadge == null) return;
        boolean offer = !demoActive && !hasRecentRealData(nowNanos) && !isLinkConnected();
        setVisibleIfChanged(demoButton, offer);
        setVisibleIfChanged(demoBadge, demoActive);
    }

    private boolean hasRecentRealData(long nowNanos) {
        long last = lastRealSampleNanos;
        return last != 0L && nowNanos - last <= REAL_DATA_GRACE_NANOS;
    }

    private boolean isLinkConnected() {
        try {
            return BluetoothManager.getInstance().isConnected();
        } catch (RuntimeException e) {
            if (!linkErrorReported) {
                linkErrorReported = true;
                appendDebugMessage("Charging HUD: cannot query link state: " + e.getMessage());
            }
            return false;
        }
    }

    private void startDemo() {
        if (demoActive || hasRecentRealData(System.nanoTime())) return;
        demoTime = 0.0;
        demoSoc = DEMO_START_SOC;
        demoSession.reset(System.currentTimeMillis());
        demoActive = true;
    }

    /** @param clearValues true when the user stops the demo; false when live data took over. */
    private void stopDemo(boolean clearValues) {
        if (!demoActive) return;
        demoActive = false;
        if (!clearValues) return;
        socHundredths = NO_VALUE;
        powerTenths = NO_VALUE;
        ttfMinutes = NO_VALUE;
        socUpdatedNanos = 0L;
        powerUpdatedNanos = 0L;
    }

    private void advanceDemo(double dtSeconds, long nowNanos, long nowMs) {
        demoTime += dtSeconds;
        double kw = demoPowerAt(demoTime, demoSoc);
        demoSoc = Math.min(100.0, demoSoc + kw * dtSeconds / 3600.0 / DEMO_CAPACITY_KWH * 100.0);
        // Quantise like the real 42e.0 / 42e.56 fields so the display behaves as in the car.
        double soc = Math.floor(demoSoc / SOC_QUANTUM) * SOC_QUANTUM;
        double power = Math.round(kw / POWER_QUANTUM) * POWER_QUANTUM;
        demoSession.onPower(power, nowMs);
        demoSession.onSoc(soc, nowMs);
        socHundredths = toHundredths(soc);
        powerTenths = toTenths(power);
        ttfMinutes = demoTimeToFull(kw);
        socUpdatedNanos = nowNanos;
        powerUpdatedNanos = nowNanos;
    }

    /** DC-style curve: ramp up, hold the peak, taper above DEMO_TAPER_SOC. */
    private static double demoPowerAt(double t, double soc) {
        if (soc >= 100.0) return 0.0;
        double ramp = easeInOut(t / DEMO_RAMP_S);
        double taper = 1.0;
        if (soc > DEMO_TAPER_SOC) {
            taper = Math.max(DEMO_MIN_KW / DEMO_PEAK_KW, (100.0 - soc) / (100.0 - DEMO_TAPER_SOC));
        }
        return DEMO_PEAK_KW * ramp * taper;
    }

    private int demoTimeToFull(double kw) {
        if (kw <= 0.1) return NO_VALUE;
        double remainingKwh = (100.0 - demoSoc) / 100.0 * DEMO_CAPACITY_KWH;
        double minutes = Math.ceil(remainingKwh / kw * 60.0);
        return isValidTtf(minutes) ? (int) minutes : NO_VALUE;
    }

    private static double easeInOut(double x) {
        double c = Math.max(0.0, Math.min(1.0, x));
        return c * c * (3.0 - 2.0 * c);
    }
}
