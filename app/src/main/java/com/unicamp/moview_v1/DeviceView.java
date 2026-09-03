package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class DeviceView {
    private TextView cellphoneTextView;

    public DeviceView(Activity activity) {
        cellphoneTextView = activity.findViewById(R.id.cellphone_text_view);
    }

    public void update(String msg) {
        cellphoneTextView.setText(msg);
    }

}
