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
        /**
         * One of the aircraft is coasting on an old report, so separation cannot be confirmed yet.
         * The alert stays up until a fresh report arrives or the track is dropped.
         */
        AWAITING_REPORT,
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

    /** How an alert ended. */
    public enum Outcome {
        /** The observed tracks no longer conflict. */
        SEPARATED,
        /** An accepted maneuver separated the pair in simulation. */
        RESOLVED,
        /**
         * One of the aircraft dropped out of the feed after coasting, so separation could not be
         * confirmed. Not a clearance.
         */
        TRACK_LOST
    }

    /**
     * A pair that was flagged and no longer is.
     *
     * @param instruction the maneuver that cleared it in simulation, or empty otherwise
     */
    public record ClearedAlert(String pairKey, String id1, String id2, String callsign1,
                               String callsign2, Outcome outcome, String instruction) {

        public boolean clearedByManeuver() {
            return outcome == Outcome.RESOLVED;
        }
    }

    /**
     * Per-cycle numbers, so it is visible whether resolution is working at all.
     *
     * @param clearedAlerts     alerts that ended because the pair separated or was resolved
     * @param clearing          alerts held up: separating but still within the hold margin, or
     *                          waiting for a fresh report on a coasting track
     * @param tracksLost        alerts that ended because one of the tracks dropped out of the feed
     * @param trafficAdvisories predicted proximity involving VFR, helicopter, likely VFR, or low
     *                          or slow traffic. These get traffic information, not separation, so
     *                          they are counted but not resolved.
     * @param towerSuppressed   predicted proximity with both aircraft inside tower airspace near
     *                          an airport, where radar alerts are suppressed.
     */
    public record Counters(int alerts, int newAlerts, int clearedAlerts, int groups,
                           int proposals, int acceptedGroups, int rejectedGroups,
                           int pairsClearedInTrial, int noSafeManeuver, int separationLost,
                           int clearing, int tracksLost, int trafficAdvisories,
                           int towerSuppressed) {
    }
}
