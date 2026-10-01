package lu.fisch.canze.media;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackClockTest {

    private static final long DURATION = 200000L;

    @Test
    public void pausedPositionDoesNotAdvance() {
        assertEquals(5000L, PlaybackClock.estimate(5000L, 1000L, 1f, false, DURATION, 9000L));
    }

    @Test
    public void runningPositionAdvancesWithElapsedTime() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 1f, true, DURATION, 4000L));
    }

    @Test
    public void speedScalesTheAdvance() {
        assertEquals(11000L, PlaybackClock.estimate(5000L, 1000L, 2f, true, DURATION, 4000L));
    }

    @Test
    public void invalidSpeedFallsBackToRealTime() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 0f, true, DURATION, 4000L));
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, Float.NaN, true, DURATION, 4000L));
    }

    @Test
    public void unknownUpdateTimeDoesNotAdvance() {
        assertEquals(5000L, PlaybackClock.estimate(5000L, 0L, 1f, true, DURATION, 4000L));
    }

    @Test
    public void positionIsClampedToDuration() {
        assertEquals(DURATION, PlaybackClock.estimate(199000L, 1000L, 1f, true, DURATION, 10000L));
    }

    @Test
    public void unknownDurationDoesNotClamp() {
        assertEquals(8000L, PlaybackClock.estimate(5000L, 1000L, 1f, true, 0L, 4000L));
    }

    @Test
    public void unknownPositionReadsAsZero() {
        assertEquals(0L, PlaybackClock.estimate(-1L, 1000L, 1f, true, DURATION, 4000L));
    }

    @Test
    public void permilleRoundTrips() {
        assertEquals(250, PlaybackClock.toPermille(50000L, DURATION));
        assertEquals(50000L, PlaybackClock.fromPermille(250, DURATION));
        assertEquals(1000, PlaybackClock.toPermille(DURATION * 2L, DURATION));
        assertEquals(0, PlaybackClock.toPermille(50000L, 0L));
        assertEquals(DURATION, PlaybackClock.fromPermille(5000, DURATION));
    }

    @Test
    public void formatsTimes() {
        assertEquals("0:00", PlaybackClock.format(0L));
        assertEquals("0:00", PlaybackClock.format(-500L));
        assertEquals("3:07", PlaybackClock.format(187000L));
        assertEquals("1:02:03", PlaybackClock.format(3723000L));
    }
}
