package com.unicamp.moview_v1;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class CellphoneService extends Service {
    public static final String CHANNEL_ID = "CellphoneServiceChannel";
    private static final String CELLPHONE_SEND_MESSAGE = "com.unicamp.moview_v1.SEND_CELLPHONE_SENSORS";
    private static final String CELLPHONE_KEY_BATTERY = "battery_cellphone", CELLPHONE_KEY_TEMPERATURE = "temperature_cellphone";
    private BroadcastReceiver CellphoneReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        CellphoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ;
                temperature = (int) (temperature / 10.0f);
                Intent cellphoneIntent = new Intent(CELLPHONE_SEND_MESSAGE);
                cellphoneIntent.putExtra(CELLPHONE_KEY_BATTERY, level);
                cellphoneIntent.putExtra(CELLPHONE_KEY_TEMPERATURE, temperature);
                sendBroadcast(cellphoneIntent);
            }
        };
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(CellphoneReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Cellphone Service")
                .setContentText("El servicio de Cellphone está activo.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(3, notification);
        registerReceiver(CellphoneReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cellphone Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
