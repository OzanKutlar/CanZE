package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.view.ViewCompat;

/**
 * A vinyl record showing the album cover on its centre label. It spins slowly while music
 * plays, eases in on play and coasts to a stop on pause. The frame loop only runs while
 * the disc is moving and visible, so a paused record costs nothing.
 */
public class VinylDiscView extends View {

    private static final float DEGREES_PER_SECOND = 60f;   // one turn every 6 s
    private static final float SPIN_UP_RATE = 4f;          // 1/s, exponential approach
    private static final float SPIN_DOWN_RATE = 8f;        // ~0.6 s to stop
    private static final float STOP_VELOCITY = 0.5f;       // deg/s
    private static final float MAX_FRAME_SECONDS = 0.1f;
    private static final float NANOS_PER_SECOND = 1e9f;
    private static final int DEFAULT_SIZE_DP = 160;
    private static final int GROOVE_COUNT = 22;
    private static final float LABEL_FRACTION = 0.38f;
    private static final float HOLE_FRACTION = 0.035f;
    private static final float GROOVE_INNER = 1.12f;
    private static final float GROOVE_OUTER = 0.96f;
    private static final float ACCENT_INSET = 0.3f;
    private static final float SHEEN_START_A = -70f;
    private static final float SHEEN_START_B = 110f;
    private static final float SHEEN_SWEEP = 35f;

    private static final int COLOR_DISC_CENTER = 0xFF1C1F26;
    private static final int COLOR_DISC_EDGE = 0xFF07090D;
    private static final int COLOR_GROOVE = 0xFF2E3440;
    private static final int COLOR_RIM = 0xFF3A4252;
    private static final int COLOR_LABEL_EMPTY = 0xFF1A2436;
    private static final int COLOR_LABEL_EDGE = 0x66000000;
    private static final int COLOR_ACCENT = 0xFF00E676;
    private static final int COLOR_SHEEN = 0x14FFFFFF;
    private static final int COLOR_HOLE = 0xFF0B0F19;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint labelEmptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final RectF discBounds = new RectF();
    private final RectF accentBounds = new RectF();
    private final float[] grooveRadii = new float[GROOVE_COUNT];
    private final int[] grooveAlpha = new int[GROOVE_COUNT];

    private final Runnable frameTask = new Runnable() {
        @Override
        public void run() {
            frameScheduled = false;
            onFrame();
        }
    };

    private Bitmap art;
    private BitmapShader artShader;
    private float cx;
    private float cy;
    private float radius;
    private float labelRadius;
    private float angle;
    private float velocity;
    private boolean playing;
    private boolean frameScheduled;
    private long lastFrameNanos;

    public VinylDiscView(Context context) {
        super(context);
        init();
    }

    public VinylDiscView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VinylDiscView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        discPaint.setStyle(Paint.Style.FILL);
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setColor(COLOR_GROOVE);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1.5f * density);
        rimPaint.setColor(COLOR_RIM);
        labelEmptyPaint.setStyle(Paint.Style.FILL);
        labelEmptyPaint.setColor(COLOR_LABEL_EMPTY);
        labelEdgePaint.setStyle(Paint.Style.STROKE);
        labelEdgePaint.setStrokeWidth(2f * density);
        labelEdgePaint.setColor(COLOR_LABEL_EDGE);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(3f * density);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setColor(COLOR_ACCENT);
        sheenPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setColor(COLOR_SHEEN);
        holePaint.setStyle(Paint.Style.FILL);
        holePaint.setColor(COLOR_HOLE);
    }

    // ------------------------------------------------------------------ public API

    /** Shows the cover on the label; null shows the empty label. */
    public void setArt(Bitmap bitmap) {
        Bitmap usable = (bitmap != null && !bitmap.isRecycled()) ? bitmap : null;
        if (usable == art) return;
        art = usable;
        artShader = usable == null ? null
                : new BitmapShader(usable, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        labelPaint.setShader(artShader);
        updateShaderMatrix();
        invalidate();
    }

    /** Spins up when true, coasts to a stop when false. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        scheduleFrame();
    }

    // ------------------------------------------------------------------ measuring

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        boolean widthFree = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED;
        boolean heightFree = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED;
        int size;
        if (widthFree && heightFree) {
            size = Math.round(DEFAULT_SIZE_DP * getResources().getDisplayMetrics().density);
        } else if (widthFree) {
            size = height;
        } else if (heightFree) {
            size = width;
        } else {
            size = Math.min(width, height);
        }
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.max(0f, Math.min(w, h) / 2f - rimPaint.getStrokeWidth());
        labelRadius = radius * LABEL_FRACTION;
        discBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float accent = labelRadius * (1f - ACCENT_INSET);
        accentBounds.set(cx - accent, cy - accent, cx + accent, cy + accent);
        discPaint.setShader(radius > 0f
                ? new RadialGradient(cx, cy, radius, COLOR_DISC_CENTER, COLOR_DISC_EDGE, Shader.TileMode.CLAMP)
                : null);
        layoutGrooves();
        updateShaderMatrix();
    }

    private void layoutGrooves() {
        float inner = labelRadius * GROOVE_INNER;
        float outer = radius * GROOVE_OUTER;
        float step = Math.max(0f, outer - inner) / GROOVE_COUNT;
        groovePaint.setStrokeWidth(Math.max(1f, step * 0.3f));
        for (int i = 0; i < GROOVE_COUNT; i++) {
            grooveRadii[i] = inner + step * (i + 0.5f);
            grooveAlpha[i] = (i % 4 == 0) ? 200 : ((i % 2 == 0) ? 120 : 70);
        }
    }

    /** Centre-crops the cover into the label circle. */
    private void updateShaderMatrix() {
        if (artShader == null || art == null || labelRadius <= 0f) return;
        int shortest = Math.min(art.getWidth(), art.getHeight());
        if (shortest <= 0) return;
        float scale = labelRadius * 2f / shortest;
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(cx - art.getWidth() * scale / 2f, cy - art.getHeight() * scale / 2f);
        artShader.setLocalMatrix(shaderMatrix);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0f) return;
        canvas.save();
        canvas.rotate(angle, cx, cy);
        canvas.drawCircle(cx, cy, radius, discPaint);
        drawGrooves(canvas);
        drawLabel(canvas);
        canvas.restore();
        // Glare stays put while the record turns underneath it.
        canvas.drawArc(discBounds, SHEEN_START_A, SHEEN_SWEEP, true, sheenPaint);
        canvas.drawArc(discBounds, SHEEN_START_B, SHEEN_SWEEP, true, sheenPaint);
        canvas.drawCircle(cx, cy, radius, rimPaint);
        canvas.drawCircle(cx, cy, radius * HOLE_FRACTION, holePaint);
    }

    private void drawGrooves(Canvas canvas) {
        for (int i = 0; i < GROOVE_COUNT; i++) {
            groovePaint.setAlpha(grooveAlpha[i]);
            canvas.drawCircle(cx, cy, grooveRadii[i], groovePaint);
        }
    }

    private void drawLabel(Canvas canvas) {
        if (artShader != null) {
            canvas.drawCircle(cx, cy, labelRadius, labelPaint);
        } else {
            canvas.drawCircle(cx, cy, labelRadius, labelEmptyPaint);
            canvas.drawArc(accentBounds, -60f, 120f, false, accentPaint);
        }
        canvas.drawCircle(cx, cy, labelRadius, labelEdgePaint);
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
        lastFrameNanos = 0L;
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
        if (frameScheduled || !isShown()) return;
        if (!playing && velocity == 0f) return;
        frameScheduled = true;
        lastFrameNanos = 0L;
        ViewCompat.postOnAnimation(this, frameTask);
    }

    private void onFrame() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f
                : Math.min(MAX_FRAME_SECONDS, (now - lastFrameNanos) / NANOS_PER_SECOND);
        lastFrameNanos = now;
        advance(dt);
        invalidate();
        if ((playing || velocity > 0f) && isShown()) {
            frameScheduled = true;
            ViewCompat.postOnAnimation(this, frameTask);
        }
    }

    private void advance(float dt) {
        float target = playing ? DEGREES_PER_SECOND : 0f;
        float rate = playing ? SPIN_UP_RATE : SPIN_DOWN_RATE;
        velocity += (target - velocity) * Math.min(1f, rate * dt);
        if (!playing && velocity < STOP_VELOCITY) velocity = 0f;
        angle = (angle + velocity * dt) % 360f;
    }
}
