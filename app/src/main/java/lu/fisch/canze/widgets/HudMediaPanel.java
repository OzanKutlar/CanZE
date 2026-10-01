package lu.fisch.canze.widgets;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
 * Car HUD media player. While a player session exists (playing or paused) it takes the
 * gear band's place: the third band in portrait, the right half of the screen in
 * landscape. Without a session the HUD looks exactly as before; the gear band returns a
 * few seconds after the session ends so track changes do not flicker.
 *
 * Views are only touched when what they show changes. Main thread only.
 * Below Android 5.0 the player never appears.
 */
public final class HudMediaPanel implements NowPlayingListener {

    private static final long TICK_INTERVAL_MS = 250L;
    private static final long SLOT_HOLD_MS = 3000L;
    private static final long SEEK_SETTLE_WINDOW_MS = 2000L;
    private static final long SEEK_MATCH_TOLERANCE_MS = 3000L;
    private static final long NO_VALUE = Long.MIN_VALUE;
    private static final int ART_SIZE_DP = 320;
    private static final float BANDS_WEIGHT = 2f;
    private static final float GEAR_SLOT_WEIGHT = 1f;
    private static final float PORTRAIT_MEDIA_SLOT_WEIGHT = 1.25f;
    private static final float SPLIT_BANDS_WEIGHT = 1f;
    private static final float SPLIT_MEDIA_WEIGHT = 1f;
    private static final float DISABLED_ALPHA = 0.35f;
    private static final int COLOR_ON = 0xFF00E676;
    private static final int COLOR_IDLE = 0xFFECEFF1;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final String NO_TIME = "--:--";

    private final Activity activity;
    private final NowPlayingTracker tracker;
    private final String unknownTitle;
    private final String unknownArtist;

    private LinearLayout root;
    private View bands;
    private View slotDivider;
    private View slot;
    private View gearBand;
    private View chip;
    private View panel;
    private CoverAmbienceView ambience;
    private VinylDiscView vinyl;
    private TextView sourceView;
    private MarqueeTextView titleView;
    private TextView artistView;
    private TextView elapsedView;
    private TextView durationView;
    private SeekBar seekBar;
    private View shuffleCell;
    private ImageView shuffleIcon;
    private View previousCell;
    private View playCell;
    private ImageView playIcon;
    private View nextCell;

    private NowPlaying state;
    private boolean landscape;
    private boolean mediaShown;
    private boolean dragging;
    private long hideMediaAt;
    private long pendingSeekMs = -1L;
    private long pendingSeekAt;
    private long lastTickAt;
    private long shownElapsedSecond = NO_VALUE;
    private long shownDurationMs = NO_VALUE;
    private int shownPlayIcon;

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
        render(SystemClock.elapsedRealtime());
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

    /** Called from the HUD's 50 ms tick: seek position at 4 Hz, and the gear hand-back. */
    public void onTick() {
        if (tracker == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTickAt < TICK_INTERVAL_MS) return;
        lastTickAt = now;
        if (state.active) {
            renderProgress(state, now);
        } else {
            updateSlot(false, now);
        }
    }

    /** Called on creation and on rotation. */
    public void applyLayout(boolean landscape) {
        this.landscape = landscape;
        applyStructure();
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
        slotDivider = find(R.id.hudSlotDivider);
        slot = find(R.id.hudSlot);
        gearBand = find(R.id.hudGearBand);
        chip = find(R.id.hudMediaChip);
        panel = find(R.id.hudMediaPanel);
        ambience = find(R.id.hudMediaAmbience);
        vinyl = find(R.id.hudMediaVinyl);
        sourceView = find(R.id.hudMediaSource);
        titleView = find(R.id.hudMediaTitle);
        artistView = find(R.id.hudMediaArtist);
        elapsedView = find(R.id.hudMediaElapsed);
        durationView = find(R.id.hudMediaDuration);
        seekBar = find(R.id.hudMediaSeek);
        shuffleCell = find(R.id.hudMediaShuffle);
        shuffleIcon = find(R.id.hudMediaShuffleIcon);
        previousCell = find(R.id.hudMediaPrevious);
        playCell = find(R.id.hudMediaPlay);
        playIcon = find(R.id.hudMediaPlayIcon);
        nextCell = find(R.id.hudMediaNext);
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
        playCell.setOnClickListener(playPause);
        vinyl.setOnClickListener(playPause);
        previousCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.previous();
            }
        });
        nextCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.next();
            }
        });
        shuffleCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker != null) tracker.toggleShuffle();
            }
        });
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessSettings();
            }
        });
        seekBar.setMax(PlaybackClock.PERMILLE_MAX);
        seekBar.setOnSeekBarChangeListener(seekListener);
    }

    // ------------------------------------------------------------------ gear slot

    /** Media takes the slot at once; gear only returns after the hold-off. */
    private void updateSlot(boolean active, long now) {
        if (active) {
            hideMediaAt = 0L;
            showMedia(true);
            return;
        }
        if (!mediaShown) return;
        if (hideMediaAt == 0L) {
            hideMediaAt = now + SLOT_HOLD_MS;
        } else if (now >= hideMediaAt) {
            hideMediaAt = 0L;
            showMedia(false);
        }
    }

    private void showMedia(boolean show) {
        if (show == mediaShown) return;
        mediaShown = show;
        setVisibility(panel, show ? View.VISIBLE : View.GONE);
        setVisibility(gearBand, show ? View.GONE : View.VISIBLE);
        applyStructure();
    }

    /**
     * Portrait, or no player: speed / SoC / slot stacked vertically, as before.
     * Landscape with a player: speed and SoC on the left, the player on the right.
     */
    private void applyStructure() {
        boolean split = landscape && mediaShown;
        float slotWeight = split ? SPLIT_MEDIA_WEIGHT
                : (mediaShown ? PORTRAIT_MEDIA_SLOT_WEIGHT : GEAR_SLOT_WEIGHT);
        root.setOrientation(split ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        bands.setLayoutParams(weighted(split, split ? SPLIT_BANDS_WEIGHT : BANDS_WEIGHT));
        slot.setLayoutParams(weighted(split, slotWeight));
        slotDivider.setLayoutParams(split
                ? new LinearLayout.LayoutParams(dp(1), MATCH)
                : new LinearLayout.LayoutParams(MATCH, dp(1)));
        ambience.setBlendTop(!split);
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
        setVisibility(chip, current.accessGranted ? View.GONE : View.VISIBLE);
        updateSlot(current.active, now);
        if (!current.active) {
            // Keep the last track on screen during the hold-off, just stop the record.
            vinyl.setPlaying(false);
            return;
        }
        renderTrack(current);
        renderButtons(current);
        renderProgress(current, now);
        vinyl.setArt(current.art);
        ambience.setArt(current.art);
        vinyl.setPlaying(current.clockRunning);
    }

    private void renderTrack(NowPlaying current) {
        setText(sourceView, current.source == null ? "" : current.source.toUpperCase(Locale.getDefault()));
        titleView.setText(TextUtils.isEmpty(current.title) ? unknownTitle : current.title);
        setText(artistView, TextUtils.isEmpty(current.artist) ? unknownArtist : current.artist);
    }

    private void renderButtons(NowPlaying current) {
        setUsable(previousCell, current.canPrevious);
        setUsable(nextCell, current.canNext);
        setUsable(playCell, current.canPlayPause);
        setUsable(shuffleCell, current.shuffleSupported);
        tint(shuffleIcon, current.shuffleSupported && current.shuffleOn ? COLOR_ON : COLOR_IDLE);
        setUsable(seekBar, current.canSeek);
        renderPlayIcon(current.playing);
    }

    private void renderPlayIcon(boolean playing) {
        int icon = playing ? R.drawable.hud_ic_pause : R.drawable.hud_ic_play;
        if (icon == shownPlayIcon) return;
        shownPlayIcon = icon;
        playIcon.setImageResource(icon);
        playCell.setContentDescription(activity.getString(playing ? R.string.hud_media_pause : R.string.hud_media_play));
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
        long second = position / 1000L;
        if (second == shownElapsedSecond) return;
        shownElapsedSecond = second;
        elapsedView.setText(PlaybackClock.format(position));
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
