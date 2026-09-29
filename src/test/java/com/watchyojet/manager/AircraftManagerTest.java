package com.watchyojet.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static Aircraft aircraft(String callsign, double altitude) {
        return new Aircraft(callsign, 40, -75, altitude, 400, 90, AircraftType.A320);
    }
}
