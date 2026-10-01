package lu.fisch.canze.media;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationManagerCompat;

import java.util.Set;

import lu.fisch.canze.activities.MainActivity;

/**
 * Version and permission checks for the media panel. Holds no references to API 21+
 * classes, so it is safe to load on every supported Android version.
 */
public final class MediaAccess {

    private static final String ACTION_LISTENER_SETTINGS =
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private MediaAccess() {
    }

    /** Other apps' media sessions can only be read from Android 5.0 on. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /** True when the user enabled CanZE under "Notification access". */
    public static boolean isGranted(Context context) {
        if (context == null || !isSupported()) return false;
        try {
            Set<String> packages = NotificationManagerCompat.getEnabledListenerPackages(context);
            return packages != null && packages.contains(context.getPackageName());
        } catch (RuntimeException e) {
            MainActivity.debug("Media: cannot read notification listeners: " + e.getMessage());
            return false;
        }
    }

    /** Opens the system screen where notification access is granted. */
    public static Intent settingsIntent() {
        return new Intent(ACTION_LISTENER_SETTINGS);
    }
}
