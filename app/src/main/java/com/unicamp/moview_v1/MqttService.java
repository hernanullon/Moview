package com.unicamp.moview_v1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MqttService extends Service {
    private static MqttAsyncClient client;
    private ScheduledExecutorService scheduler;
    private ServiceCallbacks serviceCallbacks;
    private Handler handler;

    private static final String SERVER_URI = "tcp://143.106.11.105:1883";
    private static final int REAL_TIME_RATE = 1;
    private static final String TOPIC = "unicamp/onibus/";
    private static final String CONFIGURATIONS_TOPIC = "unicamp/onibus/configurations/";
    private static final String USER_SERVER = "cellphone1";
    private static final String PASSWORD_SERVER = "W35UjSTV}!xeAe.";
    //cellphone1  -  W35UjSTV}!xeAe.
    //cellphone2  -  W35UjSTV}!xeAe.
    public static final String CHANNEL_ID = "MqttForegroundServiceChannel";

    @Override
    public IBinder onBind(Intent intent) {
        return new MyBinder();
    }

    public class MyBinder extends Binder {
        MqttService getService() {
            return MqttService.this;
        }
    }

    public void setCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForegroundService();
        connectServer();
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Mqtt Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mqtt Service")
                .setContentText("Active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(4, notification);
    }

    private void connectServer() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (client == null) {
                    client = new MqttAsyncClient(SERVER_URI, MainActivity.DEVICE_ID, null);
                }
                MqttConnectOptions options = new MqttConnectOptions();
                options.setAutomaticReconnect(true);
                options.setCleanSession(true);
                options.setUserName(USER_SERVER);
                options.setPassword(PASSWORD_SERVER.toCharArray());
                //options.setKeepAliveInterval(10);
                //options.setConnectionTimeout(10);
                //options.setMaxInflight(100);
                client.connect(options, null, new IMqttActionListener() {
                    @Override
                    public void onSuccess(IMqttToken asyncActionToken) {
                        subscribeToConfigurations();
                        renewSubscription();
                        startSendingJson();
                    }
                    @Override
                    public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                        Log.e("TAG", "Error al conectar con el broker MQTT", exception);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                connectServer();
                            }
                        }, 15000);
                    }
                });
            } catch (MqttException e) {
                Log.e("TAG", "Error al conectar con el broker MQTT", e);
            }
        });
    }

    private void subscribeToConfigurations() {
        try {
            String topicFilter = CONFIGURATIONS_TOPIC+MainActivity.DEVICE_ID;
            Log.d("TAG", "Configuration topic: " + topicFilter);
            client.subscribe(topicFilter, 2, (topic, message) -> {
                String receivedConfig = new String(message.getPayload());
                processReceivedConfigurations(receivedConfig);
            });
        } catch (MqttException e) {
            Log.e("TAG", "Error al suscribirse a configuraciones", e);
        }
    }

    private void processReceivedConfigurations(String receivedConfig) {
        Log.d("TAG", "Configuration received: " + receivedConfig);
        sendAcknowledgement(MainActivity.DEVICE_ID);
        try {
            JSONObject configJson  = new JSONObject(receivedConfig);
            SharedPreferences.Editor editor = MainActivity.sharedPreferences.edit();
            Iterator<String> keys = configJson.keys();
            Map<String, ?> allEntries = MainActivity.sharedPreferences.getAll();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allEntries.containsKey(key)) {
                    continue;
                }
                Object value = configJson.get(key);
                if (value instanceof Integer) {
                    editor.putInt(key, (Integer) value);
                } else if (value instanceof String) {
                    editor.putString(key, (String) value);
                }
            }
            editor.commit();
            restartMainActivity();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void restartMainActivity() {
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    private void renewSubscription() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            if (client != null && client.isConnected()) {
                subscribeToConfigurations();
                Log.d("TAG", "Renewed subscription to the configuration topic.");
            }
        }, 1, 15, TimeUnit.MINUTES);
    }


    private void sendAcknowledgement(String deviceId) {
        String ackTopic = "unicamp/onibus/configurations/ack/" + deviceId;
        String ackMessage = "{ \"ack\": true, \"device_id\": \"" + deviceId + "\" }";
        try {
            if (client != null && client.isConnected()) {
                MqttMessage message = new MqttMessage(ackMessage.getBytes());
                message.setQos(2);
                client.publish(ackTopic, message);
            }
        } catch (MqttException e) {
            Log.e("TAG", "Error al enviar el acuse de recibo", e);
        }
    }

    private void startSendingJson() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> {
                if (serviceCallbacks != null && MainActivity.REAL_TIME_OPERATION) {
                    JSONObject msg = serviceCallbacks.getCurrentDataMqtt();
                    sendMessageMQTT(msg);
                }
            }, 0, REAL_TIME_RATE, TimeUnit.SECONDS);
        }
    }

    public static void sendMessageMQTT(JSONObject msg) {
        try {
            if (client.isConnected()) {
                MqttMessage message = new MqttMessage(msg.toString().getBytes());
                message.setQos(0);
                client.publish(TOPIC + msg.getString("type"), message);
                Log.d("TAG", "Mensaje publicado: " + msg);
            } else {
                Log.e("TAG", "Cliente MQTT no conectado. Mensaje no enviado.");
            }
        } catch (MqttException | JSONException e) {
            Log.e("TAG", "Error al publicar mensaje MQTT", e);
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (MqttException e) {
                Log.e("TAG", "Error al desconectar el cliente MQTT", e);
            }
        }
    }

}
