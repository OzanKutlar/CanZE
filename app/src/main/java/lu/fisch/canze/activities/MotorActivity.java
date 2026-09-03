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
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
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
    private Button btnTestDrive;
    private V8SoundEngine soundEngine;
    private SeekBar sbEngineVolume;
    private TextView tvVolumeVal;
    private static final String PREF_KEY_V8_VOLUME = "v8_master_volume";

    // Test simulation state (10% throttle)
    private boolean isTestRunning = false;
    private final android.os.Handler testHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable testRunnable;
    private float simDriveSpeed = 0f;
    private float simDrivePedal = 0f;
    private float simDriveTorque = 0f;

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
        btnTestDrive = findViewById(R.id.btn_test_drive);

        initTestDriveButton();

        // V8 Sound Generator
        soundEngine = new V8SoundEngine();
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

        btnSoundToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean nowMuted = !soundEngine.isMuted();
                soundEngine.setMuted(nowMuted);
                btnSoundToggle.setText(nowMuted ? "🔇 V8 SOUND: OFF" : "🔊 V8 SOUND: ON");
                btnSoundToggle.setTextColor(nowMuted ? 0xFF8A99AD : 0xFF00E5FF);
            }
        });

        initVolumeControls();
        initSimulatorPanel();
        animateEntrance();
    }

    private void initVolumeControls() {
        sbEngineVolume = findViewById(R.id.sb_engine_volume);
        tvVolumeVal = findViewById(R.id.tv_volume_val);

        android.content.SharedPreferences prefs = getSharedPreferences("lu.fisch.canze.settings", MODE_PRIVATE);
        int savedVolume = prefs.getInt(PREF_KEY_V8_VOLUME, 100);
        if (savedVolume < 10) savedVolume = 100;

        sbEngineVolume.setProgress(savedVolume);
        tvVolumeVal.setText(String.format(Locale.getDefault(), "%d %%", savedVolume));
        if (soundEngine != null) {
            soundEngine.setMasterVolume(savedVolume / 100.0f);
        }

        sbEngineVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvVolumeVal.setText(String.format(Locale.getDefault(), "%d %%", progress));
                if (soundEngine != null) {
                    soundEngine.setMasterVolume(progress / 100.0f);
                }
                if (fromUser) {
                    android.content.SharedPreferences.Editor editor = getSharedPreferences("lu.fisch.canze.settings", MODE_PRIVATE).edit();
                    editor.putInt(PREF_KEY_V8_VOLUME, progress);
                    editor.apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
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
        if (soundEngine != null) {
            soundEngine.start();
        }
    }

    private void initTestDriveButton() {
        if (btnTestDrive == null) return;

        testRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTestRunning) return;

                // Smoothly bring pedal to 10%
                simDrivePedal += (10.0f - simDrivePedal) * 0.08f;

                // Gradually accelerate up to ~65 km/h
                if (simDriveSpeed < 65.0f) {
                    simDriveSpeed += 0.22f; // ~4.4 km/h per second
                }

                // Realistic EV motor torque under 10% pedal:
                // Starts strong at launch (~75 Nm), settles to ~35 Nm at cruising speed
                float targetTorque = 75.0f - (simDriveSpeed / 65.0f * 38.0f);
                simDriveTorque += (targetTorque - simDriveTorque) * 0.10f;

                updateSpeed(simDriveSpeed);
                updatePedal(simDrivePedal);
                updateTorque(simDriveTorque);

                if (soundEngine != null) {
                    soundEngine.setInputs(simDriveSpeed, simDrivePedal, simDriveTorque);
                }

                // 50 ms loop (20 updates/sec)
                testHandler.postDelayed(this, 50);
            }
        };

        btnTestDrive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isTestRunning) {
                    // Start Test Drive
                    isTestRunning = true;
                    simDriveSpeed = 0f;
                    simDrivePedal = 0f;
                    simDriveTorque = 0f;
                    btnTestDrive.setText("⏹ STOP TEST");
                    btnTestDrive.setTextColor(0xFFFF5252); // Red when active
                    testHandler.post(testRunnable);
                } else {
                    // Stop Test Drive
                    stopTestDrive();
                }
            }
        });
    }

    private void stopTestDrive() {
        isTestRunning = false;
        testHandler.removeCallbacks(testRunnable);
        if (btnTestDrive != null) {
            btnTestDrive.setText("▶ TEST (10%)");
            btnTestDrive.setTextColor(0xFFFFD600); // Yellow when idle
        }
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
        if (isTestRunning) {
            stopTestDrive();
        }
        if (soundEngine != null) {
            soundEngine.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isTestRunning) {
            stopTestDrive();
        }
        Field pedalField = MainActivity.fields.getBySID(SID_Pedal);
        if (pedalField != null) {
            pedalField.removeListener(this);
        }
        if (soundEngine != null) {
            soundEngine.stop();
        }
    }

    private void animateEntrance() {
        int[] cardIds = {R.id.card_volume, R.id.card_tachometer, R.id.card_speed, R.id.card_pedal, R.id.card_torque, R.id.card_simulator};
        long delay = 50;
        for (int id : cardIds) {
            final android.view.View v = findViewById(id);
            if (v != null) {
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
        // Querying SID_MeanEffectiveTorque and SID_RealSpeed (5D7.0) at INTERVAL_ASAPFAST
        // gives maximal throughput, while SID_Pedal receives updates on the exact same 186 packet.
        addField(SID_MeanEffectiveTorque, Device.INTERVAL_ASAPFAST, R.id.tv_torque_val);
        addField(SID_RealSpeed, Device.INTERVAL_ASAPFAST, R.id.tv_speed_val);

        Field pedalField = MainActivity.fields.getBySID(SID_Pedal);
        if (pedalField != null) {
            pedalField.addListener(this);
        }
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        if (field == null || isTestRunning) return;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String sid = field.getSID();
                double rawVal = field.getValue();
                if (Double.isNaN(rawVal)) return;

                switch (sid) {
                    case SID_RealSpeed:
                        updateSpeed((float) rawVal);
                        break;
                    case SID_Pedal:
                        updatePedal((float) rawVal);
                        break;
                    case SID_MeanEffectiveTorque:
                        updateTorque((float) rawVal);
                        break;
                }
                if (soundEngine != null) {
                    soundEngine.setInputs(lastSpeed, lastPedal, lastTorque);
                }
            }
        });
    }

    private void updateSpeed(float newSpeed) {
        if (newSpeed < 0) newSpeed = 0;
        animateProgress(pbSpeed, (int) lastSpeed, (int) newSpeed);
        tvSpeedVal.setText(String.format(Locale.getDefault(), "%.0f", newSpeed));
        lastSpeed = newSpeed;
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

    private void animateProgress(ProgressBar bar, int from, int to) {
        if (bar == null || from == to) return;
        ObjectAnimator anim = ObjectAnimator.ofInt(bar, "progress", from, to);
        anim.setDuration(180);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
    }
}
