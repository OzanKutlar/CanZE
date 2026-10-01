package lu.fisch.canze.widgets;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.view.ViewCompat;

/**
 * Squeeze-and-spring feedback for a touch target: the target shrinks while the finger is
 * down and springs back with a slight overshoot on release. Fires the system key haptic
 * on press, which follows the phone's touch-vibration setting. Never consumes the touch,
 * so clicks, disabled states and accessibility work exactly as before.
 */
public final class PressFeedback implements View.OnTouchListener {

    private static final float DEFAULT_PRESSED_SCALE = 0.86f;
    private static final long PRESS_MS = 90L;
    private static final long RELEASE_MS = 260L;
    private static final Interpolator PRESS_INTERPOLATOR = new DecelerateInterpolator();
    private static final Interpolator RELEASE_INTERPOLATOR = new OvershootInterpolator(2.5f);

    private final View target;
    private final float pressedScale;

    private PressFeedback(View target, float pressedScale) {
        this.target = target;
        this.pressedScale = pressedScale;
    }

    /** Feedback on {@code cell}, animating {@code target} (the cell itself when null). */
    public static void attach(View cell, View target) {
        attach(cell, target, DEFAULT_PRESSED_SCALE);
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attach(View cell, View target, float pressedScale) {
        if (cell == null) throw new IllegalArgumentException("cell is required");
        float scale = Math.max(0.5f, Math.min(1f, pressedScale));
        cell.setOnTouchListener(new PressFeedback(target != null ? target : cell, scale));
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (!view.isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                animateTo(pressedScale, PRESS_MS, PRESS_INTERPOLATOR);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animateTo(1f, RELEASE_MS, RELEASE_INTERPOLATOR);
                break;
            default:
                break;
        }
        return false;
    }

    private void animateTo(float scale, long duration, Interpolator interpolator) {
        ViewCompat.animate(target)
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start();
    }
}
