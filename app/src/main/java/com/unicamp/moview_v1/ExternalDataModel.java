package com.unicamp.moview_v1;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

public class ExternalDataModel {
    private String message;

    public ExternalDataModel(String message) {
        this.message = message;
    }

    public JSONObject toJSON() {
        try {
            JSONObject json = new JSONObject(getMessage());
            json.put("timestamp_sys", System.currentTimeMillis());
            json.put("device_id", MainActivity.DEVICE_ID);
            return json;
        } catch (JSONException e){
            e.printStackTrace();
            Log.d("TAG", "Falla al convertir" + getMessage());
            return null;
        }
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
