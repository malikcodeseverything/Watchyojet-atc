package com.watchyojet.model;

public class Aircraft {

    private final String id;
    private AircraftType type;
    private String callsign;
    private double lat;
    private double lon;
    private double altitude;
    private double speed;
    private double heading;
    private double verticalRateFpm;

    public Aircraft(String callsign, double lat, double lon,
    double altitude, double speed, double heading,
    AircraftType type) 
    {
    this(callsign, callsign, lat, lon, altitude, speed, heading, type);
    }

    public Aircraft(String id, String callsign, double lat, double lon,
    double altitude, double speed, double heading,
    AircraftType type)
    {
    this.id = id;
    this.callsign = callsign;
    this.lat = lat;
    this.lon = lon;
    this.altitude = altitude;
    this.speed = speed;//nau mi/hr
    this.heading = heading;
    this.type = type;
    this.verticalRateFpm = 0;
    }

    public double getLat()
    {
        return lat; 
    }
    public double getLon()
    { 
        return lon; 
    }
    public double getHeading() 
    {
         return heading;
    }
    public void setHeading(double heading)
    {
        this.heading = heading;
    }
    public double getSpeed() 
    { 
        return speed;
    }

    public void setLat(double lat) 
    {
        this.lat = lat; 
    }
    public void setLon(double lon)
    {
         this.lon = lon; 
    }

    public String getCallsign() 
    {
        return callsign;
    }
    public void setCallsign(String callsign)
    {
        this.callsign = callsign;
    }
    public String getId()
    {
        return id;
    }
    public double getAltitude() 
    {
        return altitude;
    }
    public void setAltitude(double altitude) 
    {
    this.altitude = altitude;
    }
    public AircraftType getType() 
    {
    return type;
    }
    public void setType(AircraftType type)
    {
        this.type = type;
    }

    public AircraftCategory getCategory()
    {
    return type.getCategory();
    }

   public String getFlightPhase()
    {
            double alt = this.getAltitude();
            double speed = this.getSpeed();

      if (alt < 1000 && speed < 150) return "TAKEOFF_LANDING";
     if (alt < 3000 && speed < 250) return "FINAL_APPROACH";
     if (alt < 10000) return "CLIMB_DESCENT";
     return "CRUISE";
}

public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getVerticalRateFpm() {
        return verticalRateFpm;
    }

    public void setVerticalRateFpm(double verticalRateFpm) {
        this.verticalRateFpm = verticalRateFpm;
    }

}
