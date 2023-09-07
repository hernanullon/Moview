package com.unicamp.moview_v1;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class SensorsService extends Service implements SensorEventListener {
    public static final String CHANNEL_ID = "SensorsServiceChannel";
    private static final String INERTIAL_SEND_MESSAGE = "com.unicamp.moview_v1.SEND_INERTIAL_SENSORS";
    private static final String INERTIAL_KEY_VALUES = "update_inertial_sensors", INERTIAL_KEY_TYPE = "type_inertial_sensors";
    private SensorManager sensorManager;
    private SharedPreferences sharedPreferences;
    private static int INERTIAL_RATE_UPDATE;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        createNotificationChannel();

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        INERTIAL_RATE_UPDATE = inertial_rate_select(sharedPreferences.getInt("InertialRate",2));

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Inertial Sensors Service")
                .setContentText("El servicio de Inertial Sensors está activo.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(2, notification);
        start();
        return START_NOT_STICKY;
    }

    public int inertial_rate_select(int pos){
        switch (pos){
            case 0:
                return SensorManager.SENSOR_DELAY_GAME;
            case 1:
                return SensorManager.SENSOR_DELAY_UI;
            case 2:
                return SensorManager.SENSOR_DELAY_NORMAL;
        }
        return SensorManager.SENSOR_DELAY_NORMAL;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Inertial Sensors Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    public void start() {
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),INERTIAL_RATE_UPDATE);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), INERTIAL_RATE_UPDATE);
        sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), INERTIAL_RATE_UPDATE);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values;
        int type = event.sensor.getType();
        Intent inertialIntent = new Intent(INERTIAL_SEND_MESSAGE);
        inertialIntent.putExtra(INERTIAL_KEY_VALUES, values);
        inertialIntent.putExtra(INERTIAL_KEY_TYPE, type);
        sendBroadcast(inertialIntent);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
