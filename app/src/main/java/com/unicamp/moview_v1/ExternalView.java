package com.unicamp.moview_v1;

import android.app.Activity;
import android.text.method.ScrollingMovementMethod;
import android.widget.TextView;

public class ExternalView {
    private TextView externalTextView;

    public ExternalView(Activity activity) {
        externalTextView = activity.findViewById(R.id.external_text_view);
        externalTextView.setMovementMethod(new ScrollingMovementMethod());
    }

    public void update(String msg) {
        externalTextView.setText(msg);
    }
}
