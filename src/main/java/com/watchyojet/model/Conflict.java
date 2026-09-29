package com.watchyojet.model;

public class Conflict {

    public enum Severity { CRITICAL, HIGH, MEDIUM }

    private final Aircraft a1;
    private final Aircraft a2;
    private final Severity severity;
    private final double   tCPA;   // seconds to closest point of approach
    private final double   dCPA;   // distance at CPA in NM
    private final String   zone;
    private final double   lateralMinimum;
    private final double   verticalMinimum;

    public Conflict(Aircraft a1, Aircraft a2, Severity severity, double tCPA, double dCPA) {
        this(a1, a2, severity, tCPA, dCPA, "En route", 5.0, 1_000.0);
    }

    public Conflict(Aircraft a1, Aircraft a2, Severity severity, double tCPA, double dCPA,
                    String zone, double lateralMinimum, double verticalMinimum) {
        this.a1       = a1;
        this.a2       = a2;
        this.severity = severity;
        this.tCPA     = tCPA;
        this.dCPA     = dCPA;
        this.zone = zone;
        this.lateralMinimum = lateralMinimum;
        this.verticalMinimum = verticalMinimum;
    }

    public Aircraft  getA1()       { return a1; }
    public Aircraft  getA2()       { return a2; }
    public Severity  getSeverity() { return severity; }
    public double    getTCPA()     { return tCPA; }
    public double    getDCPA()     { return dCPA; }
    public String    getZone()     { return zone; }
    public double    getLateralMinimum() { return lateralMinimum; }
    public double    getVerticalMinimum() { return verticalMinimum; }
}
