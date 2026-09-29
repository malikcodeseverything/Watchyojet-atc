package com.watchyojet.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftCategory;
import com.watchyojet.model.Conflict;
import com.watchyojet.model.Resolution;

public class ResolutionEngine {

    // ── Separation standards ──────────────────────────────────────────────────
    private static final double ALTITUDE_STEP     = 1000;   // ft per search step
    private static final double ALTITUDE_BUFFER   = 1000;   // ft minimum vertical separation
    private static final double MIN_ALTITUDE      = 3000;   // ft AGL floor for IFR
    private static final double HIGH_ALT_FLOOR    = 18000;  // ft — FL180 IFR/VFR band boundary
    private static final double PROXIMITY_NM      = 10.0;   // NM radius for altitude slot checks
    private static final double HDG_NEIGHBOR_NM   = 5.0;    // NM radius for heading safety checks
    private static final double HDG_MIN_SEP       = 3.5;    // NM — target separation after turn (above 3 NM threshold)
    private static final double SPEED_REDUCTION   = 0.90;   // conservative 10% reduction
    private static final double ABSOLUTE_SPEED_FLOOR = 140; // no low-speed commands from inferred types
    private static final double SPEED_MIN_SEP     = 5.0;    // NM after a proposed speed change

    // Thresholds for escalating to aggressive manoeuvres
    private static final double CRITICAL_DCPA     = 0.5;    // NM
    private static final double CRITICAL_ALT_DIFF = 200;    // ft

    // ── Cluster resolution (main entry point from ATCEngine) ──────────────────

    /**
     * Resolves a connected cluster of conflicts (aircraft sharing ≥1 conflict).
     *
     * Processes conflicts inside the cluster in CRITICAL → HIGH → MEDIUM order,
     * breaking ties by earliest tCPA so the most urgent pair is handled first.
     *
     * committed: running record of what this cycle has already decided for each
     *            aircraft [alt, hdg, spd], NaN = dimension not committed yet.
     *            Resolution logic reads effective state from here rather than the
     *            live aircraft object so later pairs in the same cluster benefit
     *            from earlier resolutions in the same cycle.
     *
     * locked: callsigns that must not be moved (on cooldown, already committed
     *         to a manoeuvre this cycle).
     */
    public List<Resolution> resolveCluster(List<Conflict> cluster,
                                           List<Aircraft> allAircraft,
                                           Map<String, double[]> committed,
                                           Set<String> locked,
                                           Set<String> hardPairs) {

        cluster.sort(java.util.Comparator.comparing(Conflict::getSeverity)
                                         .thenComparingDouble(Conflict::getTCPA));

        List<Resolution> results = new ArrayList<>();

        for (Conflict c : cluster) {
            Aircraft preferred = selectAircraftToMove(c);
            Aircraft other     = (preferred == c.getA1()) ? c.getA2() : c.getA1();

            Resolution r = null;

            if (hardPairs.contains(conflictKey(c))) {
                // Bypass cooldown, but never bypass the normal safety checks.
                r = resolveOne(preferred, other, allAircraft, committed, true);
                if (r == null) {
                    r = resolveOne(other, preferred, allAircraft, committed, true);
                }
            } else {
                double altDiffNow = Math.abs(getEffectiveAlt(c.getA1(), committed)
                                           - getEffectiveAlt(c.getA2(), committed));
                boolean critical  = c.getDCPA() < CRITICAL_DCPA && altDiffNow < CRITICAL_ALT_DIFF;

                if (!locked.contains(preferred.getId())) {
                    r = resolveOne(preferred, other, allAircraft, committed, critical);
                }
                if (r == null && !locked.contains(other.getId())) {
                    r = resolveOne(other, preferred, allAircraft, committed, critical);
                }
            }

            if (r != null) {
                results.add(r);
                applyToCommitted(r, committed);
                locked.add(r.getAircraft().getId());
            }
        }

        return results;
    }

    private static String conflictKey(Conflict c) {
        String id1 = c.getA1().getId();
        String id2 = c.getA2().getId();
        return id1.compareTo(id2) < 0 ? id1 + ":" + id2 : id2 + ":" + id1;
    }

    // ── Per-aircraft resolution: altitude → heading → speed ───────────────────

    private Resolution resolveOne(Aircraft moving, Aircraft conflicting,
                                   List<Aircraft> all,
                                   Map<String, double[]> committed,
                                   boolean critical) {
        Resolution r = tryAltitude(moving, conflicting, all, committed, critical);
        if (r == null) r = tryHeading(moving, conflicting, all, committed, critical);
        if (r == null) r = trySpeed(moving, conflicting, all, committed);
        return r;
    }

    // ── Altitude resolution ───────────────────────────────────────────────────

    private Resolution tryAltitude(Aircraft moving, Aircraft conflicting,
                                    List<Aircraft> all,
                                    Map<String, double[]> committed,
                                    boolean critical) {

        double currentAlt  = getEffectiveAlt(moving, committed);
        double conflictAlt = getEffectiveAlt(conflicting, committed);
        double maxAlt      = moving.getType().getMaxAltitude();
        double[] bounds    = getSearchBounds(moving, currentAlt);
        double floor       = bounds[0];
        double ceiling     = bounds[1];

        // critical conflicts start with ±2000 ft jumps to create fast, unambiguous separation
        int[] steps = critical
            ? new int[]{2, -2, 3, -3, 4, -4, 1, -1, 5, -5, 6, -6}
            : new int[]{1, -1, 2, -2, 3, -3, 4, -4, 5, -5};

        for (int step : steps) {
            double candidate = snapToFL(currentAlt + step * ALTITUDE_STEP);
            if (candidate < floor || candidate > ceiling)          continue;
            if (candidate > maxAlt || candidate <= 0)              continue;
            // candidate must itself provide ≥1000 ft from the conflicting aircraft's effective alt
            if (Math.abs(candidate - conflictAlt) < ALTITUDE_BUFFER) continue;
            if (!isAltFree(candidate, all, moving, committed))     continue;
            return new Resolution(moving, candidate);
        }
        return null;
    }

    // ── Heading resolution ────────────────────────────────────────────────────

    private Resolution tryHeading(Aircraft moving, Aircraft conflicting,
                                   List<Aircraft> all,
                                   Map<String, double[]> committed,
                                   boolean critical) {

        double currentHdg = getEffectiveHdg(moving, committed);

        // critical: wider turns for faster lateral divergence
        int[] deltas = critical
            ? new int[]{30, -30, 45, -45, 20, -20, 60, -60}
            : new int[]{10, -10, 20, -20};

        for (int delta : deltas) {
            double candidate = (currentHdg + delta + 360) % 360;
            if (cpaSepWithHdg(moving, candidate, conflicting, committed) < HDG_MIN_SEP) continue;

            // ensure the turn does not create a new conflict with nearby traffic
            boolean safe = true;
            for (Aircraft neighbor : all) {
                if (neighbor == moving || neighbor == conflicting) continue;
                if (distanceNM(moving.getLat(), moving.getLon(),
                               neighbor.getLat(), neighbor.getLon()) > HDG_NEIGHBOR_NM) continue;
                if (cpaSepWithHdg(moving, candidate, neighbor, committed) < HDG_MIN_SEP) {
                    safe = false;
                    break;
                }
            }
            if (!safe) continue;
            return Resolution.forHeading(moving, candidate);
        }
        return null;
    }

    // ── Speed resolution (last resort) ────────────────────────────────────────

    /**
     * Slows the moving aircraft by 20%. Shifting speed materially changes tCPA
     * so that the conflict resolves on its own over the next few cycles.
     * Only issued if the reduced speed stays above the operational floor.
     */
    Resolution trySpeed(Aircraft moving, Aircraft conflicting,
                        List<Aircraft> all,
                        Map<String, double[]> committed) {
        double current = getEffectiveSpd(moving, committed);
        double reduced = current * SPEED_REDUCTION;
        double minimum = Math.max(ABSOLUTE_SPEED_FLOOR,
                moving.getType().getMinimumSafeSpeed());
        if (reduced < minimum) return null;
        if (cpaSepWithSpeed(moving, reduced, conflicting, committed) < SPEED_MIN_SEP) return null;

        for (Aircraft neighbor : all) {
            if (neighbor == moving || neighbor == conflicting) continue;
            if (Math.abs(getEffectiveAlt(moving, committed)
                    - getEffectiveAlt(neighbor, committed)) >= ALTITUDE_BUFFER) continue;
            if (cpaSepWithSpeed(moving, reduced, neighbor, committed) < SPEED_MIN_SEP) return null;
        }
        return Resolution.forSpeed(moving, reduced);
    }

    // ── Priority selection ────────────────────────────────────────────────────

    private Aircraft selectAircraftToMove(Conflict c) {
        Aircraft a1 = c.getA1(), a2 = c.getA2();
        if (a1.getCategory() == AircraftCategory.EMERGENCY) return a2;
        if (a2.getCategory() == AircraftCategory.EMERGENCY) return a1;
        if (a1.getCategory() == AircraftCategory.MILITARY)  return a2;
        if (a2.getCategory() == AircraftCategory.MILITARY)  return a1;
        return a1;
    }

    // ── Altitude slot availability ────────────────────────────────────────────

    /**
     * Checks whether targetAlt is usable for 'self' given actual and committed
     * altitudes of all aircraft within PROXIMITY_NM.
     */
    private boolean isAltFree(double targetAlt, List<Aircraft> all, Aircraft self,
                               Map<String, double[]> committed) {
        for (Aircraft other : all) {
            if (other == self) continue;
            if (distanceNM(self.getLat(), self.getLon(),
                           other.getLat(), other.getLon()) > PROXIMITY_NM) continue;
            if (Math.abs(getEffectiveAlt(other, committed) - targetAlt) < ALTITUDE_BUFFER)
                return false;
        }
        return true;
    }

    // ── CPA with heading override ─────────────────────────────────────────────

    /**
     * Computes the CPA distance between 'moving' (using newHdgDeg) and 'other'
     * (using its committed or actual heading). Returns current separation if
     * tracks are already diverging — the new heading didn't help, so the small
     * current distance correctly fails the ≥ HDG_MIN_SEP gate.
     */
    private double cpaSepWithHdg(Aircraft moving, double newHdgDeg, Aircraft other,
                                  Map<String, double[]> committed) {
        double avgLat = (moving.getLat() + other.getLat()) / 2.0;
        double cosLat = Math.cos(Math.toRadians(avgLat));

        double x1 = moving.getLon() * 60.0 * cosLat,  y1 = moving.getLat() * 60.0;
        double x2 = other.getLon()  * 60.0 * cosLat,  y2 = other.getLat()  * 60.0;

        double otherHdg = getEffectiveHdg(other, committed);
        double vx1 = moving.getSpeed() * Math.sin(Math.toRadians(newHdgDeg));
        double vy1 = moving.getSpeed() * Math.cos(Math.toRadians(newHdgDeg));
        double vx2 = other.getSpeed()  * Math.sin(Math.toRadians(otherHdg));
        double vy2 = other.getSpeed()  * Math.cos(Math.toRadians(otherHdg));

        double dvx = vx2 - vx1,  dvy = vy2 - vy1;
        double dx  = x2  - x1,   dy  = y2  - y1;
        double dv2 = dvx * dvx + dvy * dvy;
        double currentDist = Math.sqrt(dx * dx + dy * dy);

        if (dv2 == 0) return currentDist;
        double tHours = -(dx * dvx + dy * dvy) / dv2;
        if (tHours <= 0) return currentDist;

        double tCPA = tHours * 3600.0;
        double[] p1 = TrajectoryPredictor.predictPosition(moving, tCPA, newHdgDeg);
        double[] p2 = TrajectoryPredictor.predictPosition(other,  tCPA);
        return distanceNM(p1[0], p1[1], p2[0], p2[1]);
    }

    private double cpaSepWithSpeed(Aircraft moving, double newSpeed, Aircraft other,
                                   Map<String, double[]> committed) {
        double avgLat = (moving.getLat() + other.getLat()) / 2.0;
        double cosLat = Math.cos(Math.toRadians(avgLat));
        double x1 = moving.getLon() * 60.0 * cosLat, y1 = moving.getLat() * 60.0;
        double x2 = other.getLon() * 60.0 * cosLat, y2 = other.getLat() * 60.0;

        double movingHdg = getEffectiveHdg(moving, committed);
        double otherHdg = getEffectiveHdg(other, committed);
        double otherSpeed = getEffectiveSpd(other, committed);
        double vx1 = newSpeed * Math.sin(Math.toRadians(movingHdg));
        double vy1 = newSpeed * Math.cos(Math.toRadians(movingHdg));
        double vx2 = otherSpeed * Math.sin(Math.toRadians(otherHdg));
        double vy2 = otherSpeed * Math.cos(Math.toRadians(otherHdg));
        double dvx = vx2 - vx1, dvy = vy2 - vy1;
        double dx = x2 - x1, dy = y2 - y1;
        double dv2 = dvx * dvx + dvy * dvy;
        double currentDist = Math.sqrt(dx * dx + dy * dy);
        if (dv2 == 0) return currentDist;

        double tHours = -(dx * dvx + dy * dvy) / dv2;
        if (tHours <= 0) return currentDist;
        double seconds = tHours * 3600.0;
        double[] p1 = predictPositionWithSpeed(moving, seconds, newSpeed, movingHdg);
        double[] p2 = predictPositionWithSpeed(other, seconds, otherSpeed, otherHdg);
        return distanceNM(p1[0], p1[1], p2[0], p2[1]);
    }

    private double[] predictPositionWithSpeed(Aircraft aircraft, double seconds,
                                               double speed, double headingDeg) {
        double heading = Math.toRadians(headingDeg);
        double distanceNm = speed * seconds / 3600.0;
        double dLat = distanceNm * Math.cos(heading) / 60.0;
        double dLon = distanceNm * Math.sin(heading)
                / (60.0 * Math.cos(Math.toRadians(aircraft.getLat())));
        return new double[]{aircraft.getLat() + dLat, aircraft.getLon() + dLon};
    }

    // ── Search bounds ─────────────────────────────────────────────────────────

    private double[] getSearchBounds(Aircraft a, double effectiveAlt) {
        double maxAlt = a.getType().getMaxAltitude();
        String phase  = derivePhase(effectiveAlt, a.getSpeed());

        double floor, ceiling;
        switch (phase) {
            case "TAKEOFF_LANDING":
                floor = Math.max(500, effectiveAlt - 500);        ceiling = effectiveAlt + 1000;  break;
            case "FINAL_APPROACH":
                floor = Math.max(MIN_ALTITUDE, effectiveAlt - 2000); ceiling = effectiveAlt + 2000;  break;
            case "CLIMB_DESCENT":
                floor = Math.max(MIN_ALTITUDE, effectiveAlt - 3000); ceiling = effectiveAlt + 5000;  break;
            default: // CRUISE
                floor = MIN_ALTITUDE; ceiling = maxAlt; break;
        }

        // band lock: keep IFR-high aircraft above FL180, IFR-low below it
        if (effectiveAlt >= HIGH_ALT_FLOOR) floor   = Math.max(floor, HIGH_ALT_FLOOR);
        else                                ceiling = Math.min(ceiling, HIGH_ALT_FLOOR - 1000);

        return new double[]{floor, ceiling};
    }

    private String derivePhase(double alt, double speed) {
        if (alt < 1000  && speed < 150) return "TAKEOFF_LANDING";
        if (alt < 3000  && speed < 250) return "FINAL_APPROACH";
        if (alt < 10000)                return "CLIMB_DESCENT";
        return "CRUISE";
    }

    // ── Committed state helpers ───────────────────────────────────────────────

    private double getEffectiveAlt(Aircraft a, Map<String, double[]> committed) {
        double[] s = committed.get(a.getId());
        return (s != null && !Double.isNaN(s[0])) ? s[0] : a.getAltitude();
    }

    private double getEffectiveHdg(Aircraft a, Map<String, double[]> committed) {
        double[] s = committed.get(a.getId());
        return (s != null && !Double.isNaN(s[1])) ? s[1] : a.getHeading();
    }

    private double getEffectiveSpd(Aircraft a, Map<String, double[]> committed) {
        double[] s = committed.get(a.getId());
        return (s != null && !Double.isNaN(s[2])) ? s[2] : a.getSpeed();
    }

    /**
     * Records a committed resolution into the shared map so subsequent conflicts
     * in the same cluster resolve against the updated effective state.
     * Uses NaN as a sentinel for "this dimension was not changed."
     */
    private void applyToCommitted(Resolution r, Map<String, double[]> committed) {
        String aircraftId = r.getAircraft().getId();
        double[] s = committed.computeIfAbsent(aircraftId,
                k -> new double[]{Double.NaN, Double.NaN, Double.NaN});
        if      (r.isSpeedResolution())   s[2] = r.getNewSpeed();
        else if (r.isHeadingResolution()) s[1] = r.getNewHeading();
        else                              s[0] = r.getNewAltitude();
    }

    // ── Geometry helpers ──────────────────────────────────────────────────────

    private double snapToFL(double altitude) {
        return Math.round(altitude / 1000) * 1000;
    }

    private double distanceNM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * 60.0;
        double dLon = (lon2 - lon1) * 60.0 * Math.cos(Math.toRadians(lat1));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }
}
