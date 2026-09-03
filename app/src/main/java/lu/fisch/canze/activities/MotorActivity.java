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
    private V8SoundEngine soundEngine;

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

        initSimulatorPanel();
        animateEntrance();
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
                soundEngine.setInputs(simSpeed, simPedal, simTorque);
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

    @Override
    protected void onPause() {
        super.onPause();
        if (soundEngine != null) {
            soundEngine.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (soundEngine != null) {
            soundEngine.stop();
        }
    }

    private void animateEntrance() {
        int[] cardIds = {R.id.card_tachometer, R.id.card_speed, R.id.card_pedal, R.id.card_torque, R.id.card_simulator};
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
        // Poll as fast as possible (INTERVAL_ASAPFAST = -1) so updates stream near real-time
        addField(SID_Pedal, Device.INTERVAL_ASAPFAST, R.id.tv_pedal_val);
        addField(SID_MeanEffectiveTorque, Device.INTERVAL_ASAPFAST, R.id.tv_torque_val);
        addField(SID_RealSpeed, Device.INTERVAL_ASAPFAST, R.id.tv_speed_val);
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        if (field == null) return;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String sid = field.getSID();
                double rawVal = field.getValue();
                if (Double.isNaN(rawVal)) return;

                switch (sid) {
                    case SID_RealSpeed:
                        updateSpeed((float) rawVal);
                        soundEngine.setInputs(lastSpeed, lastPedal, lastTorque);
                        break;
                    case SID_Pedal:
                        updatePedal((float) rawVal);
                        soundEngine.setInputs(lastSpeed, lastPedal, lastTorque);
                        break;
                    case SID_MeanEffectiveTorque:
                        updateTorque((float) rawVal);
                        soundEngine.setInputs(lastSpeed, lastPedal, lastTorque);
                        break;
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
