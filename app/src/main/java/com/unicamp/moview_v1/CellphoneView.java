package com.unicamp.moview_v1;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

public class CellphoneView {
    private TextView cellphoneTextView;

    public CellphoneView(Activity activity) {
        cellphoneTextView = activity.findViewById(R.id.cellphone_text_view);
    }

    public void update(String msg) {
        cellphoneTextView.setText(msg);
    }

}
