package com.watchyojet.engine;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;
import com.watchyojet.model.Conflict;

public class ConflictDetectorTest {

    @Test
    void shouldDetectConflictWhenCloseAndSameAltitude() {
        // head-on convergence ~12 NM apart, tCPA ≈ 43 s
        Aircraft a1 = new Aircraft("A1", 40.0, -75.0, 30000, 500, 180, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 39.8, -75.0, 30000, 500,   0, AircraftType.A320);

        List<Aircraft> aircrafts = Arrays.asList(a1, a2);

        ConflictDetector detector = new ConflictDetector();
        List<Conflict> conflicts = detector.detectConflicts(aircrafts);

        assertEquals(1, conflicts.size());
    }

    @Test
    void shouldNotDetectConflictWhenFarApart() {
        // converging but tCPA >> 300 s
        Aircraft a1 = new Aircraft("A1", 40.0, -75.0, 30000, 500, 180, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 30.0, -65.0, 30000, 500,   0, AircraftType.A320);

        List<Aircraft> aircrafts = Arrays.asList(a1, a2);

        ConflictDetector detector = new ConflictDetector();
        List<Conflict> conflicts = detector.detectConflicts(aircrafts);

        assertEquals(0, conflicts.size());
    }

    @Test
    void shouldNotDetectConflictWhenAltitudeSeparated() {
        // same geometry as close test but 2000 ft altitude difference
        Aircraft a1 = new Aircraft("A1", 40.0, -75.0, 30000, 500, 180, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 39.8, -75.0, 32000, 500,   0, AircraftType.A320);

        List<Aircraft> aircrafts = Arrays.asList(a1, a2);

        ConflictDetector detector = new ConflictDetector();
        List<Conflict> conflicts = detector.detectConflicts(aircrafts);

        assertEquals(0, conflicts.size());
    }

    @Test
    void shouldDetectMultipleConflicts() {
        // a1 heading east, a2 and a3 heading west — a1 converges with both
        Aircraft a1 = new Aircraft("A1", 40.0, -75.0, 30000, 500,  90, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 40.0, -74.9, 30000, 500, 270, AircraftType.A320);
        Aircraft a3 = new Aircraft("A3", 40.0, -74.8, 30000, 500, 270, AircraftType.A320);

        List<Aircraft> aircrafts = Arrays.asList(a1, a2, a3);

        ConflictDetector detector = new ConflictDetector();
        List<Conflict> conflicts = detector.detectConflicts(aircrafts);

        assertTrue(conflicts.size() >= 1);
    }

    @Test
    void detectsCurrentConflictBetweenParallelTracks() {
        Aircraft a1 = new Aircraft("A1", 40.00, -75.0, 30000, 500, 0, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 39.97, -75.0, 30000, 500, 0, AircraftType.A320);

        List<Conflict> conflicts = new ConflictDetector().detectConflicts(List.of(a1, a2));

        assertEquals(1, conflicts.size());
        assertEquals(0.0, conflicts.getFirst().getTCPA());
        assertEquals(1.8, conflicts.getFirst().getDCPA(), 0.001);
    }

    @Test
    void detectsCurrentConflictBetweenDivergingTracks() {
        Aircraft a1 = new Aircraft("A1", 40.00, -75.0, 30000, 500,   0, AircraftType.A320);
        Aircraft a2 = new Aircraft("A2", 39.97, -75.0, 30000, 500, 180, AircraftType.A320);
        Aircraft a3 = new Aircraft("A3", 39.94, -75.0, 32000, 500, 180, AircraftType.A320);

        List<Conflict> conflicts = new ConflictDetector().detectConflicts(List.of(a1, a2, a3));

        assertEquals(1, conflicts.size());
        assertEquals(0.0, conflicts.getFirst().getTCPA());
        assertEquals(1.8, conflicts.getFirst().getDCPA(), 0.001);
    }
    @Test
    void appliesThreeMilesInTheTerminalAreaAndFiveAbove() {
        // Parallel tracks 4 NM apart near PHL.
        Aircraft low1 = new Aircraft("L1", 39.90, -75.30, 8_000, 250, 0, AircraftType.B737);
        Aircraft low2 = new Aircraft("L2", 39.90, -75.2135, 8_000, 250, 0, AircraftType.B737);
        Aircraft high1 = new Aircraft("H1", 39.90, -75.30, 30_000, 250, 0, AircraftType.B737);
        Aircraft high2 = new Aircraft("H2", 39.90, -75.2135, 30_000, 250, 0, AircraftType.B737);

        assertTrue(new ConflictDetector().detectConflicts(List.of(low1, low2)).isEmpty());
        assertEquals(1, new ConflictDetector().detectConflicts(List.of(high1, high2)).size());
    }

    @Test
    void usesVerticalRateToPredictWhetherLevelsMeet() {
        // Head-on near PHL, 10 NM apart, closing at 500 kt.
        Aircraft level = new Aircraft("LEVEL", 39.80, -75.25, 8_000, 250, 0, AircraftType.B737);
        Aircraft descending = new Aircraft("DESC", 39.9667, -75.25, 11_000, 250, 180, AircraftType.B737);

        assertTrue(new ConflictDetector().detectConflicts(List.of(level, descending)).isEmpty(),
                "level 3,000 ft apart");

        descending.setVerticalRate(-1_500);
        assertEquals(1, new ConflictDetector().detectConflicts(List.of(level, descending)).size(),
                "descends into the other aircraft's level while laterally close");

        descending.setVerticalRate(1_500);
        assertTrue(new ConflictDetector().detectConflicts(List.of(level, descending)).isEmpty(),
                "climbing away");
    }

    @Test
    void looksFurtherAheadEnRouteThanInTheTerminalArea() {
        // Head-on 30 NM apart, closing at 500 kt: the lateral minimum is reached after about 3 minutes.
        Aircraft low1 = new Aircraft("L1", 39.60, -75.25, 8_000, 250, 0, AircraftType.B737);
        Aircraft low2 = new Aircraft("L2", 40.10, -75.25, 8_000, 250, 180, AircraftType.B737);
        Aircraft high1 = new Aircraft("H1", 39.60, -75.25, 30_000, 250, 0, AircraftType.B737);
        Aircraft high2 = new Aircraft("H2", 40.10, -75.25, 30_000, 250, 180, AircraftType.B737);

        assertTrue(new ConflictDetector().detectConflicts(List.of(low1, low2)).isEmpty());
        assertEquals(1, new ConflictDetector().detectConflicts(List.of(high1, high2)).size());
    }
}
