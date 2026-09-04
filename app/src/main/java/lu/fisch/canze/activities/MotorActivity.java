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

package lu.fisch.canze.activities;

import android.animation.ObjectAnimator;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Utils;
import lu.fisch.canze.bluetooth.BluetoothManager;
import lu.fisch.canze.classes.V8SoundEngine;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.FieldListener;
import lu.fisch.canze.widgets.RpmGaugeView;

/**
 * Modern real-time motor cockpit display showing Pedal Position, Motor Torque, and Vehicle Speed
 * polled at the fastest rate supported by the dongle.
 */
public class MotorActivity extends CanzeActivity implements FieldListener {

    public static final String SID_Pedal = "186.40";
    public static final String SID_MeanEffectiveTorque = "186.16";
    public static final String SID_RealSpeed = "5d7.0";

    private TextView tvSpeedVal;
    private TextView tvSpeedUnit;
    private ProgressBar pbSpeed;

    private TextView tvPedalVal;
    private ProgressBar pbPedal;

    private TextView tvTorqueVal;
    private ProgressBar pbTorque;

    private RpmGaugeView gaugeRpm;
    private Button btnSoundToggle;
    private Button btnIgnition;
    private Button btnSettings;
    private Button btnTestDrive;
    private V8SoundEngine soundEngine;

    // ---- Test drive vehicle model ----
    // Tractive effort from a field-weakened EV motor curve against rolling and aerodynamic road
    // load, so terminal speed emerges from the throttle position rather than being scripted.
    private static final float SIM_DT = 0.05f;              // handler period, seconds
    private static final float SIM_MASS_KG = 1500f;
    private static final float SIM_WHEEL_RADIUS_M = 0.31f;  // matches the 1.95 m circumference
    private static final float SIM_DRIVE_RATIO = 9.3f;      // single speed reduction
    private static final float SIM_MAX_TORQUE_NM = 220f;
    private static final float SIM_BASE_SPEED_KMH = 45f;    // constant power above this
    private static final float SIM_ROLLING_N = 162f;        // m*g*Crr
    private static final float SIM_DRAG_N_PER_MS2 = 0.38f;  // 0.5*rho*Cd*A
    private static final float SIM_REGEN_TORQUE_NM = 55f;
    private static final float SIM_MAX_SPEED_KMH = 180f;
    private static final float SIM_DEFAULT_THROTTLE = 20f;
    private static final float SIM_COAST_STOP_KMH = 0.5f;

    // Test simulation state
    private boolean isTestRunning = false;
    private boolean isTestStopping = false;
    private final android.os.Handler testHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable testRunnable;
    private float simDriveSpeed = 0f;
    private float simDrivePedal = 0f;
    private float simDriveTorque = 0f;

    private volatile float testThrottleTarget = SIM_DEFAULT_THROTTLE;

    private View cardSimulator;
    private SeekBar sbSimSpeed;
    private SeekBar sbSimPedal;
    private SeekBar sbSimTorque;
    private TextView tvSimSpeedLabel;
    private TextView tvSimPedalLabel;
    private TextView tvSimTorqueLabel;

    private float lastSpeed = 0f;
    private float lastPedal = 0f;
    private float lastTorque = 0f;

    // Live telemetry fed straight to the synthesizer from the poller thread (km/h, %, Nm).
    // Kept separate from the lastX display values, which are only ever touched on the UI thread.
    private volatile float liveSpeed = 0f;
    private volatile float livePedal = 0f;
    private volatile float liveTorque = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_motor);

        tvSpeedVal = findViewById(R.id.tv_speed_val);
        tvSpeedUnit = findViewById(R.id.tv_speed_unit);
        pbSpeed = findViewById(R.id.pb_speed);

        tvPedalVal = findViewById(R.id.tv_pedal_val);
        pbPedal = findViewById(R.id.pb_pedal);

        tvTorqueVal = findViewById(R.id.tv_torque_val);
        pbTorque = findViewById(R.id.pb_torque);

        tvSpeedUnit.setText(MainActivity.milesMode ? "mph" : "km/h");

        gaugeRpm = findViewById(R.id.gauge_rpm);
        btnSoundToggle = findViewById(R.id.btn_sound_toggle);
        btnIgnition = findViewById(R.id.btn_ignition);
        btnSettings = findViewById(R.id.btn_settings);
        btnTestDrive = findViewById(R.id.btn_test_drive);

        initTestDriveButton();

        soundEngine = new V8SoundEngine(this);
        soundEngine.setEngineListener(new V8SoundEngine.EngineListener() {
            @Override
            public void onEngineStateChanged(final float rpm, final int gear) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gaugeRpm != null) {
                            gaugeRpm.setRpmAndGear(rpm, gear);
                        }
                    }
                });
            }
        });

        // Apply saved volume and sound tunings
        lu.fisch.canze.classes.V8SettingsDialog.applySaved(this, soundEngine);

        initControlButtons();
        initSimulatorPanel();
        animateEntrance();
    }

    private int dp(int dps) {
        return (int) (dps * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateButtonStyle(Button btn, String text, int accentColor, int bgColor) {
        if (btn == null) return;
        btn.setText(text);
        btn.setTextColor(accentColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setAllCaps(false);
        btn.setGravity(Gravity.CENTER);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        int padH = dp(6);
        int padV = dp(8);
        btn.setPadding(padH, padV, padH, padV);

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(8));
        gd.setColor(bgColor);
        gd.setStroke(dp(1), accentColor);
        btn.setBackground(gd);
    }

    private void updateIgnitionButton(boolean on) {
        if (btnIgnition == null) return;
        if (on) {
            updateButtonStyle(btnIgnition, "🔑 IGNITION: ON", 0xFF00E5FF, 0xFF0D2838);
        } else {
            updateButtonStyle(btnIgnition, "🔑 IGNITION: OFF", 0xFF8A99AD, 0xFF161F2E);
        }
    }

    private void updateSoundButton(boolean muted) {
        if (btnSoundToggle == null) return;
        if (muted) {
            updateButtonStyle(btnSoundToggle, "🔇 SOUND: OFF", 0xFF8A99AD, 0xFF161F2E);
        } else {
            updateButtonStyle(btnSoundToggle, "🔊 SOUND: ON", 0xFF00E5FF, 0xFF0D2838);
        }
    }

    private void updateTestDriveButton(int state) {
        if (btnTestDrive == null) return;
        switch (state) {
            case 1: // Running
                updateButtonStyle(btnTestDrive, "⏹ STOP TEST", 0xFFFF5252, 0xFF2A151B);
                break;
            case 2: // Coasting
                updateButtonStyle(btnTestDrive, "⏳ COASTING...", 0xFF00E5FF, 0xFF0D2838);
                break;
            case 0: // Idle
            default:
                updateButtonStyle(btnTestDrive, "▶ TEST DRIVE", 0xFFFFD600, 0xFF161F2E);
                break;
        }
    }

    private void refreshCardVisibilities() {
        boolean disconnected = !BluetoothManager.getInstance().isConnected();
        if (cardSimulator != null) {
            cardSimulator.setVisibility((disconnected || MainActivity.debugLogMode) ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Wires listeners and styles for the 2x2 control buttons inside card_tachometer.
     */
    private void initControlButtons() {
        refreshCardVisibilities();

        if (btnIgnition != null) {
            btnIgnition.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (soundEngine == null) return;
                    int state = soundEngine.getEngineState();
                    boolean turnOn = (state == V8SoundEngine.ENGINE_OFF || state == V8SoundEngine.ENGINE_STOPPING);
                    soundEngine.setIgnition(turnOn);
                    updateIgnitionButton(turnOn);
                }
            });
        }

        if (btnSoundToggle != null) {
            btnSoundToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean nowMuted = !soundEngine.isMuted();
                    soundEngine.setMuted(nowMuted);
                    updateSoundButton(nowMuted);
                }
            });
        }

        if (btnSettings != null) {
            btnSettings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    lu.fisch.canze.classes.V8SettingsDialog.show(MotorActivity.this, soundEngine);
                }
            });
        }

        updateIgnitionButton(soundEngine != null && soundEngine.getEngineState() == V8SoundEngine.ENGINE_RUNNING);
        updateSoundButton(soundEngine != null && soundEngine.isMuted());
        updateButtonStyle(btnSettings, "⚙ SETTINGS", 0xFF8A99AD, 0xFF161F2E);
        updateTestDriveButton(0);
    }



    /**
     * Advances the test vehicle by one SIM_DT step. Updates simDrivePedal, simDriveTorque and
     * simDriveSpeed in place.
     */
    private void stepTestPhysics() {
        // Driver foot travel, not an instant step
        simDrivePedal += (testThrottleTarget - simDrivePedal) * 0.25f;
        float pedal = Math.max(0f, Math.min(1f, simDrivePedal / 100f));

        float speedMs = simDriveSpeed / 3.6f;

        // Constant torque up to base speed, constant power above it (field weakening)
        float availableTorque = SIM_MAX_TORQUE_NM;
        if (simDriveSpeed > SIM_BASE_SPEED_KMH) {
            availableTorque = SIM_MAX_TORQUE_NM * SIM_BASE_SPEED_KMH / simDriveSpeed;
        }

        float demandedTorque;
        if (pedal < 0.03f) {
            // Lift off: regen braking, fading out as the car comes to rest
            float fade = Math.min(1f, simDriveSpeed / 12f);
            demandedTorque = -SIM_REGEN_TORQUE_NM * fade;
        } else {
            demandedTorque = pedal * availableTorque;
        }
        simDriveTorque += (demandedTorque - simDriveTorque) * 0.25f;

        float tractiveN = simDriveTorque * SIM_DRIVE_RATIO / SIM_WHEEL_RADIUS_M;
        float resistiveN = SIM_ROLLING_N + SIM_DRAG_N_PER_MS2 * speedMs * speedMs;
        float accelMs2 = (tractiveN - resistiveN) / SIM_MASS_KG;

        speedMs += accelMs2 * SIM_DT;
        if (speedMs < 0f) speedMs = 0f;

        simDriveSpeed = speedMs * 3.6f;
        if (simDriveSpeed > SIM_MAX_SPEED_KMH) simDriveSpeed = SIM_MAX_SPEED_KMH;
    }

    private void initSimulatorPanel() {
        cardSimulator = findViewById(R.id.card_simulator);
        sbSimSpeed = findViewById(R.id.sb_sim_speed);
        sbSimPedal = findViewById(R.id.sb_sim_pedal);
        sbSimTorque = findViewById(R.id.sb_sim_torque);
        tvSimSpeedLabel = findViewById(R.id.tv_sim_speed_label);
        tvSimPedalLabel = findViewById(R.id.tv_sim_pedal_label);
        tvSimTorqueLabel = findViewById(R.id.tv_sim_torque_label);

        // Show simulation sliders when disconnected or in debug mode
        boolean disconnected = !BluetoothManager.getInstance().isConnected();
        if (disconnected || MainActivity.debugLogMode) {
            cardSimulator.setVisibility(View.VISIBLE);
        }

        SeekBar.OnSeekBarChangeListener simListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                float simSpeed = sbSimSpeed.getProgress();
                float simPedal = sbSimPedal.getProgress();
                // Offset by 150: 0 -> -150 Nm, 150 -> 0 Nm, 400 -> +250 Nm
                float simTorque = sbSimTorque.getProgress() - 150f;

                tvSimSpeedLabel.setText(String.format(Locale.getDefault(), "Simulated Speed: %.0f km/h", simSpeed));
                tvSimPedalLabel.setText(String.format(Locale.getDefault(), "Simulated Pedal: %.0f %%", simPedal));

                String torqueStatus = simTorque < 0f ? "(Regen Braking)" : (simTorque > 120f ? "(Full Pull)" : "(Drive)");
                tvSimTorqueLabel.setText(String.format(Locale.getDefault(), "Simulated Torque: %.0f Nm %s", simTorque, torqueStatus));

                updateSpeed(simSpeed);
                updatePedal(simPedal);
                updateTorque(simTorque);
                if (soundEngine != null) {
                    soundEngine.setInputs(simSpeed, simPedal, simTorque);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        sbSimSpeed.setOnSeekBarChangeListener(simListener);
        sbSimPedal.setOnSeekBarChangeListener(simListener);
        sbSimTorque.setOnSeekBarChangeListener(simListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCardVisibilities();
        // Starting unconditionally is free now: the engine parks its render thread while the
        // ignition is off, so no DSP runs until the user actually starts it. Keeping the thread
        // alive here means the first ignition press responds immediately.
        if (soundEngine != null) {
            soundEngine.start();
        }
    }

    private void initTestDriveButton() {
        if (btnTestDrive == null) return;

        testRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTestRunning && !isTestStopping) return;

                if (isTestStopping) {
                    // Coast-down now runs through the same road-load model as acceleration, so
                    // the reported speed and torque can no longer contradict each other. The old
                    // branch dropped a flat 0.42 km/h per step (a constant 8.4 km/h/s at any
                    // speed, which no car produces) while pinning torque at -20 Nm, a figure that
                    // only accounts for about 2.6 km/h/s. SpeedObserver was being asked to fuse
                    // two channels disagreeing by a factor of three and settled several km/h high
                    // for the whole manoeuvre.
                    testThrottleTarget = 0f;
                    stepTestPhysics();

                    if (simDriveSpeed < SIM_COAST_STOP_KMH) {
                        simDriveSpeed = 0f;
                        simDrivePedal = 0f;
                        simDriveTorque = 0f;
                        isTestStopping = false;
                        updateTestDriveButton(0);
                    }
                } else {
                    // Live throttle: read the slider every step so the pedal can be moved mid-test
                    stepTestPhysics();
                }

                updateSpeed(simDriveSpeed);
                updatePedal(simDrivePedal);
                updateTorque(simDriveTorque);

                if (soundEngine != null) {
                    soundEngine.setInputs(simDriveSpeed, simDrivePedal, simDriveTorque);
                }

                if (isTestRunning || isTestStopping) {
                    testHandler.postDelayed(this, 50);
                }
            }
        };

        btnTestDrive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isTestRunning && !isTestStopping) {
                    // Start Test Drive
                    isTestRunning = true;
                    isTestStopping = false;
                    simDriveSpeed = 0f;
                    simDrivePedal = 0f;
                    simDriveTorque = 0f;
                    testThrottleTarget = SIM_DEFAULT_THROTTLE;
                    updateTestDriveButton(1);
                    testHandler.post(testRunnable);
                } else if (isTestRunning) {
                    // Begin smooth coast-down deceleration
                    startCoastDown();
                }
            }
        });
    }

    private void startCoastDown() {
        isTestRunning = false;
        isTestStopping = true;
        updateTestDriveButton(2);
    }

    private void forceStopTest() {
        isTestRunning = false;
        isTestStopping = false;
        testHandler.removeCallbacks(testRunnable);
        updateTestDriveButton(0);
        updatePedal(0f);
        updateSpeed(0f);
        updateTorque(0f);
        if (soundEngine != null) {
            soundEngine.setInputs(0f, 0f, 0f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isTestRunning || isTestStopping) {
            forceStopTest();
        }
        if (soundEngine != null) {
            soundEngine.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isTestRunning || isTestStopping) {
            forceStopTest();
        }
        Field pedalField = MainActivity.fields.getBySID(SID_Pedal);
        if (pedalField != null) {
            pedalField.removeListener(this);
        }
        if (soundEngine != null) {
            // Detach the listener BEFORE stopping. stop() now allows a short fade-out window,
            // during which the engine could otherwise still post onEngineStateChanged() into a
            // destroyed activity via runOnUiThread().
            soundEngine.setEngineListener(null);
            soundEngine.stop();
            soundEngine = null;
        }
    }

    private void animateEntrance() {
        int[] cardIds = {R.id.card_tachometer, R.id.card_speed, R.id.card_pedal, R.id.card_torque, R.id.card_simulator};
        long delay = 50;
        for (int id : cardIds) {
            final android.view.View v = findViewById(id);
            if (v != null && v.getVisibility() == View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(40f);
                v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(400)
                        .setStartDelay(delay)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                delay += 80;
            }
        }
    }

    @Override
    protected void initListeners() {
        // Frame 186 contains both MeanEffectiveTorque (186.16) and Pedal (186.40).
        // By keeping SID_MeanEffectiveTorque in INTERVAL_ASAPFAST and scheduling SID_RealSpeed (5D7.0)
        // at 500ms, the ELM327 stays locked on filter 186 without ping-ponging ATCRA filters.
        // This allows Pedal and Torque to update continuously at 15-25 Hz with minimal latency.
        addField(SID_MeanEffectiveTorque, Device.INTERVAL_ASAPFAST, R.id.tv_torque_val);
        // 1000ms: speed is an integer that barely moves between samples, and every ATCRA switch
        // is another chance for the ELM stream to desynchronise.
        addField(SID_RealSpeed, 1000, R.id.tv_speed_val);

        Field pedalField = MainActivity.fields.getBySID(SID_Pedal);
        if (pedalField != null) {
            pedalField.addListener(this);
        }
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        if (field == null || isTestRunning || isTestStopping) return;

        final String sid = field.getSID();
        if (sid == null) return;

        final double rawVal = field.getValue();
        if (Double.isNaN(rawVal)) return;

        // Feed the synthesizer directly on the poller thread. setInputs() only writes volatile
        // fields, so this is thread-safe, and it avoids queuing every pedal sample behind
        // whatever else the main looper happens to be doing.
        switch (sid) {
            case SID_RealSpeed:
                liveSpeed = (float) rawVal;
                break;
            case SID_Pedal:
                livePedal = (float) rawVal;
                break;
            case SID_MeanEffectiveTorque:
                liveTorque = (float) rawVal;
                break;
            default:
                return;
        }

        if (soundEngine != null) {
            soundEngine.setInputs(liveSpeed, livePedal, liveTorque);
        }

        final float displayValue = (float) rawVal;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                switch (sid) {
                    case SID_RealSpeed:
                        updateSpeed(displayValue);
                        break;
                    case SID_Pedal:
                        updatePedal(displayValue);
                        break;
                    case SID_MeanEffectiveTorque:
                        updateTorque(displayValue);
                        break;
                }
            }
        });
    }

    /**
     * @param newSpeed road speed in km/h. Converted for display only; the synthesizer is always
     *                 fed km/h regardless of the miles setting.
     */
    private void updateSpeed(float newSpeed) {
        if (newSpeed < 0) newSpeed = 0;
        float displaySpeed = (float) Utils.kmOrMiles(newSpeed);
        animateProgress(pbSpeed, (int) lastSpeed, (int) displaySpeed);
        tvSpeedVal.setText(String.format(Locale.getDefault(), "%.0f", displaySpeed));
        lastSpeed = displaySpeed;
    }

    private void updatePedal(float newPedal) {
        if (newPedal < 0) newPedal = 0;
        if (newPedal > 100) newPedal = 100;
        animateProgress(pbPedal, (int) lastPedal, (int) newPedal);
        tvPedalVal.setText(String.format(Locale.getDefault(), "%.0f %%", newPedal));
        lastPedal = newPedal;
    }

    private void updateTorque(float newTorque) {
        int torqueInt = Math.round(newTorque);
        if (torqueInt < 0) {
            // Regenerative braking
            animateProgress(pbTorque, (int) Math.max(0, -lastTorque), Math.min(250, -torqueInt));
            tvTorqueVal.setText(String.format(Locale.getDefault(), "%d Nm (REGEN)", torqueInt));
            tvTorqueVal.setTextColor(0xFF00E5FF); // Cyan for regenerative recovery
        } else {
            // Positive acceleration drive
            animateProgress(pbTorque, (int) Math.max(0, lastTorque), Math.min(250, torqueInt));
            tvTorqueVal.setText(String.format(Locale.getDefault(), "%d Nm", torqueInt));
            tvTorqueVal.setTextColor(0xFFE040FB); // Magenta for motor power
        }
        lastTorque = newTorque;
    }

    /**
     * Sets the bar directly. Frame 186 now arrives 15-25 times a second and fires several field
     * callbacks per packet, so allocating a 180ms ObjectAnimator per update meant dozens of
     * overlapping animations per second competing over the same bar. That looked jerkier than
     * no animation at all and put steady GC pressure on the thread we just made latency critical.
     *
     * @param from retained for call-site compatibility, unused
     */
    private void animateProgress(ProgressBar bar, int from, int to) {
        if (bar == null) return;
        if (to < 0) to = 0;
        bar.setProgress(to);
    }
}
