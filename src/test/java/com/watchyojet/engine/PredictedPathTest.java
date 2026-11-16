package com.watchyojet.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.watchyojet.model.Aircraft;
import com.watchyojet.model.AircraftType;
import com.watchyojet.simulation.MovementEngine;

class PredictedPathTest {

    @Test
    void predictsThePathTheMovementEngineFlies() {
        Aircraft flown = new Aircraft("A", 40.0, -75.0, 20_000, 300, 90, AircraftType.B737);
        flown.assign(Double.NaN, 200, 250);
        Aircraft origin = flown.copy();

        PredictedPath path = PredictedPath.sample(flown, origin.getLat(), origin.getLon(),
                Math.cos(Math.toRadians(origin.getLat())), 120);
        MovementEngine movement = new MovementEngine();
        for (int cycle = 0; cycle < 60; cycle++) movement.updatePositions(List.of(flown));

        double x = (flown.getLon() - origin.getLon()) * 60.0 * Math.cos(Math.toRadians(origin.getLat()));
        double y = (flown.getLat() - origin.getLat()) * 60.0;
        assertEquals(x, path.x[120], 0.1);
        assertEquals(y, path.y[120], 0.1);
    }
}
