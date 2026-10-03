package lu.fisch.canze.widgets;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

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
 * landscape. Gear and player crossfade; the gear band returns a few seconds after the
 * session ends so track changes do not flicker.
 *
 * Motion: press feedback on every control, optimistic play/pause with a morph and pulse,
 * directional text transitions and a progress glide on track changes. Every animation is
 * settled on pause, so nothing runs in the background. Main thread only.
 * Below Android 5.0 the player never appears.
 */
public final class HudMediaPanel implements NowPlayingListener {

    // ------------------------------------------------------------------ timing

    private static final long TICK_INTERVAL_MS = 250L;
    private static final long SLOT_HOLD_MS = 3000L;
    private static final long SLOT_FADE_MS = 250L;
    private static final long SEEK_SETTLE_WINDOW_MS = 2000L;
    private static final long SEEK_MATCH_TOLERANCE_MS = 3000L;
    private static final long OPTIMISTIC_WINDOW_MS = 1500L;
    private static final long SKIP_DIRECTION_WINDOW_MS = 3000L;
    private static final long DISC_RETURN_MS = 2000L;
    private static final long ALPHA_FADE_MS = 200L;
    private static final long MORPH_HALF_MS = 90L;
    private static final long PULSE_MS = 350L;
    private static final long NUDGE_HALF_MS = 100L;
    private static final long SHUFFLE_COLOR_MS = 250L;
    private static final long HOP_UP_MS = 110L;
    private static final long HOP_DOWN_MS = 140L;
    private static final long PROGRESS_GLIDE_MS = 300L;

    // ------------------------------------------------------------------ shapes

    private static final float NUDGE_DP = 6f;
    private static final float HOP_DP = 4f;
    private static final float MORPH_SCALE = 0.4f;
    private static final float PULSE_START_ALPHA = 0.8f;
    private static final float PULSE_END_SCALE = 1.35f;
    private static final float SOFT_PRESS_SCALE = 0.95f;
    private static final float DISABLED_ALPHA = 0.35f;
    private static final int ART_SIZE_DP = 320;
    private static final float BANDS_WEIGHT = 2f;
    private static final float GEAR_SLOT_WEIGHT = 1f;
    private static final float PORTRAIT_MEDIA_SLOT_WEIGHT = 1.25f;
    private static final float SPLIT_BANDS_WEIGHT = 1f;
    private static final float SPLIT_MEDIA_WEIGHT = 1f;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    // ------------------------------------------------------------------ colours & misc

    private static final int COLOR_ON = 0xFF00E676;
    private static final int COLOR_IDLE = 0xFFECEFF1;
    private static final int COLOR_TIME = 0xFFCFD8DC;
    private static final long NO_VALUE = Long.MIN_VALUE;
    private static final String NO_TIME = "--:--";
    private static final Interpolator ACCELERATE = new AccelerateInterpolator();
    private static final Interpolator DECELERATE = new DecelerateInterpolator();

    private final Activity activity;
    private final NowPlayingTracker tracker;
    private final TrackTransition transition;
    private final String unknownTitle;
    private final String unknownArtist;
    private final Drawable thumbNormal;
    private final Drawable thumbPressed;

    private LinearLayout root;
    private View bands;
    private View slotDivider;
    private View slot;
    private View gearBand;
    private View chip;
    private View panel;
    private CoverAmbienceView ambience;
    private VinylDiscView vinyl;
    private View textBlock;
    private TextView sourceView;
    private MarqueeTextView titleView;
    private TextView artistView;
    private TextView elapsedView;
    private TextView durationView;
    private SeekBar seekBar;
    private View shuffleCell;
    private ImageView shuffleIcon;
    private View previousCell;
    private View previousIcon;
    private View playCell;
    private View playButton;
    private ImageView playIcon;
    private View playPulse;
    private View nextCell;
    private View nextIcon;

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

    // Track text currently on screen.
    private boolean hasShownTrack;
    private String displayedSource;
    private String displayedTitle;
    private String displayedArtist;

    // Tap feedback state.
    private boolean playIconInitialised;
    private boolean shownPlaying;
    private boolean optimisticPlaying;
    private long optimisticUntil;
    private int lastSkipDirection = TrackTransition.DIRECTION_NEXT;
    private long lastSkipAt;

    // Carousel: the track the record on screen belongs to, and a pending skip.
    private String discKey;
    private int discExitDirection = TrackTransition.DIRECTION_NEXT;
    private long discExitAt;
    private boolean shuffleColorInitialised;
    private int shuffleTargetColor = COLOR_IDLE;
    private int shuffleShownColor = COLOR_IDLE;
    private ValueAnimator shuffleColorAnimator;
    private ValueAnimator progressGlide;

    private final SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser && dragging) previewSeek(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
            dragging = true;
            cancelAnimator(progressGlide);
            setSeekHighlight(true);
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
            dragging = false;
            setSeekHighlight(false);
            commitSeek(bar.getProgress());
        }
    };

    private final Runnable applyLatestTrack = new Runnable() {
        @Override
        public void run() {
            applyTrackText(state);
        }
    };

    private final Runnable morphInTask = new Runnable() {
        @Override
        public void run() {
            playIcon.setImageResource(playIconResource());
            ViewCompat.animate(playIcon).scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(MORPH_HALF_MS).setInterpolator(DECELERATE).start();
        }
    };

    public HudMediaPanel(Activity activity) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        this.activity = activity;
        this.unknownTitle = activity.getString(R.string.hud_media_unknown_title);
        this.unknownArtist = activity.getString(R.string.hud_media_unknown_artist);
        this.thumbNormal = ContextCompat.getDrawable(activity, R.drawable.hud_media_seek_thumb);
        this.thumbPressed = ContextCompat.getDrawable(activity, R.drawable.hud_media_seek_thumb_pressed);
        this.state = NowPlaying.idle(MediaAccess.isGranted(activity));
        bindViews();
        this.transition = new TrackTransition(textBlock, activity.getResources().getDisplayMetrics().density);
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
        settleAnimations();
        if (tracker != null) tracker.stop();
    }

    public void release() {
        settleAnimations();
        if (tracker != null) tracker.release();
    }

    /** Called from the HUD's 50 ms tick: seek position, optimistic expiry, gear hand-back. */
    public void onTick() {
        if (tracker == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastTickAt < TICK_INTERVAL_MS) return;
        lastTickAt = now;
        returnDiscIfIgnored(now);
        if (!state.active) {
            updateSlot(false, now);
            return;
        }
        renderProgress(state, now);
        if (optimisticUntil != 0L && now >= optimisticUntil) renderPlayState(state, now);
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
        textBlock = find(R.id.hudMediaTextBlock);
        sourceView = find(R.id.hudMediaSource);
        titleView = find(R.id.hudMediaTitle);
        artistView = find(R.id.hudMediaArtist);
        elapsedView = find(R.id.hudMediaElapsed);
        durationView = find(R.id.hudMediaDuration);
        seekBar = find(R.id.hudMediaSeek);
        shuffleCell = find(R.id.hudMediaShuffle);
        shuffleIcon = find(R.id.hudMediaShuffleIcon);
        previousCell = find(R.id.hudMediaPrevious);
        previousIcon = find(R.id.hudMediaPreviousIcon);
        playCell = find(R.id.hudMediaPlay);
        playButton = find(R.id.hudMediaPlayButton);
        playIcon = find(R.id.hudMediaPlayIcon);
        playPulse = find(R.id.hudMediaPlayPulse);
        nextCell = find(R.id.hudMediaNext);
        nextIcon = find(R.id.hudMediaNextIcon);
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
        PressFeedback.attach(shuffleCell, shuffleIcon);
        PressFeedback.attach(previousCell, previousIcon);
        PressFeedback.attach(playCell, playButton);
        PressFeedback.attach(nextCell, nextIcon);
        DiscGestures.attach(vinyl, new DiscGestures.Callback() {
            @Override
            public void onDrag(float offsetPx) {
                vinyl.setDragOffset(offsetPx);
            }

            @Override
            public void onRelease(float offsetPx, boolean commit) {
                onDiscReleased(offsetPx, commit);
            }
        });
        PressFeedback.attach(chip, null, SOFT_PRESS_SCALE);
        View.OnClickListener playPause = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayPauseTapped();
            }
        };
        playCell.setOnClickListener(playPause);
        vinyl.setOnClickListener(playPause);
        previousCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSkipTapped(TrackTransition.DIRECTION_PREVIOUS);
            }
        });
        nextCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSkipTapped(TrackTransition.DIRECTION_NEXT);
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

    // ------------------------------------------------------------------ taps

    /** Optimistic: the icon, pulse and disc react at once; the player confirms later. */
    private void onPlayPauseTapped() {
        NowPlaying current = state;
        if (tracker == null || !current.active || !current.canPlayPause) return;
        boolean target = !shownPlaying;
        long now = SystemClock.elapsedRealtime();
        optimisticPlaying = target;
        optimisticUntil = now + OPTIMISTIC_WINDOW_MS;
        pulsePlay();
        renderPlayState(current, now);
        if (target) {
            tracker.play();
        } else {
            tracker.pause();
        }
    }

    /**
     * Buttons and disc swipes. The record leaves at once (left for next, right for
     * previous); the new one slides in when the player reports the track change.
     *
     * @return false when the player cannot skip in that direction
     */
    private boolean onSkipTapped(int direction) {
        NowPlaying current = state;
        if (tracker == null || !current.active) return false;
        boolean next = direction == TrackTransition.DIRECTION_NEXT;
        if (next ? !current.canNext : !current.canPrevious) return false;
        long now = SystemClock.elapsedRealtime();
        lastSkipDirection = direction;
        lastSkipAt = now;
        discExitDirection = direction;
        discExitAt = now;
        vinyl.exit(direction);
        nudge(next ? nextCell : previousCell, direction);
        if (next) {
            tracker.next();
        } else {
            tracker.previous();
        }
        return true;
    }

    /** Drag the record left for the next song, right for the previous one. */
    private void onDiscReleased(float offsetPx, boolean commit) {
        if (commit && offsetPx != 0f) {
            int direction = offsetPx < 0f
                    ? TrackTransition.DIRECTION_NEXT
                    : TrackTransition.DIRECTION_PREVIOUS;
            if (onSkipTapped(direction)) return;
        }
        vinyl.releaseDrag();
    }

    /** Direction of a skip made in the last few seconds; automatic advances count as next. */
    private int recentSkipDirection(long now) {
        boolean recent = lastSkipAt != 0L && now - lastSkipAt <= SKIP_DIRECTION_WINDOW_MS;
        return recent ? lastSkipDirection : TrackTransition.DIRECTION_NEXT;
    }

    /** A skip the player ignored (e.g. "previous" restarting the song) brings the record back. */
    private void returnDiscIfIgnored(long now) {
        if (discExitAt == 0L || now - discExitAt < DISC_RETURN_MS) return;
        discExitAt = 0L;
        if (vinyl.isAwaitingTrack()) vinyl.enter(-discExitDirection);
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
        if (!show) resetTrackDisplay();
        applyStructure();
        crossfade(show ? panel : gearBand, show ? gearBand : panel);
    }

    private void crossfade(View incoming, final View outgoing) {
        ViewCompat.animate(incoming).cancel();
        ViewCompat.animate(outgoing).cancel();
        if (incoming.getVisibility() != View.VISIBLE) {
            incoming.setAlpha(0f);
            incoming.setVisibility(View.VISIBLE);
        }
        ViewCompat.animate(incoming).alpha(1f)
                .setDuration(SLOT_FADE_MS).setInterpolator(DECELERATE).start();
        ViewCompat.animate(outgoing).alpha(0f)
                .setDuration(SLOT_FADE_MS).setInterpolator(ACCELERATE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        outgoing.setVisibility(View.GONE);
                    }
                }).start();
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

    /** The next session starts fresh: no text transition, no colour or icon morph. */
    private void resetTrackDisplay() {
        transition.finish();
        hasShownTrack = false;
        discKey = null;
        discExitAt = 0L;
        vinyl.showInPlace();
        displayedSource = null;
        displayedTitle = null;
        displayedArtist = null;
        playIconInitialised = false;
        shuffleColorInitialised = false;
        optimisticUntil = 0L;
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

    private void setSeekHighlight(boolean active) {
        Drawable thumb = active ? thumbPressed : thumbNormal;
        if (thumb != null) seekBar.setThumb(thumb);
        elapsedView.setTextColor(active ? COLOR_ON : COLOR_TIME);
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
        renderTrack(current, now);
        renderButtons(current);
        renderPlayState(current, now);
        renderProgress(current, now);
        renderDisc(current, now);
        ambience.setArt(current.art);
    }

    /** New track: one coalesced text transition, a record drop and a progress glide. */
    private void renderTrack(NowPlaying current, long now) {
        if (!hasShownTrack) {
            hasShownTrack = true;
            applyTrackText(current);
            return;
        }
        if (isDisplayedTrack(current)) {
            if (!transition.isFadingOut()) applyTrackText(current);
            return;
        }
        if (transition.isFadingOut()) return; // the swap will pick up this newest state
        transition.run(recentSkipDirection(now), applyLatestTrack);
        glideProgress(PlaybackClock.toPermille(current.positionAt(now), current.durationMs));
    }

    /**
     * Carousel: on a new track the old record slides out (left for next, right for previous)
     * and the new one slides in from the other side. After a tap or swipe the old record has
     * already left, so only the entry remains.
     */
    private void renderDisc(NowPlaying current, long now) {
        String key = current.source + '|' + current.title;
        if (discKey == null || key.equals(discKey)) {
            discKey = key;
            vinyl.setArt(current.art);
            return;
        }
        discKey = key;
        discExitAt = 0L;
        int direction = recentSkipDirection(now);
        if (!vinyl.isAwaitingTrack()) vinyl.exit(direction);
        vinyl.setArt(current.art);
        vinyl.enter(direction);
    }

    /** Same source and title; an artist that only fills in later counts as the same track. */
    private boolean isDisplayedTrack(NowPlaying current) {
        boolean artistCompatible = TextUtils.isEmpty(current.artist)
                || TextUtils.isEmpty(displayedArtist)
                || TextUtils.equals(current.artist, displayedArtist);
        return artistCompatible
                && TextUtils.equals(current.source, displayedSource)
                && TextUtils.equals(current.title, displayedTitle);
    }

    private void applyTrackText(NowPlaying current) {
        displayedSource = current.source;
        displayedTitle = current.title;
        displayedArtist = current.artist;
        setText(sourceView, current.source == null ? "" : current.source.toUpperCase(Locale.getDefault()));
        titleView.setText(TextUtils.isEmpty(current.title) ? unknownTitle : current.title);
        setText(artistView, TextUtils.isEmpty(current.artist) ? unknownArtist : current.artist);
    }

    private void renderButtons(NowPlaying current) {
        setUsable(previousCell, current.canPrevious);
        setUsable(nextCell, current.canNext);
        setUsable(playCell, current.canPlayPause);
        setUsable(shuffleCell, current.shuffleSupported);
        setUsable(seekBar, current.canSeek);
        renderShuffle(current);
    }

    /** Optimistic state wins until the player confirms it or the window runs out. */
    private void renderPlayState(NowPlaying current, long now) {
        boolean optimistic = optimisticUntil != 0L && now < optimisticUntil
                && current.playing != optimisticPlaying;
        if (!optimistic) optimisticUntil = 0L;
        renderPlayIcon(optimistic ? optimisticPlaying : current.playing);
        vinyl.setPlaying(optimistic ? optimisticPlaying : current.clockRunning);
    }

    private void renderPlayIcon(boolean playing) {
        if (playIconInitialised && playing == shownPlaying) return;
        boolean animate = playIconInitialised;
        playIconInitialised = true;
        shownPlaying = playing;
        playCell.setContentDescription(activity.getString(playing ? R.string.hud_media_pause : R.string.hud_media_play));
        if (!animate) {
            resetPlayIcon();
            return;
        }
        ViewCompat.animate(playIcon).cancel();
        ViewCompat.animate(playIcon).scaleX(MORPH_SCALE).scaleY(MORPH_SCALE).alpha(0f)
                .setDuration(MORPH_HALF_MS).setInterpolator(ACCELERATE)
                .withEndAction(morphInTask).start();
    }

    private int playIconResource() {
        return shownPlaying ? R.drawable.hud_ic_pause : R.drawable.hud_ic_play;
    }

    private void resetPlayIcon() {
        ViewCompat.animate(playIcon).cancel();
        playIcon.setImageResource(playIconResource());
        playIcon.setScaleX(1f);
        playIcon.setScaleY(1f);
        playIcon.setAlpha(1f);
    }

    private void renderShuffle(NowPlaying current) {
        int target = current.shuffleSupported && current.shuffleOn ? COLOR_ON : COLOR_IDLE;
        if (shuffleColorInitialised && target == shuffleTargetColor) return;
        boolean animate = shuffleColorInitialised;
        shuffleColorInitialised = true;
        shuffleTargetColor = target;
        cancelAnimator(shuffleColorAnimator);
        shuffleColorAnimator = null;
        if (!animate) {
            applyShuffleColor(target);
            return;
        }
        ValueAnimator fade = ValueAnimator.ofObject(new ArgbEvaluator(), shuffleShownColor, target);
        fade.setDuration(SHUFFLE_COLOR_MS);
        fade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                applyShuffleColor((Integer) animation.getAnimatedValue());
            }
        });
        shuffleColorAnimator = fade;
        fade.start();
        hop(shuffleIcon);
    }

    private void applyShuffleColor(int color) {
        shuffleShownColor = color;
        shuffleIcon.setColorFilter(color);
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
        if (!isRunning(progressGlide) && seekBar.getProgress() != progress) seekBar.setProgress(progress);
        long second = position / 1000L;
        if (second == shownElapsedSecond) return;
        shownElapsedSecond = second;
        elapsedView.setText(PlaybackClock.format(position));
    }

    // ------------------------------------------------------------------ small animations

    private void pulsePlay() {
        ViewCompat.animate(playPulse).cancel();
        playPulse.setScaleX(1f);
        playPulse.setScaleY(1f);
        playPulse.setAlpha(PULSE_START_ALPHA);
        ViewCompat.animate(playPulse).scaleX(PULSE_END_SCALE).scaleY(PULSE_END_SCALE).alpha(0f)
                .setDuration(PULSE_MS).setInterpolator(DECELERATE).start();
    }

    private void nudge(final View view, int direction) {
        ViewCompat.animate(view).translationX(direction * dp(NUDGE_DP))
                .setDuration(NUDGE_HALF_MS).setInterpolator(DECELERATE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        ViewCompat.animate(view).translationX(0f)
                                .setDuration(NUDGE_HALF_MS).setInterpolator(ACCELERATE).start();
                    }
                }).start();
    }

    private void hop(final View view) {
        ViewCompat.animate(view).translationY(-dp(HOP_DP))
                .setDuration(HOP_UP_MS).setInterpolator(DECELERATE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        ViewCompat.animate(view).translationY(0f)
                                .setDuration(HOP_DOWN_MS).setInterpolator(ACCELERATE).start();
                    }
                }).start();
    }

    private void glideProgress(int target) {
        cancelAnimator(progressGlide);
        progressGlide = null;
        int from = seekBar.getProgress();
        if (dragging || from == target) return;
        ValueAnimator glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(PROGRESS_GLIDE_MS);
        glide.setInterpolator(DECELERATE);
        glide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                seekBar.setProgress((Integer) animation.getAnimatedValue());
            }
        });
        progressGlide = glide;
        glide.start();
    }

    /** Jumps every running animation to its end state, so nothing animates in the background. */
    private void settleAnimations() {
        transition.finish();
        cancelAnimator(progressGlide);
        progressGlide = null;
        cancelAnimator(shuffleColorAnimator);
        shuffleColorAnimator = null;
        if (shuffleColorInitialised) applyShuffleColor(shuffleTargetColor);
        settleSlot();
        resetPlayIcon();
        ViewCompat.animate(playPulse).cancel();
        playPulse.setAlpha(0f);
        settleControl(shuffleCell);
        settleControl(previousCell);
        settleControl(playCell);
        settleControl(nextCell);
        settleControl(seekBar);
        settleTransform(shuffleIcon);
        settleTransform(previousIcon);
        settleTransform(nextIcon);
        settleTransform(playButton);
        settleTransform(vinyl);
        vinyl.showInPlace();
        discExitAt = 0L;
        settleTransform(chip);
        optimisticUntil = 0L;
        setSeekHighlight(false);
    }

    private void settleSlot() {
        ViewCompat.animate(panel).cancel();
        ViewCompat.animate(gearBand).cancel();
        panel.setAlpha(1f);
        gearBand.setAlpha(1f);
        setVisibility(panel, mediaShown ? View.VISIBLE : View.GONE);
        setVisibility(gearBand, mediaShown ? View.GONE : View.VISIBLE);
    }

    private static void settleControl(View view) {
        settleTransform(view);
        view.setAlpha(view.isEnabled() ? 1f : DISABLED_ALPHA);
    }

    private static void settleTransform(View view) {
        ViewCompat.animate(view).cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
    }

    // ------------------------------------------------------------------ view helpers

    private static void setText(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static void setVisibility(View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    /** Enabling is immediate; the alpha change fades. */
    private static void setUsable(View view, boolean usable) {
        if (view.isEnabled() == usable) return;
        view.setEnabled(usable);
        ViewCompat.animate(view).alpha(usable ? 1f : DISABLED_ALPHA)
                .setDuration(ALPHA_FADE_MS).setInterpolator(DECELERATE).start();
    }

    private static void cancelAnimator(ValueAnimator animator) {
        if (animator != null) animator.cancel();
    }

    private static boolean isRunning(ValueAnimator animator) {
        return animator != null && animator.isRunning();
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
