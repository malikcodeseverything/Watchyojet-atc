package com.watchyojet.engine;

import java.util.List;

import com.watchyojet.model.Aircraft;

/**
 * Receives the result of every engine cycle. Keeps the engine free of any display code.
 */
@FunctionalInterface
public interface CycleListener {

    CycleListener NONE = (report, aircraft) -> { };

    void onCycle(CycleReport report, List<Aircraft> aircraft);
}
