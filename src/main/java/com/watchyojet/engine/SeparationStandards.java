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

    /** Terminal minima apply within this distance of PHL. */
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

    public static final List<TowerZone> TOWER_ZONES = List.of(
            new TowerZone("PHL", 39.8719, -75.2411),
            new TowerZone("PNE", 40.0819, -75.0106),
            new TowerZone("TTN", 40.2767, -74.8135),
            new TowerZone("ILG", 39.6787, -75.6065));

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

    public static boolean inTerminalArea(Aircraft aircraft) {
        return aircraft.getAltitude() < TERMINAL_CEILING_FT
                && distanceNm(aircraft.getLat(), aircraft.getLon(), PHL_LAT, PHL_LON) <= TERMINAL_RADIUS_NM;
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
