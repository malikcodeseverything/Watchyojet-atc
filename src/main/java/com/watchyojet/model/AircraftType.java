package com.watchyojet.model;

public enum AircraftType {

    A320(39000, 2500, 140, AircraftCategory.COMMERCIAL),
    B737(41000, 2400, 140, AircraftCategory.COMMERCIAL),
    A350(43000, 2000, 145, AircraftCategory.COMMERCIAL),

    F16(50000, 5000, 180, AircraftCategory.MILITARY),

    C172(13000, 700, 55, AircraftCategory.GENERAL_AVIATION),

    AIR_AMBULANCE(35000, 2200, 100, AircraftCategory.EMERGENCY),

    GENERIC(35000, 2000, 80, AircraftCategory.GENERAL_AVIATION);

    private final double maxAltitude;
    private final double climbRate;
    private final double minimumSafeSpeed;
    private final AircraftCategory category;

    AircraftType(double maxAltitude, double climbRate, double minimumSafeSpeed,
                 AircraftCategory category) {
        this.maxAltitude = maxAltitude;
        this.climbRate = climbRate;
        this.minimumSafeSpeed = minimumSafeSpeed;
        this.category = category;
    }

    public double getMaxAltitude() {
        return maxAltitude;
    }

    public double getClimbRate() {
        return climbRate;
    }

    public double getMinimumSafeSpeed() {
        return minimumSafeSpeed;
    }

    public AircraftCategory getCategory() {
        return category;
    }
}
