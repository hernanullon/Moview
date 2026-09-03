package com.unicamp.moview_v1;

import org.json.JSONException;
import org.json.JSONObject;

public class DeviceModel {
    private int battery;
    private int temperature;
    private int wifi_status;
    private int rssi_device;
    private String network_type;
    private boolean state;

    public DeviceModel() {
        this.battery = -999;
        this.temperature = -999;
        this.wifi_status = 0;
        this.rssi_device = -999;
        this.network_type = "None";
        this.state = false;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("timestamp_sys", System.currentTimeMillis());
            json.put("device_id", MainActivity.DEVICE_ID);
            json.put("type", "device");
            json.put("soc", battery);
            json.put("temperature", temperature);
            json.put("wifistatus", wifi_status);
            json.put("txpower", rssi_device);
            json.put("networktype", network_type);
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
                ", wifistatus=" + wifi_status +
                ", txpower=" + rssi_device +
                ", networktype=" + network_type;
    }

    public boolean isState() {
        return state;
    }

    public void setState(boolean state) {
        this.state = state;
    }

    public int getRssi_device() {
        return rssi_device;
    }

    public void setRssi_device(int rssi_device) {
        this.rssi_device = rssi_device;
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
