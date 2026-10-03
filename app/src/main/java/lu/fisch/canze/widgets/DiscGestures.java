package lu.fisch.canze.widgets;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.view.ViewCompat;

/**
 * Touch handling for the vinyl. A tap clicks (play/pause through the view's own click
 * listener); a horizontal drag is reported as an offset, then as a release that either
 * commits (far enough, or a quick flick) or not. The disc squeezes while pressed and ticks
 * once when the drag is far enough to count. Moving the record is up to the callback.
 */
public final class DiscGestures implements View.OnTouchListener {

    public interface Callback {
        /** Horizontal finger travel since touch-down, in px (positive = right). */
        void onDrag(float offsetPx);

        /** End of a drag; {@code commit} is true for a long enough drag or a flick. */
        void onRelease(float offsetPx, boolean commit);
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_PRESSED = 1;
    private static final int STATE_SWIPING = 2;
    private static final int STATE_SCROLLED = 3;

    private static final float PRESSED_SCALE = 0.95f;
    private static final long PRESS_MS = 90L;
    private static final long RELEASE_MS = 260L;
    private static final float COMMIT_FRACTION = 0.22f;
    private static final float MIN_COMMIT_DP = 48f;
    private static final float FLING_DP_PER_SECOND = 700f;
    private static final int VELOCITY_UNITS_MS = 1000;
    private static final Interpolator PRESS_INTERPOLATOR = new DecelerateInterpolator();
    private static final Interpolator RELEASE_INTERPOLATOR = new OvershootInterpolator(2.5f);

    private final Callback callback;
    private final float touchSlop;
    private final float density;

    private VelocityTracker velocity;
    private int state = STATE_IDLE;
    private float downX;
    private float downY;
    private boolean armed;

    private DiscGestures(View view, Callback callback) {
        this.callback = callback;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        this.density = view.getResources().getDisplayMetrics().density;
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(View view, Callback callback) {
        if (view == null || callback == null) {
            throw new IllegalArgumentException("view and callback are required");
        }
        view.setOnTouchListener(new DiscGestures(view, callback));
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!view.isEnabled()) return false;
        track(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(view, event);
                break;
            case MotionEvent.ACTION_MOVE:
                onMove(view, event);
                break;
            case MotionEvent.ACTION_UP:
                onUp(view, event);
                break;
            case MotionEvent.ACTION_CANCEL:
                onCancel(view);
                break;
            default:
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ phases

    private void onDown(View view, MotionEvent event) {
        downX = event.getRawX();
        downY = event.getRawY();
        state = STATE_PRESSED;
        armed = false;
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        ViewCompat.animate(view).cancel();
        animateScale(view, PRESSED_SCALE, PRESS_MS, PRESS_INTERPOLATOR);
    }

    private void onMove(View view, MotionEvent event) {
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        if (state == STATE_PRESSED) classify(view, dx, dy);
        if (state == STATE_SWIPING) drag(view, dx);
    }

    private void onUp(View view, MotionEvent event) {
        if (state == STATE_SWIPING) {
            float dx = event.getRawX() - downX;
            boolean commit = Math.abs(dx) >= commitDistance(view) || isFling(dx);
            finish();
            callback.onRelease(dx, commit);
            return;
        }
        boolean tap = state == STATE_PRESSED && isInside(view, event);
        animateScale(view, 1f, RELEASE_MS, RELEASE_INTERPOLATOR);
        finish();
        if (tap) view.performClick();
    }

    /** Decides once per gesture: horizontal beyond the slop is a swipe, vertical is not ours. */
    private void classify(View view, float dx, float dy) {
        if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
            state = STATE_SWIPING;
            ViewParent parent = view.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            animateScale(view, 1f, RELEASE_MS, RELEASE_INTERPOLATOR);
        } else if (Math.abs(dy) > touchSlop) {
            state = STATE_SCROLLED;
            animateScale(view, 1f, RELEASE_MS, RELEASE_INTERPOLATOR);
        }
    }

    private void drag(View view, float dx) {
        callback.onDrag(dx);
        boolean reached = Math.abs(dx) >= commitDistance(view);
        if (reached == armed) return;
        armed = reached;
        if (reached) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // ------------------------------------------------------------------ helpers

    private float commitDistance(View view) {
        return Math.max(MIN_COMMIT_DP * density, view.getWidth() * COMMIT_FRACTION);
    }

    private boolean isFling(float dx) {
        if (velocity == null || dx == 0f) return false;
        velocity.computeCurrentVelocity(VELOCITY_UNITS_MS);
        float vx = velocity.getXVelocity();
        return Math.abs(vx) >= FLING_DP_PER_SECOND * density && (vx > 0f) == (dx > 0f);
    }

    private static boolean isInside(View view, MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        return x >= 0f && y >= 0f && x <= view.getWidth() && y <= view.getHeight();
    }

    /** Tracks in screen coordinates: the disc itself moves under the finger. */
    private void track(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            recycleVelocity();
            velocity = VelocityTracker.obtain();
        }
        if (velocity == null) return;
        MotionEvent screen = MotionEvent.obtain(event);
        screen.setLocation(event.getRawX(), event.getRawY());
        velocity.addMovement(screen);
        screen.recycle();
    }

    private static void animateScale(View view, float scale, long duration, Interpolator interpolator) {
        ViewCompat.animate(view).scaleX(scale).scaleY(scale)
                .setDuration(duration).setInterpolator(interpolator).start();
    }

    private void onCancel(View view) {
        boolean wasSwiping = state == STATE_SWIPING;
        animateScale(view, 1f, RELEASE_MS, RELEASE_INTERPOLATOR);
        finish();
        if (wasSwiping) callback.onRelease(0f, false);
    }

    private void finish() {
        state = STATE_IDLE;
        armed = false;
        recycleVelocity();
    }

    private void recycleVelocity() {
        if (velocity == null) return;
        velocity.recycle();
        velocity = null;
    }
}
