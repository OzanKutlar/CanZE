package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.view.ViewCompat;

/**
 * A vinyl record whose whole surface is the album cover, with a see-through spindle hole.
 * It spins slowly while music plays, eases in on play and coasts to a stop on pause.
 * New covers crossfade in while spinning. Track changes run as a carousel: the old record
 * slides out one side while the new one slides in from the other, and the resting record
 * can be dragged with the finger. The frame loop only runs while something moves and is visible.
 */
public class VinylDiscView extends View {

    private static final float DEGREES_PER_SECOND = 60f;   // one turn every 6 s
    private static final float SPIN_UP_RATE = 4f;          // 1/s, exponential approach
    private static final float SPIN_DOWN_RATE = 8f;        // ~0.6 s to stop
    private static final float STOP_VELOCITY = 0.5f;       // deg/s
    private static final float MAX_FRAME_SECONDS = 0.1f;
    private static final float NANOS_PER_SECOND = 1e9f;
    private static final long CROSSFADE_NANOS = 450000000L;
    private static final long SLIDE_NANOS = 420000000L;
    private static final long MIN_SLIDE_NANOS = 120000000L;
    private static final long SPRING_NANOS = 320000000L;
    private static final float SLIDE_FADE = 0.7f;
    private static final int PHASE_SHOWN = 0;
    private static final int PHASE_HIDDEN = 1;
    private static final int PHASE_ENTERING = 2;
    private static final int DEFAULT_SIZE_DP = 160;
    private static final int GROOVE_COUNT = 22;
    private static final float HOLE_FRACTION = 0.09f;
    private static final float LABEL_FRACTION = 0.36f;
    private static final float GROOVE_INNER = 1.12f;
    private static final float GROOVE_OUTER = 0.96f;
    private static final float ART_GROOVE_ALPHA_SCALE = 0.22f;
    private static final float ACCENT_INSET = 0.3f;
    private static final float SHEEN_START_A = -70f;
    private static final float SHEEN_START_B = 110f;
    private static final float SHEEN_SWEEP = 35f;
    private static final int OPAQUE = 255;
    private static final Interpolator FADE_INTERPOLATOR = new DecelerateInterpolator();
    private static final Interpolator SLIDE_INTERPOLATOR = new DecelerateInterpolator(1.6f);
    private static final Interpolator SPRING_INTERPOLATOR = new OvershootInterpolator(1.2f);

    private static final int COLOR_DISC_CENTER = 0xFF1C1F26;
    private static final int COLOR_DISC_EDGE = 0xFF07090D;
    private static final int COLOR_GROOVE = 0xFF2E3440;
    private static final int COLOR_GROOVE_ON_ART = 0xFF000000;
    private static final int COLOR_RIM = 0xFF3A4252;
    private static final int COLOR_LABEL_EMPTY = 0xFF1A2436;
    private static final int COLOR_HOLE_RIM = 0x99000000;
    private static final int COLOR_ACCENT = 0xFF00E676;
    private static final int COLOR_SHEEN = 0x14FFFFFF;

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint previousPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint outgoingPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holeRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelEmptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path discPath = new Path();
    private final Path labelPath = new Path();
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
    private Bitmap previousArt;
    private BitmapShader previousShader;
    private long crossfadeStartNanos;
    private long crossfadeNanos;

    // Carousel: one record leaving, the current one resting, hidden or entering.
    private Bitmap outgoingArt;
    private BitmapShader outgoingShader;
    private float outgoingFrom;
    private float outgoingTo;
    private long outgoingStartNanos;
    private long outgoingNanos;
    private int phase = PHASE_SHOWN;
    private float dragOffset;
    private float springFrom;
    private long springStartNanos;
    private long springNanos;
    private float enterFrom;
    private long enterStartNanos;
    private long enterNanos;
    private float cx;
    private float cy;
    private float radius;
    private float holeRadius;
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
        artPaint.setStyle(Paint.Style.FILL);
        previousPaint.setStyle(Paint.Style.FILL);
        outgoingPaint.setStyle(Paint.Style.FILL);
        groovePaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(1.5f * density);
        rimPaint.setColor(COLOR_RIM);
        holeRimPaint.setStyle(Paint.Style.STROKE);
        holeRimPaint.setStrokeWidth(2f * density);
        holeRimPaint.setColor(COLOR_HOLE_RIM);
        labelEmptyPaint.setStyle(Paint.Style.FILL);
        labelEmptyPaint.setColor(COLOR_LABEL_EMPTY);
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(3f * density);
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setColor(COLOR_ACCENT);
        sheenPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setColor(COLOR_SHEEN);
    }

    // ------------------------------------------------------------------ public API

    /** Covers the disc with the album art (crossfading from the previous cover). */
    public void setArt(Bitmap bitmap) {
        Bitmap usable = (bitmap != null && !bitmap.isRecycled()) ? bitmap : null;
        if (usable == art) return;
        long duration = Motion.scaledNanos(getContext(), CROSSFADE_NANOS);
        if (duration > 0L && isShown() && radius > 0f && phase != PHASE_HIDDEN) {
            previousArt = art;
            previousShader = artShader;
            previousPaint.setShader(previousShader);
            crossfadeStartNanos = System.nanoTime();
            crossfadeNanos = duration;
        } else {
            clearPrevious();
        }
        art = usable;
        artShader = usable == null ? null
                : new BitmapShader(usable, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        artPaint.setShader(artShader);
        configureShader(artShader, art);
        invalidate();
        scheduleFrame();
    }

    /** Spins up when true, coasts to a stop when false. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        scheduleFrame();
    }

    // ------------------------------------------------------------------ carousel

    /** Moves the resting record with the finger. Ignored while a slide is running. */
    public void setDragOffset(float px) {
        if (phase != PHASE_SHOWN) return;
        float limit = slideDistance();
        springNanos = 0L;
        dragOffset = limit > 0f ? Math.max(-limit, Math.min(limit, px)) : 0f;
        invalidate();
    }

    /** Springs a dragged record back to the centre. */
    public void releaseDrag() {
        if (phase != PHASE_SHOWN || dragOffset == 0f) return;
        springFrom = dragOffset;
        dragOffset = 0f;
        springStartNanos = System.nanoTime();
        springNanos = Motion.scaledNanos(getContext(), SPRING_NANOS);
        invalidate();
        scheduleFrame();
    }

    /**
     * Slides the record out of the disc area, starting wherever it is now (e.g. mid-drag):
     * DIRECTION_NEXT leaves to the left, DIRECTION_PREVIOUS to the right.
     * The area stays empty until {@link #enter(int)}.
     */
    public void exit(int direction) {
        if (phase == PHASE_HIDDEN) return;
        long now = System.nanoTime();
        float from = currentOffset(now);
        float travel = slideDistance();
        long base = Motion.scaledNanos(getContext(), SLIDE_NANOS);
        clearPrevious();
        clearOutgoing();
        phase = PHASE_HIDDEN;
        dragOffset = 0f;
        springNanos = 0L;
        if (base > 0L && travel > 0f && isShown()) {
            float to = -directionSign(direction) * travel;
            float share = Math.min(2f, Math.abs(to - from) / travel);
            outgoingArt = art;
            outgoingShader = artShader;
            outgoingPaint.setShader(outgoingShader);
            outgoingFrom = from;
            outgoingTo = to;
            outgoingStartNanos = now;
            outgoingNanos = Math.max(MIN_SLIDE_NANOS, (long) (base * share));
        }
        invalidate();
        scheduleFrame();
    }

    /** Slides the record in: DIRECTION_NEXT from the right, DIRECTION_PREVIOUS from the left. */
    public void enter(int direction) {
        float travel = slideDistance();
        long duration = Motion.scaledNanos(getContext(), SLIDE_NANOS);
        clearPrevious();
        dragOffset = 0f;
        springNanos = 0L;
        if (duration <= 0L || travel <= 0f || !isShown()) {
            phase = PHASE_SHOWN;
            enterNanos = 0L;
            invalidate();
            return;
        }
        phase = PHASE_ENTERING;
        enterFrom = directionSign(direction) * travel;
        enterStartNanos = System.nanoTime();
        enterNanos = duration;
        invalidate();
        scheduleFrame();
    }

    /** True after {@link #exit(int)} until the next {@link #enter(int)}. */
    public boolean isAwaitingTrack() {
        return phase == PHASE_HIDDEN;
    }

    /** Ends every slide at once and shows the current record resting in the centre. */
    public void showInPlace() {
        clearOutgoing();
        phase = PHASE_SHOWN;
        dragOffset = 0f;
        springNanos = 0L;
        enterNanos = 0L;
        invalidate();
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
        holeRadius = radius * HOLE_FRACTION;
        labelRadius = radius * LABEL_FRACTION;
        discBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float accent = labelRadius * (1f - ACCENT_INSET);
        accentBounds.set(cx - accent, cy - accent, cx + accent, cy + accent);
        rebuildRing(discPath, radius);
        rebuildRing(labelPath, labelRadius);
        discPaint.setShader(radius > 0f
                ? new RadialGradient(cx, cy, radius, COLOR_DISC_CENTER, COLOR_DISC_EDGE, Shader.TileMode.CLAMP)
                : null);
        layoutGrooves();
        configureShader(artShader, art);
        configureShader(previousShader, previousArt);
        configureShader(outgoingShader, outgoingArt);
    }

    /** A filled circle with the spindle hole cut out, so whatever is behind shows through. */
    private void rebuildRing(Path path, float outer) {
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        if (outer <= holeRadius) return;
        path.addCircle(cx, cy, outer, Path.Direction.CW);
        path.addCircle(cx, cy, holeRadius, Path.Direction.CW);
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

    /** Centre-crops a cover over the full disc. */
    private void configureShader(BitmapShader shader, Bitmap bitmap) {
        if (shader == null || bitmap == null || radius <= 0f) return;
        int shortest = Math.min(bitmap.getWidth(), bitmap.getHeight());
        if (shortest <= 0) return;
        float scale = radius * 2f / shortest;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(cx - bitmap.getWidth() * scale / 2f, cy - bitmap.getHeight() * scale / 2f);
        shader.setLocalMatrix(matrix);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0f) return;
        long now = System.nanoTime();
        if (isOutgoingActive(now)) drawDisc(canvas, outgoingOffset(now), true, 1f);
        if (phase != PHASE_HIDDEN) drawDisc(canvas, currentOffset(now), false, crossfadeProgress(now));
    }

    /** One record at a horizontal offset; it fades as it moves away from the centre. */
    private void drawDisc(Canvas canvas, float offset, boolean outgoing, float progress) {
        int alpha = slideAlpha(offset);
        int saved = alpha < OPAQUE
                ? canvas.saveLayerAlpha(0f, 0f, getWidth(), getHeight(), alpha, Canvas.ALL_SAVE_FLAG)
                : canvas.save();
        canvas.translate(offset, 0f);
        canvas.save();
        canvas.rotate(angle, cx, cy);
        if (outgoing) {
            drawOutgoingSurface(canvas);
        } else {
            drawSurface(canvas, progress);
        }
        canvas.restore();
        drawSheen(canvas);
        canvas.drawCircle(cx, cy, radius, rimPaint);
        canvas.drawCircle(cx, cy, holeRadius, holeRimPaint);
        canvas.restoreToCount(saved);
    }

    private void drawOutgoingSurface(Canvas canvas) {
        if (outgoingShader == null) {
            drawPlaceholder(canvas);
            return;
        }
        outgoingPaint.setAlpha(OPAQUE);
        canvas.drawPath(discPath, outgoingPaint);
        drawGrooves(canvas, COLOR_GROOVE_ON_ART, ART_GROOVE_ALPHA_SCALE);
    }

    /** Old surface underneath, new surface fading in on top (or old art fading off). */
    private void drawSurface(Canvas canvas, float progress) {
        if (artShader != null) {
            if (progress < 1f) drawPreviousSurface(canvas);
            artPaint.setAlpha(alphaOf(progress));
            canvas.drawPath(discPath, artPaint);
            drawGrooves(canvas, COLOR_GROOVE_ON_ART, ART_GROOVE_ALPHA_SCALE);
            return;
        }
        drawPlaceholder(canvas);
        if (progress < 1f && previousShader != null) {
            previousPaint.setAlpha(alphaOf(1f - progress));
            canvas.drawPath(discPath, previousPaint);
        }
    }

    private void drawPreviousSurface(Canvas canvas) {
        if (previousShader == null) {
            drawPlaceholder(canvas);
            return;
        }
        previousPaint.setAlpha(OPAQUE);
        canvas.drawPath(discPath, previousPaint);
    }

    private void drawPlaceholder(Canvas canvas) {
        canvas.drawPath(discPath, discPaint);
        drawGrooves(canvas, COLOR_GROOVE, 1f);
        canvas.drawPath(labelPath, labelEmptyPaint);
        canvas.drawArc(accentBounds, -60f, 120f, false, accentPaint);
    }

    private void drawGrooves(Canvas canvas, int color, float alphaScale) {
        groovePaint.setColor(color);
        for (int i = 0; i < GROOVE_COUNT; i++) {
            groovePaint.setAlpha(Math.round(grooveAlpha[i] * alphaScale));
            canvas.drawCircle(cx, cy, grooveRadii[i], groovePaint);
        }
    }

    /** Fixed glare: it stays put while the record turns underneath, and skips the hole. */
    private void drawSheen(Canvas canvas) {
        canvas.save();
        canvas.clipPath(discPath);
        canvas.drawArc(discBounds, SHEEN_START_A, SHEEN_SWEEP, true, sheenPaint);
        canvas.drawArc(discBounds, SHEEN_START_B, SHEEN_SWEEP, true, sheenPaint);
        canvas.restore();
    }

    private static int alphaOf(float fraction) {
        return Math.round(OPAQUE * Math.max(0f, Math.min(1f, fraction)));
    }

    // ------------------------------------------------------------------ timelines

    private boolean isCrossfading(long now) {
        return crossfadeNanos > 0L && now - crossfadeStartNanos < crossfadeNanos;
    }

    private float crossfadeProgress(long now) {
        if (!isCrossfading(now)) return 1f;
        float raw = (now - crossfadeStartNanos) / (float) crossfadeNanos;
        return FADE_INTERPOLATOR.getInterpolation(raw);
    }

    /** A record has to travel the full view width to be completely out of sight. */
    private float slideDistance() {
        return getWidth();
    }

    private float currentOffset(long now) {
        if (phase == PHASE_ENTERING) {
            float p = fraction(now, enterStartNanos, enterNanos);
            return enterFrom * (1f - SLIDE_INTERPOLATOR.getInterpolation(p));
        }
        if (isSpringing(now)) {
            float p = fraction(now, springStartNanos, springNanos);
            return springFrom * (1f - SPRING_INTERPOLATOR.getInterpolation(p));
        }
        return dragOffset;
    }

    private float outgoingOffset(long now) {
        float p = fraction(now, outgoingStartNanos, outgoingNanos);
        return outgoingFrom + (outgoingTo - outgoingFrom) * SLIDE_INTERPOLATOR.getInterpolation(p);
    }

    private boolean isOutgoingActive(long now) {
        return outgoingNanos > 0L && now - outgoingStartNanos < outgoingNanos;
    }

    private boolean isSpringing(long now) {
        return springNanos > 0L && now - springStartNanos < springNanos;
    }

    private int slideAlpha(float offset) {
        float travel = slideDistance();
        if (travel <= 0f || offset == 0f) return OPAQUE;
        float away = Math.min(1f, Math.abs(offset) / travel);
        return Math.round(OPAQUE * (1f - SLIDE_FADE * away));
    }

    private static float fraction(long now, long start, long duration) {
        if (duration <= 0L) return 1f;
        return Math.max(0f, Math.min(1f, (now - start) / (float) duration));
    }

    private static float directionSign(int direction) {
        return direction == TrackTransition.DIRECTION_PREVIOUS ? -1f : 1f;
    }

    private void finishSlidesAt(long now) {
        if (outgoingNanos > 0L && !isOutgoingActive(now)) clearOutgoing();
        if (phase == PHASE_ENTERING && fraction(now, enterStartNanos, enterNanos) >= 1f) {
            phase = PHASE_SHOWN;
            dragOffset = 0f;
        }
        if (springNanos > 0L && !isSpringing(now)) springNanos = 0L;
    }

    private void clearOutgoing() {
        outgoingArt = null;
        outgoingShader = null;
        outgoingPaint.setShader(null);
        outgoingNanos = 0L;
    }

    private void clearPrevious() {
        previousArt = null;
        previousShader = null;
        previousPaint.setShader(null);
        crossfadeNanos = 0L;
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

    private boolean needsFrames(long now) {
        return playing || velocity > 0f || isCrossfading(now) || isOutgoingActive(now)
                || phase == PHASE_ENTERING || isSpringing(now);
    }

    private void scheduleFrame() {
        if (frameScheduled || !isShown() || !needsFrames(System.nanoTime())) return;
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
        if (!isCrossfading(now)) clearPrevious();
        finishSlidesAt(now);
        invalidate();
        if (needsFrames(now) && isShown()) {
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
