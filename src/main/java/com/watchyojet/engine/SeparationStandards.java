package com.watchyojet.engine;

import java.util.List;

import com.watchyojet.model.Aircraft;

/**
 * The separation and alerting thresholds the engine applies.
 *
 * <p>Lateral minima follow the FAA radar minima in JO 7110.65, paragraph 5-5-4, in simplified
 * form: 3 NM in the terminal area around Philadelphia and 5 NM elsewhere. Vertical separation is
 * 1,000 ft. The lookahead times are WatchyoJet's own choices, kept short in the terminal area
 * because aircraft there turn, climb and descend often, so straight-line predictions age quickly.
 */
public final class SeparationStandards {

    public static final double PHL_LAT = 39.8719;
    public static final double PHL_LON = -75.2411;

    /**
     * Airports with their own approach control inside or next to the OpenSky query box. The box
     * reaches 40.9 N, so it includes the Newark area as well as Philadelphia.
     */
    public record TerminalHub(String airport, double lat, double lon) {
    }

    public static final List<TerminalHub> TERMINAL_HUBS = List.of(
            new TerminalHub("PHL", PHL_LAT, PHL_LON),
            new TerminalHub("EWR", 40.6925, -74.1687),
            new TerminalHub("ACY", 39.4576, -74.5772),
            new TerminalHub("ABE", 40.6521, -75.4408),
            new TerminalHub("BWI", 39.1754, -76.6683));

    /** Terminal minima apply within this distance of a terminal hub. */
    public static final double TERMINAL_RADIUS_NM = 40;
    /** Terminal minima apply below this altitude. */
    public static final double TERMINAL_CEILING_FT = 18_000;

    public static final double TERMINAL_LATERAL_NM = 3;
    public static final double EN_ROUTE_LATERAL_NM = 5;
    public static final double VERTICAL_FT = 1_000;

    public static final double TERMINAL_LOOKAHEAD_S = 120;
    public static final double EN_ROUTE_LOOKAHEAD_S = 300;

    /** An active alert is held until the pair clears the minima by this much. */
    public static final double HOLD_LATERAL_MARGIN_NM = 0.5;
    public static final double HOLD_VERTICAL_MARGIN_FT = 200;

    /**
     * Airspace around a towered airport. Close to the runways the tower separates traffic
     * visually and with runway procedures, and parallel approaches legally run much closer than
     * radar minima, so radar conflict alerts would mostly be false. Terminal radar systems use
     * alert inhibit areas for the same reason. The zone follows the typical size of tower
     * airspace: about 5 NM out and up to 2,500 ft.
     */
    public record TowerZone(String airport, double lat, double lon) {
    }

    /** Towered airports inside the OpenSky query box (40.9 N to 38.8 N, 76.5 W to 74.0 W). */
    public static final List<TowerZone> TOWER_ZONES = List.of(
            new TowerZone("PHL", 39.8719, -75.2411),
            new TowerZone("PNE", 40.0819, -75.0106),
            new TowerZone("TTN", 40.2767, -74.8135),
            new TowerZone("ILG", 39.6787, -75.6065),
            new TowerZone("EWR", 40.6925, -74.1687),
            new TowerZone("TEB", 40.8501, -74.0608),
            new TowerZone("MMU", 40.7994, -74.4149),
            new TowerZone("CDW", 40.8752, -74.2814),
            new TowerZone("ACY", 39.4576, -74.5772),
            new TowerZone("ABE", 40.6521, -75.4408),
            new TowerZone("RDG", 40.3785, -75.9652),
            new TowerZone("LNS", 40.1217, -76.2961),
            new TowerZone("MTN", 39.3257, -76.4138),
            new TowerZone("DOV", 39.1295, -75.4660),
            new TowerZone("WRI", 40.0156, -74.5917));

    public static final double TOWER_ZONE_RADIUS_NM = 5;
    public static final double TOWER_ZONE_CEILING_FT = 2_500;

    private SeparationStandards() {
    }

    public static boolean inTowerZone(Aircraft aircraft) {
        if (aircraft.getAltitude() >= TOWER_ZONE_CEILING_FT) return false;
        for (TowerZone zone : TOWER_ZONES) {
            if (distanceNm(aircraft.getLat(), aircraft.getLon(), zone.lat(), zone.lon())
                    <= TOWER_ZONE_RADIUS_NM) return true;
        }
        return false;
    }

    /** Radar alerts are suppressed only when both aircraft are inside tower airspace. */
    public static boolean towerPair(Aircraft a, Aircraft b) {
        return inTowerZone(a) && inTowerZone(b);
    }

    /**
     * True for an aircraft that receives traffic information rather than separation: VFR
     * (squawk 1200), a helicopter, a likely VFR private flight, or a track that is low or slow
     * enough to be in a traffic pattern, landing or hovering. Low traffic outside tower airspace
     * is mostly at airports without a tower, where IFR aircraft are handled one at a time by
     * procedure rather than by radar separation. It is still counted and reported, never dropped.
     */
    public static boolean trafficOnly(Aircraft aircraft) {
        return aircraft.isVfr() || aircraft.isRotorcraft() || likelyVfr(aircraft) || lowOrSlow(aircraft);
    }

    /** True when radar separation applies to the pair, so a predicted conflict is an alert. */
    public static boolean separationApplies(Aircraft a, Aircraft b) {
        return !towerPair(a, b) && !trafficOnly(a) && !trafficOnly(b);
    }

    public static boolean inTerminalArea(Aircraft aircraft) {
        if (aircraft.getAltitude() >= TERMINAL_CEILING_FT) return false;
        for (TerminalHub hub : TERMINAL_HUBS) {
            if (distanceNm(aircraft.getLat(), aircraft.getLon(), hub.lat(), hub.lon())
                    <= TERMINAL_RADIUS_NM) return true;
        }
        return false;
    }

    /** Below this height an aircraft is taking off, landing, in a traffic pattern or hovering. */
    public static final double LOW_LEVEL_FT = 1_000;
    /** Slower than this, a track is taxiing, hovering or a balloon. */
    public static final double SLOW_TRACK_KT = 40;

    public static boolean lowOrSlow(Aircraft aircraft) {
        return aircraft.getAltitude() < LOW_LEVEL_FT || aircraft.getSpeed() < SLOW_TRACK_KT;
    }

    private static final java.util.regex.Pattern US_REGISTRATION =
            java.util.regex.Pattern.compile("N[1-9][0-9A-Z]{0,4}");
    public static final double LIKELY_VFR_MAX_SPEED_KT = 150;
    public static final double LIKELY_VFR_MAX_ALTITUDE_FT = 10_000;

    /**
     * An estimate, because OpenSky does not report flight rules. A US-registered aircraft flying
     * under its registration (not an airline callsign), slower than 150 kt and below 10,000 ft
     * is most likely a VFR private flight. Many such pilots use flight following, so they squawk
     * a discrete code instead of 1200 while still receiving traffic information rather than
     * separation. This can misclassify a slow IFR private flight.
     */
    public static boolean likelyVfr(Aircraft aircraft) {
        return !aircraft.isSquawkingEmergency()
                && US_REGISTRATION.matcher(aircraft.getCallsign()).matches()
                && aircraft.getSpeed() < LIKELY_VFR_MAX_SPEED_KT
                && aircraft.getAltitude() < LIKELY_VFR_MAX_ALTITUDE_FT;
    }

    /** Terminal minima apply only when both aircraft are in the terminal area. */
    public static boolean terminalPair(Aircraft a, Aircraft b) {
        return inTerminalArea(a) && inTerminalArea(b);
    }

    public static double lateralMinimumNm(Aircraft a, Aircraft b) {
        return terminalPair(a, b) ? TERMINAL_LATERAL_NM : EN_ROUTE_LATERAL_NM;
    }

    public static double lookaheadSeconds(Aircraft a, Aircraft b) {
        return terminalPair(a, b) ? TERMINAL_LOOKAHEAD_S : EN_ROUTE_LOOKAHEAD_S;
    }

    /** True when the pair is inside both the lateral and vertical minima right now. */
    public static boolean insideMinimaNow(Aircraft a, Aircraft b) {
        return Math.abs(a.getAltitude() - b.getAltitude()) < VERTICAL_FT
                && distanceNm(a.getLat(), a.getLon(), b.getLat(), b.getLon()) < lateralMinimumNm(a, b);
    }

    static double distanceNm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * 60.0;
        double dLon = (lon2 - lon1) * 60.0 * Math.cos(Math.toRadians((lat1 + lat2) / 2.0));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }
}
