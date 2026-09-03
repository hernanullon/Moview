package com.unicamp.moview_v1;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class InertialModel {
    private float[] accelerometerValues;
    private float[] gyroscopeValues;
    private float[] magnetometerValues;
    private boolean state;

    public InertialModel() {
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

    @Override
    public String toString() {
        return  "accelerometerValues=" + Arrays.toString(accelerometerValues) +
                ", gyroscopeValues=" + Arrays.toString(gyroscopeValues) +
                ", magnetometerValues=" + Arrays.toString(magnetometerValues);
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("timestamp_sys", System.currentTimeMillis());
            json.put("device_id", MainActivity.DEVICE_ID);
            json.put("type", "inertial");
            json.put("accX", (float) accelerometerValues[0]);
            json.put("accY", (float) accelerometerValues[1]);
            json.put("accZ", (float) accelerometerValues[2]);
            json.put("gyrX", (float) gyroscopeValues[0]);
            json.put("gyrY", (float) gyroscopeValues[1]);
            json.put("gyrZ", (float) gyroscopeValues[2]);
            json.put("magX", (float) magnetometerValues[0]);
            json.put("magY", (float) magnetometerValues[1]);
            json.put("magZ", (float)magnetometerValues[2]);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
    }

    public boolean isState() {
        return state;
    }

    public void setState(boolean state) {
        this.state = state;
    }
}
