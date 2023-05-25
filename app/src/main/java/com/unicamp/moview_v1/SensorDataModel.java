package com.unicamp.moview_v1;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class SensorDataModel {
    private float[] accelerometerValues;
    private float[] gyroscopeValues;
    private float[] magnetometerValues;

    public SensorDataModel() {
        this.accelerometerValues = new float[3];
        this.gyroscopeValues = new float[3];
        this.magnetometerValues = new float[3];
    }

    public float[] getAccelerometerValues() {
        return accelerometerValues;
    }

    public void setAccelerometerValues(float[] accelerometerValues) {
        this.accelerometerValues = accelerometerValues;
    }

    public float[] getGyroscopeValues() {
        return gyroscopeValues;
    }

    public void setGyroscopeValues(float[] gyroscopeValues) {
        this.gyroscopeValues = gyroscopeValues;
    }

    public float[] getMagnetometerValues() {
        return magnetometerValues;
    }

    public void setMagnetometerValues(float[] magnetometerValues) {
        this.magnetometerValues = magnetometerValues;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "inertial");
            json.put("accX", accelerometerValues[0]);
            json.put("accY", accelerometerValues[1]);
            json.put("accZ", accelerometerValues[2]);
            json.put("gyrX", gyroscopeValues[0]);
            json.put("gyrY", gyroscopeValues[1]);
            json.put("gyrZ", gyroscopeValues[2]);
            json.put("magX", magnetometerValues[0]);
            json.put("magY", magnetometerValues[1]);
            json.put("magZ", magnetometerValues[2]);
            //json.put("accelerometer", new JSONArray(accelerometerValues));
            //json.put("gyroscope", new JSONArray(gyroscopeValues));
            //json.put("magnetometer", new JSONArray(magnetometerValues));
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
    }
}
