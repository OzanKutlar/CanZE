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
import java.util.HashMap;
import java.util.Map;

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;
import lu.fisch.awt.Polygon;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.classes.TimePoint;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 * Scrolling time plot of one or more fields.
 *
 * Threading: values are written by the poller thread and read by the render thread.
 * Every access to {@link #values} holds {@link #valuesLock}; drawing works on a
 * snapshot so the poller is never blocked for the duration of a frame.
 *
 * @author robertfisch
 */
public class Timeplot extends Drawable {

    private static final long SECOND_MS = 1000L;
    private static final long MINUTE_MS = 60000L;
    /** keep a little more than the visible window so the left edge never starts empty */
    private static final long WINDOW_MARGIN_MS = 2000L;

    private static final int INSET = 6;
    private static final int PAD_LEFT = 46;
    private static final int PAD_RIGHT = 16;
    private static final int PAD_RIGHT_ALT = 46;
    private static final int PAD_TOP = 38;
    private static final int PAD_BOTTOM = 26;
    private static final int GRID_LINES = 3;
    private static final int BADGE_H = 18;

    private static final Color[] TRACE_COLORS = {
            new Color(0, 229, 255),   // neon cyan
            new Color(224, 64, 251),  // neon magenta
            new Color(0, 230, 118)    // neon green
    };
    private static final Color[] AREA_COLORS = {
            new Color(30, 0, 229, 255),
            new Color(30, 224, 64, 251),
            new Color(30, 0, 230, 118)
    };
    private static final Color SKIPPED = new Color(204, 0, 0);
    private static final Color SKIPPED_AREA = new Color(30, 204, 0, 0);
    private static final Color LEGEND_TEXT = new Color(138, 153, 173);
    private static final Color PILL_FILL = new Color(24, 33, 49);
    private static final Color PILL_BORDER = new Color(38, 51, 74);
    private static final Color GRID = new Color(24, 33, 48);
    private static final Color AXIS_LABEL = new Color(94, 113, 141);
    private static final Color BASELINE = new Color(38, 51, 70);

    protected final Object valuesLock = new Object();
    protected HashMap<String, ArrayList<TimePoint>> values = new HashMap<>(); // guarded by valuesLock

    /** render-thread-only copy of values, reused frame to frame */
    private final HashMap<String, ArrayList<TimePoint>> drawSnapshot = new HashMap<>();
    // render-thread-only plot area of the current frame
    private int plotX, plotY, plotW, plotH;

    private boolean backward = true;

    public Timeplot() {
        super();
    }

    public Timeplot(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Timeplot(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        this.drawSurface = drawSurface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /* ---------------- data ---------------- */

    private long windowMs() {
        return MINUTE_MS * Math.max(1, getTimeScale()) + WINDOW_MARGIN_MS;
    }

    public void addValue(String fieldSID, double value) {
        if (fieldSID == null) return;
        // at most one point per second: fields can arrive far faster than that
        long now = (System.currentTimeMillis() / SECOND_MS) * SECOND_MS;
        TimePoint point = new TimePoint(now, value);

        synchronized (valuesLock) {
            ArrayList<TimePoint> list = values.get(fieldSID);
            if (list == null) {
                list = new ArrayList<>();
                values.put(fieldSID, list);
            }
            appendOrReplace(list, point);
            trim(list, now - windowMs());
        }
    }

    /** Replaces the last point if it is from the same second (the latest value is the one shown). */
    private static void appendOrReplace(ArrayList<TimePoint> list, TimePoint point) {
        int last = list.size() - 1;
        if (last >= 0) {
            TimePoint lastPoint = list.get(last);
            if (lastPoint != null && lastPoint.date == point.date) {
                list.set(last, point);
                return;
            }
        }
        list.add(point);
    }

    /** Drops points older than {@code oldest} from the head of a time-ordered list. */
    private static void trim(ArrayList<TimePoint> list, long oldest) {
        int size = list.size();
        int drop = 0;
        while (drop < size) {
            TimePoint point = list.get(drop);
            if (point != null && point.date >= oldest) break;
            drop++;
        }
        if (drop > 0) list.subList(0, drop).clear();
    }

    private static long lastDate(ArrayList<TimePoint> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            TimePoint point = list.get(i);
            if (point != null) return point.date;
        }
        return Long.MIN_VALUE;
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        if (field == null) return;
        addValue(field.getSID(), field.getValue());
        super.onFieldUpdateEvent(field);
    }

    @Override
    public void loadValuesFromDatabase() {
        super.loadValuesFromDatabase();

        CanzeDataSource dataSource = CanzeDataSource.getInstance();
        if (dataSource == null) return;

        long oldest = System.currentTimeMillis() - windowMs();
        ArrayList<String> sidList = new ArrayList<>(sids);
        for (String sid : sidList) {
            ArrayList<TimePoint> loaded = dataSource.getData(sid);
            ArrayList<TimePoint> data = (loaded == null) ? new ArrayList<TimePoint>() : new ArrayList<>(loaded);
            trim(data, oldest);
            mergeLoaded(sid, data);
        }
    }

    /** Installs history for a SID, keeping live points that arrived while the query ran. */
    private void mergeLoaded(String sid, ArrayList<TimePoint> data) {
        synchronized (valuesLock) {
            ArrayList<TimePoint> live = values.get(sid);
            if (live != null) {
                long newest = lastDate(data);
                for (TimePoint point : live) {
                    if (point != null && point.date > newest) data.add(point);
                }
            }
            values.put(sid, data);
        }
    }

    public void addField(String sid) {
        super.addField(sid);
        synchronized (valuesLock) {
            if (!values.containsKey(sid)) {
                values.put(sid, new ArrayList<TimePoint>());
            }
        }
    }

    public void setValues(HashMap<String, ArrayList<TimePoint>> values) {
        if (values == null) return;
        synchronized (valuesLock) {
            sids.clear();
            sids.addAll(values.keySet());
            this.values = values;
        }
    }

    /* ---------------- serialization ---------------- */

    @Override
    public String dataToJson() {
        HashMap<String, ArrayList<TimePoint>> copy = new HashMap<>();
        synchronized (valuesLock) {
            for (Map.Entry<String, ArrayList<TimePoint>> entry : values.entrySet()) {
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return new Gson().toJson(copy);
    }

    @Override
    public void dataFromJson(String json) {
        if (json == null) return;
        Type fooType = new TypeToken<HashMap<String, ArrayList<TimePoint>>>() {
        }.getType();
        HashMap<String, ArrayList<TimePoint>> parsed = new Gson().fromJson(json, fooType);
        if (parsed == null) return;
        synchronized (valuesLock) {
            values = parsed;
        }
    }

    /* ---------------- drawing (render thread) ---------------- */

    private void takeSnapshot() {
        synchronized (valuesLock) {
            for (int s = 0; s < sids.size(); s++) {
                String sid = sids.get(s);
                ArrayList<TimePoint> target = drawSnapshot.get(sid);
                if (target == null) {
                    target = new ArrayList<>();
                    drawSnapshot.put(sid, target);
                }
                target.clear();
                ArrayList<TimePoint> source = values.get(sid);
                if (source != null) target.addAll(source);
            }
        }
    }

    private Color traceColor(int index) {
        if (isFieldSkipped()) return SKIPPED;
        return TRACE_COLORS[Math.min(index, TRACE_COLORS.length - 1)];
    }

    private Color areaColor(int index) {
        if (isFieldSkipped()) return SKIPPED_AREA;
        return AREA_COLORS[Math.min(index, AREA_COLORS.length - 1)];
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    @Override
    public void draw(Graphics g) {
        if (g == null) return;
        drawModernWindowCard(g, 4);

        boolean hasAlt = minAlt != 0 || maxAlt != 0;
        plotX = x + INSET + PAD_LEFT;
        plotY = y + INSET + PAD_TOP;
        plotW = width - 2 * INSET - PAD_LEFT - (hasAlt ? PAD_RIGHT_ALT : PAD_RIGHT);
        plotH = height - 2 * INSET - PAD_TOP - PAD_BOTTOM;
        if (plotW <= 10 || plotH <= 10) return;

        takeSnapshot();
        drawLegend(g);
        drawBadges(g);
        drawGrid(g, hasAlt);
        drawTraces(g);

        g.setColor(BASELINE);
        g.drawLine(plotX, plotY + plotH, plotX + plotW, plotY + plotH);
    }

    private void drawLegend(Graphics g) {
        if (title == null || title.isEmpty()) return;
        String[] parts = title.replace(" / ", ",").split(",");
        int curX = x + INSET + 14;
        int titleY = y + INSET + 22;
        g.setTextSize(11);
        for (int s = 0; s < sids.size(); s++) {
            g.setColor(traceColor(s));
            g.fillRoundRect(curX, titleY - 8, 8, 8, 4, 4);
            curX += 12;

            String label = (s < parts.length) ? parts[s].trim().toUpperCase() : "DATA";
            g.setColor(LEGEND_TEXT);
            g.drawString(label, curX, titleY);
            curX += g.stringWidth(label) + 16;
        }
    }

    private void drawBadges(Graphics g) {
        int badgeRight = x + width - INSET - 14;
        int badgeY = y + INSET + 8;
        g.setTextSize(11);
        for (int s = sids.size() - 1; s >= 0; s--) {
            String text = badgeText(sids.get(s));
            int badgeW = g.stringWidth(text) + 16;
            int badgeX = badgeRight - badgeW;

            g.setColor(PILL_FILL);
            g.fillRoundRect(badgeX, badgeY, badgeW, BADGE_H, 9, 9);
            g.setColor(PILL_BORDER);
            g.drawRoundRect(badgeX, badgeY, badgeW, BADGE_H, 9, 9);

            g.setColor(traceColor(s));
            g.drawString(text, badgeX + 8, badgeY + 13);
            badgeRight = badgeX - 8;
        }
    }

    private String badgeText(String sid) {
        Field f = Fields.getInstance().getBySID(sid);
        if (f != null && !Double.isNaN(f.getValue())) {
            return String.format("%." + f.getDecimals() + "f %s", f.getValue(), f.getUnit()).trim();
        }
        ArrayList<TimePoint> points = drawSnapshot.get(sid);
        if (points != null && !points.isEmpty()) {
            TimePoint last = points.get(points.size() - 1);
            if (last != null && !Double.isNaN(last.value)) return String.format("%.1f", last.value);
        }
        return "--";
    }

    private void drawGrid(Graphics g, boolean hasAlt) {
        g.setTextSize(9);
        for (int i = 0; i <= GRID_LINES; i++) {
            float ratio = (float) i / GRID_LINES;
            int lineY = plotY + (int) (plotH * (1f - ratio));

            g.setColor(GRID);
            g.drawLine(plotX, lineY, plotX + plotW, lineY);

            g.setColor(AXIS_LABEL);
            String label = String.format("%.0f", min + (max - min) * (double) ratio);
            g.drawString(label, plotX - g.stringWidth(label) - 8, lineY + 3);

            if (hasAlt) {
                String alt = String.format("%.0f", minAlt + (maxAlt - minAlt) * (double) ratio);
                g.drawString(alt, plotX + plotW + 8, lineY + 3);
            }
        }
    }

    private boolean isAltTrace(String sid) {
        if (getOptions() == null) return false;
        String option = getOptions().getOption(sid);
        return option != null && option.contains("alt");
    }

    private void drawTraces(Graphics g) {
        long windowSec = 60L * Math.max(1, getTimeScale());
        long startSec = System.currentTimeMillis() / SECOND_MS - windowSec;

        for (int s = 0; s < sids.size(); s++) {
            String sid = sids.get(s);
            ArrayList<TimePoint> points = drawSnapshot.get(sid);
            if (points == null || points.isEmpty()) continue;

            boolean alt = isAltTrace(sid);
            double valMin = alt ? minAlt : min;
            double valMax = alt ? maxAlt : max;
            if (valMax <= valMin) valMax = valMin + 1.0;

            drawTrace(g, points, s, startSec, windowSec, valMin, valMax);
        }
    }

    private void drawTrace(Graphics g, ArrayList<TimePoint> points, int index,
                           long startSec, long windowSec, double valMin, double valMax) {
        int bottom = plotY + plotH;
        Polygon area = new Polygon();
        int prevX = -1;
        int prevY = -1;

        g.setColor(traceColor(index));
        g.setStrokeWidth(2.5f);
        for (int i = 0; i < points.size(); i++) {
            TimePoint tp = points.get(i);
            if (tp == null || Double.isNaN(tp.value) || tp.date == 0) continue;
            long tpSec = tp.date / SECOND_MS;
            if (tpSec < startSec) continue;

            int curX = plotX + (int) (plotW * clamp01((float) (tpSec - startSec) / windowSec));
            int curY = bottom - (int) (plotH * clamp01((float) ((tp.value - valMin) / (valMax - valMin))));

            if (area.size() == 0) area.addPoint(curX, bottom);
            area.addPoint(curX, curY);

            if (prevX >= 0) g.drawLine(prevX, prevY, curX, curY);
            prevX = curX;
            prevY = curY;
        }

        if (area.size() > 1 && prevX >= 0) {
            area.addPoint(prevX, bottom);
            g.setColor(areaColor(index));
            g.fillPolygon(area);
        }
        g.setStrokeWidth(1f);
    }

    public boolean isBackward() {
        return backward;
    }

    public void setBackward(boolean backward) {
        this.backward = backward;
    }
}
