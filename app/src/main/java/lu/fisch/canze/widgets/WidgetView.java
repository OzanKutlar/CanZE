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

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

import lu.fisch.awt.Color;
import lu.fisch.awt.Graphics;
import lu.fisch.canze.R;
import lu.fisch.canze.activities.CanzeActivity;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.activities.WidgetActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.classes.ColorRanges;
import lu.fisch.canze.classes.Intervals;
import lu.fisch.canze.classes.Options;
import lu.fisch.canze.interfaces.DrawSurfaceInterface;

public class WidgetView extends SurfaceView implements DrawSurfaceInterface, SurfaceHolder.Callback, View.OnTouchListener {

    /** how long surfaceDestroyed waits for an in-flight frame before giving up */
    private static final long RENDER_STOP_TIMEOUT_MS = 500;
    /** same white clear the old DrawThread did before every frame */
    private static final int CLEAR_COLOR = 0xFFFFFFFF;

    // your application certainly needs some data model
    private Drawable drawable = null;
    private String fieldSID = "";

    private boolean clickable = true;

    protected boolean landscape = true;

    private CanzeActivity canzeActivity = null;

    // for data sharing
    public static Drawable selectedDrawable = null;

    /* ---- rendering: one looper thread per surface, alive from surfaceCreated to surfaceDestroyed ---- */

    private final Object renderLock = new Object();
    private HandlerThread renderThread = null; // guarded by renderLock
    private Handler renderHandler = null;      // guarded by renderLock
    /** true while a frame is posted but has not started yet; further repaints fold into it */
    private final AtomicBoolean renderQueued = new AtomicBoolean(false);
    private volatile boolean surfaceReady = false;

    // only ever touched on the render thread
    private final Paint clearPaint = new Paint();
    private final PaintFlagsDrawFilter drawFilter = new PaintFlagsDrawFilter(1, Paint.ANTI_ALIAS_FLAG);
    private final Graphics graphics = new Graphics(null);

    {
        clearPaint.setColor(CLEAR_COLOR);
    }

    private final Runnable renderTask = new Runnable() {
        @Override
        public void run() {
            // clear first: an update arriving while we draw must queue another frame
            renderQueued.set(false);
            renderFrame();
        }
    };

    private final Runnable loadTask = new Runnable() {
        @Override
        public void run() {
            loadFromDatabase();
            renderFrame();
        }
    };

    public void setDrawable(Drawable drawable)
    {
        if (drawable == null) return;
        this.drawable = drawable;
        drawable.setDrawSurface(this);
        repaint();
    }

    public Drawable getDrawable()
    {
        return drawable;
    }

    public WidgetView(Context context) {
        super(context);
        init(context, null);
        setOnTouchListener(this);
    }

    public WidgetView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
        setOnTouchListener(this);
    }

    public WidgetView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
        setOnTouchListener(this);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        landscape = (right - left) > (bottom - top);
        if (changed && drawable != null) drawable.onLayout(landscape);
    }

    public void reset()
    {
        if (drawable == null) return;
        drawable.reset();
        repaint();
    }

    public String extractCarValue(String[] values)
    {
        // the first value is the default one
        String carValue = values[0];

        for (int i = 1; i < values.length; i++)
            if (values[i].startsWith(String.valueOf(MainActivity.car) + ":"))
                carValue = values[i].split(":")[1];

        return carValue;
    }

    public void init(final Context context, AttributeSet attrs)
    {
        // register our interest in hearing about changes to our surface
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
        // make sure we get key events
        setFocusable(true);

        // read attributes
        if (attrs != null)
        {
            try
            {
                // create configured widget
                String[] widgets = {"Tacho", "Kompass", "Bar", "BatteryBar", "Plotter", "Label", "Timeplot", "BarGraph"};
                TypedArray attributes = context.getTheme().obtainStyledAttributes(attrs, R.styleable.WidgetView, 0, 0);
                int widgetIndex = attributes.getInt(R.styleable.WidgetView_widget, 0);
                if (widgetIndex < widgets.length)
                {
                    String widget = widgets[widgetIndex];
                    Class clazz = Class.forName("lu.fisch.canze.widgets." + widget);
                    Constructor<?> constructor = clazz.getConstructor();
                    drawable = (Drawable) constructor.newInstance();
                    drawable.setDrawSurface(WidgetView.this);
                    // apply attributes
                    drawable.setMin(Integer.valueOf(extractCarValue(attributes.getString(R.styleable.WidgetView_min).split(","))));
                    drawable.setMax(Integer.valueOf(extractCarValue(attributes.getString(R.styleable.WidgetView_max).split(","))));
                    drawable.setMajorTicks(Integer.valueOf(extractCarValue(attributes.getString(R.styleable.WidgetView_majorTicks).split(","))));
                    drawable.setMinorTicks(Integer.valueOf(extractCarValue(attributes.getString(R.styleable.WidgetView_minorTicks).split(","))));
                    drawable.setTitle(attributes.getString(R.styleable.WidgetView_text));
                    drawable.setShowLabels(attributes.getBoolean(R.styleable.WidgetView_showLabels, true));
                    drawable.setShowValue(attributes.getBoolean(R.styleable.WidgetView_showValue, true));
                    drawable.setInverted(attributes.getBoolean(R.styleable.WidgetView_isInverted, false));

                    String colorRangesJson = attributes.getString(R.styleable.WidgetView_colorRanges);
                    if (colorRangesJson != null && !colorRangesJson.trim().isEmpty())
                        drawable.setColorRanges(new ColorRanges(colorRangesJson.replace("'", "\"")));

                    String foreground = attributes.getString(R.styleable.WidgetView_foregroundColor);
                    if (foreground != null && !foreground.isEmpty())
                        drawable.setForeground(Color.decode(foreground));

                    String background = attributes.getString(R.styleable.WidgetView_backgroundColor);
                    if (background != null && !background.isEmpty())
                        drawable.setBackground(Color.decode(background));

                    String intermediate = attributes.getString(R.styleable.WidgetView_intermediateColor);
                    if (intermediate != null && !intermediate.isEmpty())
                        drawable.setIntermediate(Color.decode(intermediate));

                    String titleColor = attributes.getString(R.styleable.WidgetView_titleColor);
                    if (titleColor != null && !titleColor.isEmpty())
                        drawable.setTitleColor(Color.decode(titleColor));

                    String intervalJson = attributes.getString(R.styleable.WidgetView_intervals);
                    if (intervalJson != null && !intervalJson.trim().isEmpty())
                        drawable.setIntervals(new Intervals(intervalJson.replace("'", "\"")));

                    String optionsJson = attributes.getString(R.styleable.WidgetView_options);
                    if (optionsJson != null && !optionsJson.trim().isEmpty())
                        drawable.setOptions(new Options(optionsJson.replace("'", "\"")));

                    String minAlt = attributes.getString(R.styleable.WidgetView_minAlt);
                    if (minAlt != null && !minAlt.trim().isEmpty())
                        drawable.setMinAlt(Integer.valueOf(extractCarValue(minAlt.split(","))));

                    String maxAlt = attributes.getString(R.styleable.WidgetView_maxAlt);
                    if (maxAlt != null && !maxAlt.trim().isEmpty())
                        drawable.setMaxAlt(Integer.valueOf(extractCarValue(maxAlt.split(","))));

                    drawable.setTimeScale(attributes.getInt(R.styleable.WidgetView_timeScale, 1));

                    fieldSID = attributes.getString(R.styleable.WidgetView_fieldSID);
                    if (fieldSID != null) {
                        String[] sids = fieldSID.split(",");
                        for (int s = 0; s < sids.length; s++) {
                            Field field = MainActivity.fields.getBySID(sids[s]);
                            if (field == null) {
                                MainActivity.debug("WidgetView: init: Field with SID <" + sids[s] + "> (index <" + s + "> in <" + R.styleable.WidgetView_text + "> not found!");
                            } else {
                                // add field to list of registered sids for this widget
                                drawable.addField(field.getSID());
                                // add listener
                                field.addListener(drawable);
                                // add filter to reader
                                if (MainActivity.device != null) {
                                    int interval = drawable.getIntervals().getInterval(field.getSID());
                                    if (interval == -1)
                                        MainActivity.device.addActivityField(field, 0); // JM added 0, see that method for rationale
                                    else
                                        MainActivity.device.addActivityField(field, interval);
                                }
                            }
                        }
                    }

                    if (MainActivity.milesMode) drawable.setTitle(drawable.getTitle().replace("km", "mi"));
                }
                else
                {
                    MainActivity.debug("WidgetView: init: WidgetIndex " + widgetIndex + " is wrong!? Not registered in <WidgetView>?");
                }
            }
            catch (Exception e)
            {
                MainActivity.debug("WidgetView: init failed: " + e);
                e.printStackTrace();
            }
        }
    }

    private boolean motionMove = false;
    private float downX, downY;

    @Override
    public boolean onTouch(View v, MotionEvent event)
    {
        int maskedAction = event.getActionMasked();

        switch (maskedAction) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                downX = event.getX();
                downY = event.getY();
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(downX - event.getX()) + Math.abs(downY - event.getY()) > 20) {
                    motionMove = true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (!motionMove && clickable && MainActivity.isSafe()) {
                    if (canzeActivity != null) canzeActivity.setWidgetClicked(true);
                    Intent intent = new Intent(this.getContext(), WidgetActivity.class);
                    selectedDrawable = this.getDrawable();
                    this.getContext().startActivity(intent);
                }
                motionMove = false;
                break;
            }
            default:
                break;
        }

        return true;
    }

    /* ---------------- surface lifecycle ---------------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        surfaceReady = true;
        startRenderThread();
        // restore history on the render thread, then draw it
        postRender(loadTask);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        repaint();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
        // no frame may be drawn once this returns
        surfaceReady = false;
        stopRenderThread();
    }

    private void startRenderThread() {
        synchronized (renderLock) {
            if (renderThread != null) return;
            HandlerThread thread = new HandlerThread("WidgetRender", Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            renderThread = thread;
            renderHandler = new Handler(thread.getLooper());
        }
    }

    private void stopRenderThread() {
        HandlerThread thread;
        synchronized (renderLock) {
            thread = renderThread;
            if (renderHandler != null) renderHandler.removeCallbacksAndMessages(null);
            renderThread = null;
            renderHandler = null;
        }
        renderQueued.set(false);
        if (thread == null) return;

        thread.quit();
        try {
            thread.join(RENDER_STOP_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive())
            MainActivity.debug("WidgetView: render thread still busy after " + RENDER_STOP_TIMEOUT_MS + " ms");
    }

    private boolean postRender(Runnable task) {
        synchronized (renderLock) {
            return renderHandler != null && renderHandler.post(task);
        }
    }

    /* ---------------- rendering ---------------- */

    /**
     * Request a frame. Safe from any thread; any number of calls before the frame starts
     * collapse into a single draw of the latest data.
     */
    public void repaint()
    {
        if (!renderQueued.compareAndSet(false, true)) return;
        if (!postRender(renderTask)) renderQueued.set(false);
    }

    /** @deprecated kept for source compatibility; rendering always happens on the render thread now */
    @Deprecated
    public void repaint2() {
        repaint();
    }

    private void loadFromDatabase() {
        Drawable current = drawable;
        if (current == null) return;
        try {
            current.loadValuesFromDatabase();
        } catch (RuntimeException e) {
            MainActivity.debug("WidgetView: loading history failed: " + e);
        }
    }

    private void renderFrame() {
        Drawable current = drawable;
        if (current == null || !surfaceReady) return;

        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        try {
            canvas = holder.lockCanvas();
            if (canvas == null) return;
            drawFrame(canvas, current);
        } catch (RuntimeException e) {
            MainActivity.debug("WidgetView: frame failed: " + e);
        } finally {
            if (canvas != null) unlockQuietly(holder, canvas);
        }
    }

    private void drawFrame(Canvas canvas, Drawable current) {
        canvas.setDrawFilter(drawFilter);
        canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), clearPaint);
        current.setWidth(canvas.getWidth());
        current.setHeight(canvas.getHeight());
        graphics.beginFrame(canvas);
        current.draw(graphics);
    }

    private static void unlockQuietly(SurfaceHolder holder, Canvas canvas) {
        try {
            holder.unlockCanvasAndPost(canvas);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // surface was released while we were drawing
            MainActivity.debug("WidgetView: unlockCanvasAndPost failed: " + e);
        }
    }

    /* *************************************
     * Getter & Setter
     * *************************************/

    public String getFieldSID()
    {
        return fieldSID;
    }

    @Override
    public boolean isClickable() {
        return clickable;
    }

    @Override
    public void setClickable(boolean clickable) {
        this.clickable = clickable;
    }

    public void setFieldSID(String fieldSID) {
        this.fieldSID = fieldSID;
    }

    public void setCanzeActivity(CanzeActivity canzeActivity) {
        this.canzeActivity = canzeActivity;
    }
}
