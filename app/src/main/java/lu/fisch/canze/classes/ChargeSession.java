/*
    CanZE
    Take a peek into your car's inner workings

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/

package lu.fisch.canze.classes;

import android.content.SharedPreferences;

/**
 * One charging session: start time, energy added and the calibration used to fill gaps.
 *
 * Energy is the trapezoidal integral of the charging power. Samples further apart than
 * MAX_INTEGRATION_GAP_MS are not integrated; instead, when the SoC resumes after such a
 * gap, the SoC rise over the gap is converted to kWh using the kWh-per-% measured live in
 * this session. Without enough calibration the gap is left out and the value is flagged
 * as estimated.
 *
 * Thread-safe: the poller thread feeds samples while the UI thread reads and saves.
 * A null SharedPreferences gives an in-memory session (used by demo mode).
 */
public final class ChargeSession {

    private static final long MAX_INTEGRATION_GAP_MS = 5000L;
    private static final long ESTIMATE_MARK_GAP_MS = 60000L;
    private static final double MIN_CALIBRATION_SOC = 1.0;
    private static final double MAX_POWER_KW = 400.0;
    private static final double MS_PER_HOUR = 3600000.0;

    private static final String KEY_START = "chargeSession.startMs";
    private static final String KEY_ENERGY = "chargeSession.energyKwh";
    private static final String KEY_LIVE_KWH = "chargeSession.liveKwh";
    private static final String KEY_LIVE_SOC = "chargeSession.liveSocRise";
    private static final String KEY_LAST_SOC = "chargeSession.lastSoc";
    private static final String KEY_LAST_SOC_MS = "chargeSession.lastSocMs";
    private static final String KEY_ESTIMATED = "chargeSession.estimated";

    private final SharedPreferences prefs;

    private long startMs;
    private double energyKwh;
    private double liveKwh;
    private double liveSocRise;
    private double lastSoc = Double.NaN;
    private long lastSocMs;
    private boolean estimated;
    private double lastPowerKw;
    private long lastPowerMs;

    public ChargeSession(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Restores the stored session, or starts a new one at nowMs if there is none. */
    public synchronized void load(long nowMs) {
        if (prefs == null || !prefs.contains(KEY_START)) {
            reset(nowMs);
            return;
        }
        try {
            startMs = prefs.getLong(KEY_START, nowMs);
            energyKwh = readDouble(KEY_ENERGY, 0.0);
            liveKwh = readDouble(KEY_LIVE_KWH, 0.0);
            liveSocRise = readDouble(KEY_LIVE_SOC, 0.0);
            lastSoc = readDouble(KEY_LAST_SOC, Double.NaN);
            lastSocMs = prefs.getLong(KEY_LAST_SOC_MS, 0L);
            estimated = prefs.getBoolean(KEY_ESTIMATED, false);
        } catch (ClassCastException e) {
            // Stored with another type: start over rather than guess.
            reset(nowMs);
            return;
        }
        sanitise(nowMs);
    }

    private void sanitise(long nowMs) {
        if (startMs <= 0L || startMs > nowMs) startMs = nowMs;
        if (!isNonNegative(energyKwh)) energyKwh = 0.0;
        if (!isNonNegative(liveKwh)) liveKwh = 0.0;
        if (Double.isNaN(liveSocRise) || Double.isInfinite(liveSocRise)) liveSocRise = 0.0;
        if (!isValidSoc(lastSoc) || lastSocMs <= 0L || lastSocMs > nowMs) {
            lastSoc = Double.NaN;
            lastSocMs = 0L;
        }
    }

    /** Starts a fresh session at nowMs and persists it. */
    public synchronized void reset(long nowMs) {
        startMs = nowMs;
        energyKwh = 0.0;
        liveKwh = 0.0;
        liveSocRise = 0.0;
        lastSoc = Double.NaN;
        lastSocMs = 0L;
        estimated = false;
        lastPowerKw = 0.0;
        lastPowerMs = 0L;
        save();
    }

    /** Integrates one charging power sample in kW. */
    public synchronized void onPower(double kw, long nowMs) {
        if (Double.isNaN(kw) || Double.isInfinite(kw)) return;
        double clamped = Math.max(0.0, Math.min(MAX_POWER_KW, kw));
        long dt = nowMs - lastPowerMs;
        if (lastPowerMs > 0L && dt > 0L && dt <= MAX_INTEGRATION_GAP_MS) {
            double added = (lastPowerKw + clamped) * 0.5 * dt / MS_PER_HOUR;
            energyKwh += added;
            liveKwh += added;
        }
        lastPowerKw = clamped;
        lastPowerMs = nowMs;
    }

    /** Tracks the SoC for calibration and fills the energy of gaps without power samples. */
    public synchronized void onSoc(double soc, long nowMs) {
        if (!isValidSoc(soc)) return;
        if (lastSocMs > 0L && nowMs > lastSocMs) {
            long dt = nowMs - lastSocMs;
            double rise = soc - lastSoc;
            if (dt > MAX_INTEGRATION_GAP_MS) {
                fillGap(rise, dt);
            } else {
                liveSocRise += rise;
            }
        }
        lastSoc = soc;
        lastSocMs = nowMs;
    }

    private void fillGap(double rise, long gapMs) {
        if (rise <= 0.0) return;
        if (liveSocRise >= MIN_CALIBRATION_SOC && liveKwh > 0.0) {
            energyKwh += rise * liveKwh / liveSocRise;
            if (gapMs > ESTIMATE_MARK_GAP_MS) estimated = true;
        } else {
            // Not calibrated yet: the energy of this gap is missing.
            estimated = true;
        }
    }

    public synchronized long getElapsedMs(long nowMs) {
        return Math.max(0L, nowMs - startMs);
    }

    public synchronized double getEnergyKwh() {
        return energyKwh;
    }

    public synchronized boolean isEstimated() {
        return estimated;
    }

    /** Persists the session; a no-op for in-memory sessions. */
    public synchronized void save() {
        if (prefs == null) return;
        prefs.edit()
                .putLong(KEY_START, startMs)
                .putLong(KEY_ENERGY, Double.doubleToRawLongBits(energyKwh))
                .putLong(KEY_LIVE_KWH, Double.doubleToRawLongBits(liveKwh))
                .putLong(KEY_LIVE_SOC, Double.doubleToRawLongBits(liveSocRise))
                .putLong(KEY_LAST_SOC, Double.doubleToRawLongBits(lastSoc))
                .putLong(KEY_LAST_SOC_MS, lastSocMs)
                .putBoolean(KEY_ESTIMATED, estimated)
                .apply();
    }

    private double readDouble(String key, double fallback) {
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private static boolean isNonNegative(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0;
    }

    private static boolean isValidSoc(double v) {
        return !Double.isNaN(v) && v >= 0.0 && v <= 100.0;
    }
}
