package com.watchyojet.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.watchyojet.model.Aircraft;

public class AircraftManager {

    private Map<String, Aircraft> aircraftMap = new ConcurrentHashMap<>();

    public void syncWithLiveData(List<Aircraft> liveData) {

        Map<String, Aircraft> updatedMap = new ConcurrentHashMap<>();

        for (Aircraft live : liveData) {

            String aircraftId = live.getId();

            if (aircraftMap.containsKey(aircraftId)) {

                Aircraft existing = aircraftMap.get(aircraftId);

                existing.setLat(live.getLat());
                existing.setLon(live.getLon());
                existing.setHeading(live.getHeading());
                existing.setSpeed(live.getSpeed());
                existing.setCallsign(live.getCallsign());
                existing.setType(live.getType());
                // Shadow-mode resolutions are hypothetical. A fresh observation must
                // restore the real reported altitude instead of drifting from reality.
                existing.setAltitude(live.getAltitude());
                updatedMap.put(aircraftId, existing);

            } else {
                updatedMap.put(aircraftId, live);
            }
        }

        aircraftMap = updatedMap;
    }

    public List<Aircraft> getAircrafts() {
        return new ArrayList<>(aircraftMap.values());
    }

    public void reset(List<Aircraft> fresh) {
        Map<String, Aircraft> newMap = new ConcurrentHashMap<>();
        for (Aircraft a : fresh) newMap.put(a.getId(), a);
        aircraftMap = newMap;
    }
}
