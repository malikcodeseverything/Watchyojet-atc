package com.watchyojet.simulation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;

public class MovementEngineTest {

    private static final double EPSILON = 0.000001;

    @Test
    void shouldMoveAircraft() {

        // Arrange
        Aircraft a = new Aircraft(
                "A1",
                0,
                0,
                30000,
                1000,
                0,
                AircraftType.A320
        );

        List<Aircraft> aircrafts = List.of(a);
        MovementEngine engine = new MovementEngine();

        double oldLat = a.getLat();
        double oldLon = a.getLon();

        // Act
        engine.updatePositions(aircrafts);

        // Calculate movement distance
        double distanceMoved = Math.sqrt(
                Math.pow(a.getLat() - oldLat, 2) +
                Math.pow(a.getLon() - oldLon, 2)
        );

        // Assert
        assertTrue(distanceMoved > EPSILON, "Aircraft should have moved");
    }

    @Test
    void shouldMoveAircraftDiagonally() {

        // Arrange
        Aircraft a = new Aircraft(
                "A1",
                0,
                0,
                30000,
                1000,
                45,
                AircraftType.A320
        );

        List<Aircraft> aircrafts = List.of(a);
        MovementEngine engine = new MovementEngine();

        double oldLat = a.getLat();
        double oldLon = a.getLon();

        // Act
        engine.updatePositions(aircrafts);

        // Assert
        boolean latChanged = Math.abs(oldLat - a.getLat()) > EPSILON;
        boolean lonChanged = Math.abs(oldLon - a.getLon()) > EPSILON;

        assertTrue(latChanged, "Latitude should change");
        assertTrue(lonChanged, "Longitude should change");
    }

    @Test
    void turnsAtStandardRateTowardTheAssignedHeading() {
        Aircraft a = new Aircraft("TURN", 40, -75, 20_000, 300, 350, AircraftType.B737);
        a.setTargetHeading(20);   // 30 degrees right, through north
        MovementEngine engine = new MovementEngine();

        engine.updatePositions(List.of(a));
        assertEquals(356, a.getHeading(), 1e-9, "6 degrees per 2 s cycle");

        for (int i = 0; i < 4; i++) engine.updatePositions(List.of(a));
        assertEquals(20, a.getHeading(), 1e-9);
        assertTrue(Double.isNaN(a.getTargetHeading()), "target cleared once reached");
    }

    @Test
    void climbsAtTheTypeRateAndLevelsOffAtTheAssignedAltitude() {
        Aircraft a = new Aircraft("CLIMB", 40, -75, 20_000, 300, 90, AircraftType.B737);
        a.setTargetAltitude(21_000);
        MovementEngine engine = new MovementEngine();

        engine.updatePositions(List.of(a));
        assertEquals(20_080, a.getAltitude(), 1e-6, "2,400 ft/min is 80 ft per cycle");
        assertEquals(2_400, a.getVerticalRate(), 1e-9);

        for (int i = 0; i < 20; i++) engine.updatePositions(List.of(a));
        assertEquals(21_000, a.getAltitude(), 1e-9);
        assertEquals(0, a.getVerticalRate(), 1e-9);
        assertTrue(Double.isNaN(a.getTargetAltitude()));
    }
}