package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class CellphoneModel {
    private int battery;
    private int temperature;
    private int wifi_status;
    private int signalStrength;
    private String network_type;

    public CellphoneModel() {
        this.battery = -999;
        this.temperature = -999;
        this.wifi_status = 0;
        this.signalStrength = -999;
        this.network_type = "None";
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("device_id", MainActivity.DEVICE_ID);
            json.put("type", "cellphone");
            json.put("battery", battery);
            json.put("temperature", temperature);
            json.put("wifi_status", wifi_status);
            json.put("signal", signalStrength);
            json.put("network", network_type);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public String toString() {
        return  "battery=" + battery +
                ", temperature=" + temperature +
                ", wifi_status=" + wifi_status +
                ", signalStrength=" + signalStrength +
                ", network_type='" + network_type;
    }

    public int getSignalStrength() {
        return signalStrength;
    }

    public void setSignalStrength(int signalStrength) {
        this.signalStrength = signalStrength;
    }

    public String getNetwork_type() {
        return network_type;
    }

    public void setNetwork_type(String network_type) {
        this.network_type = network_type;
    }

    public int getBattery() {
        return battery;
    }

    public void setBattery(int battery) {
        this.battery = battery;
    }

    public int getTemperature() {
        return temperature;
    }

    public void setTemperature(int temperature) {
        this.temperature = temperature;
    }

    public int getWifi_status() {
        return wifi_status;
    }

    public void setWifi_status(int wifi_status) {
        this.wifi_status = wifi_status;
    }
}
