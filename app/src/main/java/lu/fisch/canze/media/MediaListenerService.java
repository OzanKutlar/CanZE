package lu.fisch.canze.media;

import android.annotation.TargetApi;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Android only lets an app read other apps' media sessions when it owns an enabled
 * notification listener. This service exists solely for that: it ignores notifications.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class MediaListenerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        // Not used: only the listener's existence matters.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        // Not used: only the listener's existence matters.
    }
}
