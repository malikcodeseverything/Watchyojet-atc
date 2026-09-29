package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;
import com.watchyojet.model.Conflict;
import com.watchyojet.model.Resolution;

class ResolutionEngineTest {

    @Test
    void issuesSpeedReductionOnlyWhenItCreatesSafeSeparation() {
        Aircraft moving = new Aircraft("move-id", "MOVE", 40, -75.13,
                10_000, 250, 90, AircraftType.B737);
        Aircraft ahead = new Aircraft("ahead-id", "AHEAD", 40, -75,
                10_000, 230, 90, AircraftType.B737);

        Resolution resolution = new ResolutionEngine().trySpeed(
                moving, ahead, List.of(moving, ahead), new HashMap<>());

        assertNotNull(resolution);
        assertTrue(resolution.isSpeedResolution());
        assertEquals(225, resolution.getNewSpeed(), 0.001);
    }

    @Test
    void rejectsSpeedReductionBelowUniversalFloor() {
        Aircraft moving = new Aircraft("move-id", "MOVE", 40, -75.13,
                10_000, 155, 90, AircraftType.C172);
        Aircraft ahead = new Aircraft("ahead-id", "AHEAD", 40, -75,
                10_000, 150, 90, AircraftType.C172);

        assertNull(new ResolutionEngine().trySpeed(
                moving, ahead, List.of(moving, ahead), new HashMap<>()));
    }

    @Test
    void rejectsSpeedReductionThatCreatesConflictWithDistantNeighbor() {
        Aircraft moving = new Aircraft("move-id", "MOVE", 40, -75.13,
                10_000, 250, 90, AircraftType.B737);
        Aircraft ahead = new Aircraft("ahead-id", "AHEAD", 40, -75,
                10_000, 230, 90, AircraftType.B737);
        Aircraft fasterBehind = new Aircraft("neighbor-id", "NEIGHBOR", 40, -75.6,
                10_000, 260, 90, AircraftType.B737);

        assertNull(new ResolutionEngine().trySpeed(moving, ahead,
                List.of(moving, ahead, fasterBehind), new HashMap<>()));
    }

    @Test
    void reportsUnresolvedInsteadOfIssuingUncheckedFallback() {
        Aircraft moving = aircraft("MOVE", 13_000, 250);
        Aircraft conflict = aircraft("CONFLICT", 13_000, 250);
        List<Aircraft> traffic = new ArrayList<>(List.of(moving, conflict));

        // Occupy every valid lower altitude slot for a C172. All aircraft share
        // a position, so candidate heading changes also fail separation checks.
        for (int altitude = 3_000; altitude <= 12_000; altitude += 1_000) {
            traffic.add(aircraft("BLOCK-" + altitude, altitude, 250));
        }

        Conflict detected = new Conflict(moving, conflict,
                Conflict.Severity.CRITICAL, 30, 0);
        List<Resolution> resolutions = new ResolutionEngine().resolveCluster(
                new ArrayList<>(List.of(detected)), traffic,
                new HashMap<>(), new HashSet<>(), new HashSet<>());

        assertTrue(resolutions.isEmpty(),
                "No command is safer than an unchecked altitude fallback");
    }

    private static Aircraft aircraft(String callsign, double altitude, double speed) {
        return new Aircraft(callsign, 40, -75, altitude, speed, 90, AircraftType.C172);
    }
}
