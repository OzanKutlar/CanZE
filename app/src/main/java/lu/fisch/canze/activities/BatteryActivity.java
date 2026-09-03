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

import android.graphics.Color;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Utils;
import lu.fisch.canze.interfaces.DebugListener;
import lu.fisch.canze.interfaces.FieldListener;

public class BatteryActivity extends CanzeActivity implements FieldListener, DebugListener {

    public static final String SID_BatterySerial                  = "7bb.6162.16"; // EVC
    public static final String SID_RangeEstimate                  = "654.42";
    public static final String SID_AvailableEnergy                = "427.49";
    public static final String SID_DcPower                        = "800.6103.24"; // Virtual field
    public static final String SID_SOH_EVC                        = "7ec.623206.24";
    public static final String SID_SOH_ALT                        = "658.33";
    public static final String SID_UserSoC                        = "42e.0";
    public static final String SID_RealSoC                        = "7bb.6103.192";
    public static final String SID_RealSoC_ALT                    = "654.25";

    public static final String SID_Preamble_CellVoltages1         = "7bb.6141.";
    public static final String SID_Preamble_CellVoltages2         = "7bb.6142.";
    public static final String SID_Preamble_CompartmentTemperatures = "7bb.6104.";

    private final double[] cellVoltages = new double[97]; // 1-based (cells 1-96)
    private final boolean[] cellReceived = new boolean[97];
    private final double[] moduleTemps = new double[13]; // 1-based (1-12)
    private final boolean[] moduleReceived = new boolean[13];
    private int lastTempModule = 4;

    private double realSocVal = Double.NaN;
    private double userSocVal = Double.NaN;
    private double cumulativeSocDelta = 0.0;
    private int socDeltaSamples = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battery);

        TextView textView = findViewById(R.id.link);
        if (textView != null) {
            textView.setText(Html.fromHtml(MainActivity.getStringSingle(R.string.help_QA)));
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }

        if (MainActivity.isZOE()) {
            lastTempModule = 12;
            View extendedGrid = findViewById(R.id.gridHeatmapExtended);
            if (extendedGrid != null) {
                extendedGrid.setVisibility(View.VISIBLE);
            }
        }

        animateEntrance();
    }

    private void animateEntrance() {
        int[] cardIds = {
                R.id.card_hero_battery,
                R.id.card_soh_delta,
                R.id.card_cell_voltages,
                R.id.card_heatmap,
                R.id.card_battery_serial
        };
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
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                delay += 60;
            }
        }
    }

    @Override
    protected void initListeners() {
        MainActivity.getInstance().setDebugListener(this);

        addField(SID_BatterySerial, 6000, R.id.textBatterySerial);
        addField(SID_RangeEstimate, 3000);
        addField(SID_AvailableEnergy, 3000);
        addField(SID_DcPower, 3000);
        addField(SID_SOH_EVC, 5000);
        addField(SID_SOH_ALT, 5000);
        addField(SID_UserSoC, 3000);
        addField(SID_RealSoC, 3000);
        addField(SID_RealSoC_ALT, 3000);

        // Register cell voltages (1 to 96)
        for (int i = 1; i <= 62; i++) {
            addField(SID_Preamble_CellVoltages1 + (i * 16), 5000);
        }
        for (int i = 63; i <= 96; i++) {
            addField(SID_Preamble_CellVoltages2 + ((i - 62) * 16), 5000);
        }

        // Register module temperatures
        for (int i = 1; i <= lastTempModule; i++) {
            String sid = SID_Preamble_CompartmentTemperatures + (8 + i * 24);
            addField(sid, 5000);
        }
    }

    @Override
    public void onFieldUpdateEvent(final Field field) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final String sid = field.getSID();
                final double val = field.getValue();
                if (Double.isNaN(val)) return;

                if (sid.equals(SID_BatterySerial)) {
                    TextView tv = findViewById(R.id.textBatterySerial);
                    if (tv != null) {
                        tv.setText(String.format(Locale.getDefault(), "Serial: %X", (long) val).replace(" 26", "F"));
                    }
                    return;
                }

                if (sid.equals(SID_RangeEstimate)) {
                    updateRange(val);
                    return;
                }

                if (sid.equals(SID_AvailableEnergy) || sid.equals(SID_DcPower)) {
                    updateEnergy(val);
                    return;
                }

                if (sid.equals(SID_SOH_EVC) || sid.equals(SID_SOH_ALT)) {
                    updateSoh(val);
                    return;
                }

                if (sid.equals(SID_RealSoC) || sid.equals(SID_RealSoC_ALT)) {
                    realSocVal = val;
                    updateSocDelta();
                    return;
                }

                if (sid.equals(SID_UserSoC)) {
                    userSocVal = val;
                    updateSocDelta();
                    return;
                }

                if (sid.startsWith(SID_Preamble_CellVoltages1) || sid.startsWith(SID_Preamble_CellVoltages2)) {
                    updateCellVoltage(sid, val);
                    return;
                }

                if (sid.startsWith(SID_Preamble_CompartmentTemperatures)) {
                    updateModuleTemperature(sid, val);
                }
            }
        });
    }

    private void updateRange(double rangeKm) {
        ProgressBar spinner = findViewById(R.id.spinnerRange);
        View container = findViewById(R.id.containerRange);
        TextView tv = findViewById(R.id.textRange);
        TextView unit = findViewById(R.id.textRangeUnit);

        if (spinner != null) spinner.setVisibility(View.GONE);
        if (container != null) container.setVisibility(View.VISIBLE);

        if (tv != null) {
            double displayVal = Utils.kmOrMiles(rangeKm);
            tv.setText(String.format(Locale.getDefault(), "%.0f", displayVal));
        }
        if (unit != null) {
            unit.setText(MainActivity.milesMode ? "mi" : "km");
        }
    }

    private void updateEnergy(double energyVal) {
        ProgressBar spinner = findViewById(R.id.spinnerEnergy);
        View container = findViewById(R.id.containerEnergy);
        TextView tv = findViewById(R.id.textEnergy);
        TextView unit = findViewById(R.id.textEnergyUnit);

        if (spinner != null) spinner.setVisibility(View.GONE);
        if (container != null) container.setVisibility(View.VISIBLE);

        if (tv != null) {
            tv.setText(String.format(Locale.getDefault(), "%.1f", energyVal));
        }
        if (unit != null) {
            unit.setText("kWh");
        }
    }

    private void updateSoh(double soh) {
        ProgressBar spinner = findViewById(R.id.spinnerSOH);
        View container = findViewById(R.id.containerSOH);
        TextView tv = findViewById(R.id.textSOHVal);
        TextView sub = findViewById(R.id.textSOHSub);

        if (spinner != null) spinner.setVisibility(View.GONE);
        if (container != null) container.setVisibility(View.VISIBLE);

        if (tv != null) {
            tv.setText(String.format(Locale.getDefault(), "%.0f", soh));
        }
        if (sub != null) {
            if (soh >= 90) {
                sub.setText("Excellent Health");
                sub.setTextColor(Color.parseColor("#00E676"));
            } else if (soh >= 80) {
                sub.setText("Good Condition");
                sub.setTextColor(Color.parseColor("#00E5FF"));
            } else if (soh >= 70) {
                sub.setText("Normal Degradation");
                sub.setTextColor(Color.parseColor("#FFD600"));
            } else {
                sub.setText("Degraded Health");
                sub.setTextColor(Color.parseColor("#FF5252"));
            }
        }
    }

    private void updateSocDelta() {
        if (Double.isNaN(realSocVal) || Double.isNaN(userSocVal)) return;

        double instantDelta = realSocVal - userSocVal;
        cumulativeSocDelta += instantDelta;
        socDeltaSamples++;
        double avgDelta = cumulativeSocDelta / socDeltaSamples;

        ProgressBar spinner = findViewById(R.id.spinnerSocDelta);
        View container = findViewById(R.id.containerSocDelta);
        TextView tv = findViewById(R.id.textSocDelta);
        TextView sub = findViewById(R.id.textSocDetails);

        if (spinner != null) spinner.setVisibility(View.GONE);
        if (container != null) container.setVisibility(View.VISIBLE);

        if (tv != null) {
            String sign = avgDelta > 0 ? "+" : "";
            tv.setText(String.format(Locale.getDefault(), "%s%.1f", sign, avgDelta));
        }
        if (sub != null) {
            sub.setText(String.format(Locale.getDefault(), "Real: %.1f%%  |  Usable: %.1f%%", realSocVal, userSocVal));
        }
    }

    private void updateCellVoltage(String sid, double voltage) {
        int cellIndex = 0;
        try {
            int pos = Integer.parseInt(sid.split("[.]")[2]);
            if (sid.startsWith(SID_Preamble_CellVoltages1)) {
                cellIndex = pos / 16;
            } else if (sid.startsWith(SID_Preamble_CellVoltages2)) {
                cellIndex = (pos / 16) + 62;
            }
        } catch (Exception e) {
            return;
        }

        if (cellIndex >= 1 && cellIndex <= 96) {
            cellVoltages[cellIndex] = voltage;
            cellReceived[cellIndex] = true;
        }

        double min = 10.0;
        double max = 0.0;
        int count = 0;
        for (int i = 1; i <= 96; i++) {
            if (cellReceived[i] && cellVoltages[i] > 1.0) {
                if (cellVoltages[i] < min) min = cellVoltages[i];
                if (cellVoltages[i] > max) max = cellVoltages[i];
                count++;
            }
        }

        if (count > 0 && max >= min) {
            ProgressBar spinner = findViewById(R.id.spinnerVoltages);
            View container = findViewById(R.id.containerVoltages);
            TextView tvMin = findViewById(R.id.textCellMin);
            TextView tvMax = findViewById(R.id.textCellMax);
            TextView tvDiff = findViewById(R.id.textCellDiff);

            if (spinner != null) spinner.setVisibility(View.GONE);
            if (container != null) container.setVisibility(View.VISIBLE);

            if (tvMin != null) tvMin.setText(String.format(Locale.getDefault(), "%.3f", min));
            if (tvMax != null) tvMax.setText(String.format(Locale.getDefault(), "%.3f", max));
            if (tvDiff != null) {
                double diffMv = (max - min) * 1000.0;
                tvDiff.setText(String.format(Locale.getDefault(), "%.0f", diffMv));
            }
        }
    }

    private void updateModuleTemperature(String sid, double temp) {
        int moduleIndex = 0;
        try {
            int pos = Integer.parseInt(sid.split("[.]")[2]);
            moduleIndex = (pos - 8) / 24;
        } catch (Exception e) {
            return;
        }

        if (moduleIndex < 1 || moduleIndex > 12) return;
        moduleTemps[moduleIndex] = temp;
        moduleReceived[moduleIndex] = true;

        ProgressBar spinner = findViewById(R.id.spinnerHeatmap);
        View container = findViewById(R.id.containerHeatmap);
        if (spinner != null) spinner.setVisibility(View.GONE);
        if (container != null) container.setVisibility(View.VISIBLE);

        // Calculate mean temperature across received modules
        double sum = 0;
        int count = 0;
        for (int i = 1; i <= lastTempModule; i++) {
            if (moduleReceived[i]) {
                sum += moduleTemps[i];
                count++;
            }
        }
        double mean = count > 0 ? sum / count : temp;

        // Render module boxes with dynamic heat map colors
        for (int i = 1; i <= lastTempModule; i++) {
            if (!moduleReceived[i]) continue;
            int textId = getResources().getIdentifier("textModTemp" + i, "id", getPackageName());
            int boxId = getResources().getIdentifier("boxTemp" + i, "id", getPackageName());

            TextView tv = findViewById(textId);
            View box = findViewById(boxId);

            if (tv != null) {
                tv.setText(String.format(Locale.getDefault(), "%.0f°C", moduleTemps[i]));
            }
            if (box != null) {
                double diff = moduleTemps[i] - mean;
                int boxColor;
                if (diff > 2.0) {
                    boxColor = Color.parseColor("#5C1D24"); // Hot red-burgundy
                } else if (diff > 0.5) {
                    boxColor = Color.parseColor("#4A3519"); // Warm amber
                } else if (diff < -2.0) {
                    boxColor = Color.parseColor("#16385C"); // Cool blue
                } else if (diff < -0.5) {
                    boxColor = Color.parseColor("#123B44"); // Cool teal
                } else {
                    boxColor = Color.parseColor("#1A2436"); // Balanced dark slate
                }
                box.setBackgroundColor(boxColor);
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_empty, menu);
        return true;
    }
}
