package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class LocationModel {
    private double latitude;
    private double longitude;
    private double speed;
    private double altitude;
    private long timestamp;

    public LocationModel() {
        this.latitude = 0.0;
        this.longitude = 0.0;
        this.speed = 0.0;
        this.altitude = 0.0;
        this.timestamp = 0;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "gps");
            json.put("timestamp", timestamp);
            json.put("lat", latitude);
            json.put("long", longitude);
            json.put("speed", speed);
            json.put("alt", altitude);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public double getAltitude() {
        return altitude;
    }

    public void setAltitude(double altitude) {
        this.altitude = altitude;
    }
}
