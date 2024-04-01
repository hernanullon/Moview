package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class LocationModel {
    private double latitude;
    private double longitude;
    private double speed;
    private double altitude;
    private double accuracy;
    private long timestamp;


    public LocationModel() {
        this.latitude = 0.0;
        this.longitude = 0.0;
        this.speed = 0.0;
        this.altitude = 0.0;
        this.accuracy = 0.0;
        this.timestamp = 0;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("timestamp_sys", System.currentTimeMillis());
            json.put("device_id", MainActivity.DEVICE_ID);
            json.put("type", "location");
            json.put("timestamp_gps", timestamp);
            json.put("lat", latitude);
            json.put("lon", longitude);
            json.put("accuracy", accuracy);
            json.put("speed", speed);
            json.put("alt", altitude);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String toString() {
        return  "latitude=" + latitude +
                ", longitude=" + longitude +
                ", accuracy=" + accuracy +
                ", speed=" + speed +
                ", altitude=" + altitude +
                ", timestamp=" + timestamp;
    }

    public double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(double accuracy) {
        this.accuracy = accuracy;
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
