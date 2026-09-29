package com.watchyojet.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.watchyojet.WYJAppController;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.Conflict;
import com.watchyojet.model.Resolution;
import com.watchyojet.simulation.MovementEngine;

public class ATCEngine {

    private final ConflictDetector  detector;
    private final ResolutionEngine  resolver;
    private final MovementEngine    movement;

    // cooldown prevents re-resolving the same aircraft every cycle
    private final Map<String, Long>    resolutionCooldown  = new ConcurrentHashMap<>();
    private static final long          COOLDOWN_MS         = 15_000;

    // escalation: if a conflict pair persists > ESCALATE_CYCLES, force hard resolution
    private final Map<String, Integer> conflictPersistence = new ConcurrentHashMap<>();
    private static final int           ESCALATE_CYCLES     = 2;

    public ATCEngine() {
        this.detector = new ConflictDetector();
        this.resolver = new ResolutionEngine();
        this.movement = new MovementEngine();
    }

    public void runCycle(List<Aircraft> aircrafts) {
        runCycle(aircrafts, true);
    }

    public void runCycle(List<Aircraft> aircrafts, boolean simulationEnabled) {

        if (simulationEnabled) movement.updatePositions(aircrafts);

        List<Conflict> conflicts = detector.detectConflicts(aircrafts, simulationEnabled);

        if (!simulationEnabled) {
            List<String[]> criticalPairs = conflicts.stream()
                    .filter(Conflict::isCriticalRisk)
                    .map(c -> new String[]{c.getA1().getCallsign(), c.getA2().getCallsign()})
                    .toList();
            int monitoredProximities = conflicts.size() - criticalPairs.size();
            WYJAppController controller = WYJAppController.getInstance();
            if (controller != null) {
                controller.updateLiveAssessment(monitoredProximities, criticalPairs);
            }
            conflictPersistence.clear();
            updateMap(aircrafts);
            return;
        }

        if (conflicts.isEmpty()) {
            conflictPersistence.clear();
            updateMap(aircrafts);
            return;
        }

        // ── Persistence tracking ──────────────────────────────────────────────
        Set<String> activeKeys = new HashSet<>();
        for (Conflict c : conflicts) activeKeys.add(conflictKey(c));
        conflictPersistence.keySet().retainAll(activeKeys);
        for (String key : activeKeys) conflictPersistence.merge(key, 1, Integer::sum);

        Set<String> hardPairs = new HashSet<>();
        for (Map.Entry<String, Integer> e : conflictPersistence.entrySet()) {
            if (e.getValue() > ESCALATE_CYCLES) hardPairs.add(e.getKey());
        }
        if (!hardPairs.isEmpty()) {
            System.out.println("[ESCALATE] " + hardPairs.size()
                    + " conflict pair(s) persisted >" + ESCALATE_CYCLES + " cycles → hard resolution");
        }

        // global sort: CRITICAL first, then earliest tCPA
        conflicts.sort(Comparator.comparing(Conflict::getSeverity)
                                 .thenComparingDouble(Conflict::getTCPA));

        long now = System.currentTimeMillis();
        resolutionCooldown.entrySet().removeIf(e -> now - e.getValue() > COOLDOWN_MS * 2);

        // aircraft on cooldown or already resolved this cycle may not be moved
        Set<String> locked = new HashSet<>();
        for (Map.Entry<String, Long> e : resolutionCooldown.entrySet()) {
            if (e.getValue() + COOLDOWN_MS > now) locked.add(e.getKey());
        }

        // committed[callsign] = {alt, hdg, spd} with NaN = unchanged
        Map<String, double[]> committed = new HashMap<>();

        List<Resolution>  allResolutions = new ArrayList<>();
        List<String[]>    resolvedPairs  = new ArrayList<>();
        List<String[]>    unresolvedPairs = new ArrayList<>();

        // process each cluster holistically
        for (List<Conflict> cluster : detectClusters(conflicts)) {

            List<Resolution> clusterResolutions =
                resolver.resolveCluster(cluster, aircrafts, committed, locked, hardPairs);

            for (Resolution r : clusterResolutions) {
                String cs = r.getAircraft().getCallsign();
                String aircraftId = r.getAircraft().getId();
                locked.add(aircraftId);
                resolutionCooldown.put(aircraftId, now);
                allResolutions.add(r);

                // find both callsigns for the first conflict involving this aircraft
                String cs1 = cs, cs2 = cs;
                for (Conflict c : cluster) {
                    if (c.getA1().getId().equals(aircraftId) || c.getA2().getId().equals(aircraftId)) {
                        cs1 = c.getA1().getCallsign();
                        cs2 = c.getA2().getCallsign();
                        break;
                    }
                }

                String tag;
                if (r.isSpeedResolution()) {
                    tag = "SPD:" + (int) r.getNewSpeed();
                } else if (r.isHeadingResolution()) {
                    tag = "HDG:" + (int) r.getNewHeading();
                } else {
                    tag = String.valueOf((int) r.getNewAltitude());
                }
                resolvedPairs.add(new String[]{cs1, cs2, cs, tag});
            }

            // any conflict in the cluster with both aircraft still locked = unresolved
            for (Conflict c : cluster) {
                boolean a1resolved = allResolutions.stream()
                    .anyMatch(r -> r.getAircraft().getId().equals(c.getA1().getId()));
                boolean a2resolved = allResolutions.stream()
                    .anyMatch(r -> r.getAircraft().getId().equals(c.getA2().getId()));
                if (!a1resolved && !a2resolved) {
                    unresolvedPairs.add(new String[]{c.getA1().getCallsign(), c.getA2().getCallsign()});
                }
            }
        }

        // apply resolutions
        for (Resolution r : allResolutions) {
            Aircraft ac = r.getAircraft();
            if (r.isSpeedResolution()) {
                ac.setSpeed(r.getNewSpeed());
                System.out.println("[RESOLVED-SPD] " + ac.getCallsign()
                        + " → " + (int) r.getNewSpeed() + " kt");
            } else if (r.isHeadingResolution()) {
                ac.setHeading(r.getNewHeading());
                System.out.println("[RESOLVED-HDG] " + ac.getCallsign()
                        + " → heading " + (int) r.getNewHeading() + "°");
            } else {
                ac.setAltitude(r.getNewAltitude());
                System.out.println("[RESOLVED] " + ac.getCallsign()
                        + " → " + (int) r.getNewAltitude() + " ft");
            }
        }

        if (!unresolvedPairs.isEmpty()) {
            System.out.println("[UNRESOLVED] " + unresolvedPairs.size()
                    + " conflict(s) could not be resolved this cycle");
        }

        // post-resolution sanity check
        if (!allResolutions.isEmpty()) {
            Set<String> originalPairs = new HashSet<>();
            for (Conflict conflict : conflicts) originalPairs.add(conflictKey(conflict));
            List<Conflict> postCheck = detector.detectConflicts(aircrafts, false);
            long newlyCreated = postCheck.stream()
                .filter(pc -> !originalPairs.contains(conflictKey(pc)))
                .filter(pc -> allResolutions.stream().anyMatch(res ->
                    res.getAircraft().getId().equals(pc.getA1().getId()) ||
                    res.getAircraft().getId().equals(pc.getA2().getId())))
                .count();
            if (newlyCreated > 0)
                System.out.println("[WARNING] " + newlyCreated
                        + " new conflict(s) introduced by resolution — will handle next cycle");
        }

        // Only unresolved pairs belong in the active-alert panel. Previously the
        // UI received every pre-resolution conflict, which made resolved traffic
        // look active for another 15 seconds and caused the alert flood.
        notifyConflicts(unresolvedPairs, resolvedPairs);
        updateMap(aircrafts);
    }

    // ── Conflict key ─────────────────────────────────────────────────────────

    private static String conflictKey(Conflict c) {
        String id1 = c.getA1().getId();
        String id2 = c.getA2().getId();
        return id1.compareTo(id2) < 0 ? id1 + ":" + id2 : id2 + ":" + id1;
    }

    // ── BFS cluster detection ─────────────────────────────────────────────────

    private List<List<Conflict>> detectClusters(List<Conflict> conflicts) {
        // build adjacency: callsign → set of conflicts involving that aircraft
        Map<String, Set<Conflict>> byCallsign = new HashMap<>();
        for (Conflict c : conflicts) {
            byCallsign.computeIfAbsent(c.getA1().getId(), k -> new HashSet<>()).add(c);
            byCallsign.computeIfAbsent(c.getA2().getId(), k -> new HashSet<>()).add(c);
        }

        Set<Conflict>       visited  = new HashSet<>();
        List<List<Conflict>> clusters = new ArrayList<>();

        for (Conflict seed : conflicts) {
            if (visited.contains(seed)) continue;
            List<Conflict>  cluster = new ArrayList<>();
            Deque<Conflict> queue   = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);
            while (!queue.isEmpty()) {
                Conflict curr = queue.poll();
                cluster.add(curr);
                for (String aircraftId : new String[]{curr.getA1().getId(), curr.getA2().getId()}) {
                    for (Conflict neighbor : byCallsign.getOrDefault(aircraftId, Collections.emptySet())) {
                        if (visited.add(neighbor)) queue.add(neighbor);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    // ── UI bridge ─────────────────────────────────────────────────────────────

    private void notifyConflicts(List<String[]> unresolvedPairs, List<String[]> resolvedPairs) {
        WYJAppController ctrl = WYJAppController.getInstance();
        if (ctrl == null) return;
        ctrl.batchNotify(unresolvedPairs, resolvedPairs);
    }

    private void updateMap(List<Aircraft> aircrafts) {
        WYJAppController ctrl = WYJAppController.getInstance();
        if (ctrl == null) return;
        ctrl.updateAllAircraft(aircrafts);
    }
}
