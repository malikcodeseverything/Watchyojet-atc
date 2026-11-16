package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
        var original = new ConflictDetector().detectConflicts(aircraft, false);
        assertEquals(1, original.size());

        var trial = new ATCEngine().validateTrial(aircraft, original,
                Map.of(moving.getId(), new double[]{31_000, Double.NaN, Double.NaN}));

        assertTrue(trial.isEmpty());
        assertEquals(30_000, moving.getAltitude());
    }

    @Test
    void evaluatesAClearTrialWithoutChangingTheInputTracks() {
        var original = new ConflictDetector().detectConflicts(aircraft, false);

        var trial = new ATCEngine().validateTrial(aircraft, original,
                Map.of(moving.getId(), new double[]{32_000, Double.NaN, Double.NaN}));

        assertTrue(trial.orElseThrow().isEmpty());
        assertEquals(30_000, moving.getAltitude());
    }

    @Test
    void appliesTheAcceptedManeuversInTheScriptedDemo() {
        var tracks = DemoScenario.build();
        var detector = new ConflictDetector();
        assertEquals(3, detector.detectConflicts(tracks, false).size());

        new ATCEngine().runCycle(tracks);

        assertTrue(detector.detectConflicts(tracks, false).isEmpty());
    }

    private static Aircraft aircraft(String callsign, double latitude, double altitude) {
        return new Aircraft(callsign, latitude, -75, altitude, 300, 0, AircraftType.B737);
    }
}
