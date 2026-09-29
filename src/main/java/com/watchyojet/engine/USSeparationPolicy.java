package com.watchyojet.engine;

import com.watchyojet.model.Aircraft;

/**
 * Simplified United States radar-separation profiles for the simulator.
 *
 * <p>The profiles deliberately model operating environments rather than city
 * names. FAA terminal airspace is individually charted, and the OpenSky feed
 * does not expose controller jurisdiction, flight rules, wake category, or
 * assigned procedures. Treating every city as if it had a unique numeric rule
 * would therefore create false precision.</p>
 */
public final class USSeparationPolicy {

    public enum Zone {
        TERMINAL("Terminal", 3.0, 120.0),
        EN_ROUTE("En route", 5.0, 300.0);

        private final String label;
        private final double lateralNm;
        private final double lookaheadSeconds;

        Zone(String label, double lateralNm, double lookaheadSeconds) {
            this.label = label;
            this.lateralNm = lateralNm;
            this.lookaheadSeconds = lookaheadSeconds;
        }

        public String label() { return label; }
        public double lateralNm() { return lateralNm; }
        public double lookaheadSeconds() { return lookaheadSeconds; }
        public double verticalFeet() { return 1_000.0; }
    }

    private static final double TERMINAL_CEILING_FEET = 10_000.0;

    private USSeparationPolicy() {}

    /**
     * Uses the more conservative environment when a pair straddles a boundary.
     * The live feed currently covers the Philadelphia terminal region; the same
     * profiles are reusable for any U.S. city without pretending that an
     * airport's individually tailored Class B/C shelves are known.
     */
    public static Zone zoneFor(Aircraft first, Aircraft second) {
        double highestAltitude = Math.max(first.getAltitude(), second.getAltitude());
        return highestAltitude < TERMINAL_CEILING_FEET ? Zone.TERMINAL : Zone.EN_ROUTE;
    }
}
