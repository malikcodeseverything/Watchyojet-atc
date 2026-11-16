package com.watchyojet.engine;

import static java.lang.System.Logger.Level.INFO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.watchyojet.engine.CycleReport.ActiveAlert;
import com.watchyojet.engine.CycleReport.Advice;
import com.watchyojet.engine.CycleReport.ClearedAlert;
import com.watchyojet.engine.CycleReport.Counters;
import com.watchyojet.engine.CycleReport.Outcome;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.Conflict;
import com.watchyojet.model.Resolution;
import com.watchyojet.simulation.MovementEngine;
import com.watchyojet.util.Logs;

/**
 * Runs one detection and resolution cycle at a time.
 *
 * <p>Alerts are tracked by aircraft pair, so the same encounter keeps its identity and
 * first-seen time across OpenSky refreshes. Each group of related conflicts gets its own
 * trial: a group is accepted when its maneuvers create no new predicted conflict, and
 * rejected otherwise, without affecting the other groups.
 *
 * <p>In {@link CycleMode#ADVISE} the tracks are never changed. In
 * {@link CycleMode#SIMULATE} accepted maneuvers are applied to the simulated tracks.
 */
public class ATCEngine {

    private static final System.Logger LOG = Logs.get(ATCEngine.class);

    // cooldown prevents re-resolving the same simulated aircraft every cycle
    private static final long COOLDOWN_MS = 15_000;

    // escalation: if a simulated conflict persists > ESCALATE_CYCLES, search wider maneuvers
    private static final int ESCALATE_CYCLES = 2;

    private final ConflictDetector detector;
    private final ResolutionEngine resolver;
    private final MovementEngine movement;
    private final CycleListener listener;

    private final Map<String, Long> resolutionCooldown = new HashMap<>();
    private final Map<String, TrackedAlert> tracked = new LinkedHashMap<>();

    private record TrackedAlert(String id1, String id2, String callsign1, String callsign2,
                                long firstSeenMillis, int cycles, Conflict last) {
        TrackedAlert seenAgain(Conflict conflict) {
            return new TrackedAlert(id1, id2, conflict.getA1().getCallsign(),
                    conflict.getA2().getCallsign(), firstSeenMillis, cycles + 1, conflict);
        }
    }

    public ATCEngine() {
        this(CycleListener.NONE);
    }

    public ATCEngine(CycleListener listener) {
        this.detector = new ConflictDetector();
        this.resolver = new ResolutionEngine();
        this.movement = new MovementEngine();
        this.listener = listener == null ? CycleListener.NONE : listener;
    }

    /** Runs a simulation cycle that applies accepted maneuvers. */
    public CycleReport runCycle(List<Aircraft> aircraft) {
        return runCycle(aircraft, CycleMode.SIMULATE);
    }

    public CycleReport runCycle(List<Aircraft> aircraft, CycleMode mode) {
        return runCycle(aircraft, mode, System.currentTimeMillis());
    }

    CycleReport runCycle(List<Aircraft> aircraft, CycleMode mode, long now) {
        movement.updatePositions(aircraft);

        // Pairs inside tower airspace are the tower's to separate. VFR, helicopter and low or
        // slow traffic gets traffic information from ATC, not separation. Both are counted.
        List<Conflict> conflicts = new ArrayList<>();
        int trafficAdvisories = 0;
        int towerSuppressed = 0;
        for (Conflict conflict : detector.detectConflicts(aircraft)) {
            if (isTowerPair(conflict)) towerSuppressed++;
            else if (isTrafficAdvisory(conflict)) trafficAdvisories++;
            else conflicts.add(conflict);
        }
        conflicts.sort(Comparator.comparing(Conflict::getSeverity)
                .thenComparingDouble(Conflict::getTCPA));

        // ── Alert identity across cycles ──────────────────────────────────────
        Map<String, Conflict> byKey = new LinkedHashMap<>();
        for (Conflict conflict : conflicts) byKey.putIfAbsent(conflictKey(conflict), conflict);

        Map<String, Aircraft> byId = new HashMap<>();
        for (Aircraft track : aircraft) byId.put(track.getId(), track);

        // An alert that drops below the threshold is held until the pair clears it by a margin,
        // so a pair hovering at the limit does not flicker between new and cleared. When one of
        // the tracks is gone, separation can no longer be confirmed, so the alert ends as a lost
        // track rather than a clearance. While either aircraft is coasting, its position is an
        // extrapolation, so the alert is held until a fresh report confirms the separation.
        List<ClearedAlert> cleared = new ArrayList<>();
        Map<String, Conflict> held = new LinkedHashMap<>();
        Set<String> awaitingReport = new HashSet<>();
        int tracksLost = 0;
        for (Map.Entry<String, TrackedAlert> entry : tracked.entrySet()) {
            if (byKey.containsKey(entry.getKey())) continue;
            TrackedAlert gone = entry.getValue();
            Aircraft first = byId.get(gone.id1());
            Aircraft second = byId.get(gone.id2());
            if (first == null || second == null) {
                tracksLost++;
                cleared.add(new ClearedAlert(entry.getKey(), gone.id1(), gone.id2(),
                        gone.callsign1(), gone.callsign2(), Outcome.TRACK_LOST, ""));
                continue;
            }
            Optional<Conflict> stillClose = holdingConflict(first, second);
            if (first.isCoasting() || second.isCoasting()) {
                held.put(entry.getKey(), stillClose.orElse(gone.last()));
                awaitingReport.add(entry.getKey());
            } else if (stillClose.isPresent()) {
                held.put(entry.getKey(), stillClose.get());
            } else {
                cleared.add(new ClearedAlert(entry.getKey(), gone.id1(), gone.id2(),
                        gone.callsign1(), gone.callsign2(), Outcome.SEPARATED, ""));
            }
        }
        tracked.keySet().removeIf(key -> !byKey.containsKey(key) && !held.containsKey(key));

        int newAlerts = 0;
        for (Map.Entry<String, Conflict> entry : byKey.entrySet()) {
            Conflict conflict = entry.getValue();
            TrackedAlert previous = tracked.get(entry.getKey());
            if (previous == null) {
                newAlerts++;
                tracked.put(entry.getKey(), new TrackedAlert(conflict.getA1().getId(),
                        conflict.getA2().getId(), conflict.getA1().getCallsign(),
                        conflict.getA2().getCallsign(), now, 1, conflict));
                logNewAlert(conflict);
            } else {
                tracked.put(entry.getKey(), previous.seenAgain(conflict));
            }
        }

        if (mode == CycleMode.DETECT_ONLY || conflicts.isEmpty()) {
            List<ActiveAlert> active = new ArrayList<>();
            for (Map.Entry<String, Conflict> entry : byKey.entrySet()) {
                active.add(new ActiveAlert(entry.getKey(), entry.getValue(),
                        tracked.get(entry.getKey()).firstSeenMillis(), Advice.NOT_EVALUATED, ""));
            }
            addHeld(active, held, awaitingReport);
            Counters counters = new Counters(active.size(), newAlerts, cleared.size() - tracksLost,
                    0, 0, 0, 0, 0, 0, 0, held.size(), tracksLost, trafficAdvisories, towerSuppressed);
            if (mode != CycleMode.DETECT_ONLY) logCycle(mode, counters);
            return publish(new CycleReport(mode, active, cleared, counters), aircraft);
        }

        // ── Resolution, one trial per group of related conflicts ──────────────
        boolean simulate = mode == CycleMode.SIMULATE;
        Set<String> cooling = new HashSet<>();
        Set<String> hardPairs = new HashSet<>();
        if (simulate) {
            resolutionCooldown.entrySet().removeIf(e -> now - e.getValue() > COOLDOWN_MS * 2);
            for (Map.Entry<String, Long> e : resolutionCooldown.entrySet()) {
                if (e.getValue() + COOLDOWN_MS > now) cooling.add(e.getKey());
            }
            for (Map.Entry<String, TrackedAlert> e : tracked.entrySet()) {
                if (e.getValue().cycles() > ESCALATE_CYCLES) hardPairs.add(e.getKey());
            }
        }

        Map<String, double[]> accepted = new HashMap<>();
        Map<String, Resolution> acceptedByAircraft = new HashMap<>();
        Map<String, Resolution> rejectedByAircraft = new HashMap<>();
        Map<String, String> instructionByAircraft = new HashMap<>();
        Set<String> groupsWithoutProposal = new HashSet<>();
        Set<String> rejectedPairs = new HashSet<>();
        int groups = 0, proposals = 0, acceptedGroups = 0, rejectedGroups = 0;

        for (List<Conflict> group : detectClusters(conflicts)) {
            groups++;
            Map<String, double[]> proposed = copyOf(accepted);
            Set<String> locked = new HashSet<>(cooling);
            List<Resolution> resolutions =
                    resolver.resolveCluster(new ArrayList<>(group), aircraft, proposed, locked, hardPairs);

            if (resolutions.isEmpty()) {
                for (Conflict conflict : group) groupsWithoutProposal.add(conflictKey(conflict));
                continue;
            }
            proposals += resolutions.size();
            for (Resolution resolution : resolutions) {
                instructionByAircraft.put(resolution.getAircraft().getId(),
                        Instructions.describe(resolution));
            }

            if (validateTrial(aircraft, conflicts, proposed).isPresent()) {
                acceptedGroups++;
                accepted = proposed;
                for (Resolution r : resolutions) acceptedByAircraft.put(r.getAircraft().getId(), r);
            } else {
                rejectedGroups++;
                for (Resolution r : resolutions) rejectedByAircraft.put(r.getAircraft().getId(), r);
                for (Conflict conflict : group) rejectedPairs.add(conflictKey(conflict));
            }
        }

        // Pairs still predicted once every accepted group is applied to trial copies.
        Set<String> remaining = new HashSet<>();
        for (Conflict conflict : validateTrial(aircraft, conflicts, accepted).orElse(conflicts)) {
            remaining.add(conflictKey(conflict));
        }

        if (simulate) {
            for (Map.Entry<String, double[]> change : accepted.entrySet()) {
                for (Aircraft track : aircraft) {
                    if (track.getId().equals(change.getKey())) {
                        double[] values = change.getValue();
                        track.assign(values[0], values[1], values[2]);
                    }
                }
                resolutionCooldown.put(change.getKey(), now);
            }
        }

        // ── Build the report ──────────────────────────────────────────────────
        List<ActiveAlert> active = new ArrayList<>();
        int pairsClearedInTrial = 0;
        int noSafeManeuver = 0;
        int separationLost = 0;
        for (Map.Entry<String, Conflict> entry : byKey.entrySet()) {
            String key = entry.getKey();
            Conflict conflict = entry.getValue();
            String id1 = conflict.getA1().getId();
            String id2 = conflict.getA2().getId();
            boolean clearsInTrial = !remaining.contains(key);

            if (clearsInTrial) pairsClearedInTrial++;

            if (simulate && clearsInTrial) {
                TrackedAlert alert = tracked.remove(key);
                cleared.add(new ClearedAlert(key, id1, id2, alert.callsign1(), alert.callsign2(),
                        Outcome.RESOLVED,
                        instructionFor(acceptedByAircraft, instructionByAircraft, id1, id2)));
                continue;
            }

            Advice advice;
            String instruction;
            if (rejectedPairs.contains(key)) {
                advice = Advice.REJECTED_NEW_CONFLICT;
                instruction = instructionFor(rejectedByAircraft, instructionByAircraft, id1, id2);
            } else if (clearsInTrial) {
                advice = Advice.CLEARS_IN_SIMULATION;
                instruction = instructionFor(acceptedByAircraft, instructionByAircraft, id1, id2);
            } else if (SeparationStandards.insideMinimaNow(conflict.getA1(), conflict.getA2())) {
                advice = Advice.SEPARATION_LOST;
                instruction = instructionFor(acceptedByAircraft, instructionByAircraft, id1, id2);
                separationLost++;
            } else if (groupsWithoutProposal.contains(key)
                    && cooling.contains(id1) && cooling.contains(id2)) {
                advice = Advice.MANEUVER_IN_PROGRESS;
                instruction = "";
            } else if (groupsWithoutProposal.contains(key)) {
                advice = Advice.NO_SAFE_MANEUVER;
                instruction = "";
                noSafeManeuver++;
            } else {
                instruction = instructionFor(acceptedByAircraft, instructionByAircraft, id1, id2);
                advice = instruction.isEmpty() ? Advice.NO_SAFE_MANEUVER : Advice.DOES_NOT_CLEAR;
                if (instruction.isEmpty()) noSafeManeuver++;
            }
            active.add(new ActiveAlert(key, conflict, tracked.get(key).firstSeenMillis(),
                    advice, instruction));
        }

        addHeld(active, held, awaitingReport);
        Counters counters = new Counters(active.size(), newAlerts, cleared.size() - tracksLost, groups,
                proposals, acceptedGroups, rejectedGroups, pairsClearedInTrial, noSafeManeuver,
                separationLost, held.size(), tracksLost, trafficAdvisories, towerSuppressed);
        logCycle(mode, counters);
        return publish(new CycleReport(mode, active, cleared, counters), aircraft);
    }

    /**
     * Tests proposed changes on copies of the tracks. Returns the conflicts still predicted,
     * or empty when the proposal creates a pair that was not in conflict before.
     */
    Optional<List<Conflict>> validateTrial(List<Aircraft> aircraft, List<Conflict> originalConflicts,
                                           Map<String, double[]> proposedState) {
        if (proposedState.isEmpty()) return Optional.of(originalConflicts);

        List<Aircraft> trialAircraft = new ArrayList<>(aircraft.size());
        for (Aircraft track : aircraft) {
            double[] changes = proposedState.get(track.getId());
            if (changes == null) {
                trialAircraft.add(track);
                continue;
            }
            Aircraft trial = track.copy();
            trial.assign(changes[0], changes[1], changes[2]);
            trialAircraft.add(trial);
        }

        Set<String> originalPairs = new HashSet<>();
        for (Conflict conflict : originalConflicts) originalPairs.add(conflictKey(conflict));
        // Compare like with like: traffic and tower-airspace pairs are excluded from the original
        // list, so they are excluded here too. Otherwise every trial would look like it created
        // a new conflict.
        List<Conflict> predicted = new ArrayList<>();
        for (Conflict conflict : detector.detectConflicts(trialAircraft)) {
            if (SeparationStandards.separationApplies(conflict.getA1(), conflict.getA2())) {
                predicted.add(conflict);
            }
        }
        for (Conflict conflict : predicted) {
            if (!originalPairs.contains(conflictKey(conflict))) return Optional.empty();
        }
        return Optional.of(predicted);
    }

    private CycleReport publish(CycleReport report, List<Aircraft> aircraft) {
        listener.onCycle(report, aircraft);
        return report;
    }

    private static String instructionFor(Map<String, Resolution> resolutions,
                                         Map<String, String> instructions, String id1, String id2) {
        if (resolutions.containsKey(id1)) return instructions.getOrDefault(id1, "");
        if (resolutions.containsKey(id2)) return instructions.getOrDefault(id2, "");
        return "";
    }

    private static Map<String, double[]> copyOf(Map<String, double[]> state) {
        Map<String, double[]> copy = new HashMap<>();
        for (Map.Entry<String, double[]> e : state.entrySet()) copy.put(e.getKey(), e.getValue().clone());
        return copy;
    }

    /** See {@link SeparationStandards#trafficOnly(Aircraft)}. */
    private static boolean isTrafficAdvisory(Conflict conflict) {
        return SeparationStandards.trafficOnly(conflict.getA1())
                || SeparationStandards.trafficOnly(conflict.getA2());
    }

    private static boolean isTowerPair(Conflict conflict) {
        return SeparationStandards.towerPair(conflict.getA1(), conflict.getA2());
    }

    private Optional<Conflict> holdingConflict(Aircraft a1, Aircraft a2) {
        if (!SeparationStandards.separationApplies(a1, a2)) return Optional.empty();
        return detector.evaluate(a1, a2, SeparationStandards.HOLD_LATERAL_MARGIN_NM,
                SeparationStandards.HOLD_VERTICAL_MARGIN_FT);
    }

    private void addHeld(List<ActiveAlert> active, Map<String, Conflict> held,
                         Set<String> awaitingReport) {
        for (Map.Entry<String, Conflict> entry : held.entrySet()) {
            Advice advice = awaitingReport.contains(entry.getKey())
                    ? Advice.AWAITING_REPORT : Advice.CLEARING;
            active.add(new ActiveAlert(entry.getKey(), entry.getValue(),
                    tracked.get(entry.getKey()).firstSeenMillis(), advice, ""));
        }
    }

    private static void logNewAlert(Conflict conflict) {
        LOG.log(INFO, () -> String.format("[ALERT] %s  %s  tCPA %.0f s  dCPA %.2f NM%n        %s%n        %s",
                conflict.getSeverity(), conflict.getA1().getCallsign() + " <-> "
                        + conflict.getA2().getCallsign(),
                conflict.getTCPA(), conflict.getDCPA(),
                describe(conflict.getA1()), describe(conflict.getA2())));
    }

    /** One line per aircraft, so a log shows why a pair was flagged. */
    static String describe(Aircraft a) {
        double fromPhl = SeparationStandards.distanceNm(a.getLat(), a.getLon(),
                SeparationStandards.PHL_LAT, SeparationStandards.PHL_LON);
        return String.format(java.util.Locale.ROOT,
                "%-8s %,6.0f ft %+6.0f fpm %3.0f kt sq %-4s cat %-2d %4.1f NM from PHL%s%s",
                a.getCallsign(), a.getAltitude(), a.getVerticalRate(), a.getSpeed(),
                a.getSquawk() == null ? "----" : a.getSquawk(), a.getEmitterCategory(), fromPhl,
                SeparationStandards.inTerminalArea(a) ? " terminal" : "",
                a.isCoasting() ? " coasting" : "");
    }

    private static void logCycle(CycleMode mode, Counters c) {
        LOG.log(INFO, () -> String.format("[CYCLE] %s alerts=%d new=%d cleared=%d trackLost=%d clearing=%d"
                        + " groups=%d proposals=%d accepted=%d rejected=%d clearInTrial=%d"
                        + " noSafeManeuver=%d separationLost=%d traffic=%d towerSuppressed=%d",
                mode, c.alerts(), c.newAlerts(), c.clearedAlerts(), c.tracksLost(), c.clearing(),
                c.groups(), c.proposals(), c.acceptedGroups(), c.rejectedGroups(),
                c.pairsClearedInTrial(), c.noSafeManeuver(), c.separationLost(),
                c.trafficAdvisories(), c.towerSuppressed()));
    }

    // ── Conflict key ─────────────────────────────────────────────────────────

    static String conflictKey(Conflict c) {
        String id1 = c.getA1().getId();
        String id2 = c.getA2().getId();
        return id1.compareTo(id2) < 0 ? id1 + ":" + id2 : id2 + ":" + id1;
    }

    // ── BFS cluster detection ─────────────────────────────────────────────────

    private List<List<Conflict>> detectClusters(List<Conflict> conflicts) {
        // adjacency: aircraft ID → conflicts involving that aircraft
        Map<String, Set<Conflict>> byAircraft = new HashMap<>();
        for (Conflict c : conflicts) {
            byAircraft.computeIfAbsent(c.getA1().getId(), k -> new HashSet<>()).add(c);
            byAircraft.computeIfAbsent(c.getA2().getId(), k -> new HashSet<>()).add(c);
        }

        Set<Conflict> visited = new HashSet<>();
        List<List<Conflict>> clusters = new ArrayList<>();

        for (Conflict seed : conflicts) {
            if (visited.contains(seed)) continue;
            List<Conflict> cluster = new ArrayList<>();
            Deque<Conflict> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);
            while (!queue.isEmpty()) {
                Conflict curr = queue.poll();
                cluster.add(curr);
                for (String aircraftId : new String[]{curr.getA1().getId(), curr.getA2().getId()}) {
                    for (Conflict neighbor : byAircraft.getOrDefault(aircraftId, Collections.emptySet())) {
                        if (visited.add(neighbor)) queue.add(neighbor);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }
}
