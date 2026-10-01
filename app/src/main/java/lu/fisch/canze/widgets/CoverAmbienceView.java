package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.core.view.ViewCompat;

import lu.fisch.canze.activities.MainActivity;

/**
 * Dark, blurred colour field taken from the album cover, like YouTube Music's player
 * background. The cover is reduced to a 4x4 grid of average colours that is stretched
 * with bilinear filtering: one tiny bitmap per track, nothing extra per frame.
 * Track changes crossfade (eased, honouring the animator scale); one edge fades into the
 * HUD background colour.
 */
public class CoverAmbienceView extends View {

    private static final int SAMPLE_SIZE = 48;
    private static final int GRID = 4;
    private static final int CELL = SAMPLE_SIZE / GRID;
    private static final int BASE_COLOR = 0xFF0B0F19;
    private static final int BASE_TRANSPARENT = 0x000B0F19;
    private static final int DIM_COLOR = 0xB4000000;
    private static final float EDGE_FRACTION = 0.2f;
    private static final long FADE_NANOS = 600000000L;
    private static final int OPAQUE = 255;
    private static final Interpolator FADE_INTERPOLATOR = new DecelerateInterpolator();

    private final Paint fieldPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint edgePaint = new Paint();
    private final Rect fieldSource = new Rect(0, 0, GRID, GRID);
    private final RectF fieldTarget = new RectF();
    private final Runnable frameTask = new Runnable() {
        @Override
        public void run() {
            frameScheduled = false;
            onFrame();
        }
    };

    private Bitmap sourceArt;
    private Bitmap currentField;
    private Bitmap previousField;
    private long fadeStartNanos;
    private long fadeNanos;
    private boolean frameScheduled;
    private boolean blendTop = true;

    public CoverAmbienceView(Context context) {
        super(context);
        init();
    }

    public CoverAmbienceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CoverAmbienceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dimPaint.setColor(DIM_COLOR);
    }

    // ------------------------------------------------------------------ public API

    /** Rebuilds the colour field for a new cover; null fades back to the plain background. */
    public void setArt(Bitmap art) {
        Bitmap usable = (art != null && !art.isRecycled()) ? art : null;
        if (usable == sourceArt) return;
        sourceArt = usable;
        Bitmap field = usable == null ? null : buildField(usable);
        long duration = Motion.scaledNanos(getContext(), FADE_NANOS);
        previousField = duration > 0L ? currentField : null;
        currentField = field;
        fadeNanos = duration;
        fadeStartNanos = System.nanoTime();
        invalidate();
        scheduleFrame();
    }

    /** true: fade the top edge into the HUD (portrait); false: fade the left edge (landscape). */
    public void setBlendTop(boolean top) {
        if (blendTop == top) return;
        blendTop = top;
        updateEdgeShader();
        invalidate();
    }

    // ------------------------------------------------------------------ layout & drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fieldTarget.set(0f, 0f, w, h);
        updateEdgeShader();
    }

    private void updateEdgeShader() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            edgePaint.setShader(null);
            return;
        }
        edgePaint.setShader(blendTop
                ? new LinearGradient(0f, 0f, 0f, h * EDGE_FRACTION, BASE_COLOR, BASE_TRANSPARENT, Shader.TileMode.CLAMP)
                : new LinearGradient(0f, 0f, w * EDGE_FRACTION, 0f, BASE_COLOR, BASE_TRANSPARENT, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(BASE_COLOR);
        float progress = fadeProgress(System.nanoTime());
        if (previousField != null && progress < 1f) {
            drawField(canvas, previousField, currentField == null ? 1f - progress : 1f);
        }
        if (currentField != null) drawField(canvas, currentField, progress);
        canvas.drawRect(fieldTarget, dimPaint);
        if (edgePaint.getShader() != null) canvas.drawRect(fieldTarget, edgePaint);
    }

    private void drawField(Canvas canvas, Bitmap field, float alpha) {
        fieldPaint.setAlpha(Math.round(OPAQUE * Math.max(0f, Math.min(1f, alpha))));
        canvas.drawBitmap(field, fieldSource, fieldTarget, fieldPaint);
    }

    private float fadeProgress(long now) {
        if (fadeNanos <= 0L) return 1f;
        float raw = Math.min(1f, (now - fadeStartNanos) / (float) fadeNanos);
        return FADE_INTERPOLATOR.getInterpolation(raw);
    }

    // ------------------------------------------------------------------ crossfade

    private void scheduleFrame() {
        if (frameScheduled || !isShown() || fadeProgress(System.nanoTime()) >= 1f) return;
        frameScheduled = true;
        ViewCompat.postOnAnimation(this, frameTask);
    }

    private void onFrame() {
        invalidate();
        if (fadeProgress(System.nanoTime()) < 1f && isShown()) {
            frameScheduled = true;
            ViewCompat.postOnAnimation(this, frameTask);
        } else {
            previousField = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frameTask);
        frameScheduled = false;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ colour field

    private static Bitmap buildField(Bitmap art) {
        int[] pixels = samplePixels(art);
        if (pixels == null) return null;
        int[] cells = new int[GRID * GRID];
        for (int gy = 0; gy < GRID; gy++) {
            for (int gx = 0; gx < GRID; gx++) {
                cells[gy * GRID + gx] = averageCell(pixels, gx * CELL, gy * CELL);
            }
        }
        try {
            return Bitmap.createBitmap(cells, GRID, GRID, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException e) {
            MainActivity.debug("HUD media: cannot build cover ambience: " + e.getMessage());
            return null;
        }
    }

    private static int[] samplePixels(Bitmap art) {
        Bitmap sample = null;
        try {
            sample = Bitmap.createScaledBitmap(art, SAMPLE_SIZE, SAMPLE_SIZE, true);
            int[] pixels = new int[SAMPLE_SIZE * SAMPLE_SIZE];
            sample.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
            return pixels;
        } catch (RuntimeException e) {
            MainActivity.debug("HUD media: cannot sample cover colours: " + e.getMessage());
            return null;
        } finally {
            if (sample != null && sample != art) sample.recycle();
        }
    }

    private static int averageCell(int[] pixels, int startX, int startY) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        for (int y = startY; y < startY + CELL; y++) {
            int row = y * SAMPLE_SIZE;
            for (int x = startX; x < startX + CELL; x++) {
                int color = pixels[row + x];
                red += (color >> 16) & 0xFF;
                green += (color >> 8) & 0xFF;
                blue += color & 0xFF;
            }
        }
        int count = CELL * CELL;
        return 0xFF000000 | ((int) (red / count) << 16) | ((int) (green / count) << 8) | (int) (blue / count);
    }
}
