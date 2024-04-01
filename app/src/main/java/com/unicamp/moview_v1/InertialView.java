package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class InertialView {
    private TextView inertialTextView;

    public InertialView(Activity activity) {
        inertialTextView = activity.findViewById(R.id.inertial_text_view);
    }

    public void update(String msg) {
        inertialTextView.setText(msg);
    }
}
