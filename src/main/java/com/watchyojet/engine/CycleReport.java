package com.watchyojet.engine;

import java.util.List;

import com.watchyojet.model.Conflict;

/**
 * Everything one engine cycle concluded, in a form the display can render without
 * reaching back into the engine.
 */
public record CycleReport(CycleMode mode, List<ActiveAlert> active, List<ClearedAlert> cleared,
                          Counters counters) {

    public CycleReport {
        active = List.copyOf(active);
        cleared = List.copyOf(cleared);
    }

    /** What the engine can say about resolving one active alert. */
    public enum Advice {
        /** The proposed maneuver separates this pair on the trial tracks. */
        CLEARS_IN_SIMULATION,
        /** The proposal for this group created a new predicted conflict, so it was discarded. */
        REJECTED_NEW_CONFLICT,
        /** A maneuver was accepted for the group, but this pair is still predicted to conflict. */
        DOES_NOT_CLEAR,
        /** No candidate altitude, heading or speed passed the safety checks. */
        NO_SAFE_MANEUVER,
        /** The pair is already inside both minima and no maneuver separates it in the trial. */
        SEPARATION_LOST,
        /** No longer inside the minima, but still within the hold margin, so the alert stays up. */
        CLEARING,
        /** Both aircraft were maneuvered recently and are left alone until the cooldown ends. */
        MANEUVER_IN_PROGRESS,
        /** Maneuvers are not evaluated in this mode. */
        NOT_EVALUATED
    }

    /**
     * A predicted conflict that is still present.
     *
     * @param firstSeenMillis when this aircraft pair was first flagged, kept across refreshes
     * @param instruction     the proposed maneuver as a short clearance, or empty when there is none
     */
    public record ActiveAlert(String pairKey, Conflict conflict, long firstSeenMillis,
                              Advice advice, String instruction) {
    }

    /**
     * A pair that was flagged and no longer is.
     *
     * @param instruction the maneuver that cleared it in simulation, or empty when the observed
     *                    tracks separated on their own
     */
    public record ClearedAlert(String pairKey, String id1, String id2, String callsign1,
                               String callsign2, String instruction) {

        public boolean clearedByManeuver() {
            return !instruction.isEmpty();
        }
    }

    /**
     * Per-cycle numbers, so it is visible whether resolution is working at all.
     *
     * @param trafficAdvisories predicted proximity involving a VFR aircraft (squawk 1200). These
     *                          get traffic information, not separation, so they are counted but
     *                          not resolved.
     * @param towerSuppressed   predicted proximity with both aircraft inside tower airspace near
     *                          an airport, where radar alerts are suppressed.
     */
    public record Counters(int alerts, int newAlerts, int clearedAlerts, int groups,
                           int proposals, int acceptedGroups, int rejectedGroups,
                           int pairsClearedInTrial, int noSafeManeuver, int separationLost,
                           int clearing, int trafficAdvisories, int towerSuppressed) {
    }
}
