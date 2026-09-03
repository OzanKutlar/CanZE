/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.
*/

package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Modern cyber-cockpit Tachometer gauge displaying real-time engine RPM,
 * redline warning arc, and virtual transmission gear indicator.
 */
public class RpmGaugeView extends View {

    private static final float MAX_RPM = 7000f;
    private static final float REDLINE_RPM = 5500f;
    private static final float START_ANGLE = 140f;
    private static final float SWEEP_TOTAL = 260f;

    private float currentRpm = 800f;
    private int currentGear = 1;

    private Paint trackPaint;
    private Paint progressPaint;
    private Paint redlinePaint;
    private Paint textPaint;
    private Paint subTextPaint;
    private Paint tickPaint;
    private RectF arcBounds = new RectF();

    public RpmGaugeView(Context context) {
        super(context);
        init();
    }

    public RpmGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RpmGaugeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0xFF1E283A); // #1E283A dark track
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setColor(0xFF00E5FF); // #00E5FF Neon Cyan
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        redlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        redlinePaint.setStyle(Paint.Style.STROKE);
        redlinePaint.setColor(0xFFFF1744); // #FF1744 Redline Crimson
        redlinePaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f);
        tickPaint.setColor(0xFF384964);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setFakeBoldText(true);

        subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        subTextPaint.setColor(0xFF8A99AD);
    }

    public void setRpmAndGear(float rpm, int gear) {
        this.currentRpm = Math.max(0f, Math.min(MAX_RPM, rpm));
        this.currentGear = gear;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w / 2f;
        float cy = h * 0.52f;
        float strokeW = Math.min(w, h) * 0.08f;
        float radius = (Math.min(w, h) - strokeW * 2.5f) / 2f;

        trackPaint.setStrokeWidth(strokeW);
        progressPaint.setStrokeWidth(strokeW);
        redlinePaint.setStrokeWidth(strokeW * 1.15f);

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 1. Background Arc Track
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_TOTAL, false, trackPaint);

        // 2. Redline Zone (5,500 to 7,000 RPM)
        float redlineStartSweep = (REDLINE_RPM / MAX_RPM) * SWEEP_TOTAL;
        float redlineSweep = ((MAX_RPM - REDLINE_RPM) / MAX_RPM) * SWEEP_TOTAL;
        canvas.drawArc(arcBounds, START_ANGLE + redlineStartSweep, redlineSweep, false, redlinePaint);

        // 3. Active RPM Sweep
        float rpmFraction = currentRpm / MAX_RPM;
        float activeSweep = rpmFraction * SWEEP_TOTAL;
        if (currentRpm >= REDLINE_RPM) {
            progressPaint.setColor(0xFFFF1744); // Flash red in redline
        } else {
            progressPaint.setColor(0xFF00E5FF); // Neon cyan
        }
        canvas.drawArc(arcBounds, START_ANGLE, activeSweep, false, progressPaint);

        // 4. Tick Marks & Numbers (0 to 7 x1000)
        float tickInnerR = radius - strokeW * 0.8f;
        float tickOuterR = radius - strokeW * 0.35f;
        for (int i = 0; i <= 7; i++) {
            float tickFrac = (float) i / 7f;
            float angle = (float) Math.toRadians(START_ANGLE + tickFrac * SWEEP_TOTAL);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);

            float x1 = cx + tickInnerR * cos;
            float y1 = cy + tickInnerR * sin;
            float x2 = cx + tickOuterR * cos;
            float y2 = cy + tickOuterR * sin;

            tickPaint.setColor(i >= 6 ? 0xFFFF1744 : 0xFF384964);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // 5. Center Digital Readout: Big RPM Number
        textPaint.setTextSize(radius * 0.38f);
        textPaint.setColor(currentRpm >= REDLINE_RPM ? 0xFFFF1744 : 0xFFFFFFFF);
        canvas.drawText(String.format(Locale.getDefault(), "%.0f", currentRpm), cx, cy - radius * 0.05f, textPaint);

        // 6. Subtext: "RPM x 1000"
        subTextPaint.setTextSize(radius * 0.13f);
        canvas.drawText("ENGINE RPM", cx, cy - radius * 0.28f, subTextPaint);

        // 7. Gear Badge: Pill at Bottom Center
        String gearText = currentGear == 0 ? "NEUTRAL" : "GEAR " + currentGear;
        subTextPaint.setTextSize(radius * 0.16f);
        subTextPaint.setFakeBoldText(true);
        subTextPaint.setColor(currentGear == 0 ? 0xFFFFD600 : 0xFF00E5FF);
        canvas.drawText(gearText, cx, cy + radius * 0.32f, subTextPaint);
        subTextPaint.setFakeBoldText(false);
    }
}
