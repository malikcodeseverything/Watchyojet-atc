package com.watchyojet.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.watchyojet.engine.ConflictDetector;

class DemoScenarioTest {

    @Test
    void detectsTheThreeScriptedConflictPairs() {
        var conflicts = new ConflictDetector().detectConflicts(DemoScenario.build());

        var pairs = conflicts.stream()
                .map(conflict -> Set.of(conflict.getA1().getId(), conflict.getA2().getId()))
                .collect(Collectors.toSet());

        assertEquals(3, conflicts.size());
        assertEquals(Set.of(
                Set.of("DEMO01", "DEMO02"),
                Set.of("DEMO03", "DEMO04"),
                Set.of("DEMO05", "DEMO06")), pairs);
    }
}
