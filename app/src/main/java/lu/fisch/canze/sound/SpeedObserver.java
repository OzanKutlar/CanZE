/*
    CanZE
    Take a closer look at your ZE car

    Copyright (C) 2015 - The CanZE Team
    http://canze.fisch.lu

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or any
    later version.
*/

package lu.fisch.canze.sound;

/**
 * Continuous time alpha beta observer that fuses fast motor torque with slow road speed.
 *
 * The two telemetry sources have very different characters. Motor torque rides on CAN frame 186
 * at roughly twenty samples a second. Road speed comes from 5D7 once a second as an integer.
 * Deriving engine pitch from speed alone means smearing one hertz integer steps, which reads as
 * revs lagging the road. Deriving it from torque alone means integrating an approximate model
 * with no reference, which drifts without bound.
 *
 * So torque drives a prediction and speed continuously corrects it:
 *
 *   predict   v += a(torque) * dt
 *   correct   v += ALPHA * error * dt
 *             bias -= BETA * error * dt
 *
 * The correction runs every control frame rather than only when a new speed sample arrives.
 * A discrete correct on arrival would never fire while the indicated speed sat still, which is
 * exactly when a drifting predictor most needs pulling back. Running continuously at a low per
 * second gain also spreads each correction over hundreds of milliseconds, so it is heard as the
 * engine loading up rather than as a once per second pitch step.
 *
 * The bias term is what makes a one hertz measurement sufficient. The prediction error is not
 * random: it is dominated by road gradient, load, wind, and the fact that the reported torque is
 * measured at the motor rather than at the contact patch. Learning that offset means the model
 * stops fighting the same error every second, and after roughly eight seconds on a gradient the
 * predictor already accounts for it.
 */
public final class SpeedObserver {

    // Vehicle constants. Dynamic rolling circumference rather than geometric, because the speed
    // we are fusing against is itself derived from wheel rotation.
    public static final float WHEEL_CIRCUMFERENCE_M = 1.958f;
    public static final float WHEEL_RADIUS_M = WHEEL_CIRCUMFERENCE_M / (2f * (float) Math.PI);
    public static final float REDUCER_RATIO = 9.3f;

    private static final float MASS_KG = 1500f;
    private static final float ROLLING_N = 162f;
    private static final float DRAG_N_PER_MS2 = 0.38f;

    /** Position gain, per second. Larger tracks the measurement harder and drifts less. */
    private static final float ALPHA_PER_S = 1.6f;

    /** Bias learning gain, per second. Deliberately slow: speed is quantised to whole km/h. */
    private static final float BETA_PER_S = 0.12f;

    /** Beyond this the model is not merely biased, it is wrong. Snap rather than integrate. */
    private static final float DIVERGENCE_KMH = 15f;

    /** Keeps the learned bias from standing in for a broken torque signal. */
    private static final float MAX_BIAS_N = 2500f;

    private float speedKmH = 0f;
    private float biasN = 0f;
    private float accelKmHPerSec = 0f;

    public float getSpeedKmH() {
        return speedKmH;
    }

    public float getAccelKmHPerSec() {
        return accelKmHPerSec;
    }

    public float getBiasN() {
        return biasN;
    }

    public void reset(float speedKmH) {
        this.speedKmH = Math.max(0f, speedKmH);
        this.biasN = 0f;
        this.accelKmHPerSec = 0f;
    }

    /**
     * Advances the model by one control frame.
     *
     * @param torqueNm signed motor torque, negative under regeneration
     * @param dt       frame length in seconds, must be positive
     */
    public void predict(float torqueNm, float dt) {
        if (dt <= 0f || Float.isNaN(torqueNm)) return;

        final float speedMs = speedKmH / 3.6f;

        final float tractiveN = torqueNm * REDUCER_RATIO / WHEEL_RADIUS_M;
        final float dragN = DRAG_N_PER_MS2 * speedMs * speedMs;

        // Rolling resistance and the learned bias only act on a moving vehicle, otherwise they
        // would push a stationary car backwards.
        final float rollingN = (speedMs > 0.05f) ? ROLLING_N : 0f;

        final float netN = tractiveN - rollingN - dragN - biasN;
        final float accelMs2 = netN / MASS_KG;

        float next = speedMs + accelMs2 * dt;
        if (next < 0f) next = 0f;

        speedKmH = next * 3.6f;
        accelKmHPerSec = accelMs2 * 3.6f;
    }

    /**
     * Pulls the prediction toward the measurement and learns the persistent offset.
     *
     * @param measuredKmH latest reported road speed
     * @param dt          frame length in seconds, must be positive
     */
    public void correct(float measuredKmH, float dt) {
        if (dt <= 0f || Float.isNaN(measuredKmH)) return;
        if (measuredKmH < 0f) measuredKmH = 0f;

        final float error = measuredKmH - speedKmH;

        if (error > DIVERGENCE_KMH || error < -DIVERGENCE_KMH) {
            speedKmH = measuredKmH;
            biasN = 0f;
            accelKmHPerSec = 0f;
            return;
        }

        speedKmH += ALPHA_PER_S * error * dt;
        if (speedKmH < 0f) speedKmH = 0f;

        // A positive error means we are predicting too slow, so the modelled resistance is too
        // high and the bias must come down.
        biasN -= BETA_PER_S * error * dt * MASS_KG;
        if (biasN > MAX_BIAS_N) biasN = MAX_BIAS_N;
        if (biasN < -MAX_BIAS_N) biasN = -MAX_BIAS_N;
    }

    /**
     * @param speedKmH road speed
     * @return electric motor speed in rpm at that road speed
     */
    public static float motorRpm(float speedKmH) {
        if (speedKmH <= 0f) return 0f;
        final float wheelRpm = (speedKmH / 3.6f) * 60f / WHEEL_CIRCUMFERENCE_M;
        return wheelRpm * REDUCER_RATIO;
    }
}
