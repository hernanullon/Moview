package com.unicamp.moview_v1;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.net.Socket;
import java.util.Arrays;

public class NetworkThread extends Thread {
    private final Socket socket;
    private final Context context;
    private final int port;
    private static final String EXTERNAL_SEND_MESSAGE = "com.unicamp.moview_v1.SEND_EXTERNAL_SENSORS";
    private static final String EXTERNAL_KEY_VALUES = "update_external_sensors";

    public NetworkThread(Socket socket,int port, Context context) {
        this.socket = socket;
        this.context = context.getApplicationContext();
        this.port = port;
    }

    @Override
    public void run() {
        try {
            InputStream inputStream = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String line;
            while ((line = reader.readLine()) != null) {
                Intent externalIntent = new Intent(EXTERNAL_SEND_MESSAGE);
                externalIntent.putExtra(EXTERNAL_KEY_VALUES, line);
                if(!line.isEmpty()) {
                    context.sendBroadcast(externalIntent);
                }
            }
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}