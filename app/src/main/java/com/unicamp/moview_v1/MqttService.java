package com.unicamp.moview_v1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONObject;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MqttService extends Service {
    private static MqttAsyncClient client;
    private ScheduledExecutorService scheduler;
    private ServiceCallbacks serviceCallbacks;

    private static final String SERVER_URI = "tcp://143.106.11.105:1883";
    //private static final String SERVER_URI = "tcp://broker.hivemq.com:1883";
    private static final String CLIENT_ID = "SamsungGalaxyA5";
    private static final String TOPIC = "unicamp/electric/onibus";
    private static final String PASSWORD_SERVER = "ql!FIzt2W0B08";

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

    public void connectServer() {
        try {
            client = new MqttAsyncClient(SERVER_URI, CLIENT_ID, null);
            Executors.newSingleThreadExecutor().execute(() -> {
                Timer timer = new Timer();
                timer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        Log.d("TAG", "-- Ciclo MQTT --");
                        try {
                            if (!client.isConnected()) {
                                IMqttToken token = client.connect();
                                token.waitForCompletion();
                            } else {
                                Log.d("TAG", "-- Conectado --");
                                startSendingJson();
                                timer.cancel(); // Detener el temporizador una vez que se haya establecido la conexión
                            }
                        } catch (MqttException e) {
                            Log.d("TAG", "No conectado 1... Intentando reconectar en 2 segundos");
                        }
                    }
                }, 0, 2000); // Intentar reconexión cada 2 segundos (ajusta el valor según tus necesidades)
            });
        } catch (MqttException e) {
            e.printStackTrace();
            Log.d("TAG", "No conectado 2...");
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mqtt Service")
                .setContentText("El servicio de Mqtt está activo.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(4, notification);
        //client = new MqttAsyncClient(SERVER_URI, CLIENT_ID, null);
        connectServer();
        Log.d("TAG", "Start send data");

        return START_NOT_STICKY;
    }

    public void startSendingJson() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                //Log.d("TAG", "Scheduler MQTT 1s");
                if (serviceCallbacks != null && MainActivity.REAL_TIME_OPERATION) {
                    JSONObject msg = serviceCallbacks.getCurrentDataMqtt();
                    sendMessageMQTT(msg);
                }
            }
        }, 0, 1, TimeUnit.SECONDS);
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

    public static void sendMessageMQTT(JSONObject msg) {
        try {
            int qos = 2;
            MqttMessage message = new MqttMessage();
            message.setPayload(msg.toString().getBytes());
            message.setQos(qos);
            client.publish(TOPIC, message);
            Log.d("TAG", "Public: " + msg);
        } catch (MqttException e) {
            Log.d("TAG", "Reconectando...");
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Thread.sleep(2000);
                    IMqttToken token = client.connect();
                    token.waitForCompletion();
                    Log.d("TAG", "Reconectado...");
                } catch (MqttException | InterruptedException ex) {
                    Log.d("TAG", "Reconexión fallida...");
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        Log.d("TAG", "Scheduler destroy ====");
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        if (client != null) {
            try {
                client.disconnect();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
        super.onDestroy();
    }

}
