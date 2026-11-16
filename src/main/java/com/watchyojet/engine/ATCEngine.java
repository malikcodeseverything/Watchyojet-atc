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

        movement.updatePositions(aircrafts);

        List<Conflict> conflicts = detector.detectConflicts(aircrafts);

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

        // committed[aircraft ID] = {alt, hdg, spd} with NaN = unchanged
        Map<String, double[]> committed = new HashMap<>();

        List<Resolution> allResolutions = new ArrayList<>();

        for (List<Conflict> cluster : detectClusters(conflicts)) {
            allResolutions.addAll(resolver.resolveCluster(cluster, aircrafts, committed, locked, hardPairs));
        }

        Map<String, Resolution> appliedByAircraft = new HashMap<>();
        // apply resolutions
        for (Resolution r : allResolutions) {
            Aircraft ac = r.getAircraft();
            resolutionCooldown.put(ac.getId(), now);
            appliedByAircraft.put(ac.getId(), r);
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

        List<Conflict> postCheck = allResolutions.isEmpty()
                ? conflicts : detector.detectConflicts(aircrafts, false);
        Set<String> remainingPairs = new HashSet<>();
        for (Conflict conflict : postCheck) remainingPairs.add(conflictKey(conflict));

        List<String[]> resolvedPairs = new ArrayList<>();
        int unresolvedCount = 0;
        for (Conflict conflict : conflicts) {
            if (remainingPairs.remove(conflictKey(conflict))) {
                unresolvedCount++;
                continue;
            }
            Resolution action = appliedByAircraft.get(conflict.getA1().getId());
            if (action == null) action = appliedByAircraft.get(conflict.getA2().getId());
            if (action != null) resolvedPairs.add(resolvedPair(conflict, action));
        }

        if (unresolvedCount > 0) {
            System.out.println("[UNRESOLVED] " + unresolvedCount
                    + " conflict(s) still predicted after this cycle");
        }
        if (!remainingPairs.isEmpty()) {
            System.out.println("[WARNING] " + remainingPairs.size()
                    + " new conflict(s) introduced by resolution — will handle next cycle");
        }

        notifyConflicts(postCheck, resolvedPairs);
        updateMap(aircrafts);
    }

    private static String[] resolvedPair(Conflict conflict, Resolution action) {
        String tag;
        if (action.isSpeedResolution()) {
            tag = "SPD:" + (int) action.getNewSpeed();
        } else if (action.isHeadingResolution()) {
            tag = "HDG:" + (int) action.getNewHeading();
        } else {
            tag = String.valueOf((int) action.getNewAltitude());
        }
        return new String[]{conflict.getA1().getCallsign(), conflict.getA2().getCallsign(),
                action.getAircraft().getCallsign(), tag};
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

    private void notifyConflicts(List<Conflict> conflicts, List<String[]> resolvedPairs) {
        WYJAppController ctrl = WYJAppController.getInstance();
        if (ctrl == null) return;
        List<String[]> allPairs = new ArrayList<>();
        for (Conflict c : conflicts)
            allPairs.add(new String[]{c.getA1().getCallsign(), c.getA2().getCallsign()});
        ctrl.batchNotify(allPairs, resolvedPairs);
    }

    private void updateMap(List<Aircraft> aircrafts) {
        WYJAppController ctrl = WYJAppController.getInstance();
        if (ctrl == null) return;
        ctrl.updateAllAircraft(aircrafts);
    }
}
