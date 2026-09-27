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

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 * Created by robertfisch on 26.10.2015.
 *
 * Data handling and locking are inherited from {@link Plotter}; only the car filter
 * and the drawing differ.
 */
public class BarGraph extends Plotter {

    /** upper bound on tick iterations, whatever min/max/tick attributes a layout sets */
    private static final int MAX_TICKS = 1000;

    public BarGraph() {
        super();
    }

    public BarGraph(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    public BarGraph(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        super(drawSurface, x, y, width, height);
    }

    @Override
    protected boolean acceptsCar(Field field) {
        return field.getCar() == 0 || field.getCar() == MainActivity.car;
    }

    @Override
    public void draw(Graphics g) {
        if (g == null) return;

        // black border
        g.setColor(Color.BLACK);
        g.drawRect(x, y, width, height);

        int barWidth = width - Math.max(g.stringWidth(min + ""), g.stringWidth(max + "")) - 10 - 10;

        drawTicks(g, barWidth);

        // graph frame
        g.drawRect(x + width - barWidth, y, barWidth, height);
        drawMarkers(g, barWidth);
        drawTitle(g, barWidth);
    }

    private void drawTicks(Graphics g, int barWidth) {
        if (minorTicks <= 0 && majorTicks <= 0) return;

        int toTicks = minorTicks;
        if (toTicks == 0) toTicks = majorTicks;
        double accel = (double) height / ((max - min) / (double) toTicks);
        // a non-positive or non-finite step would never terminate the loop below
        if (Double.isNaN(accel) || Double.isInfinite(accel) || accel <= 0) return;

        double ax, ay, bx, by;
        int actual = min;
        int sum = 0;
        int iterations = 0;
        for (double i = height; i >= 0 && iterations < MAX_TICKS; i -= accel, iterations++)
        {
            if (minorTicks > 0)
            {
                g.setColor(Color.GRAY);
                ax = x + width - barWidth - 5;
                ay = y + i;
                bx = x + width - barWidth;
                by = y + i;
                g.drawLine((int) ax, (int) ay, (int) bx, (int) by);
            }
            // draw majorTicks
            if (majorTicks != 0 && sum % majorTicks == 0) {
                if (majorTicks > 0)
                {
                    g.setColor(Color.GRAY_LIGHT);
                    ax = x + width - barWidth - 10;
                    ay = y + i;
                    bx = x + width;
                    by = y + i;
                    g.drawLine((int) ax, (int) ay, (int) bx, (int) by);

                    g.setColor(Color.GRAY);
                    ax = x + width - barWidth - 10;
                    ay = y + i;
                    bx = x + width - barWidth;
                    by = y + i;
                    g.drawLine((int) ax, (int) ay, (int) bx, (int) by);
                }

                // draw String
                if (showLabels)
                {
                    g.setColor(Color.GRAY);
                    String text = (actual) + "";
                    double sw = g.stringWidth(text);
                    bx = x + width - barWidth - 16 - sw;
                    by = y + i;
                    g.drawString(text, (int) (bx), (int) (by + g.stringHeight(text) * (1 - i / height)));
                }

                actual += majorTicks;
            }
            sum += minorTicks;
        }
    }

    private void drawMarkers(Graphics g, int barWidth) {
        int count = snapshotValues();
        if (count == 0) return;

        double range = getMax() - getMin();
        if (range == 0) range = 1;
        double w = (double) barWidth / count;
        double h = (double) getHeight() / range;
        int padding = 2;

        g.setColor(Color.RED);
        for (int i = 0; i < count; i++)
        {
            double v = drawValues[i];
            if (Double.isNaN(v)) continue;
            double mx = i * w;
            double my = getHeight() - (v - getMin()) * h;
            g.fillRect(
                    (float) (getX() + getWidth() - barWidth + (int) mx) + padding,
                    (float) (getY() + (int) my),
                    (float) w - 2 * padding,
                    2f
            );
        }
    }

    private void drawTitle(Graphics g, int barWidth) {
        if (title == null || title.equals("")) return;
        g.setColor(Color.BLUE);
        g.setTextSize(20);
        int th = g.stringHeight(title);
        int tx = getX() + width - barWidth + 8;
        int ty = getY() + th + 4;
        g.drawString(title, tx, ty);
    }
}
