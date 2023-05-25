package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class SensorDataView {
    private TextView inertialTextView;

    public SensorDataView(Activity activity) {
        inertialTextView = activity.findViewById(R.id.inertial_text_view);
    }

    public void update(JSONObject json) {
        inertialTextView.setText("MSG: " + json.toString());
    }
}
