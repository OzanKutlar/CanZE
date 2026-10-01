package lu.fisch.canze.widgets;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import lu.fisch.canze.R;
import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.media.MediaAccess;
import lu.fisch.canze.media.NowPlaying;
import lu.fisch.canze.media.NowPlayingListener;
import lu.fisch.canze.media.NowPlayingTracker;
import lu.fisch.canze.media.PlaybackClock;

/**
 * The Car HUD media panel: spinning vinyl cover, track info, seek bar and transport
 * buttons. Like the rest of the HUD, views are only touched when what they show changes.
 * Main thread only. Below Android 5.0 the panel hides itself.
 */
public final class HudMediaPanel implements NowPlayingListener {

    private static final long TICK_INTERVAL_MS = 250L;
    private static final long SEEK_SETTLE_WINDOW_MS = 2000L;
    private static final long SEEK_MATCH_TOLERANCE_MS = 3000L;
    private static final long NO_VALUE = Long.MIN_VALUE;
    private static final int ART_SIZE_DP = 320;
    private static final int INFO_GAP_DP = 12;
    private static final float PORTRAIT_BANDS_WEIGHT = 3f;
    private static final float PORTRAIT_MEDIA_WEIGHT = 1.4f;
    private static final float LANDSCAPE_BANDS_WEIGHT = 1.15f;
    private static final float LANDSCAPE_MEDIA_WEIGHT = 1f;
    private static final float PORTRAIT_VINYL_WEIGHT = 0.42f;
    private static final float PORTRAIT_INFO_WEIGHT = 0.58f;
    private static final float DISABLED_ALPHA = 0.35f;
    private static final int COLOR_ON = 0xFF00E676;
    private static final int COLOR_IDLE = 0xFFCFD8DC;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String NO_TIME = "--:--";

    private final Activity activity;
    private final NowPlayingTracker tracker;
    private final String unknownTitle;
    private final String unknownArtist;

    private LinearLayout root;
    private View bands;
    private View divider;
    private View panel;
    private LinearLayout content;
    private View vinylFrame;
    private View info;
    private View overlay;
    private View grantButton;
    private VinylDiscView vinyl;
    private TextView sourceView;
    private TextView titleView;
    private TextView artistView;
    private TextView elapsedView;
    private TextView durationView;
    private TextView overlayText;
    private SeekBar seekBar;
    private ImageButton shuffleButton;
    private ImageButton previousButton;
    private ImageButton playButton;
    private ImageButton nextButton;

    private NowPlaying state;
    private boolean dragging;
    private long pendingSeekMs = -1L;
    private long pendingSeekAt;
    private long lastTickAt;
    private long shownElapsedSecond = NO_VALUE;
    private long shownDurationMs = NO_VALUE;
    private int shownPlayIcon;
    private int shownOverlayText;

    private final SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser && dragging) previewSeek(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
            dragging = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
            dragging = false;
            commitSeek(bar.getProgress());
        }
    };

    public HudMediaPanel(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        this.activity = activity;
        this.unknownTitle = activity.getString(R.string.hud_media_unknown_title);
        this.unknownArtist = activity.getString(R.string.hud_media_unknown_artist);
        this.state = NowPlaying.idle(MediaAccess.isGranted(activity));
        bindViews();
        wireControls();
        this.tracker = createTracker();
        if (tracker == null) {
            hidePanel();
        } else {
            render(SystemClock.elapsedRealtime());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void onResume() {
        if (tracker != null) tracker.start();
    }

    public void onPause() {
        dragging = false;
        if (tracker != null) tracker.stop();
    }

    public void release() {
        if (tracker != null) tracker.release();
    }

    /** Called from the HUD's 50 ms tick; refreshes the seek position at 4 Hz while active. */
    public void onTick() {
        if (tracker == null || !state.active) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTickAt < TICK_INTERVAL_MS) return;
        lastTickAt = now;
        renderProgress(state, now);
    }

    /** Portrait: bands above the player. Landscape: bands left, player right. */
    public void applyLayout(boolean landscape) {
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        bands.setLayoutParams(weighted(landscape, landscape ? LANDSCAPE_BANDS_WEIGHT : PORTRAIT_BANDS_WEIGHT));
        panel.setLayoutParams(weighted(landscape, landscape ? LANDSCAPE_MEDIA_WEIGHT : PORTRAIT_MEDIA_WEIGHT));
        divider.setLayoutParams(landscape
                ? new LinearLayout.LayoutParams(dp(1), MATCH)
                : new LinearLayout.LayoutParams(MATCH, dp(1)));
        layoutContent(landscape);
    }

    @Override
    public void onNowPlaying(NowPlaying next) {
        if (next == null) return;
        long now = SystemClock.elapsedRealtime();
        settlePendingSeek(next, now);
        state = next;
        render(now);
    }

    // ------------------------------------------------------------------ setup

    private NowPlayingTracker createTracker() {
        if (!MediaAccess.isSupported()) return null;
        try {
            return new NowPlayingTracker(activity, dp(ART_SIZE_DP), this);
        } catch (RuntimeException e) {
            MainActivity.debug("HUD media: tracker unavailable: " + e.getMessage());
            return null;
        }
    }

    private void bindViews() {
        root = find(R.id.hudRoot);
        bands = find(R.id.hudBands);
        divider = find(R.id.hudMediaDivider);
        panel = find(R.id.hudMediaPanel);
        content = find(R.id.hudMediaContent);
        vinylFrame = find(R.id.hudMediaVinylFrame);
        info = find(R.id.hudMediaInfo);
        overlay = find(R.id.hudMediaOverlay);
        overlayText = find(R.id.hudMediaOverlayText);
        grantButton = find(R.id.hudMediaGrant);
        vinyl = find(R.id.hudMediaVinyl);
        sourceView = find(R.id.hudMediaSource);
        titleView = find(R.id.hudMediaTitle);
        artistView = find(R.id.hudMediaArtist);
        elapsedView = find(R.id.hudMediaElapsed);
        durationView = find(R.id.hudMediaDuration);
        seekBar = find(R.id.hudMediaSeek);
        shuffleButton = find(R.id.hudMediaShuffle);
        previousButton = find(R.id.hudMediaPrevious);
        playButton = find(R.id.hudMediaPlay);
        nextButton = find(R.id.hudMediaNext);
        titleView.setSelected(true); // starts the marquee for long titles
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elapsedView.setFontFeatureSettings("tnum");
            durationView.setFontFeatureSettings("tnum");
        }
    }

    private <T extends View> T find(int id) {
        T view = activity.findViewById(id);
        if (view == null) {
            throw new IllegalStateException("activity_hud.xml lacks media view 0x" + Integer.toHexString(id));
        }
        return view;
    }

    private void wireControls() {
        View.OnClickListener playPause = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.togglePlayPause();
            }
        };
        playButton.setOnClickListener(playPause);
        vinyl.setOnClickListener(playPause);
        previousButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.previous();
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.next();
            }
        });
        shuffleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.toggleShuffle();
            }
        });
        grantButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessSettings();
            }
        });
        seekBar.setMax(PlaybackClock.PERMILLE_MAX);
        seekBar.setOnSeekBarChangeListener(seekListener);
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
    }

    private void layoutContent(boolean landscape) {
        content.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        if (landscape) {
            vinylFrame.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, 1f));
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            infoParams.topMargin = dp(INFO_GAP_DP);
            info.setLayoutParams(infoParams);
        } else {
            vinylFrame.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH, PORTRAIT_VINYL_WEIGHT));
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, WRAP, PORTRAIT_INFO_WEIGHT);
            infoParams.leftMargin = dp(INFO_GAP_DP);
            infoParams.gravity = Gravity.CENTER_VERTICAL;
            info.setLayoutParams(infoParams);
        }
    }

    private static LinearLayout.LayoutParams weighted(boolean horizontal, float weight) {
        return horizontal
                ? new LinearLayout.LayoutParams(0, MATCH, weight)
                : new LinearLayout.LayoutParams(MATCH, 0, weight);
    }

    private void openAccessSettings() {
        try {
            activity.startActivity(MediaAccess.settingsIntent());
        } catch (ActivityNotFoundException e) {
            reportSettingsFailure(e);
        } catch (SecurityException e) {
            reportSettingsFailure(e);
        }
    }

    private void reportSettingsFailure(Exception e) {
        MainActivity.debug("HUD media: cannot open notification access settings: " + e.getMessage());
        Toast.makeText(activity, R.string.hud_media_access_error, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ seeking

    private void previewSeek(int progress) {
        elapsedView.setText(PlaybackClock.format(PlaybackClock.fromPermille(progress, state.durationMs)));
        shownElapsedSecond = NO_VALUE;
    }

    private void commitSeek(int progress) {
        NowPlaying current = state;
        if (tracker == null || !current.active || !current.canSeek) return;
        long target = PlaybackClock.fromPermille(progress, current.durationMs);
        pendingSeekMs = target;
        pendingSeekAt = SystemClock.elapsedRealtime();
        tracker.seekTo(target);
        renderProgress(current, pendingSeekAt);
    }

    /** Holds the bar at the requested spot until the player confirms the seek. */
    private void settlePendingSeek(NowPlaying next, long now) {
        if (pendingSeekMs < 0L) return;
        boolean confirmed = next.positionUpdatedAtMs >= pendingSeekAt
                && Math.abs(next.positionAt(now) - pendingSeekMs) <= SEEK_MATCH_TOLERANCE_MS;
        if (confirmed || !next.active) pendingSeekMs = -1L;
    }

    private long displayedPosition(NowPlaying current, long now) {
        if (pendingSeekMs >= 0L) {
            if (now - pendingSeekAt <= SEEK_SETTLE_WINDOW_MS) {
                return PlaybackClock.estimate(pendingSeekMs, pendingSeekAt, current.speed,
                        current.clockRunning, current.durationMs, now);
            }
            pendingSeekMs = -1L;
        }
        return current.positionAt(now);
    }

    // ------------------------------------------------------------------ rendering

    private void render(long now) {
        if (tracker == null) return;
        NowPlaying current = state;
        renderOverlay(current);
        renderTrack(current);
        renderButtons(current);
        renderProgress(current, now);
        vinyl.setArt(current.art);
        vinyl.setPlaying(current.active && current.clockRunning);
    }

    private void renderOverlay(NowPlaying current) {
        boolean needsAccess = !current.accessGranted;
        boolean showOverlay = needsAccess || !current.active;
        setVisibility(overlay, showOverlay ? View.VISIBLE : View.GONE);
        setVisibility(content, showOverlay ? View.INVISIBLE : View.VISIBLE);
        setVisibility(grantButton, needsAccess ? View.VISIBLE : View.GONE);
        int text = needsAccess ? R.string.hud_media_access_body : R.string.hud_media_nothing_playing;
        if (text == shownOverlayText) return;
        shownOverlayText = text;
        overlayText.setText(text);
    }

    private void renderTrack(NowPlaying current) {
        setText(sourceView, current.source == null ? "" : current.source.toUpperCase(Locale.getDefault()));
        setText(titleView, TextUtils.isEmpty(current.title) ? unknownTitle : current.title);
        setText(artistView, TextUtils.isEmpty(current.artist) ? unknownArtist : current.artist);
    }

    private void renderButtons(NowPlaying current) {
        setUsable(previousButton, current.active && current.canPrevious);
        setUsable(nextButton, current.active && current.canNext);
        setUsable(playButton, current.active && current.canPlayPause);
        boolean shuffleUsable = current.active && current.shuffleSupported;
        setUsable(shuffleButton, shuffleUsable);
        tint(shuffleButton, shuffleUsable && current.shuffleOn ? COLOR_ON : COLOR_IDLE);
        setUsable(seekBar, current.active && current.canSeek);
        renderPlayIcon(current.playing);
    }

    private void renderPlayIcon(boolean playing) {
        int icon = playing ? R.drawable.hud_ic_pause : R.drawable.hud_ic_play;
        if (icon == shownPlayIcon) return;
        shownPlayIcon = icon;
        playButton.setImageResource(icon);
        playButton.setContentDescription(activity.getString(playing ? R.string.hud_media_pause : R.string.hud_media_play));
    }

    private void renderProgress(NowPlaying current, long now) {
        long duration = current.durationMs;
        if (duration != shownDurationMs) {
            shownDurationMs = duration;
            durationView.setText(duration > 0L ? PlaybackClock.format(duration) : NO_TIME);
        }
        if (dragging) return;
        long position = displayedPosition(current, now);
        int progress = PlaybackClock.toPermille(position, duration);
        if (seekBar.getProgress() != progress) seekBar.setProgress(progress);
        long second = current.active ? position / 1000L : -1L;
        if (second == shownElapsedSecond) return;
        shownElapsedSecond = second;
        elapsedView.setText(current.active ? PlaybackClock.format(position) : NO_TIME);
    }

    // ------------------------------------------------------------------ view helpers

    private static void setText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static void setVisibility(View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private static void setUsable(View view, boolean usable) {
        if (view.isEnabled() != usable) view.setEnabled(usable);
        float alpha = usable ? 1f : DISABLED_ALPHA;
        if (view.getAlpha() != alpha) view.setAlpha(alpha);
    }

    private static void tint(ImageView view, int color) {
        Object shown = view.getTag();
        if (shown instanceof Integer && (Integer) shown == color) return;
        view.setTag(color);
        view.setColorFilter(color);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
