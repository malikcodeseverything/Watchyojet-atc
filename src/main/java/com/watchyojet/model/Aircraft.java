package com.watchyojet.model;

import java.util.Set;

public class Aircraft {

    /** Transponder codes for hijack, radio failure and emergency. */
    private static final Set<String> EMERGENCY_CODES = Set.of("7500", "7600", "7700");

    /** Transponder code for VFR aircraft not receiving ATC separation. */
    private static final String VFR_CODE = "1200";

    private final String id;
    private AircraftType type;
    private String callsign;
    private double lat;
    private double lon;
    private double altitude;      // ft
    private double speed;         // knots (ground speed for live traffic)
    private double heading;       // degrees true
    private double verticalRate;  // ft/min, positive when climbing
    private String squawk;        // transponder code, or null when not reported
    private int emitterCategory;  // ADS-B emitter category from OpenSky, 0 when unknown

    // Assigned values the aircraft is moving toward (simulated traffic only), NaN when none.
    private double targetAltitude = Double.NaN;
    private double targetHeading = Double.NaN;
    private double targetSpeed = Double.NaN;

    public Aircraft(String callsign, double lat, double lon,
                    double altitude, double speed, double heading,
                    AircraftType type) {
        this(callsign, callsign, lat, lon, altitude, speed, heading, type);
    }

    public Aircraft(String id, String callsign, double lat, double lon,
                    double altitude, double speed, double heading,
                    AircraftType type) {
        this.id = id;
        this.callsign = callsign;
        this.lat = lat;
        this.lon = lon;
        this.altitude = altitude;
        this.speed = speed;
        this.heading = heading;
        this.type = type;
    }

    /** An independent copy, used for trial maneuvers that must not touch the original. */
    public Aircraft copy() {
        Aircraft copy = new Aircraft(id, callsign, lat, lon, altitude, speed, heading, type);
        copy.verticalRate = verticalRate;
        copy.squawk = squawk;
        copy.emitterCategory = emitterCategory;
        copy.targetAltitude = targetAltitude;
        copy.targetHeading = targetHeading;
        copy.targetSpeed = targetSpeed;
        return copy;
    }

    public String getId() { return id; }

    public String getCallsign() { return callsign; }
    public void setCallsign(String callsign) { this.callsign = callsign; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }

    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; }

    public double getSpeed() { return speed; }
    public void setSpeed(double speed) { this.speed = speed; }

    public double getHeading() { return heading; }
    public void setHeading(double heading) { this.heading = heading; }

    public double getVerticalRate() { return verticalRate; }
    public void setVerticalRate(double verticalRate) { this.verticalRate = verticalRate; }

    public String getSquawk() { return squawk; }
    public void setSquawk(String squawk) { this.squawk = squawk; }

    public int getEmitterCategory() { return emitterCategory; }
    public void setEmitterCategory(int emitterCategory) { this.emitterCategory = emitterCategory; }

    public double getTargetAltitude() { return targetAltitude; }
    public void setTargetAltitude(double targetAltitude) { this.targetAltitude = targetAltitude; }

    public double getTargetHeading() { return targetHeading; }
    public void setTargetHeading(double targetHeading) { this.targetHeading = targetHeading; }

    public double getTargetSpeed() { return targetSpeed; }
    public void setTargetSpeed(double targetSpeed) { this.targetSpeed = targetSpeed; }

    /** The heading the aircraft is flying or has been assigned, used for prediction. */
    public double getIntendedHeading() {
        return Double.isNaN(targetHeading) ? heading : targetHeading;
    }

    /** The speed the aircraft is flying or has been assigned, used for prediction. */
    public double getIntendedSpeed() {
        return Double.isNaN(targetSpeed) ? speed : targetSpeed;
    }

    /**
     * Predicted altitude after {@code seconds}. With an assigned altitude the aircraft climbs or
     * descends at its type's rate and levels off there; otherwise it holds its reported rate.
     */
    public double altitudeAfter(double seconds) {
        if (Double.isNaN(targetAltitude)) return altitude + verticalRate * seconds / 60.0;
        double remaining = targetAltitude - altitude;
        double rate = type.getClimbRate() / 60.0;   // ft per second
        double travel = rate * seconds;
        return Math.abs(remaining) <= travel ? targetAltitude : altitude + Math.signum(remaining) * travel;
    }

    /** Seconds until an assigned climb or descent levels off, or 0 when there is none. */
    public double secondsToLevelOff() {
        if (Double.isNaN(targetAltitude)) return 0;
        return Math.abs(targetAltitude - altitude) / (type.getClimbRate() / 60.0);
    }

    public AircraftType getType() { return type; }
    public void setType(AircraftType type) { this.type = type; }

    /** True when the transponder reports 7500, 7600 or 7700. */
    public boolean isSquawkingEmergency() {
        return squawk != null && EMERGENCY_CODES.contains(squawk);
    }

    /** True when the transponder reports 1200, the VFR code. */
    public boolean isVfr() {
        return VFR_CODE.equals(squawk);
    }

    /** True when the ADS-B emitter category is 8, rotorcraft. */
    public boolean isRotorcraft() {
        return emitterCategory == 8;
    }

    /** An emergency squawk outranks the category inferred from the aircraft type. */
    public AircraftCategory getCategory() {
        return isSquawkingEmergency() ? AircraftCategory.EMERGENCY : type.getCategory();
    }

    public String getFlightPhase() {
        return flightPhase(altitude, speed);
    }

    public static String flightPhase(double altitude, double speed) {
        if (altitude < 1000 && speed < 150) return "TAKEOFF_LANDING";
        if (altitude < 3000 && speed < 250) return "FINAL_APPROACH";
        if (altitude < 10000) return "CLIMB_DESCENT";
        return "CRUISE";
    }
}
