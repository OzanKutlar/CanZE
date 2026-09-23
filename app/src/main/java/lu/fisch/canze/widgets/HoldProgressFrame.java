/*
    CanZE
    Take a peek into your car's inner workings

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;

/**
 * FrameLayout that confirms a press-and-hold with a progress stroke running clockwise
 * around its edge, starting at the top-left corner. Releasing early retracts the stroke;
 * a completed hold gives a haptic tick and notifies the listener. A short tap is
 * forwarded as a normal click. Animation frames are only requested while the stroke
 * is visible.
 */
public class HoldProgressFrame extends FrameLayout {

    public interface OnHoldCompleteListener {
        void onHoldComplete();
    }

    public static final long HOLD_DURATION_MS = 1500L;
    private static final long RETRACT_DURATION_MS = 250L;
    private static final long FADE_DURATION_MS = 350L;
    private static final long TAP_MAX_MS = 300L;
    private static final float STROKE_DP = 4f;
    private static final float INSET_DP = 6f;
    private static final float CORNER_DP = 14f;
    private static final int STROKE_COLOR = Color.parseColor("#FFD600");

    private static final int STATE_IDLE = 0;
    private static final int STATE_HOLDING = 1;
    private static final int STATE_RETRACTING = 2;
    private static final int STATE_FADING = 3;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path segment = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final RectF arcRect = new RectF();
    private final Runnable completeTask = new Runnable() {
        @Override
        public void run() {
            notifyComplete();
        }
    };

    private OnHoldCompleteListener listener;
    private float density = 1f;
    private float outlineLength;
    private float progress;
    private float retractFrom;
    private float fadeAlpha = 1f;
    private int state = STATE_IDLE;
    private long stateStartMs;
    private long pressStartMs;

    public HoldProgressFrame(Context context) {
        super(context);
        init(context);
    }

    public HoldProgressFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public HoldProgressFrame(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(STROKE_DP * density);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setColor(STROKE_COLOR);
        setClickable(true);
        setFocusable(true);
    }

    public void setOnHoldCompleteListener(OnHoldCompleteListener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------ geometry

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildOutline(w, h);
    }

    /** Rounded rectangle traced clockwise from the top-left corner. */
    private void buildOutline(int w, int h) {
        outline.reset();
        outlineLength = 0f;
        float inset = INSET_DP * density;
        float l = inset;
        float t = inset;
        float r = w - inset;
        float b = h - inset;
        if (r - l < 2f || b - t < 2f) return;
        float rad = Math.max(1f, Math.min(CORNER_DP * density, Math.min(r - l, b - t) / 2f));
        outline.moveTo(l + rad, t);
        outline.lineTo(r - rad, t);
        arcRect.set(r - 2f * rad, t, r, t + 2f * rad);
        outline.arcTo(arcRect, 270f, 90f, false);
        outline.lineTo(r, b - rad);
        arcRect.set(r - 2f * rad, b - 2f * rad, r, b);
        outline.arcTo(arcRect, 0f, 90f, false);
        outline.lineTo(l + rad, b);
        arcRect.set(l, b - 2f * rad, l + 2f * rad, b);
        outline.arcTo(arcRect, 90f, 90f, false);
        outline.lineTo(l, t + rad);
        arcRect.set(l, t, l + 2f * rad, t + 2f * rad);
        outline.arcTo(arcRect, 180f, 90f, false);
        pathMeasure.setPath(outline, false);
        outlineLength = pathMeasure.getLength();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startHold();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!isInside(event)) releaseHold();
                return true;
            case MotionEvent.ACTION_UP:
                boolean shortTap = state == STATE_HOLDING
                        && SystemClock.uptimeMillis() - pressStartMs < TAP_MAX_MS;
                releaseHold();
                if (shortTap) performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseHold();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private boolean isInside(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        return x >= 0f && y >= 0f && x <= getWidth() && y <= getHeight();
    }

    /** Starts, or resumes from a retracting stroke. */
    private void startHold() {
        long now = SystemClock.uptimeMillis();
        advance(now);
        removeCallbacks(completeTask);
        float from = state == STATE_RETRACTING ? progress : 0f;
        state = STATE_HOLDING;
        fadeAlpha = 1f;
        progress = from;
        pressStartMs = now;
        stateStartMs = now - (long) (from * HOLD_DURATION_MS);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    private void releaseHold() {
        long now = SystemClock.uptimeMillis();
        advance(now);
        if (state != STATE_HOLDING) return;
        retractFrom = progress;
        state = STATE_RETRACTING;
        stateStartMs = now;
        ViewCompat.postInvalidateOnAnimation(this);
    }

    // ------------------------------------------------------------------ animation

    private void advance(long now) {
        float elapsed = Math.max(0L, now - stateStartMs);
        switch (state) {
            case STATE_HOLDING:
                progress = Math.min(1f, elapsed / HOLD_DURATION_MS);
                if (progress >= 1f) completeHold(now);
                break;
            case STATE_RETRACTING:
                progress = retractFrom * (1f - Math.min(1f, elapsed / RETRACT_DURATION_MS));
                if (progress <= 0f) enterIdle();
                break;
            case STATE_FADING:
                fadeAlpha = 1f - Math.min(1f, elapsed / FADE_DURATION_MS);
                if (fadeAlpha <= 0f) enterIdle();
                break;
            default:
                break;
        }
    }

    private void completeHold(long now) {
        progress = 1f;
        fadeAlpha = 1f;
        state = STATE_FADING;
        stateStartMs = now;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // Posted so the listener never runs inside a draw pass.
        post(completeTask);
    }

    private void enterIdle() {
        state = STATE_IDLE;
        progress = 0f;
        fadeAlpha = 1f;
    }

    private void notifyComplete() {
        OnHoldCompleteListener l = listener;
        if (l != null) l.onHoldComplete();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (state == STATE_IDLE) return;
        advance(SystemClock.uptimeMillis());
        drawStroke(canvas);
        if (state != STATE_IDLE) ViewCompat.postInvalidateOnAnimation(this);
    }

    private void drawStroke(Canvas canvas) {
        if (progress <= 0f || outlineLength <= 0f) return;
        segment.reset();
        if (!pathMeasure.getSegment(0f, outlineLength * progress, segment, true)) return;
        // Workaround for getSegment output not rendering on some hardware-accelerated versions.
        segment.rLineTo(0f, 0f);
        strokePaint.setAlpha(Math.round(255f * fadeAlpha));
        canvas.drawPath(segment, strokePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(completeTask);
        enterIdle();
        super.onDetachedFromWindow();
    }
}
