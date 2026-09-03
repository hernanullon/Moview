package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class LocationModel {
    private float latitude;
    private float longitude;
    private float speed;
    private float altitude;
    private float bearing;
    private int accuracy_hor;  // Estimated horizontal accuracy radius in meters of this location at the 68th percentile confidence level. ( float getAccuracy () )
    private int accuracy_ver; // Returns the estimated altitude accuracy in meters of this location at the 68th percentile confidence level.(  float getVerticalAccuracyMeters () )
    private int accuracy_bea; // Returns the estimated bearing accuracy in degrees of this location at the 68th percentile confidence level ( float getBearingAccuracyDegrees () )
    private int accuracy_speed; // Returns the estimated speed accuracy in meters per second of this location at the 68th percentile confidence level. ( float getSpeedAccuracyMetersPerSecond () )
    private long timestamp;
    private boolean state;

    public LocationModel() {
        this.latitude = 0.0F;
        this.longitude = 0.0F;
        this.speed = 0.0F;
        this.altitude = 0.0F;
        this.bearing = 0.0F;
        this.accuracy_hor = 0;
        this.accuracy_ver = 0;
        this.accuracy_bea = 0;
        this.accuracy_speed = 0;
        this.timestamp = 0;
        this.state = false;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("timestamp_sys", System.currentTimeMillis());
            json.put("device_id", MainActivity.DEVICE_ID);
            json.put("type", "location");
            json.put("timestampgnss", timestamp);
            json.put("latitude", (float) latitude);
            json.put("longitude", (float) longitude);
            json.put("bearing", (float) bearing);
            json.put("speed", (float) speed);
            json.put("altitude", (float) altitude);
            json.put("accuracyhor", (int) accuracy_hor);
            json.put("accuracyver", (int) accuracy_ver);
            json.put("accuracybea", (int) accuracy_bea);
            json.put("accuracyspeed", (int) accuracy_speed);
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
                ", bearing=" + bearing +
                ", speed=" + speed +
                ", altitude=" + altitude +
                ", accuracyhor=" + accuracy_hor +
                ", accuracyver=" + accuracy_ver +
                ", accuracybea=" + accuracy_bea +
                ", accuracyspeed=" + accuracy_speed +
                ", timestamp=" + timestamp;
    }

    public boolean isState() {
        return state;
    }

    public void setState(boolean state) {
        this.state = state;
    }

    public int getAccuracy_hor() {
        return accuracy_hor;
    }

    public void setAccuracy_hor(int accuracy_hor) {
        this.accuracy_hor = accuracy_hor;
    }

    public int getAccuracy_ver() {
        return accuracy_ver;
    }

    public void setAccuracy_ver(int accuracy_ver) {
        this.accuracy_ver = accuracy_ver;
    }

    public int getAccuracy_bea() {
        return accuracy_bea;
    }

    public void setAccuracy_bea(int accuracy_bea) {
        this.accuracy_bea = accuracy_bea;
    }

    public int getAccuracy_speed() {
        return accuracy_speed;
    }

    public void setAccuracy_speed(int accuracy_speed) {
        this.accuracy_speed = accuracy_speed;
    }

    public float getBearing() { return bearing; }

    public void setBearing(float bearing) { this.bearing = bearing;}

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public float getLatitude() {
        return latitude;
    }

    public void setLatitude(float latitude) {
        this.latitude = latitude;
    }

    public float getLongitude() {
        return longitude;
    }

    public void setLongitude(float longitude) {
        this.longitude = longitude;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public float getAltitude() {
        return altitude;
    }

    public void setAltitude(float altitude) {
        this.altitude = altitude;
    }


}
