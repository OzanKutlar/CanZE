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
 * Cross plane V8 firing geometry and the pressure lobe each event contributes.
 *
 * Firing order 1-8-4-3-6-5-7-2 over 720 degrees. The banks are deliberately unevenly spaced:
 * one bank sees gaps of 90, 180, 90 and 360 degrees rather than an even 180. That asymmetry is
 * the entire reason a cross plane V8 burbles instead of humming, and it is why a flat plane
 * order sounds like a different engine rather than a detuned version of this one.
 *
 * Two lobes per event. The blowdown is the exhaust valve cracking open against cylinder
 * pressure, and carries the fundamental. The overlap chuff is the smaller, later, softer
 * disturbance around valve overlap, and is what stops the train sounding like a bare pulse
 * oscillator.
 *
 * All state free and allocation free: the tables are built once in a static initialiser.
 */
public final class CylinderPulse {

    public static final double CYCLE_RADIANS = 4.0 * Math.PI;

    private static final double DEG = Math.PI / 180.0;

    /** Firing angle of each cylinder within the 720 degree cycle, indexed by cylinder minus one. */
    private static final double[] FIRING_ANGLES = {
            0.0 * DEG,   // Cyl 1, bank 1
            630.0 * DEG, // Cyl 2, bank 2
            270.0 * DEG, // Cyl 3, bank 1
            180.0 * DEG, // Cyl 4, bank 2
            450.0 * DEG, // Cyl 5, bank 1
            360.0 * DEG, // Cyl 6, bank 2
            540.0 * DEG, // Cyl 7, bank 1
            90.0 * DEG   // Cyl 8, bank 2
    };

    private static final int[] BANK_ONE = {0, 2, 4, 6};
    private static final int[] BANK_TWO = {1, 3, 5, 7};

    private static final int TABLE_SIZE = 2048;

    private static final double BLOWDOWN_SPAN = 170.0 * DEG;
    private static final double CHUFF_SPAN = 120.0 * DEG;
    private static final double CHUFF_OFFSET = 210.0 * DEG;
    private static final double CHUFF_LEVEL = 0.22;

    private static final double[] BLOWDOWN = new double[TABLE_SIZE + 1];
    private static final double[] CHUFF = new double[TABLE_SIZE + 1];

    static {
        for (int i = 0; i <= TABLE_SIZE; i++) {
            final double x = (double) i / TABLE_SIZE;

            // sin squared times an exponential decay. Zero value and zero slope at both ends,
            // which is what keeps the pulse free of the buzz a discontinuity would create.
            final double s = Math.sin(x * Math.PI);
            BLOWDOWN[i] = s * s * Math.exp(-0.85 * x);

            final double c = Math.sin(x * Math.PI);
            CHUFF[i] = c * c * Math.exp(-1.9 * x);
        }
    }

    private CylinderPulse() {
    }

    /**
     * @param crankPhase current position within the 720 degree cycle, in radians
     * @return summed pressure contribution of the four bank one cylinders
     */
    public static double bankOne(double crankPhase) {
        return bank(crankPhase, BANK_ONE);
    }

    /**
     * @param crankPhase current position within the 720 degree cycle, in radians
     * @return summed pressure contribution of the four bank two cylinders
     */
    public static double bankTwo(double crankPhase) {
        return bank(crankPhase, BANK_TWO);
    }

    private static double bank(double crankPhase, int[] cylinders) {
        double sum = 0.0;
        for (int cylinder : cylinders) {
            final double firing = FIRING_ANGLES[cylinder];
            sum += sample(BLOWDOWN, crankPhase, firing, BLOWDOWN_SPAN);
            sum += CHUFF_LEVEL * sample(CHUFF, crankPhase, firing + CHUFF_OFFSET, CHUFF_SPAN);
        }
        return sum;
    }

    /**
     * Linearly interpolated table lookup of one lobe.
     *
     * @param table       lobe shape, indexed zero to TABLE_SIZE inclusive
     * @param crankPhase  current crank position in radians
     * @param firingAngle where this lobe starts, in radians, may exceed one cycle
     * @param span        lobe width in radians, must be positive
     */
    private static double sample(double[] table, double crankPhase, double firingAngle, double span) {
        double start = firingAngle;
        if (start >= CYCLE_RADIANS) start -= CYCLE_RADIANS;

        double delta = crankPhase - start;
        if (delta < 0.0) delta += CYCLE_RADIANS;
        if (delta >= span) return 0.0;

        final double position = (delta / span) * TABLE_SIZE;
        int index = (int) position;
        if (index < 0) return 0.0;
        if (index >= TABLE_SIZE) return table[TABLE_SIZE];

        final double frac = position - index;
        return table[index] + (table[index + 1] - table[index]) * frac;
    }
}
