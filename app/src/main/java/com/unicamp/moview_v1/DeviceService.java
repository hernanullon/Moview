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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.Manifest;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.util.Pair;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

public class DeviceService extends Service {
    public static final String CHANNEL_ID = "CellphoneServiceChannel";
    private static final String CELLPHONE_SEND_MESSAGE = "com.unicamp.mms.SEND_CELLPHONE_SENSORS";
    private static final String CELLPHONE_KEY_BATTERY = "battery_cellphone", CELLPHONE_KEY_TEMPERATURE = "temperature_cellphone";
    private static final String CELLPHONE_KEY_SIGNAL = "signal_cellphone", CELLPHONE_KEY_NETWORK = "network_cellphone", CELLPHONE_KEY_HOTSPOT = "hotspot_cellphone";
    private BroadcastReceiver CellphoneReceiver;
    private TelephonyManager telephonyManager;
    private boolean receiverRegistered = false;

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
                Pair<String, Integer> signalInfo = checkSignalStrength();

                // Obtener el estado del WiFi
                WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                // Determinar si el hotspot está activo; este es un método no oficial y puede no ser confiable en todos los dispositivos
                boolean isHotspotEnabled = false;
                try {
                    Method method = wifiManager.getClass().getDeclaredMethod("getWifiApState");
                    method.setAccessible(true);
                    int hotspotState = (Integer) method.invoke(wifiManager);
                    isHotspotEnabled = hotspotState == 13;
                } catch (Exception e) {
                    e.printStackTrace();
                }

                Intent cellphoneIntent = new Intent(CELLPHONE_SEND_MESSAGE);
                cellphoneIntent.putExtra(CELLPHONE_KEY_BATTERY, level);
                cellphoneIntent.putExtra(CELLPHONE_KEY_TEMPERATURE, temperature);
                cellphoneIntent.putExtra(CELLPHONE_KEY_SIGNAL, signalInfo.second);
                cellphoneIntent.putExtra(CELLPHONE_KEY_NETWORK, signalInfo.first);
                cellphoneIntent.putExtra(CELLPHONE_KEY_HOTSPOT, isHotspotEnabled ? 1 : 0);
                sendBroadcast(cellphoneIntent);
            }
        };
    }

    public Pair<String, Integer> checkSignalStrength() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Handle permission request here
            return new Pair<>("None", 0);
        }
        List<CellInfo> cellInfos = telephonyManager.getAllCellInfo();
        if (cellInfos != null) {
            for (CellInfo cellInfo : cellInfos) {
                int signalStrengthValue;
                String networkTypeValue;
                if (cellInfo instanceof CellInfoGsm) {
                    CellSignalStrengthGsm cellSignalStrengthGsm = ((CellInfoGsm) cellInfo).getCellSignalStrength();
                    signalStrengthValue = cellSignalStrengthGsm.getDbm();
                    networkTypeValue = "GSM";
                    // Handle GSM signal strength
                } else if (cellInfo instanceof CellInfoCdma) {
                    CellSignalStrengthCdma cellSignalStrengthCdma = ((CellInfoCdma) cellInfo).getCellSignalStrength();
                    signalStrengthValue = cellSignalStrengthCdma.getDbm();
                    networkTypeValue = "CDMA";
                    // Handle CDMA signal strength
                } else if (cellInfo instanceof CellInfoLte) {
                    CellSignalStrengthLte cellSignalStrengthLte = ((CellInfoLte) cellInfo).getCellSignalStrength();
                    signalStrengthValue = cellSignalStrengthLte.getDbm();
                    networkTypeValue = "LTE";
                    // Handle LTE signal strength
                } else if (cellInfo instanceof CellInfoWcdma) {
                    CellSignalStrengthWcdma cellSignalStrengthWcdma = ((CellInfoWcdma) cellInfo).getCellSignalStrength();
                    signalStrengthValue = cellSignalStrengthWcdma.getDbm();
                    networkTypeValue = "WCDMA";
                    // Handle WCDMA signal strength
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo instanceof CellInfoNr) {
                    CellSignalStrengthNr cellSignalStrengthNr = (CellSignalStrengthNr) ((CellInfoNr) cellInfo).getCellSignalStrength();
                    signalStrengthValue = cellSignalStrengthNr.getSsRsrp();
                    networkTypeValue = "NR5G";
                    // Handle NR (5G) signal strength
                }else {
                    continue;
                }
                return new Pair<>(networkTypeValue, signalStrengthValue);
            }
        }
        return new Pair<>("None", 0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (receiverRegistered) {
            try {
                unregisterReceiver(CellphoneReceiver);
                receiverRegistered = false;
            } catch (Exception ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Cellphone Service")
                .setContentText("Active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(3, notification);
        if (!receiverRegistered) {
            registerReceiver(CellphoneReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            receiverRegistered = true;
        }
        return START_STICKY;
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
