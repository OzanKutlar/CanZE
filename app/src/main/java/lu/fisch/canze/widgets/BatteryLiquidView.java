package lu.fisch.canze.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import lu.fisch.canze.classes.SocObserver;

/**
 * Fullscreen battery with a liquid fill, a two-decimal readout and a charge bolt.
 *
 * Threading: submitSoc and the setters only write volatile or atomic fields, so the
 * poller thread may call them directly. Everything else runs on the UI thread inside
 * onDraw, which also drives the frame loop through postInvalidateOnAnimation().
 * onDraw allocates nothing except a new readout string when the hundredths change.
 */
public class BatteryLiquidView extends View {

    private static final int COLOR_RED = 0xFFFF5252;
    private static final int COLOR_AMBER = 0xFFFFD600;
    private static final int COLOR_GREEN = 0xFF00E676;
    private static final int COLOR_SHELL = 0xFFB0BEC5;
    private static final int COLOR_INNER = 0xFF121826;
    private static final int COLOR_BOLT = 0xFFFFD600;
    private static final int COLOR_TEXT_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DARK = 0xFF0B0F19;
    private static final int COLOR_HINT = 0x99FFFFFF;
    private static final int COLOR_HIGHLIGHT = 0x14FFFFFF;

    private static final long STALE_NANOS = 10000000000L;
    private static final float MAX_FRAME_DT = 0.1f;
    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private static final int WAVE_SEGMENTS = 48;
    private static final int BUBBLE_COUNT = 12;
    private static final float BOLT_FADE_PER_S = 4f;
    private static final float GLOW_FADE_PER_S = 2f;
    private static final float DIM_FADE_PER_S = 1.5f;
    private static final float IDLE_BOLT_ALPHA = 0.4f;
    private static final float STALE_DIM = 0.55f;
    private static final String SAMPLE_TEXT = "100.00";
    private static final String PLACEHOLDER = "--.--";
    private static final String PERCENT = "%";
    private static final String HINT_DEMO = "DEMO  -  tap the battery to cycle plug state";
    private static final String HINT_WAITING = "Waiting for state of charge...";
    private static final String HINT_STALE = "No data for 10 s";

    // Bolt outline in unit coordinates (x, y pairs). Height 1, width about 0.6.
    private static final float[] BOLT_POINTS = {
            0.38f, 0.00f, 0.00f, 0.58f, 0.26f, 0.58f, 0.16f, 1.00f,
            0.60f, 0.38f, 0.33f, 0.38f, 0.48f, 0.00f
    };

    // Written from any thread.
    private final AtomicLong pendingSeq = new AtomicLong();
    private volatile double pendingSoc = Double.NaN;
    private volatile boolean plugConnected;
    private volatile boolean charging;
    private volatile boolean demoMode;

    // UI thread only.
    private final SocObserver observer = new SocObserver();
    private final Random random = new Random(42L);
    private long consumedSeq;
    private long lastMeasurementNanos;
    private long lastFrameNanos;
    private boolean running;
    private boolean windowVisible = true;
    private float wavePhaseA;
    private float wavePhaseB;
    private float pulsePhase;
    private float boltAlpha;
    private float glowAmount;
    private float bubbleAlpha;
    private float dimAmount;
    private float level;
    private float surfaceY;
    private int cachedCenti = Integer.MIN_VALUE;
    private String cachedText = PLACEHOLDER;

    private final float[] bubbleX = new float[BUBBLE_COUNT];
    private final float[] bubbleY = new float[BUBBLE_COUNT];
    private final float[] bubbleSpeed = new float[BUBBLE_COUNT];
    private final float[] bubbleRadius = new float[BUBBLE_COUNT];

    // Geometry, recomputed in onSizeChanged.
    private final RectF body = new RectF();
    private final RectF inner = new RectF();
    private final RectF cap = new RectF();
    private final RectF highlight = new RectF();
    private final RectF boltBox = new RectF();
    private final Path innerPath = new Path();
    private final Path backWave = new Path();
    private final Path frontWave = new Path();
    private final Path boltPath = new Path();
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private float bodyCorner;
    private float readoutX;
    private float readoutBaseline;
    private float hintBaseline;
    private boolean readoutInside;

    private final Paint shellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint digitsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boltPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BatteryLiquidView(Context context) {
        super(context);
        init();
    }

    public BatteryLiquidView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BatteryLiquidView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shellPaint.setStyle(Paint.Style.STROKE);
        shellPaint.setColor(COLOR_SHELL);
        capPaint.setStyle(Paint.Style.FILL);
        capPaint.setColor(COLOR_SHELL);
        innerPaint.setStyle(Paint.Style.FILL);
        innerPaint.setColor(COLOR_INNER);
        liquidPaint.setStyle(Paint.Style.FILL);
        highlightPaint.setStyle(Paint.Style.FILL);
        highlightPaint.setColor(COLOR_HIGHLIGHT);
        bubblePaint.setStyle(Paint.Style.FILL);
        initTextPaint(digitsPaint);
        initTextPaint(percentPaint);
        hintPaint.setColor(COLOR_HINT);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        boltPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            respawnBubble(i, random.nextFloat());
        }
    }

    private static void initTextPaint(Paint paint) {
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            paint.setFontFeatureSettings("tnum");
        }
    }

    // ------------------------------------------------------------------ inputs (any thread)

    public void submitSoc(double value) {
        if (!SocObserver.isValidSoc(value)) return;
        pendingSoc = value;
        pendingSeq.incrementAndGet();
    }

    public void setPlugConnected(boolean connected) {
        plugConnected = connected;
    }

    public void setCharging(boolean active) {
        charging = active;
    }

    public void setDemoMode(boolean demo) {
        demoMode = demo;
    }

    // ------------------------------------------------------------------ frame loop lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        restartLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        windowVisible = visibility == VISIBLE;
        if (windowVisible) restartLoop();
    }

    private void restartLoop() {
        lastFrameNanos = 0L;
        invalidate();
    }

    private void scheduleNextFrame() {
        if (running && windowVisible) postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        boolean landscape = w > h;
        float areaWidth = landscape ? w * 0.45f : w;
        layoutBattery(areaWidth, h, landscape);
        layoutReadout(w, h, areaWidth, landscape);
        layoutBolt();
        layoutHint(w, h);
    }

    private void layoutBattery(float areaWidth, int h, boolean landscape) {
        float bodyHeight = h * (landscape ? 0.74f : 0.66f);
        float bodyWidth = Math.min(bodyHeight * 0.5f, areaWidth * 0.62f);
        float capHeight = bodyHeight * 0.05f;
        float top = (h - bodyHeight - capHeight) / 2f + capHeight;
        float cx = areaWidth / 2f;
        body.set(cx - bodyWidth / 2f, top, cx + bodyWidth / 2f, top + bodyHeight);
        float stroke = bodyWidth * 0.035f;
        shellPaint.setStrokeWidth(stroke);
        bodyCorner = bodyWidth * 0.12f;
        cap.set(cx - bodyWidth * 0.18f, top - capHeight, cx + bodyWidth * 0.18f, top);
        float inset = stroke * 1.8f;
        inner.set(body.left + inset, body.top + inset, body.right - inset, body.bottom - inset);
        float innerCorner = Math.max(0f, bodyCorner - inset);
        innerPath.reset();
        innerPath.addRoundRect(inner, innerCorner, innerCorner, Path.Direction.CW);
        highlight.set(inner.left + inner.width() * 0.08f, inner.top + inner.height() * 0.04f,
                inner.left + inner.width() * 0.16f, inner.bottom - inner.height() * 0.04f);
    }

    private void layoutReadout(int w, int h, float areaWidth, boolean landscape) {
        readoutInside = !landscape;
        float fitWidth;
        float maxSize;
        float centerY;
        if (readoutInside) {
            readoutX = inner.centerX();
            centerY = inner.centerY();
            fitWidth = inner.width() * 0.84f;
            maxSize = inner.height() * 0.2f;
        } else {
            readoutX = areaWidth + (w - areaWidth) / 2f;
            centerY = h / 2f;
            fitWidth = (w - areaWidth) * 0.85f;
            maxSize = h * 0.32f;
        }
        fitReadoutSize(fitWidth, maxSize);
        digitsPaint.getFontMetrics(metrics);
        readoutBaseline = centerY - (metrics.ascent + metrics.descent) / 2f;
    }

    private void fitReadoutSize(float fitWidth, float maxSize) {
        final float probe = 100f;
        digitsPaint.setTextSize(probe);
        percentPaint.setTextSize(probe * 0.45f);
        float probeWidth = digitsPaint.measureText(SAMPLE_TEXT) + percentPaint.measureText(PERCENT);
        float size = probeWidth > 0f ? probe * fitWidth / probeWidth : probe;
        size = Math.max(1f, Math.min(size, maxSize));
        digitsPaint.setTextSize(size);
        percentPaint.setTextSize(size * 0.45f);
    }

    private void layoutBolt() {
        float height = cap.height() * 2.2f;
        float width = height * 0.6f;
        float left = cap.right + body.width() * 0.08f;
        float cy = cap.centerY();
        boltBox.set(left, cy - height / 2f, left + width, cy + height / 2f);
        glowPaint.setStrokeWidth(width * 0.22f);
        boltPath.reset();
        for (int i = 0; i + 1 < BOLT_POINTS.length; i += 2) {
            float x = boltBox.left + BOLT_POINTS[i] * height;
            float y = boltBox.top + BOLT_POINTS[i + 1] * height;
            if (i == 0) {
                boltPath.moveTo(x, y);
            } else {
                boltPath.lineTo(x, y);
            }
        }
        boltPath.close();
    }

    private void layoutHint(int w, int h) {
        hintPaint.setTextSize(Math.max(10f, Math.min(w, h) * 0.032f));
        hintBaseline = h - hintPaint.getTextSize() * 1.2f;
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (body.isEmpty()) {
            scheduleNextFrame();
            return;
        }
        advance(System.nanoTime());
        drawBackground(canvas);
        drawLiquid(canvas);
        drawBubbles(canvas);
        drawGlass(canvas);
        drawReadout(canvas);
        drawBolt(canvas);
        drawHint(canvas);
        scheduleNextFrame();
    }

    private void advance(long now) {
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1e9f;
        dt = Math.max(0f, Math.min(MAX_FRAME_DT, dt));
        lastFrameNanos = now;
        consumeMeasurement(now);
        boolean fresh = lastMeasurementNanos != 0L && now - lastMeasurementNanos < STALE_NANOS;
        observer.step(dt, fresh);
        advanceIndicators(dt, fresh);
        advanceWaves(dt);
        advanceBubbles(dt);
        updateSurface();
    }

    private void consumeMeasurement(long now) {
        long seq = pendingSeq.get();
        if (seq == consumedSeq) return;
        consumedSeq = seq;
        observer.setMeasurement(pendingSoc);
        lastMeasurementNanos = now;
    }

    private void advanceIndicators(float dt, boolean fresh) {
        boolean plugged = plugConnected;
        boolean flowing = plugged && charging;
        boltAlpha = approach(boltAlpha, plugged ? 1f : 0f, BOLT_FADE_PER_S * dt);
        glowAmount = approach(glowAmount, flowing ? 1f : 0f, GLOW_FADE_PER_S * dt);
        bubbleAlpha = approach(bubbleAlpha, flowing ? 1f : 0f, GLOW_FADE_PER_S * dt);
        dimAmount = approach(dimAmount, fresh ? 0f : 1f, DIM_FADE_PER_S * dt);
        pulsePhase = wrap(pulsePhase + dt * TWO_PI * 0.8f);
    }

    private void advanceWaves(float dt) {
        float speedBoost = 1f + glowAmount * 0.6f;
        wavePhaseA = wrap(wavePhaseA + dt * 1.6f * speedBoost);
        wavePhaseB = wrap(wavePhaseB + dt * 2.3f * speedBoost);
    }

    private void advanceBubbles(float dt) {
        if (bubbleAlpha <= 0f) return;
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            bubbleY[i] += bubbleSpeed[i] * dt;
            if (bubbleY[i] >= 1f) respawnBubble(i, 0f);
        }
    }

    private void respawnBubble(int i, float startY) {
        if (i < 0 || i >= BUBBLE_COUNT) return;
        bubbleX[i] = 0.12f + random.nextFloat() * 0.76f;
        bubbleY[i] = startY;
        bubbleSpeed[i] = 0.12f + random.nextFloat() * 0.18f;
        bubbleRadius[i] = 0.012f + random.nextFloat() * 0.018f;
    }

    private void updateSurface() {
        double soc = observer.isInitialised() ? observer.getDisplaySoc() : 0.0;
        level = (float) (soc / 100.0);
        surfaceY = inner.bottom - inner.height() * level;
    }

    // ------------------------------------------------------------------ drawing

    private void drawBackground(Canvas canvas) {
        float capCorner = cap.height() * 0.35f;
        canvas.drawRoundRect(cap, capCorner, capCorner, capPaint);
        canvas.drawPath(innerPath, innerPaint);
    }

    private void drawLiquid(Canvas canvas) {
        if (!observer.isInitialised() || level <= 0f) return;
        float amplitude = inner.height() * 0.012f * edgeFactor(level);
        buildWave(backWave, -amplitude * 0.6f, amplitude, wavePhaseB + (float) Math.PI, wavePhaseA);
        buildWave(frontWave, 0f, amplitude, wavePhaseA, wavePhaseB);
        int color = socColor(level * 100f);
        float alpha = 1f - STALE_DIM * dimAmount;
        canvas.save();
        canvas.clipPath(innerPath);
        liquidPaint.setColor(withAlpha(color, alpha * 0.45f));
        canvas.drawPath(backWave, liquidPaint);
        liquidPaint.setColor(withAlpha(color, alpha));
        canvas.drawPath(frontWave, liquidPaint);
        canvas.restore();
    }

    private void buildWave(Path path, float yOffset, float amplitude, float phaseA, float phaseB) {
        path.reset();
        float width = inner.width();
        path.moveTo(inner.left, inner.bottom);
        for (int i = 0; i <= WAVE_SEGMENTS; i++) {
            float t = i / (float) WAVE_SEGMENTS;
            float y = surfaceY + yOffset
                    + amplitude * (float) Math.sin(t * TWO_PI * 1.3f + phaseA)
                    + amplitude * 0.5f * (float) Math.sin(t * TWO_PI * 2.7f + phaseB);
            path.lineTo(inner.left + width * t, y);
        }
        path.lineTo(inner.right, inner.bottom);
        path.close();
    }

    private void drawBubbles(Canvas canvas) {
        if (bubbleAlpha <= 0.01f || level <= 0.02f) return;
        float liquidHeight = inner.bottom - surfaceY;
        canvas.save();
        canvas.clipPath(frontWave);
        for (int i = 0; i < BUBBLE_COUNT; i++) {
            float fade = 1f - bubbleY[i] * bubbleY[i];
            bubblePaint.setColor(withAlpha(0xFFFFFFFF, 0.35f * bubbleAlpha * fade));
            float x = inner.left + bubbleX[i] * inner.width();
            float y = inner.bottom - bubbleY[i] * liquidHeight;
            canvas.drawCircle(x, y, bubbleRadius[i] * inner.width(), bubblePaint);
        }
        canvas.restore();
    }

    private void drawGlass(Canvas canvas) {
        float r = highlight.width() / 2f;
        canvas.drawRoundRect(highlight, r, r, highlightPaint);
        canvas.drawRoundRect(body, bodyCorner, bodyCorner, shellPaint);
    }

    private void drawReadout(Canvas canvas) {
        String text = readoutText();
        float alpha = 1f - STALE_DIM * dimAmount;
        drawDigits(canvas, text, withAlpha(COLOR_TEXT_LIGHT, alpha));
        if (!readoutInside || level <= 0f) return;
        // Dark copy clipped to the liquid keeps the number legible at any level.
        canvas.save();
        canvas.clipPath(frontWave);
        drawDigits(canvas, text, COLOR_TEXT_DARK);
        canvas.restore();
    }

    private void drawDigits(Canvas canvas, String text, int color) {
        digitsPaint.setColor(color);
        percentPaint.setColor(color);
        float digitsWidth = digitsPaint.measureText(text);
        float totalWidth = digitsWidth + percentPaint.measureText(PERCENT);
        float x = readoutX - totalWidth / 2f;
        canvas.drawText(text, x, readoutBaseline, digitsPaint);
        canvas.drawText(PERCENT, x + digitsWidth, readoutBaseline, percentPaint);
    }

    private String readoutText() {
        if (!observer.isInitialised()) return PLACEHOLDER;
        int centi = (int) Math.round(observer.getDisplaySoc() * 100.0);
        if (centi != cachedCenti) {
            cachedCenti = centi;
            cachedText = String.format(Locale.US, "%d.%02d", centi / 100, centi % 100);
        }
        return cachedText;
    }

    private void drawBolt(Canvas canvas) {
        if (boltAlpha <= 0.01f) return;
        float pulse = 0.85f + 0.15f * (float) Math.sin(pulsePhase);
        float strength = IDLE_BOLT_ALPHA + (1f - IDLE_BOLT_ALPHA) * glowAmount * pulse;
        if (glowAmount > 0.01f) {
            glowPaint.setColor(withAlpha(COLOR_BOLT, 0.3f * glowAmount * pulse * boltAlpha));
            canvas.drawPath(boltPath, glowPaint);
        }
        boltPaint.setColor(withAlpha(COLOR_BOLT, strength * boltAlpha));
        canvas.drawPath(boltPath, boltPaint);
    }

    private void drawHint(Canvas canvas) {
        String hint = currentHint();
        if (hint == null) return;
        canvas.drawText(hint, getWidth() / 2f, hintBaseline, hintPaint);
    }

    private String currentHint() {
        if (demoMode) return HINT_DEMO;
        if (!observer.isInitialised()) return HINT_WAITING;
        if (dimAmount > 0.5f) return HINT_STALE;
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static float approach(float current, float target, float maxDelta) {
        if (maxDelta <= 0f) return current;
        if (current < target) return Math.min(target, current + maxDelta);
        return Math.max(target, current - maxDelta);
    }

    private static float wrap(float phase) {
        return phase % TWO_PI;
    }

    private static float edgeFactor(float level) {
        float f = Math.min(level, 1f - level) * 25f;
        return Math.max(0f, Math.min(1f, f));
    }

    private static int socColor(float soc) {
        if (soc <= 15f) return COLOR_RED;
        if (soc <= 35f) return lerpColor(COLOR_RED, COLOR_AMBER, (soc - 15f) / 20f);
        if (soc <= 55f) return lerpColor(COLOR_AMBER, COLOR_GREEN, (soc - 35f) / 20f);
        return COLOR_GREEN;
    }

    private static int lerpColor(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int r = lerpChannel(from >> 16, to >> 16, k);
        int g = lerpChannel(from >> 8, to >> 8, k);
        int b = lerpChannel(from, to, k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        int a = from & 0xFF;
        int b = to & 0xFF;
        return Math.round(a + (b - a) * t);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
