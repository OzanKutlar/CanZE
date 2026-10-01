package lu.fisch.canze.media;

import android.graphics.Bitmap;

/**
 * Immutable snapshot of the followed media session, delivered on the main thread.
 * Positions use the SystemClock.elapsedRealtime() clock, like PlaybackState does.
 */
public final class NowPlaying {

    public final boolean accessGranted;
    public final boolean active;
    public final String source;
    public final String title;
    public final String artist;
    public final Bitmap art;
    public final long durationMs;
    public final long positionMs;
    public final long positionUpdatedAtMs;
    public final float speed;
    public final boolean playing;
    public final boolean clockRunning;
    public final boolean canPlayPause;
    public final boolean canPrevious;
    public final boolean canNext;
    public final boolean canSeek;
    public final boolean shuffleSupported;
    public final boolean shuffleOn;

    private NowPlaying(Builder b) {
        accessGranted = b.accessGranted;
        active = b.active;
        source = b.source;
        title = b.title;
        artist = b.artist;
        art = b.art;
        durationMs = b.durationMs;
        positionMs = b.positionMs;
        positionUpdatedAtMs = b.positionUpdatedAtMs;
        speed = b.speed;
        playing = b.playing;
        clockRunning = b.clockRunning;
        canPlayPause = b.canPlayPause;
        canPrevious = b.canPrevious;
        canNext = b.canNext;
        canSeek = b.canSeek;
        shuffleSupported = b.shuffleSupported;
        shuffleOn = b.shuffleOn;
    }

    /** No session to show (nothing playing, or no permission yet). */
    public static NowPlaying idle(boolean accessGranted) {
        Builder builder = new Builder();
        builder.accessGranted = accessGranted;
        return builder.build();
    }

    /** Live position, extrapolated from the player's last report. */
    public long positionAt(long nowElapsedMs) {
        return PlaybackClock.estimate(positionMs, positionUpdatedAtMs, speed, clockRunning,
                durationMs, nowElapsedMs);
    }

    static final class Builder {
        boolean accessGranted;
        boolean active;
        String source;
        String title;
        String artist;
        Bitmap art;
        long durationMs;
        long positionMs;
        long positionUpdatedAtMs;
        float speed = 1f;
        boolean playing;
        boolean clockRunning;
        boolean canPlayPause;
        boolean canPrevious;
        boolean canNext;
        boolean canSeek;
        boolean shuffleSupported;
        boolean shuffleOn;

        NowPlaying build() {
            return new NowPlaying(this);
        }
    }
}
