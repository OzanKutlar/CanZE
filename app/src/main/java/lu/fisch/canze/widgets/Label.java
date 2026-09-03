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
        // modern dark card background
        g.setColor(getBackground());
        g.fillRect(x, y, width, height);

        // subtle card border
        g.setColor(new Color(38, 51, 70));
        g.drawRect(x, y, width, height);

        // draw the value
        if(showValue) {
            if(field !=null)
            {
                // get text
                String text = String.format("%." + String.valueOf(field.getDecimals()) + "f", field.getValue()).trim();

                int th, tw;
                //if(textSize==-1) {
                    // init
                    textSize = 10;
                    // find out what the biggest text size could be
                    do {
                        g.setTextSize(textSize);
                        tw = g.stringWidth(text);
                        th = g.stringHeight(text);
                        textSize++;
                    } while (th < getHeight() * 0.9 && tw < getWidth() * 0.9);

                    textSize--;
                //}
                g.setTextSize(textSize);
                tw = g.stringWidth(text);
                th = g.stringHeight(text);

                int tx = getX()+getWidth()/2-tw/2;
                int ty = getY()+getHeight()/2+th/2;
                // Glowing neon green for healthy state
                g.setColor(isFieldSkipped() ? new Color(204, 0, 0) : new Color(0, 230, 118));
                g.drawString(text, tx, ty);
            }
        }

        // draw the title
        if(title!=null && !title.equals(""))
        {
            g.setColor(getTitleColor());
            g.setTextSize(16);
            int tx = getX()+8;
            int ty = getY()+g.stringHeight(title)+8;
            g.drawString(title,tx,ty);
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
