package com.watchyojet.model;

public class Conflict {

    private static final double CRITICAL_TIME_SECONDS = 60.0;
    private static final double CRITICAL_LATERAL_NM = 1.0;
    private static final double CRITICAL_VERTICAL_FEET = 500.0;

    public enum Severity { CRITICAL, HIGH, MEDIUM }

    private final Aircraft a1;
    private final Aircraft a2;
    private final Severity severity;
    private final double   tCPA;   // seconds to closest point of approach
    private final double   dCPA;   // distance at CPA in NM
    private final String   zone;
    private final double   lateralMinimum;
    private final double   verticalMinimum;
    private final double   projectedVerticalSeparation;

    public Conflict(Aircraft a1, Aircraft a2, Severity severity, double tCPA, double dCPA) {
        this(a1, a2, severity, tCPA, dCPA, "En route", 5.0, 1_000.0,
                Math.abs(a1.getAltitude() - a2.getAltitude()));
    }

    public Conflict(Aircraft a1, Aircraft a2, Severity severity, double tCPA, double dCPA,
                    String zone, double lateralMinimum, double verticalMinimum) {
        this(a1, a2, severity, tCPA, dCPA, zone, lateralMinimum, verticalMinimum,
                Math.abs(a1.getAltitude() - a2.getAltitude()));
    }

    public Conflict(Aircraft a1, Aircraft a2, Severity severity, double tCPA, double dCPA,
                    String zone, double lateralMinimum, double verticalMinimum,
                    double projectedVerticalSeparation) {
        this.a1       = a1;
        this.a2       = a2;
        this.severity = severity;
        this.tCPA     = tCPA;
        this.dCPA     = dCPA;
        this.zone = zone;
        this.lateralMinimum = lateralMinimum;
        this.verticalMinimum = verticalMinimum;
        this.projectedVerticalSeparation = projectedVerticalSeparation;
    }

    public Aircraft  getA1()       { return a1; }
    public Aircraft  getA2()       { return a2; }
    public Severity  getSeverity() { return severity; }
    public double    getTCPA()     { return tCPA; }
    public double    getDCPA()     { return dCPA; }
    public String    getZone()     { return zone; }
    public double    getLateralMinimum() { return lateralMinimum; }
    public double    getVerticalMinimum() { return verticalMinimum; }
    public double    getProjectedVerticalSeparation() { return projectedVerticalSeparation; }

    /**
     * A deliberately narrow collision-risk signal for incomplete public data.
     * Wider separation-standard predictions remain proximity advisories because
     * OpenSky does not include clearances, flight rules, or controller intent.
     */
    public boolean isCriticalRisk() {
        return tCPA <= CRITICAL_TIME_SECONDS
                && dCPA < CRITICAL_LATERAL_NM
                && projectedVerticalSeparation < CRITICAL_VERTICAL_FEET;
    }
}
