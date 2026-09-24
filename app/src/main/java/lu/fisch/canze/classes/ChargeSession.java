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
 * One charging session: start time and the latest charging power.
 *
 * Energy added is the latest charging power multiplied by the time since the session
 * started. This assumes the power has been constant since the start of the charge, which
 * holds for the Fluence Z.E.: it charges at the same rate on any AC source and does not taper.
 *
 * Thread-safe: the poller thread feeds samples while the UI thread reads and saves.
 * A null SharedPreferences gives an in-memory session (used by demo mode).
 */
public final class ChargeSession {

    private static final double MAX_POWER_KW = 400.0;
    private static final double MS_PER_HOUR = 3600000.0;

    private static final String KEY_START = "chargeSession.startMs";
    private static final String KEY_POWER = "chargeSession.powerKw";

    /** Keys written by the previous integrating implementation; removed on save. */
    private static final String[] OBSOLETE_KEYS = {
            "chargeSession.energyKwh",
            "chargeSession.liveKwh",
            "chargeSession.liveSocRise",
            "chargeSession.lastSoc",
            "chargeSession.lastSocMs",
            "chargeSession.estimated"
    };

    private final SharedPreferences prefs;

    private long startMs;
    private double powerKw;

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
            powerKw = readDouble(KEY_POWER, 0.0);
        } catch (ClassCastException e) {
            // Stored with another type: start over rather than guess.
            reset(nowMs);
            return;
        }
        sanitise(nowMs);
    }

    private void sanitise(long nowMs) {
        if (startMs <= 0L || startMs > nowMs) startMs = nowMs;
        if (!isValidPower(powerKw)) powerKw = 0.0;
    }

    /** Starts a fresh session at nowMs and persists it. */
    public synchronized void reset(long nowMs) {
        startMs = nowMs;
        powerKw = 0.0;
        save();
    }

    /** Records the current charging power in kW. */
    public synchronized void onPower(double kw) {
        if (Double.isNaN(kw) || Double.isInfinite(kw)) return;
        powerKw = Math.max(0.0, Math.min(MAX_POWER_KW, kw));
    }

    public synchronized long getElapsedMs(long nowMs) {
        return Math.max(0L, nowMs - startMs);
    }

    /** Current kW multiplied by the hours since the session started. */
    public synchronized double getEnergyKwh(long nowMs) {
        return powerKw * getElapsedMs(nowMs) / MS_PER_HOUR;
    }

    /** Persists the session; a no-op for in-memory sessions. */
    public synchronized void save() {
        if (prefs == null) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_START, startMs)
                .putLong(KEY_POWER, Double.doubleToRawLongBits(powerKw));
        for (String key : OBSOLETE_KEYS) editor.remove(key);
        editor.apply();
    }

    private double readDouble(String key, double fallback) {
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private static boolean isValidPower(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0 && v <= MAX_POWER_KW;
    }
}
