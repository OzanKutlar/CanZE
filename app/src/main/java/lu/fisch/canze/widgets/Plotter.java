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

package lu.fisch.canze.widgets;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.fragments.MainFragment;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 *
 * @author robertfisch
 */
public class Plotter extends Drawable {

    protected ArrayList<Double> values = new ArrayList<>();
    //protected ArrayList<Double> minValues = new ArrayList<>();
    //protected ArrayList<Double> maxValues = new ArrayList<>();
    protected ArrayList<String> sids = new ArrayList<>();

    public Plotter() {
        super();
    }

    public Plotter(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        // test
    }

    public Plotter(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        this.drawSurface=drawSurface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setValue(int index, double value)
    {
        try {
            values.set(index, value);
        } catch (IndexOutOfBoundsException e) {
            // Bail out. Based on Play Console Crash Report
        }
        //if(value<minValues.get(index)) minValues.set(index,value);
        //if(value>maxValues.get(index)) maxValues.set(index,value);
    }

    @Override
    public void setValue(int value) {
        super.setValue(value);
        //addValue(value);
    }

    @Override
    public void draw(Graphics g) {
        // Draw sleek modern card container window
        drawModernWindowCard(g, 4);

        int inset = 6;
        int padTop = 40;
        int padBottom = 22;
        int padSide = 18;

        int graphX = x + inset + padSide;
        int graphY = y + inset + padTop;
        int graphW = width - 2 * inset - 2 * padSide;
        int graphH = height - 2 * inset - padTop - padBottom;

        if (graphW <= 10 || graphH <= 10) return;

        // Compute pack min, max, delta statistics across all values
        double valMin = Double.MAX_VALUE;
        double valMax = -Double.MAX_VALUE;
        int validCount = 0;
        for (int i = 0; i < values.size(); i++) {
            Double d = values.get(i);
            if (d != null && !Double.isNaN(d) && d > 0) {
                if (d < valMin) valMin = d;
                if (d > valMax) valMax = d;
                validCount++;
            }
        }

        // Header: Title with glowing neon dot
        int titleX = x + inset + 14;
        int titleY = y + inset + 22;
        g.setTextSize(11);
        Color dotCol = isFieldSkipped() ? new Color(204, 0, 0) : new Color(0, 229, 255);
        g.setColor(dotCol);
        g.fillRoundRect(titleX, titleY - 8, 8, 8, 4, 4);

        g.setColor(new Color(255, 255, 255));
        String cleanTitle = (title != null && !title.isEmpty()) ? title.toUpperCase() : "PACK TELEMETRY";
        g.drawString(cleanTitle, titleX + 14, titleY);

        // Pack statistics badge pill (top right): e.g. "MIN: 3.98V  MAX: 4.02V  Δ 14mV"
        if (validCount > 0 && valMin != Double.MAX_VALUE && valMax != -Double.MAX_VALUE) {
            double delta = valMax - valMin;
            String statText;
            if (valMax < 10.0) { // Cell voltage mode (V & mV)
                statText = String.format(java.util.Locale.US, "MIN: %.2fV  MAX: %.2fV  Δ %.0fmV", valMin, valMax, delta * 1000.0);
            } else { // Temperature mode (°C)
                statText = String.format(java.util.Locale.US, "MIN: %.0f°C  MAX: %.0f°C  Δ %.0f°", valMin, valMax, delta);
            }
            g.setTextSize(10);
            int statW = g.stringWidth(statText);
            int pillW = statW + 16;
            int pillH = 18;
            int pillX = x + width - inset - 14 - pillW;
            int pillY = y + inset + 8;

            g.setColor(new Color(24, 33, 49));
            g.fillRoundRect(pillX, pillY, pillW, pillH, 9, 9);
            g.setColor(new Color(38, 51, 74));
            g.drawRoundRect(pillX, pillY, pillW, pillH, 9, 9);

            g.setColor(delta > 0.040 && valMax < 10.0 ? new Color(255, 179, 0) : new Color(0, 229, 255));
            g.drawString(statText, pillX + 8, pillY + 13);
        }

        // Subtle horizontal guide lines
        int lines = 3;
        for (int l = 0; l <= lines; l++) {
            int ly = graphY + (int) (graphH * (float) l / lines);
            g.setColor(new Color(24, 33, 48));
            g.drawLine(graphX, ly, graphX + graphW, ly);
        }

        // Render Equalizer Cell Bars
        if (values.size() > 0) {
            double barSlotW = (double) graphW / values.size();
            double h = (double) graphH / (getMax() - getMin());
            double barPad = Math.max(0.5, barSlotW * 0.12);
            int barW = Math.max(2, (int) (barSlotW - 2 * barPad));

            Color normBarCol = isFieldSkipped() ? new Color(204, 0, 0) : new Color(0, 200, 255);
            Color normCapCol = isFieldSkipped() ? new Color(255, 60, 60) : new Color(130, 245, 255);
            Color warnBarCol = new Color(255, 153, 0);
            Color warnCapCol = new Color(255, 214, 0);

            for (int i = 0; i < values.size(); i++) {
                try {
                    double val = values.get(i);
                    if (Double.isNaN(val) || val <= 0) continue;
                    double barH = (val - getMin()) * h;
                    if (barH < 0) barH = 0;
                    if (barH > graphH) barH = graphH;

                    int bx = graphX + (int) (i * barSlotW + barPad);
                    int by = graphY + graphH - (int) barH;

                    boolean isImbalanced = (validCount > 1 && (valMax - val) > 0.035 && valMax < 10.0);
                    Color bCol = isImbalanced ? warnBarCol : normBarCol;
                    Color cCol = isImbalanced ? warnCapCol : normCapCol;

                    // Sleek pill-column bar
                    g.setColor(bCol);
                    g.fillRoundRect(bx, by, barW, (int) barH, 3, 3);

                    // Glowing top cap
                    g.setColor(cCol);
                    g.fillRoundRect(bx, by, barW, Math.min(3, (int) barH), 2, 2);
                } catch (Exception e) {
                    /* ignore */
                }
            }
        }

        // Subtle bottom border baseline
        g.setColor(new Color(38, 51, 70));
        g.drawLine(graphX, graphY + graphH, graphX + graphW, graphY + graphH);
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        // only take data fofr valid cars
        //MainActivity.debug("Plotter: "+field.getSID()+" --> "+field.getValue());
        //MainActivity.debug("Car = "+MainActivity.car+" / "+field.getCar()+" / "+field.isCar(MainActivity.car));

        if(field.isCar(MainActivity.car)) {
            String sid = field.getSID();

            //MainActivity.debug("!! Plotter: "+sid+" --> "+field.getValue());

            int index = sids.indexOf(sid);
            if (index == -1) {
                sids.add(sid);
                values.add(field.getValue());
                //minValues.add(CanzeDataSource.getInstance().getMin(sid));
                //maxValues.add(CanzeDataSource.getInstance().getMax(sid));

            } else setValue(index, field.getValue());
            // only repaint if the last field has been updated
            //if(index==sids.size()-1)
            super.onFieldUpdateEvent(field);
        }
    }

    /* --------------------------------
     * Serialization
     \ ------------------------------ */

    @Override
    public void loadValuesFromDatabase() {
        super.loadValuesFromDatabase();

        values.clear();
        //maxValues.clear();
        //minValues.clear();

        for(int s=0; s<sids.size(); s++) {
            String sid = sids.get(s);
            values.add(CanzeDataSource.getInstance().getLast(sid));
            //maxValues.add(CanzeDataSource.getInstance().getMax(sid));
            //minValues.add(CanzeDataSource.getInstance().getMin(sid));
        }
    }

    @Override
    public String dataToJson() {
        Gson gson = new Gson();
        ArrayList<ArrayList<Double>> data = new ArrayList<>();
        data.add((ArrayList<Double>) values.clone());
        //data.add((ArrayList<Double>) minValues.clone());
        //data.add((ArrayList<Double>) maxValues.clone());
        return gson.toJson(data);
    }

    @Override
    public void dataFromJson(String json) {
        Gson gson = new Gson();
        Type fooType = new TypeToken<ArrayList<ArrayList<Double>>>() {}.getType();

        ArrayList<ArrayList<Double>> data = gson.fromJson(json, fooType);
        values = data.get(0);
        //minValues=data.get(1);
        //maxValues=data.get(2);
    }


    public void setValues(ArrayList<Double> values) {
        this.values     = values;
    }
}
