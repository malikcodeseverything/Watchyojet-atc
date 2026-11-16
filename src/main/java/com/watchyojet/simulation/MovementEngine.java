package com.watchyojet.simulation;

import java.util.List;

import com.watchyojet.model.Aircraft;

/**
 * Advances every track by one cycle.
 *
 * <p>Observed traffic coasts on its reported track, speed and vertical rate until the next
 * OpenSky snapshot. Simulated traffic with an assigned altitude, heading or speed moves toward
 * it at realistic rates: the type's climb rate, a standard-rate turn of 3 degrees per second,
 * and a speed change of 1 knot per second.
 */
public class MovementEngine {

    private static final double CYCLE_SECONDS = 2.0;
    static final double STANDARD_RATE_DEG_PER_S = 3.0;
    static final double SPEED_CHANGE_KT_PER_S = 1.0;

    public void updatePositions(List<Aircraft> aircraft) {
        for (Aircraft a : aircraft) {
            turnTowardTarget(a);
            changeSpeedTowardTarget(a);
            move(a);
            climbOrDescend(a);
        }
    }

    private static void turnTowardTarget(Aircraft a) {
        double target = a.getTargetHeading();
        if (Double.isNaN(target)) return;
        double turn = ((target - a.getHeading() + 540) % 360) - 180;
        double step = STANDARD_RATE_DEG_PER_S * CYCLE_SECONDS;
        if (Math.abs(turn) <= step) {
            a.setHeading((target + 360) % 360);
            a.setTargetHeading(Double.NaN);
        } else {
            a.setHeading((a.getHeading() + Math.signum(turn) * step + 360) % 360);
        }
    }

    private static void changeSpeedTowardTarget(Aircraft a) {
        double target = a.getTargetSpeed();
        if (Double.isNaN(target)) return;
        double change = target - a.getSpeed();
        double step = SPEED_CHANGE_KT_PER_S * CYCLE_SECONDS;
        if (Math.abs(change) <= step) {
            a.setSpeed(target);
            a.setTargetSpeed(Double.NaN);
        } else {
            a.setSpeed(a.getSpeed() + Math.signum(change) * step);
        }
    }

    private static void move(Aircraft a) {
        double heading = Math.toRadians(a.getHeading());
        double distanceNm = a.getSpeed() * (CYCLE_SECONDS / 3600.0);
        // 1 degree of latitude is 60 NM; longitude shrinks with cos(latitude).
        double dLat = distanceNm * Math.cos(heading) / 60.0;
        double dLon = distanceNm * Math.sin(heading) / (60.0 * Math.cos(Math.toRadians(a.getLat())));
        a.setLat(a.getLat() + dLat);
        a.setLon(a.getLon() + dLon);
    }

    private static void climbOrDescend(Aircraft a) {
        double target = a.getTargetAltitude();
        if (Double.isNaN(target)) {
            // Observed traffic: coast the reported climb or descent until the next snapshot.
            double climbFt = a.getVerticalRate() * (CYCLE_SECONDS / 60.0);
            a.setAltitude(Math.max(0, a.getAltitude() + climbFt));
            return;
        }
        double next = a.altitudeAfter(CYCLE_SECONDS);
        if (next == target) {
            a.setAltitude(target);
            a.setVerticalRate(0);
            a.setTargetAltitude(Double.NaN);
        } else {
            a.setVerticalRate(Math.signum(target - a.getAltitude()) * a.getType().getClimbRate());
            a.setAltitude(next);
        }
    }
}
