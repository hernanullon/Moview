package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class LocationView {
    private TextView gpsTextView;

    public LocationView(Activity activity) {
        gpsTextView = activity.findViewById(R.id.gps_text_view);
    }

    public void update(String msg) {
        gpsTextView.setText(msg);
    }
}
