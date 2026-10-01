package lu.fisch.canze.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

/**
 * Fade-out / swap / fade-in for the track text block. The view slides out one way and
 * back in from the other. The swap runs the most recent apply action, so metadata that
 * arrives in pieces, or rapid skips, give one smooth transition instead of a stutter.
 * Main thread only.
 */
final class TrackTransition {

    /** Exit to the left, enter from the right. */
    static final int DIRECTION_NEXT = 1;
    /** Exit to the right, enter from the left. */
    static final int DIRECTION_PREVIOUS = -1;

    private static final int PHASE_IDLE = 0;
    private static final int PHASE_OUT = 1;
    private static final int PHASE_IN = 2;
    private static final long OUT_MS = 150L;
    private static final long IN_MS = 220L;
    private static final float SLIDE_DP = 12f;
    private static final Interpolator OUT_INTERPOLATOR = new AccelerateInterpolator();
    private static final Interpolator IN_INTERPOLATOR = new DecelerateInterpolator();

    private final View view;
    private final float slidePx;
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

    private Runnable pendingApply;
    private int phase = PHASE_IDLE;
    private int direction = DIRECTION_NEXT;
    private float startAlpha = 1f;
    private float startTranslation;
    private boolean cancelled;

    TrackTransition(View view, float density) {
        if (view == null) throw new IllegalArgumentException("view is required");
        this.view = view;
        this.slidePx = SLIDE_DP * density;
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                applyFrame(animation.getAnimatedFraction());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                cancelled = false;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) onPhaseEnd();
            }
        });
    }

    boolean isFadingOut() {
        return phase == PHASE_OUT;
    }

    /**
     * Transitions to new content. {@code apply} runs once the old content is invisible;
     * calling again before that only replaces the pending apply and direction.
     */
    void run(int direction, Runnable apply) {
        if (apply == null) return;
        pendingApply = apply;
        this.direction = direction == DIRECTION_PREVIOUS ? DIRECTION_PREVIOUS : DIRECTION_NEXT;
        if (phase == PHASE_OUT) return;
        startPhase(PHASE_OUT);
    }

    /** Ends any transition at once: pending content is applied and the view fully shown. */
    void finish() {
        animator.cancel();
        phase = PHASE_IDLE;
        Runnable apply = pendingApply;
        pendingApply = null;
        if (apply != null) apply.run();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        setHardwareLayer(false);
    }

    private void startPhase(int next) {
        animator.cancel();
        phase = next;
        startAlpha = view.getAlpha();
        startTranslation = view.getTranslationX();
        long duration = next == PHASE_OUT
                ? Math.max(1L, Math.round(OUT_MS * startAlpha)) // half-visible text leaves faster
                : IN_MS;
        animator.setDuration(duration);
        animator.setInterpolator(next == PHASE_OUT ? OUT_INTERPOLATOR : IN_INTERPOLATOR);
        setHardwareLayer(true);
        animator.start();
    }

    private void applyFrame(float fraction) {
        if (phase == PHASE_OUT) {
            float exit = -direction * slidePx;
            view.setAlpha(startAlpha * (1f - fraction));
            view.setTranslationX(startTranslation + (exit - startTranslation) * fraction);
        } else if (phase == PHASE_IN) {
            float enter = direction * slidePx;
            view.setAlpha(fraction);
            view.setTranslationX(enter * (1f - fraction));
        }
    }

    private void onPhaseEnd() {
        if (phase == PHASE_OUT) {
            Runnable apply = pendingApply;
            pendingApply = null;
            if (apply != null) apply.run();
            startPhase(PHASE_IN);
            return;
        }
        phase = PHASE_IDLE;
        setHardwareLayer(false);
    }

    private void setHardwareLayer(boolean enabled) {
        int type = enabled ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (view.getLayerType() != type) view.setLayerType(type, null);
    }
}
