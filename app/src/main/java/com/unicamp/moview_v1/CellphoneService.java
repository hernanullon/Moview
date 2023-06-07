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
import android.content.pm.PackageManager;
import android.hardware.SensorManager;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

public class CellphoneService extends Service {
    public static final String CHANNEL_ID = "CellphoneServiceChannel";
    private static final String CELLPHONE_SEND_MESSAGE = "com.unicamp.moview_v1.SEND_CELLPHONE_SENSORS";
    private static final String CELLPHONE_KEY_BATTERY = "battery_cellphone", CELLPHONE_KEY_TEMPERATURE = "temperature_cellphone";
    private static final String CELLPHONE_KEY_SIGNAL = "signal_cellphone",CELLPHONE_KEY_NETWORK = "network_cellphone";
    private BroadcastReceiver CellphoneReceiver;
    private TelephonyManager telephonyManager;

    @Override
    public void onCreate() {
        super.onCreate();
        CellphoneReceiver = new BroadcastReceiver() {
            @RequiresApi(api = Build.VERSION_CODES.P)
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                temperature = (int) (temperature / 10.0f);
                int signalLevel = -1;
                String networkType = "None";
                if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    android.telephony.SignalStrength signalStrength = telephonyManager.getSignalStrength();
                    networkType = getNetworkTypeString(telephonyManager.getNetworkType());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Versión de Android 10 o superior
                        signalLevel = signalStrength.getLevel();
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Versión de Android 9 (Pie)
                        signalLevel = signalStrength.getLevel();
                    } else {
                        // Versiones anteriores a Android 9
                        signalLevel = signalStrength.getGsmSignalStrength();
                    }
                }
                Intent cellphoneIntent = new Intent(CELLPHONE_SEND_MESSAGE);
                cellphoneIntent.putExtra(CELLPHONE_KEY_BATTERY, level);
                cellphoneIntent.putExtra(CELLPHONE_KEY_TEMPERATURE, temperature);
                cellphoneIntent.putExtra(CELLPHONE_KEY_SIGNAL, signalLevel);
                cellphoneIntent.putExtra(CELLPHONE_KEY_NETWORK, networkType);
                sendBroadcast(cellphoneIntent);
            }
        };
    }

    public String getNetworkTypeString(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            default:
                return "Desconocido";
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(CellphoneReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);


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
