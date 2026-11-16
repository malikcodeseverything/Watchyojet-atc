package com.watchyojet.engine;

import java.util.Locale;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.Resolution;

/**
 * Turns a resolution into a short, controller-style instruction such as
 * "DAL123 climb and maintain 12,000 ft". Must be called before the maneuver is applied,
 * because the wording depends on the aircraft's current state.
 */
final class Instructions {

    private static final double FLIGHT_LEVEL_FLOOR_FT = 18_000;

    private Instructions() {
    }

    static String describe(Resolution resolution) {
        Aircraft aircraft = resolution.getAircraft();
        return aircraft.getCallsign() + " " + action(resolution);
    }

    private static String action(Resolution resolution) {
        Aircraft aircraft = resolution.getAircraft();
        if (resolution.isSpeedResolution()) {
            return "reduce speed to " + Math.round(resolution.getNewSpeed()) + " knots";
        }
        if (resolution.isHeadingResolution()) {
            double target = resolution.getNewHeading();
            double turn = ((target - aircraft.getHeading() + 540) % 360) - 180;
            String direction = turn >= 0 ? "right" : "left";
            long heading = Math.round(target) % 360;
            if (heading == 0) heading = 360;
            return "turn " + direction + " heading " + String.format(Locale.ROOT, "%03d", heading);
        }
        double target = resolution.getNewAltitude();
        String verb = target >= aircraft.getAltitude() ? "climb" : "descend";
        return verb + " and maintain " + altitude(target);
    }

    static String altitude(double feet) {
        if (feet >= FLIGHT_LEVEL_FLOOR_FT) {
            return String.format(Locale.ROOT, "FL%03d", Math.round(feet / 100));
        }
        return String.format(Locale.ROOT, "%,d ft", Math.round(feet));
    }
}
