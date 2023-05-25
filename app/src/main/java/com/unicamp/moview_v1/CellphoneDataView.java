package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class CellphoneDataView {
    private TextView cellphoneTextView;

    public CellphoneDataView(Activity activity) {
        cellphoneTextView = activity.findViewById(R.id.cellphone_text_view);
    }

    public void update(JSONObject json) {
        cellphoneTextView.setText("MSG: " + json.toString());
    }
}
