package com.watchyojet.engine;

import com.watchyojet.model.Aircraft;
import com.watchyojet.simulation.MovementEngine;

/**
 * Lateral positions an aircraft is predicted to pass through, one per second.
 *
 * <p>Used when an aircraft is still turning or changing speed toward an assigned value. It
 * follows the same rules the movement engine flies: a standard-rate turn the short way round and
 * a speed change of 1 knot per second, then a straight line. A trial maneuver is therefore
 * judged on the path the aircraft would actually fly, including the turn itself.
 */
final class PredictedPath {

    final double[] x;
    final double[] y;

    private PredictedPath(double[] x, double[] y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Samples the path for {@code seconds} seconds, in NM east and north of the origin on a flat
     * projection scaled by {@code cosLat}.
     */
    static PredictedPath sample(Aircraft aircraft, double originLat, double originLon,
                                double cosLat, int seconds) {
        double[] xs = new double[seconds + 1];
        double[] ys = new double[seconds + 1];
        double px = (aircraft.getLon() - originLon) * 60.0 * cosLat;
        double py = (aircraft.getLat() - originLat) * 60.0;
        double heading = aircraft.getHeading();
        double speed = aircraft.getSpeed();
        double targetHeading = aircraft.getTargetHeading();
        double targetSpeed = aircraft.getTargetSpeed();
        double sin = Math.sin(Math.toRadians(heading));
        double cos = Math.cos(Math.toRadians(heading));
        xs[0] = px;
        ys[0] = py;

        for (int t = 1; t <= seconds; t++) {
            if (!Double.isNaN(targetHeading)) {
                double turn = ((targetHeading - heading + 540) % 360) - 180;
                if (Math.abs(turn) <= MovementEngine.STANDARD_RATE_DEG_PER_S) {
                    heading = targetHeading;
                    targetHeading = Double.NaN;
                } else {
                    heading = (heading + Math.signum(turn) * MovementEngine.STANDARD_RATE_DEG_PER_S + 360) % 360;
                }
                sin = Math.sin(Math.toRadians(heading));
                cos = Math.cos(Math.toRadians(heading));
            }
            if (!Double.isNaN(targetSpeed)) {
                double change = targetSpeed - speed;
                if (Math.abs(change) <= MovementEngine.SPEED_CHANGE_KT_PER_S) {
                    speed = targetSpeed;
                    targetSpeed = Double.NaN;
                } else {
                    speed += Math.signum(change) * MovementEngine.SPEED_CHANGE_KT_PER_S;
                }
            }
            double step = speed / 3600.0;
            px += step * sin;
            py += step * cos;
            xs[t] = px;
            ys[t] = py;
        }
        return new PredictedPath(xs, ys);
    }
}
