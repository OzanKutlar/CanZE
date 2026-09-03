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

import android.content.res.Resources;
import android.util.TypedValue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;
import lu.fisch.awt.Polygon;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Fields;
import lu.fisch.canze.classes.TimePoint;
import lu.fisch.canze.database.CanzeDataSource;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 * @author robertfisch
 */
public class Timeplot extends Drawable {

    protected HashMap<String, ArrayList<TimePoint>> values = new HashMap<>();

    private boolean backward = true;

    public Timeplot() {
        super();
    }

    public Timeplot(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        // test
    }

    public Timeplot(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        this.drawSurface = drawSurface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void addValue(String fieldSID, double value) {
        long iTime = Calendar.getInstance().getTimeInMillis();
        // with the new dongle, fields may come in too fast, so let's
        // make sure we do not get an overflow >> very slow app reaction
        // maximum each second a new value!
        iTime = (iTime / 1000) * 1000;

        //MainActivity.debug(values.size()+"");
        if (!values.containsKey(fieldSID)) values.put(fieldSID, new ArrayList<TimePoint>());

        // don't add every point, but check if for the given second we allready have point
        // remembering more than on point a second is kind of overkill
        //values.get(fieldSID).add(new TimePoint(Calendar.getInstance().getTimeInMillis(), value));

        // if empty, add
        if (values.get(fieldSID).size() == 0)
            values.get(fieldSID).add(new TimePoint(iTime, value));
        else {
            TimePoint lastTP = values.get(fieldSID).get(values.get(fieldSID).size() - 1);
            // if this is really a new point, add it
            if (lastTP == null || lastTP.date != iTime)
                values.get(fieldSID).add(new TimePoint(iTime, value));
                // if not, replace the previous point
                // ( database will store the max, but as the value of the last point is also being
                //   displayed on the screen, we should prefer having the real last point here )
            else {
                values.get(fieldSID).set(values.get(fieldSID).size() - 1, new TimePoint(iTime, value));
            }
        }


        /*
        if(value<min) setMin((int) value - 1);
        else if(value>max) setMax((int) value + 1);
        */

        /*setMinorTicks(0);
        setMajorTicks(1);
        if(getMax()-getMin()>100) setMajorTicks(10);
        else if(getMax()-getMin()>1000) setMajorTicks(100);
        else if(getMax()-getMin()>10000) setMajorTicks(1000);
        /**/
    }

    private Color getColor(int i) {
        if (i == 0) return new Color(0, 229, 255);    // Neon Cyan #00E5FF
        else if (i == 1) return new Color(224, 64, 251); // Neon Magenta #E040FB
        else return new Color(0, 230, 118);            // Neon Green #00E676
    }
    @Override
    public void draw(Graphics g) {
        // Draw sleek modern card container window
        drawModernWindowCard(g, 4);

        int inset = 6;
        int padLeft = 46;
        int padRight = (minAlt != 0 || maxAlt != 0) ? 46 : 16;
        int padTop = 38;
        int padBottom = 26;

        int graphX = x + inset + padLeft;
        int graphY = y + inset + padTop;
        int graphW = width - 2 * inset - padLeft - padRight;
        int graphH = height - 2 * inset - padTop - padBottom;

        if (graphW <= 10 || graphH <= 10) return;

        // Top Header: Title & Trace Legend
        int titleX = x + inset + 14;
        int titleY = y + inset + 22;
        g.setTextSize(11);
        if (title != null && !title.isEmpty()) {
            String[] parts = title.replace(" / ", ",").split(",");
            int curX = titleX;
            for (int s = 0; s < sids.size(); s++) {
                Color dotCol = isFieldSkipped() ? new Color(204, 0, 0) : getColor(s);
                g.setColor(dotCol);
                g.fillRoundRect(curX, titleY - 8, 8, 8, 4, 4);
                curX += 12;

                String lbl = (s < parts.length) ? parts[s].trim().toUpperCase() : "DATA";
                g.setColor(new Color(138, 153, 173));
                g.drawString(lbl, curX, titleY);
                curX += g.stringWidth(lbl) + 16;
            }
        }

        // Live metric pill badges (top right)
        int badgeRight = x + width - inset - 14;
        for (int s = sids.size() - 1; s >= 0; s--) {
            String sid = sids.get(s);
            Field f = Fields.getInstance().getBySID(sid);
            String valStr = "--";
            if (f != null && !Double.isNaN(f.getValue())) {
                valStr = String.format("%." + f.getDecimals() + "f %s", f.getValue(), f.getUnit()).trim();
            } else if (values.containsKey(sid) && !values.get(sid).isEmpty()) {
                double lastV = values.get(sid).get(values.get(sid).size() - 1).value;
                if (!Double.isNaN(lastV)) valStr = String.format("%.1f", lastV);
            }
            g.setTextSize(11);
            int strW = g.stringWidth(valStr);
            int badgeW = strW + 16;
            int badgeH = 18;
            int badgeX = badgeRight - badgeW;
            int badgeY = y + inset + 8;

            // Pill background
            g.setColor(new Color(24, 33, 49));
            g.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 9, 9);
            g.setColor(new Color(38, 51, 74));
            g.drawRoundRect(badgeX, badgeY, badgeW, badgeH, 9, 9);

            // Text
            Color valColor = isFieldSkipped() ? new Color(204, 0, 0) : getColor(s);
            g.setColor(valColor);
            g.drawString(valStr, badgeX + 8, badgeY + 13);
            badgeRight = badgeX - 8;
        }

        // Minimalist horizontal grid lines & Y-axis labels
        int gridLines = 3;
        for (int i = 0; i <= gridLines; i++) {
            float ratio = (float) i / gridLines;
            int lineY = graphY + (int) (graphH * (1f - ratio));
            double yVal = min + (max - min) * ratio;

            // Subtle horizontal gridline
            g.setColor(new Color(24, 33, 48));
            g.drawLine(graphX, lineY, graphX + graphW, lineY);

            // Label on left
            g.setColor(new Color(94, 113, 141));
            g.setTextSize(9);
            String numStr = String.format("%.0f", yVal);
            int numW = g.stringWidth(numStr);
            g.drawString(numStr, graphX - numW - 8, lineY + 3);

            // Alt label on right if exists
            if (minAlt != 0 || maxAlt != 0) {
                double altVal = minAlt + (maxAlt - minAlt) * ratio;
                String altStr = String.format("%.0f", altVal);
                g.drawString(altStr, graphX + graphW + 8, lineY + 3);
            }
        }

        // Draw Data Traces (Smooth curves + translucent area fills)
        long nowSec = Calendar.getInstance().getTimeInMillis() / 1000;
        long windowSec = 60L * timeSale;
        long startSec = nowSec - windowSec;

        for (int s = 0; s < sids.size(); s++) {
            String sid = sids.get(s);
            ArrayList<TimePoint> tpList = this.values.get(sid);
            if (tpList == null || tpList.isEmpty()) continue;

            Color traceColor = isFieldSkipped() ? new Color(204, 0, 0) : getColor(s);
            double valMin = min;
            double valMax = max;
            if (getOptions().getOption(sid) != null && getOptions().getOption(sid).contains("alt")) {
                valMin = minAlt;
                valMax = maxAlt;
            }
            if (valMax <= valMin) valMax = valMin + 1.0;

            Polygon areaPoly = new Polygon();
            int prevX = -1;
            int prevY = -1;

            for (int i = 0; i < tpList.size(); i++) {
                TimePoint tp = tpList.get(i);
                if (tp == null || Double.isNaN(tp.value) || tp.date == 0) continue;
                long tpSec = tp.date / 1000;
                if (tpSec < startSec) continue;

                float xRatio = (float) (tpSec - startSec) / windowSec;
                if (xRatio < 0f) xRatio = 0f;
                if (xRatio > 1f) xRatio = 1f;
                int curX = graphX + (int) (graphW * xRatio);

                float yRatio = (float) ((tp.value - valMin) / (valMax - valMin));
                if (yRatio < 0f) yRatio = 0f;
                if (yRatio > 1f) yRatio = 1f;
                int curY = graphY + graphH - (int) (graphH * yRatio);

                if (areaPoly.size() == 0) {
                    areaPoly.addPoint(curX, graphY + graphH);
                }
                areaPoly.addPoint(curX, curY);

                // Draw thick glowing connecting line
                if (prevX >= 0 && prevY >= 0) {
                    g.setColor(traceColor);
                    g.setStrokeWidth(2.5f);
                    g.drawLine(prevX, prevY, curX, curY);
                }
                prevX = curX;
                prevY = curY;
            }

            // Complete area polygon
            if (areaPoly.size() > 1 && prevX >= 0) {
                areaPoly.addPoint(prevX, graphY + graphH);
                // Translucent fill under trace
                Color areaCol = new Color(30, traceColor.getRed(), traceColor.getGreen(), traceColor.getBlue());
                g.setColor(areaCol);
                g.fillPolygon(areaPoly);
            }
            g.setStrokeWidth(1f);
        }

        // Subtle bottom border baseline
        g.setColor(new Color(38, 51, 70));
        g.drawLine(graphX, graphY + graphH, graphX + graphW, graphY + graphH);
    }

    @Override
    public void onFieldUpdateEvent(Field field) {
        addValue(field.getSID(), field.getValue());

        super.onFieldUpdateEvent(field);
    }

    @Override
    public String dataToJson() {
        Gson gson = new Gson();
        return gson.toJson(values.clone());
    }

    @Override
    public void dataFromJson(String json) {
        Gson gson = new Gson();
        Type fooType = new TypeToken<HashMap<String, ArrayList<TimePoint>>>() {
        }.getType();

        values = gson.fromJson(json, fooType);
    }

    @Override
    public void loadValuesFromDatabase() {
        super.loadValuesFromDatabase();

        //values.clear(); // not needed as items will be replaced anyway!
        for (int s = 0; s < sids.size(); s++) {
            String sid = sids.get(s);
            values.put(sid, CanzeDataSource.getInstance().getData(sid));
        }
    }

    public void addField(String sid) {
        super.addField(sid);
        if (!values.containsKey(sid)) {
            values.put(sid, new ArrayList<TimePoint>());
        }
    }

    public void setValues(HashMap<String, ArrayList<TimePoint>> values) {
        sids.clear();

        for (String key : values.keySet()) {
            sids.add(key);
        }

        this.values = values;
    }


    public boolean isBackward() {
        return backward;
    }

    public void setBackward(boolean backward) {
        this.backward = backward;
    }

    private boolean testErrorPoint(double x, double y, String er) {
        double maxdelta = 2.0;
        if (Double.isNaN(x)) {
            // MainActivity.toast ("x is NaN, " + er);
            return true;
        }
        if (Double.isNaN(y)) {
            // MainActivity.toast ("y is NaN, " + er);
            return true;
        }
        if (x >= -maxdelta && x <= maxdelta && y >= -maxdelta && y <= maxdelta) {
            // MainActivity.toast ("x:" + x + ", y:" + y + ", " + er);
            return true;
        } else
            return false;
    }
}
