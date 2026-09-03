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
import android.util.Log;
import android.widget.Spinner;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class InertialService extends Service implements SensorEventListener {
    public static final String CHANNEL_ID = "SensorsServiceChannel";
    private static final String INERTIAL_SEND_MESSAGE = "com.unicamp.mms.SEND_INERTIAL_SENSORS";
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
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Inertial Service")
                .setContentText("Active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(2, notification);
        start();
        return START_STICKY;
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
        Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyr = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        logSensorCapabilities(acc, gyr, mag);

        // maxReportLatencyUs = 0 → sin batching, entrega inmediata
        sensorManager.registerListener(this, acc, 200_000);
        sensorManager.registerListener(this, gyr, 200_000);
        sensorManager.registerListener(this, mag, 200_000);

    }

    private void logSensorCapabilities(Sensor acc, Sensor gyr, Sensor mag) {
        if (acc != null) Log.d("SENSOR", "ACC minDelay=" + acc.getMinDelay()
                + "µs maxDelay=" + acc.getMaxDelay() + "µs");
        if (gyr != null) Log.d("SENSOR", "GYR minDelay=" + gyr.getMinDelay()
                + "µs maxDelay=" + gyr.getMaxDelay() + "µs");
        if (mag != null) Log.d("SENSOR", "MAG minDelay=" + mag.getMinDelay()
                + "µs maxDelay=" + mag.getMaxDelay() + "µs");
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
        stop();
    }
}
