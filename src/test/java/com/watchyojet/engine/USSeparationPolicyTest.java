package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

class USSeparationPolicyTest {

    @Test
    void usesTerminalProfileWhenBothAircraftAreBelowTenThousandFeet() {
        Aircraft first = aircraft("A1", 5_000);
        Aircraft second = aircraft("A2", 9_999);

        USSeparationPolicy.Zone zone = USSeparationPolicy.zoneFor(first, second);

        assertEquals(USSeparationPolicy.Zone.TERMINAL, zone);
        assertEquals(3.0, zone.lateralNm());
        assertEquals(120.0, zone.lookaheadSeconds());
    }

    @Test
    void usesEnRouteProfileAtAndAboveTenThousandFeet() {
        Aircraft first = aircraft("A1", 9_000);
        Aircraft second = aircraft("A2", 10_000);

        USSeparationPolicy.Zone zone = USSeparationPolicy.zoneFor(first, second);

        assertEquals(USSeparationPolicy.Zone.EN_ROUTE, zone);
        assertEquals(5.0, zone.lateralNm());
        assertEquals(300.0, zone.lookaheadSeconds());
    }

    private static Aircraft aircraft(String callsign, double altitude) {
        return new Aircraft(callsign, 39.87, -75.24, altitude, 220, 90, AircraftType.A320);
    }
}
