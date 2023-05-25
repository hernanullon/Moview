package com.unicamp.moview_v1;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

public class MqttController {
    private MqttAsyncClient client;
    private String serverUri;
    private String clientId;
    private String password;

    public MqttController(String serverUri, String clientId, String password) {
        this.serverUri = serverUri;
        this.clientId = clientId;
        this.password = password;
        try {
            client = new MqttAsyncClient(serverUri, clientId, null);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public void connectServer(){
        try {
            IMqttToken token = client.connect();
            token.waitForCompletion();
            Log.d("TAG", "Conectando...");
        } catch (MqttException e) {
            //e.printStackTrace();
            Log.d("TAG", "No conectado...");
        }
    }

    public void sendMessageMQTT(JSONObject msg) {
        try {
            int qos = 2;
            String topic = "unicamp/electric/onibus";
            MqttMessage message = new MqttMessage();
            message.setPayload(msg.toString().getBytes());
            message.setQos(qos);
            client.publish(topic, message);
            Log.d("TAG", "Publicado...");
        } catch (MqttException e) {
            //e.printStackTrace();
            Log.d("TAG", "Reconectando...");
            connectServer();
        }
    }
}
