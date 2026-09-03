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
import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.devices.Device;
import lu.fisch.canze.interfaces.FieldListener;

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

        animateEntrance();
    }

    private void animateEntrance() {
        int[] cardIds = {R.id.card_speed, R.id.card_pedal, R.id.card_torque};
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
                        break;
                    case SID_Pedal:
                        updatePedal((float) rawVal);
                        break;
                    case SID_MeanEffectiveTorque:
                        updateTorque((float) rawVal);
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
        animateProgress(pbTorque, (int) Math.max(0, lastTorque), Math.max(0, torqueInt));
        tvTorqueVal.setText(String.format(Locale.getDefault(), "%d Nm", torqueInt));
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
