package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

class ATCEngineTest {

    @Test
    void observationModeDoesNotMoveOrResolveLiveAircraft() {
        Aircraft first = new Aircraft("A1", 40.0, -75.0, 5_000, 300, 90, AircraftType.A320);
        Aircraft second = new Aircraft("A2", 40.0, -74.8, 5_000, 300, 270, AircraftType.A320);

        new ATCEngine().runCycle(List.of(first, second), false);

        assertEquals(40.0, first.getLat());
        assertEquals(-75.0, first.getLon());
        assertEquals(5_000, first.getAltitude());
        assertEquals(90, first.getHeading());
        assertEquals(300, first.getSpeed());
    }
}
