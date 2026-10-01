package lu.fisch.canze.media;

/** Receives now-playing snapshots on the main thread. */
public interface NowPlayingListener {
    void onNowPlaying(NowPlaying state);
}
