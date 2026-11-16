package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.watchyojet.engine.CycleReport.ActiveAlert;
import com.watchyojet.engine.CycleReport.Advice;
import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;
import com.watchyojet.simulation.DemoScenario;

class ATCEngineTest {

    private final Aircraft moving = aircraft("MOVE", 40.00, 30_000);
    private final Aircraft originalNeighbor = aircraft("NEAR", 39.97, 29_500);
    private final Aircraft otherNeighbor = aircraft("OTHER", 40.03, 31_000);
    private final List<Aircraft> aircraft = List.of(moving, originalNeighbor, otherNeighbor);

    @Test
    void rejectsATrialThatTradesOneConflictForAnother() {
        var original = new ConflictDetector().detectConflicts(aircraft);
        assertEquals(1, original.size());

        var trial = new ATCEngine().validateTrial(aircraft, original,
                Map.of(moving.getId(), new double[]{31_000, Double.NaN, Double.NaN}));

        assertTrue(trial.isEmpty());
        assertEquals(30_000, moving.getAltitude());
    }

    @Test
    void evaluatesAClearTrialWithoutChangingTheInputTracks() {
        var original = new ConflictDetector().detectConflicts(aircraft);

        var trial = new ATCEngine().validateTrial(aircraft, original,
                Map.of(moving.getId(), new double[]{32_000, Double.NaN, Double.NaN}));

        assertTrue(trial.orElseThrow().isEmpty());
        assertEquals(30_000, moving.getAltitude());
    }

    @Test
    void appliesTheAcceptedManeuversInTheScriptedDemo() {
        var tracks = DemoScenario.build();
        var detector = new ConflictDetector();
        assertEquals(3, detector.detectConflicts(tracks).size());

        CycleReport report = new ATCEngine().runCycle(tracks);

        assertTrue(detector.detectConflicts(tracks).isEmpty());
        assertEquals(3, report.cleared().size());
        assertTrue(report.cleared().stream().allMatch(CycleReport.ClearedAlert::clearedByManeuver));
    }

    @Test
    void preservesScriptedConflictsDuringTheDemoPreview() {
        var tracks = DemoScenario.build();
        var engine = new ATCEngine();
        var detector = new ConflictDetector();

        CycleReport preview = engine.runCycle(tracks, CycleMode.DETECT_ONLY);
        assertEquals(3, detector.detectConflicts(tracks).size());
        assertTrue(preview.active().stream().allMatch(a -> a.advice() == Advice.NOT_EVALUATED));

        engine.runCycle(tracks);
        assertTrue(detector.detectConflicts(tracks).isEmpty());
    }

    @Test
    void advisoryModeNeverChangesObservedTracks() {
        var tracks = DemoScenario.build();
        double[][] before = snapshot(tracks);

        CycleReport report = new ATCEngine().runCycle(tracks, CycleMode.ADVISE);

        for (int i = 0; i < tracks.size(); i++) {
            assertEquals(before[i][0], tracks.get(i).getAltitude());
            assertEquals(before[i][1], tracks.get(i).getHeading());
            assertEquals(before[i][2], tracks.get(i).getSpeed());
        }
        assertEquals(3, new ConflictDetector().detectConflicts(tracks).size());
        assertEquals(3, report.active().size());
        assertTrue(report.cleared().isEmpty());
        for (ActiveAlert alert : report.active()) {
            assertEquals(Advice.CLEARS_IN_SIMULATION, alert.advice());
            assertFalse(alert.instruction().isEmpty());
        }
    }

    @Test
    void keepsAlertIdentityAcrossRefreshes() {
        var tracks = DemoScenario.build();
        var engine = new ATCEngine();

        CycleReport first = engine.runCycle(tracks, CycleMode.ADVISE, 1_000);
        CycleReport second = engine.runCycle(tracks, CycleMode.ADVISE, 3_000);

        assertEquals(3, first.counters().newAlerts());
        assertEquals(0, second.counters().newAlerts());
        assertTrue(second.active().stream().allMatch(a -> a.firstSeenMillis() == 1_000));
    }

    @Test
    void clearsAnAdvisoryOnlyWhenTheObservedTracksSeparate() {
        Aircraft north = new Aircraft("N1", 40.00, -75.00, 30_000, 300, 0, AircraftType.B737);
        Aircraft south = new Aircraft("S1", 40.20, -75.00, 30_500, 300, 180, AircraftType.B737);
        List<Aircraft> tracks = List.of(north, south);
        var engine = new ATCEngine();

        engine.runCycle(tracks, CycleMode.ADVISE, 0);
        CycleReport stillPredicted = engine.runCycle(tracks, CycleMode.ADVISE, 2_000);
        assertEquals(1, stillPredicted.active().size());
        assertTrue(stillPredicted.cleared().isEmpty());

        // A fresh observation shows the aircraft really climbed.
        south.setAltitude(33_000);
        CycleReport separated = engine.runCycle(tracks, CycleMode.ADVISE, 4_000);

        assertTrue(separated.active().isEmpty());
        assertEquals(1, separated.cleared().size());
        assertFalse(separated.cleared().get(0).clearedByManeuver());
    }

    @Test
    void acceptsSafeGroupsEvenWhenAnotherGroupIsRejected() {
        // Group X: A must descend to FL290, straight into C's path, so its proposal is rejected.
        Aircraft a = new Aircraft("A", 40.00, -75.00, 30_000, 300, 0, AircraftType.B737);
        Aircraft b = new Aircraft("B", 40.20, -75.00, 30_500, 300, 180, AircraftType.B737);
        Aircraft c = new Aircraft("C", 40.40, -75.00, 29_000, 300, 180, AircraftType.B737);
        // Group Y: an unrelated encounter 45 NM away with a clean fix.
        Aircraft d = new Aircraft("D", 40.00, -76.00, 20_000, 300, 0, AircraftType.B737);
        Aircraft e = new Aircraft("E", 40.20, -76.00, 20_500, 300, 180, AircraftType.B737);

        CycleReport report = new ATCEngine().runCycle(List.of(a, b, c, d, e), CycleMode.ADVISE);

        assertEquals(2, report.counters().groups());
        assertEquals(1, report.counters().acceptedGroups());
        assertEquals(1, report.counters().rejectedGroups());
        assertEquals(Advice.REJECTED_NEW_CONFLICT, adviceFor(report, "A", "B"));
        assertEquals(Advice.CLEARS_IN_SIMULATION, adviceFor(report, "D", "E"));
    }

    @Test
    void phrasesProposalsAsInstructions() {
        Aircraft jet = new Aircraft("DAL123", 40.0, -75.0, 12_000, 280, 90, AircraftType.B737);
        assertEquals("DAL123 climb and maintain 13,000 ft",
                Instructions.describe(new com.watchyojet.model.Resolution(jet, 13_000)));
        assertEquals("DAL123 descend and maintain FL240",
                Instructions.describe(new com.watchyojet.model.Resolution(
                        new Aircraft("DAL123", 40.0, -75.0, 25_000, 280, 90, AircraftType.B737), 24_000)));
        assertEquals("DAL123 turn left heading 060",
                Instructions.describe(com.watchyojet.model.Resolution.forHeading(jet, 60)));
        assertEquals("DAL123 turn right heading 120",
                Instructions.describe(com.watchyojet.model.Resolution.forHeading(jet, 120)));
        assertEquals("DAL123 reduce speed to 252 knots",
                Instructions.describe(com.watchyojet.model.Resolution.forSpeed(jet, 252)));
    }

    @Test
    void reportsVfrProximityAsTrafficInsteadOfResolvingIt() {
        var tracks = DemoScenario.build();
        tracks.get(4).setSquawk("1200");   // DEMO05

        CycleReport report = new ATCEngine().runCycle(tracks, CycleMode.ADVISE);

        assertEquals(1, report.counters().trafficAdvisories());
        assertEquals(2, report.active().size());
        assertTrue(report.active().stream().noneMatch(a -> a.pairKey().contains("DEMO05")));
        // The VFR pair must not make every trial look like it created a new conflict.
        assertEquals(2, report.counters().acceptedGroups());
        assertEquals(0, report.counters().rejectedGroups());
        assertTrue(report.active().stream().allMatch(a -> a.advice() == Advice.CLEARS_IN_SIMULATION));
    }

    @Test
    void holdsAnAlertUntilThePairClearsByAMargin() {
        Aircraft north = new Aircraft("N1", 40.00, -75.00, 30_000, 300, 0, AircraftType.B737);
        Aircraft south = new Aircraft("S1", 40.20, -75.00, 30_500, 300, 180, AircraftType.B737);
        List<Aircraft> tracks = List.of(north, south);
        var engine = new ATCEngine();
        engine.runCycle(tracks, CycleMode.ADVISE, 0);

        south.setAltitude(31_100);   // legally separated, but inside the 200 ft hold margin
        CycleReport hovering = engine.runCycle(tracks, CycleMode.ADVISE, 2_000);
        assertEquals(1, hovering.active().size());
        assertEquals(Advice.CLEARING, hovering.active().get(0).advice());
        assertEquals(0, hovering.active().get(0).firstSeenMillis());
        assertTrue(hovering.cleared().isEmpty());

        south.setAltitude(31_300);
        CycleReport separated = engine.runCycle(tracks, CycleMode.ADVISE, 4_000);
        assertTrue(separated.active().isEmpty());
        assertEquals(1, separated.cleared().size());
    }

    @Test
    void neverMovesAnAircraftSquawkingEmergency() {
        Aircraft mayday = new Aircraft("MAYDAY", 40.00, -75.00, 30_000, 300, 0, AircraftType.B737);
        Aircraft other = new Aircraft("OTHER", 40.20, -75.00, 30_500, 300, 180, AircraftType.B737);
        mayday.setSquawk("7700");

        CycleReport report = new ATCEngine().runCycle(List.of(mayday, other), CycleMode.ADVISE);

        assertEquals(1, report.active().size());
        assertTrue(report.active().get(0).instruction().startsWith("OTHER "));
    }

    @Test
    void treatsHelicoptersAsTrafficInsteadOfSeparatingThem() {
        var tracks = DemoScenario.build();
        tracks.get(5).setEmitterCategory(8);   // DEMO06 reported as rotorcraft

        CycleReport report = new ATCEngine().runCycle(tracks, CycleMode.ADVISE);

        assertEquals(1, report.counters().trafficAdvisories());
        assertEquals(2, report.active().size());
        assertEquals(0, report.counters().rejectedGroups());
    }

    @Test
    void leavesPairsInsideTowerAirspaceToTheTower() {
        // Parallel arrivals 1 NM apart, 3 NM west of PHL.
        Aircraft left = new Aircraft("LEFT", 39.8719, -75.30, 1_500, 140, 90, AircraftType.B737);
        Aircraft right = new Aircraft("RIGHT", 39.8886, -75.30, 1_500, 140, 90, AircraftType.B737);

        CycleReport low = new ATCEngine().runCycle(List.of(left, right), CycleMode.ADVISE);
        assertTrue(low.active().isEmpty());
        assertEquals(1, low.counters().towerSuppressed());

        left.setAltitude(4_000);
        right.setAltitude(4_000);
        CycleReport high = new ATCEngine().runCycle(List.of(left, right), CycleMode.ADVISE);
        assertEquals(1, high.active().size());
        assertEquals(0, high.counters().towerSuppressed());
    }

    private static Advice adviceFor(CycleReport report, String id1, String id2) {
        return report.active().stream()
                .filter(alert -> alert.pairKey().equals(id1 + ":" + id2))
                .findFirst().orElseThrow().advice();
    }

    private static double[][] snapshot(List<Aircraft> tracks) {
        double[][] values = new double[tracks.size()][];
        for (int i = 0; i < tracks.size(); i++) {
            Aircraft t = tracks.get(i);
            values[i] = new double[]{t.getAltitude(), t.getHeading(), t.getSpeed()};
        }
        return values;
    }

    private static Aircraft aircraft(String callsign, double latitude, double altitude) {
        return new Aircraft(callsign, latitude, -75, altitude, 300, 0, AircraftType.B737);
    }
}
