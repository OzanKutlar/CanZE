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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Locale;

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 * Bar plot with one bar per field (cell voltages, module temperatures).
 *
 * Threading: {@link #values} and {@link #sids} are written by the poller thread and read
 * by the render thread; both are only touched while holding {@link #valuesLock}.
 * Drawing reads a snapshot in {@link #drawValues}.
 *
 * @author robertfisch
 */
public class Plotter extends Drawable {

    private static final int INSET = 6;
    private static final int PAD_TOP = 40;
    private static final int PAD_BOTTOM = 22;
    private static final int PAD_SIDE = 18;
    private static final int GUIDE_LINES = 3;

    private static final Color NEON_CYAN = new Color(0, 229, 255);
    private static final Color WHITE = new Color(255, 255, 255);
    private static final Color SKIPPED = new Color(204, 0, 0);
    private static final Color SKIPPED_CAP = new Color(255, 60, 60);
    private static final Color PILL_FILL = new Color(24, 33, 49);
    private static final Color PILL_BORDER = new Color(38, 51, 74);
    private static final Color AMBER = new Color(255, 179, 0);
    private static final Color GUIDE = new Color(24, 33, 48);
    private static final Color BAR = new Color(0, 200, 255);
    private static final Color CAP = new Color(130, 245, 255);
    private static final Color WARN_BAR = new Color(255, 153, 0);
    private static final Color WARN_CAP = new Color(255, 214, 0);
    private static final Color BASELINE = new Color(38, 51, 70);

    protected final Object valuesLock = new Object();
    protected ArrayList<Double> values = new ArrayList<>(); // guarded by valuesLock
    protected ArrayList<String> sids = new ArrayList<>();   // guarded by valuesLock

    /** render-thread-only snapshot of values, grown as needed and reused */
    protected double[] drawValues = new double[0];

    // render-thread-only statistics of the current frame
    private double statMin, statMax;
    private int statCount;

    public Plotter() {
        super();
    }

    public Plotter(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Plotter(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        this.drawSurface = drawSurface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /* ---------------- data ---------------- */

    public void setValue(int index, double value)
    {
        synchronized (valuesLock) {
            if (index >= 0 && index < values.size()) values.set(index, value);
        }
    }

    @Override
    public void setValue(int value) {
        super.setValue(value);
    }

    /** Whether this widget shows data for the car the field belongs to. */
    protected boolean acceptsCar(Field field) {
        return field.isCar(MainActivity.car);
    }

    private void record(String sid, double value) {
        synchronized (valuesLock) {
            int index = sids.indexOf(sid);
            if (index == -1) {
                sids.add(sid);
                values.add(value);
            } else if (index < values.size()) {
                values.set(index, value);
            }
        }
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        if (field == null || !acceptsCar(field)) return;
        record(field.getSID(), field.getValue());
        super.onFieldUpdateEvent(field);
    }

    /**
     * Copies the current values into {@link #drawValues}. Call on the render thread only.
     *
     * @return the number of valid entries in drawValues
     */
    protected int snapshotValues() {
        synchronized (valuesLock) {
            int count = values.size();
            if (drawValues.length < count) drawValues = new double[count];
            for (int i = 0; i < count; i++) {
                Double d = values.get(i);
                drawValues[i] = (d == null) ? Double.NaN : d;
            }
            return count;
        }
    }

    /* ---------------- drawing (render thread) ---------------- */

    @Override
    public void draw(Graphics g) {
        if (g == null) return;
        drawModernWindowCard(g, 4);

        int graphX = x + INSET + PAD_SIDE;
        int graphY = y + INSET + PAD_TOP;
        int graphW = width - 2 * INSET - 2 * PAD_SIDE;
        int graphH = height - 2 * INSET - PAD_TOP - PAD_BOTTOM;
        if (graphW <= 10 || graphH <= 10) return;

        int count = snapshotValues();
        computeStats(count);
        drawHeader(g);
        drawStatsPill(g);
        drawGuides(g, graphX, graphY, graphW, graphH);
        drawBars(g, count, graphX, graphY, graphW, graphH);

        g.setColor(BASELINE);
        g.drawLine(graphX, graphY + graphH, graphX + graphW, graphY + graphH);
    }

    private void computeStats(int count) {
        statMin = Double.MAX_VALUE;
        statMax = -Double.MAX_VALUE;
        statCount = 0;
        for (int i = 0; i < count; i++) {
            double d = drawValues[i];
            if (Double.isNaN(d) || d <= 0) continue;
            if (d < statMin) statMin = d;
            if (d > statMax) statMax = d;
            statCount++;
        }
    }

    private void drawHeader(Graphics g) {
        int titleX = x + INSET + 14;
        int titleY = y + INSET + 22;
        g.setTextSize(11);
        g.setColor(isFieldSkipped() ? SKIPPED : NEON_CYAN);
        g.fillRoundRect(titleX, titleY - 8, 8, 8, 4, 4);

        g.setColor(WHITE);
        String cleanTitle = (title != null && !title.isEmpty()) ? title.toUpperCase() : "PACK TELEMETRY";
        g.drawString(cleanTitle, titleX + 14, titleY);
    }

    private void drawStatsPill(Graphics g) {
        if (statCount == 0) return;
        double delta = statMax - statMin;
        boolean voltageMode = statMax < 10.0;
        String statText = voltageMode
                ? String.format(Locale.US, "MIN: %.2fV  MAX: %.2fV  Δ %.0fmV", statMin, statMax, delta * 1000.0)
                : String.format(Locale.US, "MIN: %.0f°C  MAX: %.0f°C  Δ %.0f°", statMin, statMax, delta);

        g.setTextSize(10);
        int pillW = g.stringWidth(statText) + 16;
        int pillH = 18;
        int pillX = x + width - INSET - 14 - pillW;
        int pillY = y + INSET + 8;

        g.setColor(PILL_FILL);
        g.fillRoundRect(pillX, pillY, pillW, pillH, 9, 9);
        g.setColor(PILL_BORDER);
        g.drawRoundRect(pillX, pillY, pillW, pillH, 9, 9);

        g.setColor(delta > 0.040 && voltageMode ? AMBER : NEON_CYAN);
        g.drawString(statText, pillX + 8, pillY + 13);
    }

    private void drawGuides(Graphics g, int graphX, int graphY, int graphW, int graphH) {
        g.setColor(GUIDE);
        for (int l = 0; l <= GUIDE_LINES; l++) {
            int ly = graphY + (int) (graphH * (float) l / GUIDE_LINES);
            g.drawLine(graphX, ly, graphX + graphW, ly);
        }
    }

    private void drawBars(Graphics g, int count, int graphX, int graphY, int graphW, int graphH) {
        if (count == 0) return;
        double range = getMax() - getMin();
        if (range <= 0) range = 1;

        double barSlotW = (double) graphW / count;
        double scale = graphH / range;
        double barPad = Math.max(0.5, barSlotW * 0.12);
        int barW = Math.max(2, (int) (barSlotW - 2 * barPad));
        boolean skipped = isFieldSkipped();

        for (int i = 0; i < count; i++) {
            double val = drawValues[i];
            if (Double.isNaN(val) || val <= 0) continue;
            double barH = Math.max(0, Math.min(graphH, (val - getMin()) * scale));

            int bx = graphX + (int) (i * barSlotW + barPad);
            int by = graphY + graphH - (int) barH;

            boolean imbalanced = statCount > 1 && (statMax - val) > 0.035 && statMax < 10.0;

            // pill-column bar
            g.setColor(imbalanced ? WARN_BAR : (skipped ? SKIPPED : BAR));
            g.fillRoundRect(bx, by, barW, (int) barH, 3, 3);

            // glowing top cap
            g.setColor(imbalanced ? WARN_CAP : (skipped ? SKIPPED_CAP : CAP));
            g.fillRoundRect(bx, by, barW, Math.min(3, (int) barH), 2, 2);
        }
    }

    /* --------------------------------
     * Serialization
     \ ------------------------------ */

    @Override
    public void loadValuesFromDatabase() {
        super.loadValuesFromDatabase();

        CanzeDataSource dataSource = CanzeDataSource.getInstance();
        if (dataSource == null) return;

        ArrayList<String> sidList;
        synchronized (valuesLock) {
            sidList = new ArrayList<>(sids);
        }

        // query outside the lock: the poller must not wait on SQLite
        ArrayList<Double> loaded = new ArrayList<>(sidList.size());
        for (String sid : sidList) {
            loaded.add(dataSource.getLast(sid));
        }

        synchronized (valuesLock) {
            // sids only ever grow, so entries past what we loaded are newer live values
            for (int i = loaded.size(); i < values.size(); i++) {
                loaded.add(values.get(i));
            }
            values = loaded;
        }
    }

    @Override
    public String dataToJson() {
        ArrayList<ArrayList<Double>> data = new ArrayList<>();
        synchronized (valuesLock) {
            data.add(new ArrayList<>(values));
        }
        return new Gson().toJson(data);
    }

    @Override
    public void dataFromJson(String json) {
        if (json == null) return;
        Type fooType = new TypeToken<ArrayList<ArrayList<Double>>>() {}.getType();
        ArrayList<ArrayList<Double>> data = new Gson().fromJson(json, fooType);
        if (data == null || data.isEmpty() || data.get(0) == null) return;
        synchronized (valuesLock) {
            values = data.get(0);
        }
    }

    public void setValues(ArrayList<Double> values) {
        if (values == null) return;
        synchronized (valuesLock) {
            this.values = values;
        }
    }
}
