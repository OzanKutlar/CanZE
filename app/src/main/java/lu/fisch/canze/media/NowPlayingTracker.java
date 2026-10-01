package lu.fisch.canze.media;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.List;
import java.util.Locale;

import lu.fisch.canze.activities.MainActivity;

/**
 * Follows the media session of whatever app is playing (YouTube Music, Samsung Music,
 * Spotify, ...) and exposes its state and transport controls.
 *
 * Selection: the first playing session wins; otherwise the current session is kept;
 * otherwise the system's top session is used. While nothing plays, the session list is
 * re-read every few seconds so a player that starts later is picked up.
 *
 * Main thread only. Requires notification access (see MediaAccess).
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public final class NowPlayingTracker {

    private static final long RESELECT_INTERVAL_MS = 2000L;
    private static final int MAX_SESSIONS = 16;
    private static final int MAX_CUSTOM_ACTIONS = 16;
    private static final String SHUFFLE_HINT = "shuffle";
    private static final char KEY_SEPARATOR = '|';

    private final Context context;
    private final NowPlayingListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;
    private final AlbumArtLoader artLoader;

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private MediaControllerCompat compatController;
    private String sourceLabel;
    private String shuffleCustomAction;
    private String artKey;
    private String artUri;
    private Bitmap art;
    private boolean started;
    private boolean accessGranted;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    if (started) bind(pick(controllers));
                }
            };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    private final MediaControllerCompat.Callback compatCallback = new MediaControllerCompat.Callback() {
        @Override
        public void onSessionReady() {
            publish();
        }

        @Override
        public void onShuffleModeChanged(int shuffleMode) {
            publish();
        }
    };

    private final AlbumArtLoader.Callback artCallback = new AlbumArtLoader.Callback() {
        @Override
        public void onArtLoaded(String key, Bitmap bitmap) {
            if (!started || bitmap == null || key == null || !key.equals(artKey)) return;
            art = bitmap;
            publish();
        }
    };

    private final Runnable reselectTask = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            if (!isPlaying(controller)) refreshSessions();
            handler.postDelayed(this, RESELECT_INTERVAL_MS);
        }
    };

    public NowPlayingTracker(Context context, int artSizePx, NowPlayingListener listener) {
        if (context == null || listener == null) {
            throw new IllegalArgumentException("context and listener are required");
        }
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.listenerComponent = new ComponentName(this.context, MediaListenerService.class);
        this.artLoader = new AlbumArtLoader(this.context, artSizePx);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts following sessions. Re-checks the permission every time it is called. */
    public void start() {
        if (started) return;
        started = true;
        accessGranted = MediaAccess.isGranted(context);
        if (!accessGranted || !attachSessionListener()) {
            publish();
            return;
        }
        refreshSessions();
        handler.postDelayed(reselectTask, RESELECT_INTERVAL_MS);
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(reselectTask);
        detachSessionListener();
        bind(null);
    }

    /** Stops tracking and frees the art thread. Do not start the tracker again afterwards. */
    public void release() {
        stop();
        artLoader.shutdown();
        handler.removeCallbacksAndMessages(null);
    }

    private boolean attachSessionListener() {
        sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessionManager == null) {
            debug("media session service unavailable");
            return false;
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler);
            return true;
        } catch (SecurityException e) {
            accessGranted = false;
            debug("session access refused: " + e.getMessage());
            return false;
        }
    }

    private void detachSessionListener() {
        if (sessionManager == null) return;
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (RuntimeException e) {
            debug("cannot remove session listener: " + e.getMessage());
        }
        sessionManager = null;
    }

    // ------------------------------------------------------------------ controls

    public void togglePlayPause() {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            if (isPlaying(controller)) {
                controls.pause();
            } else {
                controls.play();
            }
        } catch (RuntimeException e) {
            debug("play/pause failed: " + e.getMessage());
        }
    }

    /** Explicit play: used by the HUD's optimistic toggle so repeated taps never invert. */
    public void play() {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            controls.play();
        } catch (RuntimeException e) {
            debug("play failed: " + e.getMessage());
        }
    }

    /** Explicit pause: counterpart of {@link #play()}. */
    public void pause() {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            controls.pause();
        } catch (RuntimeException e) {
            debug("pause failed: " + e.getMessage());
        }
    }

    public void next() {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            controls.skipToNext();
        } catch (RuntimeException e) {
            debug("next failed: " + e.getMessage());
        }
    }

    public void previous() {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            controls.skipToPrevious();
        } catch (RuntimeException e) {
            debug("previous failed: " + e.getMessage());
        }
    }

    public void seekTo(long positionMs) {
        MediaController.TransportControls controls = controls();
        if (controls == null) return;
        try {
            controls.seekTo(Math.max(0L, positionMs));
        } catch (RuntimeException e) {
            debug("seek failed: " + e.getMessage());
        }
    }

    /** Compat shuffle mode first; falls back to a player's "shuffle" custom action. */
    public void toggleShuffle() {
        if (toggleCompatShuffle()) return;
        String action = shuffleCustomAction;
        MediaController.TransportControls controls = controls();
        if (action == null || controls == null) return;
        try {
            controls.sendCustomAction(action, null);
        } catch (RuntimeException e) {
            debug("shuffle action failed: " + e.getMessage());
        }
    }

    private boolean toggleCompatShuffle() {
        MediaControllerCompat compat = compatController;
        int mode = compatShuffleMode();
        if (compat == null || mode == PlaybackStateCompat.SHUFFLE_MODE_INVALID) return false;
        int next = mode == PlaybackStateCompat.SHUFFLE_MODE_NONE
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE;
        try {
            compat.getTransportControls().setShuffleMode(next);
            return true;
        } catch (RuntimeException e) {
            debug("shuffle mode change failed: " + e.getMessage());
            return false;
        }
    }

    private MediaController.TransportControls controls() {
        MediaController current = controller;
        if (current == null) return null;
        try {
            return current.getTransportControls();
        } catch (RuntimeException e) {
            debug("transport controls unavailable: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ session selection

    private void refreshSessions() {
        if (!started || sessionManager == null) return;
        try {
            bind(pick(sessionManager.getActiveSessions(listenerComponent)));
        } catch (SecurityException e) {
            accessGranted = false;
            debug("session access revoked: " + e.getMessage());
            bind(null);
        }
    }

    private MediaController pick(List<MediaController> controllers) {
        if (controllers == null || controllers.isEmpty()) return null;
        int count = Math.min(controllers.size(), MAX_SESSIONS);
        for (int i = 0; i < count; i++) {
            if (isPlaying(controllers.get(i))) return controllers.get(i);
        }
        for (int i = 0; i < count; i++) {
            if (sameSession(controllers.get(i), controller)) return controllers.get(i);
        }
        return controllers.get(0);
    }

    private void bind(MediaController next) {
        if (next != null && sameSession(next, controller)) {
            publish();
            return;
        }
        unbindCurrent();
        if (next != null) attach(next);
        publish();
    }

    private void attach(MediaController next) {
        try {
            next.registerCallback(controllerCallback, handler);
        } catch (RuntimeException e) {
            debug("cannot follow " + next.getPackageName() + ": " + e.getMessage());
            return;
        }
        controller = next;
        compatController = createCompat(next);
        sourceLabel = appLabel(next.getPackageName());
    }

    private void unbindCurrent() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (RuntimeException e) {
                debug("cannot unregister session callback: " + e.getMessage());
            }
        }
        if (compatController != null) {
            try {
                compatController.unregisterCallback(compatCallback);
            } catch (RuntimeException e) {
                debug("cannot unregister compat callback: " + e.getMessage());
            }
        }
        controller = null;
        compatController = null;
        sourceLabel = null;
        shuffleCustomAction = null;
        artKey = null;
        artUri = null;
        art = null;
    }

    private MediaControllerCompat createCompat(MediaController source) {
        try {
            MediaSessionCompat.Token token = MediaSessionCompat.Token.fromToken(source.getSessionToken());
            MediaControllerCompat compat = new MediaControllerCompat(context, token);
            compat.registerCallback(compatCallback, handler);
            return compat;
        } catch (Exception e) {
            // RemoteException or a player without a compat session: shuffle falls back to custom actions.
            debug("no compat session for " + source.getPackageName() + ": " + e.getMessage());
            return null;
        }
    }

    private String appLabel(String packageName) {
        if (packageName == null) return null;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : packageName;
        } catch (PackageManager.NameNotFoundException e) {
            debug("no label for " + packageName);
            return packageName;
        }
    }

    // ------------------------------------------------------------------ snapshot

    private void publish() {
        MediaController current = controller;
        if (current == null) {
            listener.onNowPlaying(NowPlaying.idle(accessGranted));
            return;
        }
        MediaMetadata metadata = metadataOf(current);
        PlaybackState state = stateOf(current);
        if (metadata == null && (state == null || state.getState() == PlaybackState.STATE_NONE)) {
            listener.onNowPlaying(NowPlaying.idle(true));
            return;
        }
        refreshArt(metadata, current.getPackageName());
        listener.onNowPlaying(snapshot(metadata, state));
    }

    private NowPlaying snapshot(MediaMetadata metadata, PlaybackState state) {
        NowPlaying.Builder builder = new NowPlaying.Builder();
        builder.accessGranted = true;
        builder.active = true;
        builder.source = sourceLabel;
        builder.art = art;
        fillMetadata(builder, metadata);
        fillPlayback(builder, state);
        fillShuffle(builder, state);
        return builder.build();
    }

    private static void fillMetadata(NowPlaying.Builder builder, MediaMetadata metadata) {
        if (metadata == null) return;
        builder.title = firstText(metadata,
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        builder.artist = firstText(metadata,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_AUTHOR,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        builder.durationMs = Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
    }

    private static void fillPlayback(NowPlaying.Builder builder, PlaybackState state) {
        if (state == null) return;
        int code = state.getState();
        builder.playing = isPlayingState(code);
        builder.clockRunning = code == PlaybackState.STATE_PLAYING;
        builder.positionMs = state.getPosition();
        builder.positionUpdatedAtMs = state.getLastPositionUpdateTime();
        builder.speed = state.getPlaybackSpeed();
        long actions = state.getActions();
        boolean unknown = actions == 0L;
        builder.canPlayPause = unknown || has(actions,
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE);
        builder.canPrevious = unknown || has(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        builder.canNext = unknown || has(actions, PlaybackState.ACTION_SKIP_TO_NEXT);
        builder.canSeek = has(actions, PlaybackState.ACTION_SEEK_TO) && builder.durationMs > 0L;
    }

    private void fillShuffle(NowPlaying.Builder builder, PlaybackState state) {
        int mode = compatShuffleMode();
        if (mode != PlaybackStateCompat.SHUFFLE_MODE_INVALID) {
            shuffleCustomAction = null;
            builder.shuffleSupported = true;
            builder.shuffleOn = mode == PlaybackStateCompat.SHUFFLE_MODE_ALL
                    || mode == PlaybackStateCompat.SHUFFLE_MODE_GROUP;
            return;
        }
        shuffleCustomAction = findShuffleAction(state);
        builder.shuffleSupported = shuffleCustomAction != null;
    }

    private int compatShuffleMode() {
        MediaControllerCompat compat = compatController;
        if (compat == null) return PlaybackStateCompat.SHUFFLE_MODE_INVALID;
        try {
            return compat.isSessionReady() ? compat.getShuffleMode() : PlaybackStateCompat.SHUFFLE_MODE_INVALID;
        } catch (RuntimeException e) {
            debug("shuffle mode unavailable: " + e.getMessage());
            return PlaybackStateCompat.SHUFFLE_MODE_INVALID;
        }
    }

    private static String findShuffleAction(PlaybackState state) {
        if (state == null) return null;
        List<PlaybackState.CustomAction> actions = state.getCustomActions();
        if (actions == null) return null;
        int count = Math.min(actions.size(), MAX_CUSTOM_ACTIONS);
        for (int i = 0; i < count; i++) {
            PlaybackState.CustomAction action = actions.get(i);
            if (action != null && mentionsShuffle(action.getAction(), action.getName())) {
                return action.getAction();
            }
        }
        return null;
    }

    private static boolean mentionsShuffle(String action, CharSequence name) {
        if (action != null && action.toLowerCase(Locale.ROOT).contains(SHUFFLE_HINT)) return true;
        return name != null && name.toString().toLowerCase(Locale.ROOT).contains(SHUFFLE_HINT);
    }

    // ------------------------------------------------------------------ album art

    private void refreshArt(MediaMetadata metadata, String packageName) {
        String key = artKeyOf(metadata, packageName);
        Bitmap embedded = AlbumArtLoader.embedded(metadata);
        if (embedded != null) {
            artKey = key;
            artUri = null;
            art = embedded;
            return;
        }
        String uri = AlbumArtLoader.uriOf(metadata);
        boolean sameTrack = key.equals(artKey);
        if (sameTrack && (art != null || uri == null || uri.equals(artUri))) return;
        if (!sameTrack) art = null;
        artKey = key;
        artUri = uri;
        if (uri != null) artLoader.load(key, uri, artCallback);
    }

    private static String artKeyOf(MediaMetadata metadata, String packageName) {
        StringBuilder key = new StringBuilder(64).append(packageName);
        if (metadata == null) return key.toString();
        key.append(KEY_SEPARATOR).append(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        key.append(KEY_SEPARATOR).append(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
        key.append(KEY_SEPARATOR).append(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
        return key.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static MediaMetadata metadataOf(MediaController source) {
        try {
            return source.getMetadata();
        } catch (RuntimeException e) {
            debug("metadata unavailable: " + e.getMessage());
            return null;
        }
    }

    private static PlaybackState stateOf(MediaController source) {
        if (source == null) return null;
        try {
            return source.getPlaybackState();
        } catch (RuntimeException e) {
            debug("playback state unavailable: " + e.getMessage());
            return null;
        }
    }

    private static boolean isPlaying(MediaController candidate) {
        PlaybackState state = stateOf(candidate);
        return state != null && isPlayingState(state.getState());
    }

    private static boolean isPlayingState(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING;
    }

    private static boolean sameSession(MediaController a, MediaController b) {
        if (a == null || b == null) return false;
        return a.getSessionToken().equals(b.getSessionToken());
    }

    private static boolean has(long actions, long mask) {
        return (actions & mask) != 0L;
    }

    private static String firstText(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            String value = metadata.getString(key);
            if (value != null && value.trim().length() > 0) return value.trim();
        }
        return null;
    }

    private static void debug(String message) {
        MainActivity.debug("Media: " + message);
    }
}
