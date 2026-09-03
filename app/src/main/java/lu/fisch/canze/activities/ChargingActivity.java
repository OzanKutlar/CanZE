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
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package lu.fisch.canze.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

public class ChargingActivity extends CanzeActivity implements FieldListener, DebugListener {

    public static final String SID_MaxCharge                        = "7bb.6101.336";
    public static final String SID_UserSoC                          = "42e.0";
    public static final String SID_RealSoC                          = "7bb.6103.192";
    public static final String SID_AvChargingPower                  = "427.40";
    public static final String SID_ACPilot                          = "42e.38";
    public static final String SID_HvTemp                           = "42e.44";
    public static final String SID_HvTempFluKan                     = "7bb.6103.56";
    public static final String SID_RangeEstimate                    = "654.42";
    public static final String SID_DcPower                          = "800.6103.24"; // Virtual field
    public static final String SID_SOH                              = "7ec.623206.24";

    double avChPwr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_charging);
        animateEntrance();
    }

    private void animateEntrance() {
        int[] cardIds = {R.id.card_charging_power, R.id.card_dc_range, R.id.card_battery_telemetry};
        long delay = 60;
        for (int id : cardIds) {
            final View v = findViewById(id);
            if (v != null) {
                v.setAlpha(0f);
                v.setTranslationY(30f);
                v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setStartDelay(delay)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();
                delay += 70;
            }
        }
    }

    @Override
    protected void initListeners() {
        MainActivity.getInstance().setDebugListener(this);
        addField(SID_MaxCharge, 5000, R.id.text_max_charge);
        addField(SID_UserSoC, 5000, R.id.textUserSOC);
        addField(SID_RealSoC, 5000, R.id.textRealSOC);
        addField(SID_SOH, 5000, R.id.textSOH);
        addField(SID_RangeEstimate, 5000, R.id.textKMA);
        addField(SID_DcPower, 5000, R.id.textDcPwr);
        if (MainActivity.car == MainActivity.CAR_ZOE_Q210 || MainActivity.car == MainActivity.CAR_ZOE_R240 || MainActivity.car == MainActivity.CAR_ZOE_Q90 || MainActivity.car == MainActivity.CAR_ZOE_R90) {
            addField(SID_AvChargingPower, 5000, R.id.textAvChPwr);
            addField(SID_HvTemp, 5000, R.id.textHvTemp);
        } else { // FluKan
            addField(SID_HvTempFluKan, 5000);
            addField(SID_ACPilot, 5000, R.id.textAvChPwr);
        }
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String fieldId = field.getSID();
                TextView tv = null;
                int spinnerId = 0;
                int containerId = 0;

                switch (fieldId) {
                    case SID_MaxCharge:
                        double maxCharge = field.getValue();
                        int color = 0xffc0c0c0;
                        if (maxCharge < (avChPwr * 0.8) && avChPwr < 45.0) {
                            color = 0xffffc0c0;
                        }
                        tv = findViewById(R.id.text_max_charge);
                        if (tv != null) tv.setBackgroundColor(color);
                        spinnerId = R.id.spinnerMaxCharge;
                        containerId = R.id.containerMaxCharge;
                        break;
                    case SID_UserSoC:
                        tv = findViewById(R.id.textUserSOC);
                        spinnerId = R.id.spinnerUserSOC;
                        break;
                    case SID_RealSoC:
                        tv = findViewById(R.id.textRealSOC);
                        spinnerId = R.id.spinnerRealSOC;
                        break;
                    case SID_HvTemp:
                        tv = findViewById(R.id.textHvTemp);
                        spinnerId = R.id.spinnerHvTemp;
                        break;
                    case SID_SOH:
                        tv = findViewById(R.id.textSOH);
                        spinnerId = R.id.spinnerSOH;
                        break;
                    case SID_RangeEstimate:
                        tv = findViewById(R.id.textKMA);
                        spinnerId = R.id.spinnerKMA;
                        containerId = R.id.containerKMA;
                        if (tv != null) {
                            if (field.getValue() >= 1023) {
                                tv.setText("---");
                            } else {
                                tv.setText(String.format(Locale.getDefault(), "%.0f", field.getValue()));
                            }
                        }
                        tv = null;
                        break;
                    case SID_DcPower:
                        tv = findViewById(R.id.textDcPwr);
                        spinnerId = R.id.spinnerDcPwr;
                        containerId = R.id.containerDcPwr;
                        break;
                    case SID_AvChargingPower:
                        avChPwr = field.getValue();
                        tv = findViewById(R.id.textAvChPwr);
                        spinnerId = R.id.spinnerAvChPwr;
                        containerId = R.id.containerAvChPwr;
                        if (avChPwr > 45.0) {
                            if (tv != null) tv.setText("---");
                            tv = null;
                        }
                        break;
                    case SID_ACPilot:
                        avChPwr = field.getValue() * 0.225;
                        tv = findViewById(R.id.textAvChPwr);
                        spinnerId = R.id.spinnerAvChPwr;
                        containerId = R.id.containerAvChPwr;
                        break;
                }

                if (spinnerId != 0) {
                    View spinner = findViewById(spinnerId);
                    if (spinner != null) spinner.setVisibility(View.GONE);
                }
                if (containerId != 0) {
                    View container = findViewById(containerId);
                    if (container != null) container.setVisibility(View.VISIBLE);
                }

                if (tv != null) {
                    tv.setVisibility(View.VISIBLE);
                    tv.setText(String.format(Locale.getDefault(), "%.1f", field.getValue()));
                }
            }
        });
    }
}
