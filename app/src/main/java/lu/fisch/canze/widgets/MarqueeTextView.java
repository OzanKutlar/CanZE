package lu.fisch.canze.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;

/**
 * Single-line label that scrolls text which does not fit: a short pause, a smooth scroll
 * to the left, and a second copy that follows after a gap, so the text keeps flowing in
 * from the right. Unlike TextView's marquee it only restarts when the text changes,
 * never because something else in the layout was re-measured.
 *
 * Reads android:textSize, android:textStyle and android:textColor from XML.
 */
public class MarqueeTextView extends View {

    // Must stay sorted by attribute id: textSize < textStyle < textColor.
    private static final int[] TEXT_ATTRS = {
            android.R.attr.textSize,
            android.R.attr.textStyle,
            android.R.attr.textColor
    };
    private static final int ATTR_SIZE = 0;
    private static final int ATTR_STYLE = 1;
    private static final int ATTR_COLOR = 2;
    private static final int STYLE_BOLD = 1;

    private static final float DEFAULT_TEXT_SP = 22f;
    private static final float SPEED_DP_PER_SECOND = 30f;
    private static final float GAP_DP = 56f;
    private static final float FADE_DP = 20f;
    private static final long START_PAUSE_NANOS = 1500000000L;
    private static final long NANOS_PER_MILLI = 1000000L;
    private static final float NANOS_PER_SECOND = 1e9f;
    private static final float MAX_FRAME_SECONDS = 0.1f;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable frameTask = new Runnable() {
        @Override
        public void run() {
            frameScheduled = false;
            onFrame();
        }
    };

    private String text = "";
    private float textWidth;
    private float ascent;
    private float descent;
    private float gapPx;
    private float speedPx;
    private float offset;
    private boolean overflowing;
    private boolean frameScheduled;
    private long lastFrameNanos;
    private long pauseUntilNanos;

    public MarqueeTextView(Context context) {
        super(context);
        init(null);
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        float density = getResources().getDisplayMetrics().density;
        gapPx = GAP_DP * density;
        speedPx = SPEED_DP_PER_SECOND * density;
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP,
                getResources().getDisplayMetrics());
        int style = Typeface.NORMAL;
        int color = Color.WHITE;
        if (attrs != null) {
            TypedArray values = getContext().obtainStyledAttributes(attrs, TEXT_ATTRS);
            try {
                size = values.getDimension(ATTR_SIZE, size);
                style = values.getInt(ATTR_STYLE, style);
                color = values.getColor(ATTR_COLOR, color);
            } finally {
                values.recycle();
            }
        }
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTypeface((style & STYLE_BOLD) != 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        ascent = metrics.ascent;
        descent = metrics.descent;
        setHorizontalFadingEdgeEnabled(true);
        setFadingEdgeLength(Math.round(FADE_DP * density));
    }

    // ------------------------------------------------------------------ public API

    /** Changing the text restarts the scroll; setting the same text is a no-op. */
    public void setText(CharSequence value) {
        String next = value == null ? "" : value.toString();
        if (next.equals(text)) return;
        text = next;
        textWidth = paint.measureText(text);
        setContentDescription(text);
        if (isWrapWidth()) requestLayout();
        refreshOverflow(true);
    }

    public CharSequence getText() {
        return text;
    }

    private boolean isWrapWidth() {
        ViewGroup.LayoutParams params = getLayoutParams();
        return params != null && params.width == ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int desiredWidth = (int) Math.ceil(textWidth) + getPaddingLeft() + getPaddingRight();
        int desiredHeight = (int) Math.ceil(descent - ascent) + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(desiredWidth, widthSpec), resolveSize(desiredHeight, heightSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refreshOverflow(false);
    }

    /** Only resets the scroll when the text changed or it started/stopped overflowing. */
    private void refreshOverflow(boolean restart) {
        float available = getWidth() - getPaddingLeft() - getPaddingRight();
        boolean overflow = available > 0f && textWidth > available;
        if (restart || overflow != overflowing) {
            overflowing = overflow;
            offset = 0f;
            pauseUntilNanos = System.nanoTime() + START_PAUSE_NANOS;
        }
        invalidate();
        scheduleFrame();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (text.length() == 0) return;
        int left = getPaddingLeft();
        int right = getWidth() - getPaddingRight();
        float contentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float baseline = getPaddingTop() + (contentHeight - (descent - ascent)) / 2f - ascent;
        float x = left - offset;
        canvas.save();
        canvas.clipRect(left, 0, right, getHeight());
        canvas.drawText(text, x, baseline, paint);
        if (overflowing) canvas.drawText(text, x + textWidth + gapPx, baseline, paint);
        canvas.restore();
    }

    @Override
    protected float getLeftFadingEdgeStrength() {
        return overflowing && offset > 0f ? 1f : 0f;
    }

    @Override
    protected float getRightFadingEdgeStrength() {
        return overflowing ? 1f : 0f;
    }

    // ------------------------------------------------------------------ animation

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frameTask);
        frameScheduled = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) scheduleFrame();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) scheduleFrame();
    }

    private void scheduleFrame() {
        if (frameScheduled || !overflowing || !isShown()) return;
        frameScheduled = true;
        lastFrameNanos = 0L;
        ViewCompat.postOnAnimation(this, frameTask);
    }

    private void onFrame() {
        if (!overflowing || !isShown()) return;
        long now = System.nanoTime();
        if (now < pauseUntilNanos) {
            // Sleep through the pause instead of spinning frames.
            lastFrameNanos = 0L;
            frameScheduled = true;
            ViewCompat.postOnAnimationDelayed(this, frameTask, (pauseUntilNanos - now) / NANOS_PER_MILLI + 1L);
            return;
        }
        float dt = lastFrameNanos == 0L ? 0f
                : Math.min(MAX_FRAME_SECONDS, (now - lastFrameNanos) / NANOS_PER_SECOND);
        lastFrameNanos = now;
        advance(dt, now);
        invalidate();
        frameScheduled = true;
        ViewCompat.postOnAnimation(this, frameTask);
    }

    private void advance(float dt, long now) {
        float cycle = textWidth + gapPx;
        offset += speedPx * dt;
        if (offset < cycle) return;
        // The second copy now sits where the first one started: snap back and pause again.
        offset = 0f;
        pauseUntilNanos = now + START_PAUSE_NANOS;
    }
}
