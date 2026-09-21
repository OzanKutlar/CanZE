package lu.fisch.canze.activities;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.ActionBar;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.SocObserver;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.widgets.BatteryLiquidView;

/**
 * Fullscreen state-of-charge screen.
 *
 * Polling follows MotorActivity: the SoC field (42e.0) runs at INTERVAL_ASAPFAST so the
 * ELM stays on one filter, and the charging power (42e.56) rides along on the same frame
 * through a plain listener. The plug state (654.2) lives on another frame and is polled
 * slowly. Poller-thread callbacks go straight into the view's volatile setters; nothing
 * is posted per packet.
 *
 * Without a live connection a demo simulation drives the view. Tapping the battery then
 * cycles charging, plugged-in idle and driving.
 */
public class BatteryFullscreenActivity extends CanzeActivity implements FieldListener, DebugListener {

    private static final String SID_SOC = "42e.0";
    private static final String SID_CHARGING_POWER = "42e.56";
    private static final String SID_PLUG = "654.2";
    private static final int PLUG_INTERVAL_MS = 2000;
    private static final double CHARGING_THRESHOLD_KW = 0.3;

    private static final long DEMO_TICK_MS = 50L;
    private static final long REAL_DATA_GRACE_NANOS = 3000000000L;
    private static final double DEMO_START_SOC = 42.0;
    private static final double DEMO_CHARGE_RATE = 0.04;  // %/s, a brisk charging session
    private static final double DEMO_DRIVE_RATE = -0.02;  // %/s, motorway driving
    private static final int DEMO_DRIVING = 0;
    private static final int DEMO_CHARGING = 1;
    private static final int DEMO_IDLE = 2;
    private static final int DEMO_STATE_COUNT = 3;

    private final Handler demoHandler = new Handler(Looper.getMainLooper());
    private final Runnable demoTick = new Runnable() {
        @Override
        public void run() {
            tickDemo();
            demoHandler.postDelayed(this, DEMO_TICK_MS);
        }
    };

    private BatteryLiquidView batteryView;
    private volatile Field chargingPowerField;
    private volatile long lastRealSampleNanos;
    private boolean demoActive;
    private boolean linkErrorReported;
    private int demoState = DEMO_CHARGING;
    private double demoSoc = DEMO_START_SOC;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery_fullscreen);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideActionBar();
        batteryView = findViewById(R.id.batteryLiquidView);
        batteryView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBatteryTapped();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        demoHandler.removeCallbacks(demoTick);
        demoHandler.post(demoTick);
    }

    @Override
    protected void onPause() {
        demoHandler.removeCallbacks(demoTick);
        detachSiblingListener();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        demoHandler.removeCallbacks(demoTick);
        detachSiblingListener();
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

    // ------------------------------------------------------------------ fields

    @Override
    protected void initListeners() {
        addField(SID_SOC, Device.INTERVAL_ASAPFAST);
        addField(SID_PLUG, PLUG_INTERVAL_MS);
        attachSiblingListener();
    }

    /** 42e.56 shares frame 42E with the SoC, so it arrives with every fast packet. */
    private void attachSiblingListener() {
        if (chargingPowerField != null) return;
        Field field = Fields.getInstance().getBySID(SID_CHARGING_POWER);
        if (field == null) {
            appendDebugMessage("Charging power field " + SID_CHARGING_POWER + " not available");
            return;
        }
        field.addListener(this);
        chargingPowerField = field;
    }

    private void detachSiblingListener() {
        Field field = chargingPowerField;
        if (field == null) return;
        field.removeListener(this);
        chargingPowerField = null;
    }

    /** Runs on the poller thread. Only writes volatile state; never posts per packet. */
    @Override
    public void onFieldUpdateEvent(final Field field) {
        BatteryLiquidView view = batteryView;
        if (field == null || view == null) return;
        String sid = field.getSID();
        double value = field.getValue();
        if (sid == null || Double.isNaN(value)) return;
        if (SID_SOC.equalsIgnoreCase(sid)) {
            lastRealSampleNanos = System.nanoTime();
            view.submitSoc(value);
        } else if (SID_PLUG.equalsIgnoreCase(sid)) {
            view.setPlugConnected(value >= 0.5);
        } else if (SID_CHARGING_POWER.equalsIgnoreCase(sid)) {
            view.setCharging(value > CHARGING_THRESHOLD_KW);
        }
    }

    // ------------------------------------------------------------------ demo mode

    private void tickDemo() {
        if (batteryView == null) return;
        boolean wanted = isDemoWanted();
        if (wanted != demoActive) setDemoActive(wanted);
        if (demoActive) advanceDemo(DEMO_TICK_MS / 1000.0);
    }

    private boolean isDemoWanted() {
        if (isLinkConnected()) return false;
        long last = lastRealSampleNanos;
        return last == 0L || System.nanoTime() - last > REAL_DATA_GRACE_NANOS;
    }

    private boolean isLinkConnected() {
        try {
            return BluetoothManager.getInstance().isConnected();
        } catch (RuntimeException e) {
            if (!linkErrorReported) {
                linkErrorReported = true;
                appendDebugMessage("Battery screen: cannot query link state: " + e.getMessage());
            }
            return false;
        }
    }

    private void setDemoActive(boolean active) {
        demoActive = active;
        batteryView.setDemoMode(active);
        if (active) {
            applyDemoState();
        } else {
            batteryView.setPlugConnected(false);
            batteryView.setCharging(false);
        }
    }

    private void advanceDemo(double dtSeconds) {
        double rate = 0.0;
        if (demoState == DEMO_CHARGING) rate = DEMO_CHARGE_RATE;
        if (demoState == DEMO_DRIVING) rate = DEMO_DRIVE_RATE;
        demoSoc = Math.max(0.0, Math.min(100.0, demoSoc + rate * dtSeconds));
        if (demoState == DEMO_CHARGING && demoSoc >= 100.0) {
            demoState = DEMO_IDLE;
            applyDemoState();
        }
        // Quantise like the real 42e.0 field so the observer is exercised exactly as in the car.
        double quantised = Math.floor(demoSoc / SocObserver.QUANTUM) * SocObserver.QUANTUM;
        batteryView.submitSoc(quantised);
    }

    private void applyDemoState() {
        batteryView.setPlugConnected(demoState != DEMO_DRIVING);
        batteryView.setCharging(demoState == DEMO_CHARGING);
    }

    /** Cycles charging, plugged-in idle, driving. Ignored when live data is shown. */
    private void onBatteryTapped() {
        if (!demoActive) return;
        demoState = (demoState + 1) % DEMO_STATE_COUNT;
        applyDemoState();
    }
}
