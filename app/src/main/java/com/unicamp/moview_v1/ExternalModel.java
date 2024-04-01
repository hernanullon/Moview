package com.unicamp.moview_v1;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ExternalModel {
    private Map<String, String> deviceMessages = new HashMap<>();

    public void updateDeviceMessage(String type, String jsonData) {
        deviceMessages.put(type, jsonData);
    }

    public void clearAllDeviceMessages() {
        deviceMessages.clear();
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("ExternalModel{deviceMessages=[");
        boolean first = true;
        for (Map.Entry<String, String> entry : deviceMessages.entrySet()) {
            if (!first) {
                result.append(", ");
            } else {
                first = false;
            }
            String type = entry.getKey();
            String message = entry.getValue();
            result.append("{").append(type).append(": ").append(message).append("}");
        }
        result.append("]}");
        return result.toString();
    }

    public String getDeviceMessage(String type) {
        return deviceMessages.getOrDefault(type, "{}");
    }

    public Map<String, String> getAllDeviceMessages() {
        return new HashMap<>(deviceMessages);
    }
}

