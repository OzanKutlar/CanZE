package lu.fisch.canze.widgets;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import lu.fisch.canze.activities.MainActivity;

/**
 * Reads the system animator duration scale, so animations driven by hand (frame loops
 * rather than Animator objects) honour "remove animations" just like framework ones.
 */
final class Motion {

    private Motion() {
    }

    /** 1 by default, 0 when the user turned animations off. */
    static float durationScale(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return 1f;
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (RuntimeException e) {
            MainActivity.debug("HUD media: cannot read animator scale: " + e.getMessage());
            return 1f;
        }
    }

    /** Scales a duration; 0 means "jump to the end". */
    static long scaledNanos(Context context, long nanos) {
        float scale = durationScale(context);
        return scale <= 0f ? 0L : (long) (nanos * scale);
    }
}
