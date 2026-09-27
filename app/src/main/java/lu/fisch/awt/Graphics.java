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

package lu.fisch.awt;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Thin AWT-style wrapper around an Android canvas.
 *
 * One instance can serve every frame through {@link #beginFrame(Canvas)}: the paint,
 * rectangles and path are recycled, so the drawing hot path allocates nothing.
 * Not thread-safe; use one instance per render thread.
 */
public class Graphics
{
    private static final float DEFAULT_TEXT_SIZE = 12f;

    private Canvas canvas;
    private Color color = Color.BLACK;
    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    private final Rect textBounds = new Rect();
    private final Path path = new Path();

    public Graphics(Canvas canvas)
    {
        this.canvas = canvas;
        resetPaint();
    }

    /** Re-targets this instance at a new canvas and restores the default drawing state. */
    public void beginFrame(Canvas canvas)
    {
        this.canvas = canvas;
        this.color = Color.BLACK;
        resetPaint();
    }

    private void resetPaint()
    {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setDither(true);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTextSize(DEFAULT_TEXT_SIZE);
    }

    private void prepare(Paint.Style style)
    {
        paint.setColor(color.getAndroidColor());
        paint.setStyle(style);
    }

    public void setStrokeWidth(float width)
    {
        paint.setStrokeWidth(width);
    }

    public void fillRoundRect(int x, int y, int width, int height, int rx, int ry)
    {
        fillRoundRect((float) x, (float) y, (float) width, (float) height, (float) rx, (float) ry);
    }

    public void fillRoundRect(float x, float y, float width, float height, float rx, float ry)
    {
        if (canvas == null) return;
        prepare(Paint.Style.FILL);
        rect.set(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, rx, ry, paint);
    }

    public Canvas getCanvas()
    {
        return canvas;
    }

    public int getWidth()
    {
        return canvas == null ? 0 : canvas.getWidth();
    }

    public int getHeight()
    {
        return canvas == null ? 0 : canvas.getHeight();
    }

    public void fillOval(int x, int y, int width, int height)
    {
        fillOval((float) x, (float) y, (float) width, (float) height);
    }

    public void fillOval(float x, float y, float width, float height)
    {
        if (canvas == null) return;
        prepare(Paint.Style.FILL);
        rect.set(x, y, x + width, y + height);
        canvas.drawOval(rect, paint);
    }

    public void drawOval(int x, int y, int width, int height)
    {
        drawOval((float) x, (float) y, (float) width, (float) height);
    }

    public void drawOval(float x, float y, float width, float height)
    {
        if (canvas == null) return;
        prepare(Paint.Style.STROKE);
        rect.set(x, y, x + width, y + height);
        canvas.drawOval(rect, paint);
    }

    public void fillRect(int x, int y, int width, int height)
    {
        fillRect((float) x, (float) y, (float) width, (float) height);
    }

    public void fillRect(float x, float y, float width, float height)
    {
        if (canvas == null) return;
        prepare(Paint.Style.FILL);
        rect.set(x, y, x + width, y + height);
        canvas.drawRect(rect, paint);
    }

    public void drawRect(int x, int y, int width, int height)
    {
        drawRect((float) x, (float) y, (float) width, (float) height);
    }

    public void drawRect(float x, float y, float width, float height)
    {
        if (canvas == null) return;
        prepare(Paint.Style.STROKE);
        rect.set(x, y, x + width, y + height);
        canvas.drawRect(rect, paint);
    }

    public void drawRoundRect(int x, int y, int width, int height, int rx, int ry)
    {
        drawRoundRect((float) x, (float) y, (float) width, (float) height, (float) rx, (float) ry);
    }

    public void drawRoundRect(float x, float y, float width, float height, float rx, float ry)
    {
        if (canvas == null) return;
        prepare(Paint.Style.STROKE);
        rect.set(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, rx, ry, paint);
    }

    public void drawLine(int x1, int y1, int x2, int y2)
    {
        drawLine((float) x1, (float) y1, (float) x2, (float) y2);
    }

    public void drawLine(float x1, float y1, float x2, float y2)
    {
        if (canvas == null) return;
        prepare(Paint.Style.STROKE);
        canvas.drawLine(x1, y1, x2, y2, paint);
    }

    public void drawString(String text, int x, int y)
    {
        drawString(text, (float) x, (float) y);
    }

    public void drawString(String text, float x, float y)
    {
        if (canvas == null || text == null) return;
        prepare(Paint.Style.FILL);
        canvas.drawText(text, x, y, paint);
    }

    public void drawString(String text, int x, int y, int size)
    {
        drawString(text, (float) x, (float) y, (float) size);
    }

    public void drawString(String text, float x, float y, float size)
    {
        paint.setTextSize(size);
        drawString(text, x, y);
    }

    public void setColor(Color color)
    {
        if (color != null) this.color = color;
    }

    public Color getColor(Color color)
    {
        return color;
    }

    public void drawPolygon(Polygon p)
    {
        if (!buildPath(p)) return;
        prepare(Paint.Style.STROKE);
        canvas.drawPath(path, paint);
    }

    public void fillPolygon(Polygon p)
    {
        if (!buildPath(p)) return;
        prepare(Paint.Style.FILL_AND_STROKE);
        canvas.drawPath(path, paint);
    }

    /** Loads the polygon into the shared path; false if there is nothing to draw. */
    private boolean buildPath(Polygon p)
    {
        if (canvas == null || p == null || p.size() == 0) return false;
        path.reset();
        Point last = p.get(p.size() - 1);
        path.moveTo(last.x, last.y);
        for (int i = 0; i < p.size(); i++)
        {
            Point point = p.get(i);
            path.lineTo(point.x, point.y);
        }
        return true;
    }

    public void setGradient(int x1, int y1, int x2, int y2, int[] colors, float[] spacings)
    {
        LinearGradient lg = new LinearGradient(x1, y1, x2, y2,
                colors,
                spacings,
                Shader.TileMode.REPEAT);
        paint.setShader(lg);
    }

    public void clearGradient()
    {
        paint.setShader(null);
    }

    public void rotate(float degrees, float cx, float cy)
    {
        if (canvas == null) return;
        canvas.rotate(degrees, cx, cy);
    }

    /* *********************************
     * String things ...
     * *********************************/

    public void setTextSize(float textSize)
    {
        paint.setTextSize(textSize);
    }

    public float getTextSize()
    {
        return paint.getTextSize();
    }

    public int stringWidth(String _string)
    {
        if (_string == null || _string.isEmpty()) return 0;
        paint.getTextBounds(_string, 0, _string.length(), textBounds);
        return textBounds.width();
    }

    public int stringHeight(String _string)
    {
        String sample = (_string == null || _string.isEmpty()) ? "O" : _string;
        paint.getTextBounds(sample, 0, sample.length(), textBounds);
        return textBounds.height();
    }

    public Paint getPaint() {
        return paint;
    }
}
