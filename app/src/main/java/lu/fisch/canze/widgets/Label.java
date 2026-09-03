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
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

/**
 * Created by robertfisch on 04.10.2015.
 */
public class Label extends Drawable {

    private int textSize = -1;

    public Label() {
        super();
    }

    public Label(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        // test
    }

    public Label(DrawSurfaceInterface drawSurface, int x, int y, int width, int height) {
        this.drawSurface=drawSurface;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    @Override
    public void onLayout(boolean landscape)
    {
        textSize=-1;
    }

    @Override
    public void reset()
    {
        textSize=-1;
    }

    @Override
    public void draw(Graphics g) {
        // Draw sleek modern card container window
        drawModernWindowCard(g, 4);

        int inset = 6;
        // Header: Category Title with glowing dot
        int titleX = x + inset + 14;
        int titleY = y + inset + 22;
        g.setTextSize(11);
        Color dotCol = isFieldSkipped() ? new Color(204, 0, 0) : new Color(0, 230, 118);
        g.setColor(dotCol);
        g.fillRoundRect(titleX, titleY - 8, 8, 8, 4, 4);

        g.setColor(new Color(138, 153, 173));
        String cleanTitle = (title != null && !title.isEmpty()) ? title.toUpperCase() : "STATE OF HEALTH";
        g.drawString(cleanTitle, titleX + 14, titleY);

        // Hero Big Value in center
        if (showValue && field != null) {
            double val = field.getValue();
            String numStr = Double.isNaN(val) ? "--" : String.format(java.util.Locale.US, "%." + field.getDecimals() + "f", val);
            String unitStr = "%";

            // Dynamic modern typography fitting
            g.setTextSize(Math.min(42, (int) (height * 0.38)));
            int numW = g.stringWidth(numStr);
            int numH = g.stringHeight(numStr);

            int cx = x + width / 2;
            int cy = y + height / 2 + 4;

            Color heroColor = isFieldSkipped() ? new Color(204, 0, 0) : (val < 75.0 ? new Color(255, 179, 0) : new Color(0, 230, 118));
            g.setColor(heroColor);
            g.drawString(numStr, cx - numW / 2 - 10, cy + numH / 2);

            g.setTextSize(16);
            g.setColor(new Color(138, 153, 173));
            g.drawString(unitStr, cx + numW / 2 - 4, cy + numH / 2);

            // Modern status pill at bottom center
            String statusText = isFieldSkipped() ? "SKIPPED" : (val >= 90.0 ? "EXCELLENT" : (val >= 80.0 ? "GOOD" : "CHECK PACK"));
            g.setTextSize(9);
            int sW = g.stringWidth(statusText);
            int pW = sW + 16;
            int pH = 16;
            int pX = cx - pW / 2;
            int pY = y + height - inset - 22;

            g.setColor(new Color(24, 33, 49));
            g.fillRoundRect(pX, pY, pW, pH, 8, 8);
            g.setColor(new Color(38, 51, 74));
            g.drawRoundRect(pX, pY, pW, pH, 8, 8);

            g.setColor(heroColor);
            g.drawString(statusText, pX + 8, pY + 11);
        }
    }

    /* --------------------------------
     * Serialization
     \ ------------------------------ */

    @Override
    public String dataToJson() {
        return "";
    }

    @Override
    public void dataFromJson(String json) {
    }

}
