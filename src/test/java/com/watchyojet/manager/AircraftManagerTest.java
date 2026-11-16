package com.watchyojet.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

class AircraftManagerTest {

    @Test
    void freshObservationRestoresReportedAltitude() {
        AircraftManager manager = new AircraftManager();
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000)));

        manager.getAircrafts().get(0).setAltitude(32_000);
        manager.syncWithLiveData(List.of(aircraft("A1", 30_500)));

        assertEquals(30_500, manager.getAircrafts().get(0).getAltitude());
    }

    @Test
    void keepsAircraftWithDuplicateCallsignsWhenIcaoIdsDiffer() {
        AircraftManager manager = new AircraftManager();
        manager.syncWithLiveData(List.of(
                new Aircraft("abc123", "DUPLICATE", 40, -75, 10_000, 200, 90, AircraftType.C172),
                new Aircraft("def456", "DUPLICATE", 41, -75, 11_000, 210, 180, AircraftType.C172)));

        assertEquals(2, manager.getAircrafts().size());
    }

    @Test
    void coastsAMissingTrackBeforeDroppingIt() {
        AircraftManager manager = new AircraftManager();
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000), aircraft("B2", 31_000)), 0);

        // B2 drops out of one snapshot: it coasts on its last report.
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000)), 20_000);
        assertEquals(2, manager.getAircrafts().size());
        assertTrue(find(manager, "B2").isCoasting());

        // It reappears: back to a normal track.
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000), aircraft("B2", 31_000)), 40_000);
        assertFalse(find(manager, "B2").isCoasting());

        // Missing for longer than the coast limit: dropped.
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000)), 60_000);
        manager.syncWithLiveData(List.of(aircraft("A1", 30_000)), 40_000 + AircraftManager.COAST_LIMIT_MS + 1);
        assertEquals(1, manager.getAircrafts().size());
    }

    @Test
    void neverCoastsScriptedTracksIntoLiveTraffic() {
        AircraftManager manager = new AircraftManager();
        manager.reset(List.of(aircraft("DEMO01", 22_000)));

        manager.syncWithLiveData(List.of(aircraft("A1", 30_000)), 0);

        assertEquals(1, manager.getAircrafts().size());
        assertEquals("A1", manager.getAircrafts().get(0).getId());
    }

    private static Aircraft find(AircraftManager manager, String id) {
        return manager.getAircrafts().stream().filter(a -> a.getId().equals(id)).findFirst().orElseThrow();
    }

    private static Aircraft aircraft(String callsign, double altitude) {
        return new Aircraft(callsign, 40, -75, altitude, 400, 90, AircraftType.A320);
    }
}
