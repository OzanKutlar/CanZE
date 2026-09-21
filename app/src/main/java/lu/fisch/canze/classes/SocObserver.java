package lu.fisch.canze.classes;

/**
 * Turns the quantised user SoC (field 42e.0, 0.02 % per step) into a value that
 * moves continuously on screen.
 *
 * Same idea as SpeedObserver: an alpha-beta observer that predicts along a learned
 * rate every frame and continuously corrects toward the latest measurement. The rate
 * is learned from the timing of the 0.02 % steps, so the hundredths roll at the real
 * charge or discharge speed instead of stalling between steps.
 *
 * Guards:
 *  - the display is clamped to +/- BAND around the last measurement
 *  - an error above DIVERGENCE snaps to the measurement (first sample, resume after a gap)
 *  - when data is stale the learned rate bleeds away and extrapolation stops
 *
 * Not thread-safe: call from the UI thread only.
 */
public final class SocObserver {

    /** Resolution of field 42e.0 in percent. */
    public static final double QUANTUM = 0.02;

    // Critically damped second-order tracker: omega = 0.15 rad/s, zeta = 1.
    private static final double ALPHA_PER_S = 0.30;
    private static final double BETA_PER_S2 = 0.0225;

    private static final double DIVERGENCE = 0.5;        // % error that forces a snap
    private static final double MAX_RATE = 0.25;         // %/s, above any realistic charge rate
    private static final double BAND = 1.5 * QUANTUM;    // max distance from last measurement
    private static final double MAX_DT = 0.1;            // s, largest step integrated at once
    private static final double STALE_DECAY_PER_S = 0.5; // how fast the rate bleeds when stale

    private double soc;
    private double rate;
    private double measured;
    private boolean initialised;

    public static boolean isValidSoc(double value) {
        return !Double.isNaN(value) && value >= 0.0 && value <= 100.0;
    }

    public boolean isInitialised() {
        return initialised;
    }

    public double getDisplaySoc() {
        return soc;
    }

    public double getRate() {
        return rate;
    }

    /** Hard reset to a known value, forgetting the learned rate. */
    public void reset(double value) {
        if (!isValidSoc(value)) return;
        soc = value;
        measured = value;
        rate = 0.0;
        initialised = true;
    }

    /** Registers a fresh measurement. Snaps on the first sample or on a large jump. */
    public void setMeasurement(double value) {
        if (!isValidSoc(value)) return;
        if (!initialised || Math.abs(value - soc) > DIVERGENCE) {
            reset(value);
            return;
        }
        measured = value;
    }

    /**
     * Advances the observer by one frame.
     * @param dtSeconds time since the previous frame
     * @param fresh     false when no measurement arrived recently; stops extrapolation
     */
    public void step(double dtSeconds, boolean fresh) {
        if (!initialised) return;
        double dt = clampDt(dtSeconds);
        if (dt <= 0.0) return;
        if (fresh) {
            predict(dt);
            correct(dt);
        } else {
            settle(dt);
        }
        enforceBounds();
    }

    private void predict(double dt) {
        soc += rate * dt;
    }

    private void correct(double dt) {
        double error = measured - soc;
        soc += ALPHA_PER_S * error * dt;
        rate = clamp(rate + BETA_PER_S2 * error * dt, -MAX_RATE, MAX_RATE);
    }

    private void settle(double dt) {
        rate -= rate * Math.min(1.0, STALE_DECAY_PER_S * dt);
        soc += ALPHA_PER_S * (measured - soc) * dt;
    }

    private void enforceBounds() {
        soc = clamp(soc, measured - BAND, measured + BAND);
        soc = clamp(soc, 0.0, 100.0);
    }

    private static double clampDt(double dt) {
        if (Double.isNaN(dt) || dt <= 0.0) return 0.0;
        return Math.min(dt, MAX_DT);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
