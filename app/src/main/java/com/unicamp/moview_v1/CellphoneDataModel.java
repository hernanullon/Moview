package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class CellphoneDataModel {
    private int battery;
    private int temperature;
    private int wifi_status;

    public CellphoneDataModel(int battery, int temperature, int wifi_status) {
        this.battery = battery;
        this.temperature = temperature;
        this.wifi_status = wifi_status;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "cellphone");
            json.put("battery", battery);
            json.put("temperature", temperature);
            json.put("wifi_status", wifi_status);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            return null;
        }
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
