package com.watchyojet.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.Conflict;

/**
 * Predicts losses of separation between pairs of aircraft.
 *
 * <p>Each aircraft is projected along a straight line at constant ground speed, track and
 * vertical rate. A pair is in conflict when the time window in which it is inside the lateral
 * minimum overlaps the window in which it is inside the vertical minimum, within the lookahead.
 * Using both windows means a climbing aircraft is flagged only if it actually reaches the other
 * aircraft's level while they are laterally close.
 */
public class ConflictDetector {

    public List<Conflict> detectConflicts(List<Aircraft> aircraft) {
        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < aircraft.size(); i++) {
            for (int j = i + 1; j < aircraft.size(); j++) {
                evaluate(aircraft.get(i), aircraft.get(j), 0, 0).ifPresent(conflicts::add);
            }
        }
        return conflicts;
    }

    /**
     * Evaluates one pair. The margins widen the minima, which is how an active alert is held
     * until the pair is clearly separated instead of flickering at the threshold.
     */
    public Optional<Conflict> evaluate(Aircraft a1, Aircraft a2,
                                       double lateralMarginNm, double verticalMarginFt) {
        double lateral = SeparationStandards.lateralMinimumNm(a1, a2) + lateralMarginNm;
        double vertical = SeparationStandards.VERTICAL_FT + verticalMarginFt;
        double horizon = SeparationStandards.lookaheadSeconds(a1, a2);

        // Relative position (NM) and velocity (NM per second) on a local flat projection.
        double cosLat = Math.cos(Math.toRadians((a1.getLat() + a2.getLat()) / 2.0));
        double dx = (a2.getLon() - a1.getLon()) * 60.0 * cosLat;
        double dy = (a2.getLat() - a1.getLat()) * 60.0;
        double dvx = (velocityX(a2) - velocityX(a1)) / 3600.0;
        double dvy = (velocityY(a2) - velocityY(a1)) / 3600.0;

        // Lateral window: |d + v t| < lateral, a quadratic in t.
        double qa = dvx * dvx + dvy * dvy;
        double qb = 2 * (dx * dvx + dy * dvy);
        double qc = dx * dx + dy * dy - lateral * lateral;
        double lateralStart;
        double lateralEnd;
        if (qa < 1e-12) {
            if (qc >= 0) return Optional.empty();
            lateralStart = 0;
            lateralEnd = horizon;
        } else {
            double discriminant = qb * qb - 4 * qa * qc;
            if (discriminant <= 0) return Optional.empty();
            double root = Math.sqrt(discriminant);
            lateralStart = Math.max(0, (-qb - root) / (2 * qa));
            lateralEnd = Math.min(horizon, (-qb + root) / (2 * qa));
        }
        if (lateralStart >= lateralEnd) return Optional.empty();

        // Vertical window: |dh + dvr t| < vertical, linear in t.
        double dh = a2.getAltitude() - a1.getAltitude();
        double dvr = (a2.getVerticalRate() - a1.getVerticalRate()) / 60.0;
        double verticalStart;
        double verticalEnd;
        if (Math.abs(dvr) < 1e-9) {
            if (Math.abs(dh) >= vertical) return Optional.empty();
            verticalStart = Double.NEGATIVE_INFINITY;
            verticalEnd = Double.POSITIVE_INFINITY;
        } else {
            double t1 = (-vertical - dh) / dvr;
            double t2 = (vertical - dh) / dvr;
            verticalStart = Math.min(t1, t2);
            verticalEnd = Math.max(t1, t2);
        }

        double start = Math.max(lateralStart, verticalStart);
        double end = Math.min(lateralEnd, verticalEnd);
        if (start >= end) return Optional.empty();

        // Report the closest lateral approach inside the window where both minima are infringed.
        double closest = qa < 1e-12 ? start : clamp(-qb / (2 * qa), start, end);
        double px = dx + dvx * closest;
        double py = dy + dvy * closest;
        double distance = Math.sqrt(px * px + py * py);
        double altitudeDiff = Math.abs(dh + dvr * closest);

        return Optional.of(new Conflict(a1, a2, classifySeverity(distance, altitudeDiff),
                closest, distance));
    }

    private static double velocityX(Aircraft a) {
        return a.getSpeed() * Math.sin(Math.toRadians(a.getHeading()));
    }

    private static double velocityY(Aircraft a) {
        return a.getSpeed() * Math.cos(Math.toRadians(a.getHeading()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Conflict.Severity classifySeverity(double distance, double altitudeDiff) {
        if (distance < 1.0 && altitudeDiff < 300) return Conflict.Severity.CRITICAL;
        if (distance < 2.0) return Conflict.Severity.HIGH;
        return Conflict.Severity.MEDIUM;
    }
}
