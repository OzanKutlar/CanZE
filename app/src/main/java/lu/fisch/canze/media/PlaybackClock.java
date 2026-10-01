package lu.fisch.canze.media;

/**
 * Pure-Java helpers for media position math and time formatting.
 * Kept free of Android types so it can be unit tested on the JVM.
 */
public final class PlaybackClock {

    /** Seek bar resolution: positions are mapped to 0..PERMILLE_MAX. */
    public static final int PERMILLE_MAX = 1000;

    private static final float MAX_SPEED = 8f;
    private static final long MS_PER_SECOND = 1000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;

    private PlaybackClock() {
    }

    /**
     * Extrapolates the playback position from the player's last report.
     *
     * @param positionMs  position at the last report, negative when unknown
     * @param updatedAtMs elapsedRealtime of the last report, 0 when unknown
     * @param speed       playback speed; invalid values fall back to real time
     * @param running     true only while the player is actually advancing
     * @param durationMs  track length, 0 or less when unknown (no clamping then)
     * @param nowMs       current elapsedRealtime
     */
    public static long estimate(long positionMs, long updatedAtMs, float speed,
                                boolean running, long durationMs, long nowMs) {
        if (positionMs < 0L) return 0L;
        long position = positionMs;
        if (running && updatedAtMs > 0L && nowMs > updatedAtMs) {
            position += (long) ((nowMs - updatedAtMs) * sanitiseSpeed(speed));
        }
        return clamp(position, durationMs);
    }

    static float sanitiseSpeed(float speed) {
        if (Float.isNaN(speed) || speed <= 0f) return 1f;
        return Math.min(speed, MAX_SPEED);
    }

    public static long clamp(long positionMs, long durationMs) {
        if (positionMs < 0L) return 0L;
        if (durationMs > 0L && positionMs > durationMs) return durationMs;
        return positionMs;
    }

    public static int toPermille(long positionMs, long durationMs) {
        if (durationMs <= 0L) return 0;
        return (int) (clamp(positionMs, durationMs) * PERMILLE_MAX / durationMs);
    }

    public static long fromPermille(int permille, long durationMs) {
        if (durationMs <= 0L) return 0L;
        int bounded = Math.max(0, Math.min(PERMILLE_MAX, permille));
        return durationMs * bounded / PERMILLE_MAX;
    }

    /** Formats as m:ss, or h:mm:ss for long tracks. Negative input shows 0:00. */
    public static String format(long ms) {
        long totalSeconds = Math.max(0L, ms) / MS_PER_SECOND;
        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds / SECONDS_PER_MINUTE) % SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;
        StringBuilder text = new StringBuilder(8);
        if (hours > 0L) {
            text.append(hours).append(':');
            appendTwoDigits(text, minutes);
        } else {
            text.append(minutes);
        }
        text.append(':');
        appendTwoDigits(text, seconds);
        return text.toString();
    }

    private static void appendTwoDigits(StringBuilder text, long value) {
        if (value < 10L) text.append('0');
        text.append(value);
    }
}
