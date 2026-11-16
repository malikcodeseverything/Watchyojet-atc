package com.watchyojet.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.watchyojet.model.Aircraft;

/**
 * Keeps the current set of tracks in step with the OpenSky snapshots.
 *
 * <p>An aircraft missing from one snapshot is not dropped at once. ADS-B coverage has gaps, and
 * an aircraft can drop out of a single snapshot while still flying, so the track coasts on its
 * last reported state for up to {@link #COAST_LIMIT_MS} before it is removed. Radar displays
 * coast a track over a few missed scans for the same reason.
 */
public class AircraftManager {

    /** How long a track coasts without a fresh report before it is dropped. */
    public static final long COAST_LIMIT_MS = 45_000;

    private Map<String, Aircraft> aircraftMap = new ConcurrentHashMap<>();
    private Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public void syncWithLiveData(List<Aircraft> liveData) {
        syncWithLiveData(liveData, System.currentTimeMillis());
    }

    public void syncWithLiveData(List<Aircraft> liveData, long nowMillis) {

        Map<String, Aircraft> updatedMap = new ConcurrentHashMap<>();
        Map<String, Long> updatedSeen = new ConcurrentHashMap<>();

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
                // A fresh observation replaces the coasted state with what OpenSky reports.
                existing.setAltitude(live.getAltitude());
                existing.setVerticalRate(live.getVerticalRate());
                existing.setSquawk(live.getSquawk());
                existing.setEmitterCategory(live.getEmitterCategory());
                existing.setCoasting(false);
                updatedMap.put(aircraftId, existing);

            } else {
                updatedMap.put(aircraftId, live);
            }
            updatedSeen.put(aircraftId, nowMillis);
        }

        for (Map.Entry<String, Aircraft> entry : aircraftMap.entrySet()) {
            String aircraftId = entry.getKey();
            if (updatedMap.containsKey(aircraftId)) continue;
            // Tracks loaded by reset, such as the scripted demo, were never seen live: drop them.
            Long seen = lastSeen.get(aircraftId);
            if (seen == null || nowMillis - seen > COAST_LIMIT_MS) continue;
            entry.getValue().setCoasting(true);
            updatedMap.put(aircraftId, entry.getValue());
            updatedSeen.put(aircraftId, seen);
        }

        aircraftMap = updatedMap;
        lastSeen = updatedSeen;
    }

    public List<Aircraft> getAircrafts() {
        return new ArrayList<>(aircraftMap.values());
    }

    public void reset(List<Aircraft> fresh) {
        Map<String, Aircraft> newMap = new ConcurrentHashMap<>();
        for (Aircraft a : fresh) newMap.put(a.getId(), a);
        aircraftMap = newMap;
        lastSeen = new ConcurrentHashMap<>();
    }
}
