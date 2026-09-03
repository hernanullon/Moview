package com.unicamp.moview_v1;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.threetenabp.AndroidThreeTen;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.core.util.Pair;
import org.threeten.bp.LocalTime;
import org.threeten.bp.format.DateTimeFormatter;

public class MainActivity extends AppCompatActivity implements AmqpService.ServiceCallbacks {

    private InertialModel inertialModel;
    private InertialView inertialView;

    private LocationModel locationModel;
    private LocationView locationView;

    private DeviceModel deviceModel;
    private DeviceView deviceView;

    private ExternalModel externalModel;
    private ExternalView externalView;

    private JSONDatabaseHelper buffer;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private boolean updAcc = false, updGyr = false, updMag = false;
    private Button btnStart;

    private AmqpService amqpService;
    private boolean bound = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedulerOffline;

    public static boolean REAL_TIME_OPERATION = true, START_MONITORING = false;

    public static LocalTime FINAL_TIME_OFFLINE, INITIAL_TIME_OFFLINE;

    public static int BATTERY_MAX, BATTERY_MIN, TEMP_MAX, TEMP_MIN;
    public static int LEVEL_BATTERY = -1, TEMPERATURE_BATTERY = -1;
    public static String DEVICE_ID, IP_DRIVER, IP_SERVER, PORT_SERVER, USER_SERVER, PASSWORD_SERVER;

    public static SharedPreferences sharedPreferences;

    // Control del ciclo realtime/offline
    private volatile boolean realtimeStarted = false;       // evita arrancar realtime dos veces
    private volatile boolean offlineInProgress = false;     // evita lanzar 2 flush offline a la vez
    private volatile Future<AmqpService.UploadStats> offlineFuture; // para cancelar si cambia el horario
    private long lastEmptyOfflineLogAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidThreeTen.init(this);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        resetSharedPreferences();
        loadSharedPreferences();
        saveSharedPreferences();
        Log.d("INFO", "TempMN: " + TEMP_MIN +" TempMM: " + TEMP_MAX);

        TextView txt_view = this.findViewById(R.id.textview_device_id);
        txt_view.setText(DEVICE_ID + " - " + IP_DRIVER + " - " + IP_SERVER + ":" + PORT_SERVER);

        btnStart = this.findViewById(R.id.btn_start);
        btnStart.setEnabled(false);
        btnStart.setBackgroundColor(Color.GRAY);

        inertialModel = new InertialModel();
        inertialView = new InertialView(this);

        locationModel = new LocationModel();
        locationView = new LocationView(this);

        deviceModel = new DeviceModel();
        deviceView = new DeviceView(this);

        externalModel = new ExternalModel();
        externalView = new ExternalView(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.mms.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_EXTERNAL_SENSORS");
        registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper(this);

        // Iniciamos y hacemos bind al AmqpService
        bindService(new Intent(this, AmqpService.class), serviceConnection, Context.BIND_AUTO_CREATE);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean whitelisted = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            if (!whitelisted) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    // Fallback: abre la lista general por si el intent directo no existe
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            } else {
                Log.d("INFO", "Battery optimization permission is already granted");
            }
        }
//
//        Intent intent = new Intent();
//        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
//        intent.setData(Uri.parse("package:" + getPackageName()));
//        startActivity(intent);

        PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
        if(powerManager.isIgnoringBatteryOptimizations(this.getPackageName()))
            Log.d("INFO", "Battery optimization permission is granted");
        else
            Log.e("ERROR", "Battery optimization permission not granted");

        enableHotspot(true);
        START_MONITORING = true;
        startMonitoring();
        startServer();

        // Programamos el ciclo que decide realtime/offline y orquesta el envío por lotes
        SchedulerOfflineDetect();

        btnStart.setEnabled(true);
        btnStart.setBackgroundColor(Color.BLUE);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateViews();
            }
        });
    }

    private void resetSharedPreferences(){
        sharedPreferences.edit().clear().apply();
        Log.d("INFO", "SharedPreferences reseteado a valores por defecto");
    }

    private void loadSharedPreferences(){
        Log.d("INFO", "Update configurations");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        INITIAL_TIME_OFFLINE = LocalTime.parse(sharedPreferences.getString("INITIAL_TIME_OFFLINE", "04:30"), formatter);
        FINAL_TIME_OFFLINE = LocalTime.parse(sharedPreferences.getString("FINAL_TIME_OFFLINE", "06:45"), formatter);

        //JCL9G80 - 192.168.43.235 - producer2 - sVKV76zfoEvnAS8KCG9N
        //GIO5B97 - 192.168.43.121 - producer1 - 3e3z2IQiEDWBxNPgS09G

        DEVICE_ID = sharedPreferences.getString("DEVICE_ID", "P0RTUG4L");
        IP_DRIVER = sharedPreferences.getString("IP_DRIVER", "192.168.43.41");
        IP_SERVER = sharedPreferences.getString("IP_SERVER", "143.106.8.17");
        PORT_SERVER = sharedPreferences.getString("PORT_SERVER", "5672");
        USER_SERVER = sharedPreferences.getString("USER_SERVER", "producer2");
        PASSWORD_SERVER = sharedPreferences.getString("PASSWORD_SERVER", "sVKV76zfoEvnAS8KCG9N");
        TEMP_MIN = sharedPreferences.getInt("TEMP_MIN",30);
        TEMP_MAX = sharedPreferences.getInt("TEMP_MAX",35);
        BATTERY_MIN = sharedPreferences.getInt("BATTERY_MIN",40);
        BATTERY_MAX = sharedPreferences.getInt("BATTERY_MAX",90);
    }

    private void saveSharedPreferences(){
        SharedPreferences.Editor editor = sharedPreferences.edit();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        String initialTimeFormatted = INITIAL_TIME_OFFLINE.format(formatter);
        String finalTimeFormatted = FINAL_TIME_OFFLINE.format(formatter);

        editor.putString("INITIAL_TIME_OFFLINE", initialTimeFormatted);
        editor.putString("FINAL_TIME_OFFLINE", finalTimeFormatted);
        editor.putString("DEVICE_ID", DEVICE_ID);
        editor.putString("IP_DRIVER", IP_DRIVER);
        editor.putString("IP_SERVER", IP_SERVER);
        editor.putString("PORT_SERVER", PORT_SERVER);
        editor.putString("USER_SERVER", USER_SERVER);
        editor.putString("PASSWORD_SERVER", PASSWORD_SERVER);
        editor.putInt("TEMP_MIN", TEMP_MIN);
        editor.putInt("TEMP_MAX", TEMP_MAX);
        editor.putInt("BATTERY_MIN", BATTERY_MIN);
        editor.putInt("BATTERY_MAX", BATTERY_MAX);
        editor.commit();
    }

    private void updateViews(){
        locationView.update(locationModel.toString());
        deviceView.update(deviceModel.toString());
        externalView.update(externalModel.toString());
        inertialView.update(inertialModel.toString());
    }

    // Conexión al AmqpService -> arrancamos modo según ventana actual
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AmqpService.MyBinder binder = (AmqpService.MyBinder) service;
            amqpService = binder.getService();
            amqpService.setCallbacks(MainActivity.this);
            bound = true;
            Log.d("INFO", "AMQP Service connected");

            boolean offlineNow = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
            if (offlineNow) {
                enterOffline();  // no duplica si ya está en progreso
            } else {
                enterRealtime();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (amqpService != null) amqpService.setCallbacks(null);
            bound = false;
            realtimeStarted = false;
            Log.d("INFO", "AMQP Service disconnected");
        }
    };

    private void startServer() {
        ServerThread serverThread = new ServerThread(8888,this);
        serverThread.start();
    }

    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            }
        }
    }

    @SuppressLint("MissingPermission")
    public boolean enableHotspot(boolean enable) {
        WifiManager wifiManager =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            Log.e("ERROR", "WifiManager es null");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                Log.d("INFO", "Modo pre-Oreo (setWifiApEnabled por reflexión)");
                // Pre-O: activa/desactiva AP y alterna Wi-Fi con espera de estado

                Method setWifiApEnabled =
                        wifiManager.getClass().getMethod("setWifiApEnabled",
                                WifiConfiguration.class, boolean.class);

                if (enable) {
                    // Hotspot ON → apaga Wi-Fi primero y espera a que quede DISABLED
                    if (wifiManager.isWifiEnabled()) {
                        wifiManager.setWifiEnabled(false);
                        waitForWifiState(wifiManager, WifiManager.WIFI_STATE_DISABLED, 10_000);
                    }
                    setWifiApEnabled.invoke(wifiManager, null, true);
                    Log.d("INFO", "Hotspot ON (pre-O)");
                    return true;
                } else {
                    // Hotspot OFF → apaga AP, enciende Wi-Fi y espera ENABLED
                    setWifiApEnabled.invoke(wifiManager, null, false);
                    Log.d("INFO", "Hotspot OFF (pre-O) → encendiendo Wi-Fi…");

                    wifiManager.setWifiEnabled(true);
                    boolean ok = waitForWifiState(wifiManager, WifiManager.WIFI_STATE_ENABLED, 20_000);
                    if (!ok) Log.w("INFO", "Wi-Fi no llegó a ENABLED en el timeout (pre-O).");
                    return ok;
                }
            } else {
                Log.d("INFO", "Modo Oreo+ (tethering por reflexión a mService)");
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) {
                    Log.e("ERROR", "ConnectivityManager es null");
                    return false;
                }

                // Obtener mService interno
                @SuppressLint("SoonBlockedPrivateApi") Field f = cm.getClass().getDeclaredField("mService");
                f.setAccessible(true);
                Object iConn = f.get(cm);
                if (iConn == null) {
                    Log.e("ERROR", "mService es null");
                    return false;
                }

                // Usar el tipo correcto: TETHERING_WIFI
                int TETHERING_WIFI;
                try {
                    Field fTw = ConnectivityManager.class.getField("TETHERING_WIFI");
                    TETHERING_WIFI = fTw.getInt(null);
                } catch (Exception ignore) {
                    // Fallback: 0 suele coincidir con TETHERING_WIFI
                    TETHERING_WIFI = 0;
                }

                if (enable) {
                    // Hotspot ON → apaga Wi-Fi, espera DISABLED y arranca tethering Wi-Fi
                    if (wifiManager.isWifiEnabled()) {
                        wifiManager.setWifiEnabled(false);
                        waitForWifiState(wifiManager, WifiManager.WIFI_STATE_DISABLED, 10_000);
                    }
                    Method startTethering =
                            iConn.getClass().getMethod("startTethering",
                                    int.class, ResultReceiver.class, boolean.class);
                    startTethering.invoke(iConn, TETHERING_WIFI, null, true);
                    Log.d("INFO", "Hotspot ON (O+)");
                    return true;
                } else {
                    // Hotspot OFF → detiene tethering, enciende Wi-Fi y espera ENABLED
                    Method stopTethering =
                            iConn.getClass().getMethod("stopTethering", int.class);
                    stopTethering.invoke(iConn, TETHERING_WIFI);
                    Log.d("INFO", "Hotspot OFF (O+) → encendiendo Wi-Fi…");

                    wifiManager.setWifiEnabled(true);
                    boolean ok = waitForWifiState(wifiManager, WifiManager.WIFI_STATE_ENABLED, 20_000);
                    if (!ok) Log.w("INFO", "Wi-Fi no llegó a ENABLED en el timeout (O+).");
                    return ok;
                }
            }
        } catch (Throwable t) {
            Log.e("ERROR", "enableHotspot() falló", t);
            return false;
        }
    }

    /**
     * Espera activamente a que el Wi-Fi alcance un estado objetivo (ENABLED/DISABLED),
     * con polling cada 500 ms y timeout configurable.
     */
    private boolean waitForWifiState(WifiManager wm, int targetState, int timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        int last = -1;

        while (System.currentTimeMillis() < deadline) {
            int st = wm.getWifiState();
            if (st != last) {
                last = st;
                // Estados posibles: WIFI_STATE_DISABLED(1), DISABLING(0), ENABLING(2), ENABLED(3), UNKNOWN(4)
                Log.d("INFO", "Wi-Fi state = " + st);
            }
            if (st == targetState) return true;

            try { Thread.sleep(500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return wm.getWifiState() == targetState;
    }

    private void addJSON(JSONObject j2, JSONObject j1){
        if(j1 != null) {
            Iterator<String> keys = j1.keys();
            while (keys.hasNext()){
                String key = keys.next();
                try {
                    j2.put(key, j1.get(key));
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Crash adding JSON");
                }
            }
        }
    }

    public JSONObject removeKeysFromJSONObject(JSONObject jsonObject, String[] keysToRemove) {
        for (String key : keysToRemove) {
            jsonObject.remove(key);
        }
        return jsonObject;
    }

    private JSONObject concatJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "realtime"); // en realtime enviamos un “paquete” consolidado
            json.put("device_id", DEVICE_ID);
            json.put("timestamp_sys", System.currentTimeMillis());
            if(locationModel.isState())
                addJSON(json, removeKeysFromJSONObject(locationModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(inertialModel.isState())
                addJSON(json, removeKeysFromJSONObject(inertialModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(deviceModel.isState())
                addJSON(json, removeKeysFromJSONObject(deviceModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            for (Map.Entry<String, String> entry : externalModel.getAllDeviceMessages().entrySet()) {
                String type = entry.getKey();
                String message = entry.getValue();
                JSONObject messageJson = new JSONObject(message);
                messageJson.remove("type");
                json.put(type, messageJson);
            }
            externalModel.clearAllDeviceMessages();
            return json;

        } catch (JSONException e) {
            e.printStackTrace();
            Log.e("ERROR", "Failed to parse JSON" + e);
        }
        return null;
    }

    private void startMonitoring() {
        requestLocationPermissions();
    }

    private void requestLocationPermissions() {
        ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServices();
                Log.d("INFO", "Services started");
            } else {
                Toast.makeText(this, "Permissions not granted. The app will not function properly", Toast.LENGTH_LONG).show();
                Log.e("ERROR", "Permissions not granted. The app will not function properly");
            }
        }
    }

    private void startServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        ContextCompat.startForegroundService(this, serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        ContextCompat.startForegroundService(this, serviceIntent3);

        // Inicia AmqpService en foreground
        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        ContextCompat.startForegroundService(this, serviceIntent4);
    }

    private void stopServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        stopService(serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        stopService(serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        stopService(serviceIntent3);

        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        stopService(serviceIntent4);
    }

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            Location location = (Location) intent.getParcelableExtra("update_location");
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude((float) location.getLatitude());
            locationModel.setLongitude((float) location.getLongitude());
            locationModel.setBearing((float) location.getBearing());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude((float) location.getAltitude());
            locationModel.setAccuracy_hor((int) location.getAccuracy());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locationModel.setAccuracy_ver((int) location.getVerticalAccuracyMeters());
                locationModel.setAccuracy_bea((int) location.getBearingAccuracyDegrees());
                locationModel.setAccuracy_speed((int) location.getSpeedAccuracyMetersPerSecond());
            } else {
                locationModel.setAccuracy_ver(0);
                locationModel.setAccuracy_bea(0);
                locationModel.setAccuracy_speed(0);
            }

            locationModel.setState(true);
            if (REAL_TIME_OPERATION)
                buffer.insertJson(locationModel.toJSON().toString());
        }
    };

    private final BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            JSONObject last_msg_cellphone = deviceModel.toJSON();
            last_msg_cellphone.remove("timestamp_sys");
            deviceModel.setBattery(intent.getIntExtra("battery_cellphone", deviceModel.getBattery()));
            deviceModel.setTemperature(intent.getIntExtra("temperature_cellphone", deviceModel.getTemperature()));
            deviceModel.setRssi_device(intent.getIntExtra("signal_cellphone", deviceModel.getRssi_device()));
            deviceModel.setWifi_status(intent.getIntExtra("hotspot_cellphone", deviceModel.getWifi_status()));
            String networkType = intent.getStringExtra("network_cellphone");
            if(networkType == null)
                networkType = deviceModel.getNetwork_type();
            deviceModel.setNetwork_type(networkType);
            JSONObject actual_msg_cellphone = deviceModel.toJSON();
            actual_msg_cellphone.remove("timestamp_sys");
            if(!last_msg_cellphone.toString().equals(actual_msg_cellphone.toString())){
                deviceModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(deviceModel.toJSON().toString());
                LEVEL_BATTERY = deviceModel.getBattery();
                TEMPERATURE_BATTERY = deviceModel.getTemperature();
            }
        }
    };

    private final BroadcastReceiver externalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String jsonString_external = intent.getStringExtra("update_external_sensors");
            if (jsonString_external != null) {
                try {
                    JSONObject jsonObject = new JSONObject(jsonString_external);
                    String type = jsonObject.getString("type");
                    externalModel.updateDeviceMessage(type, jsonString_external);
                    if(REAL_TIME_OPERATION) {
                        jsonObject.put("timestamp_sys", System.currentTimeMillis());
                        jsonObject.put("device_id", MainActivity.DEVICE_ID);
                        buffer.insertJson(jsonObject.toString());
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Error parsing JSON from external sensors", e);
                }
            }
        }
    };

    private final BroadcastReceiver inertialReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            float[] values = intent.getFloatArrayExtra("update_inertial_sensors");
            int type = intent.getIntExtra("type_inertial_sensors", 0);
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    inertialModel.setAccelerometerValues(values);
                    updAcc = true;
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    inertialModel.setGyroscopeValues(values);
                    updGyr = true;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    inertialModel.setMagnetometerValues(values);
                    updMag = true;
                    break;
            }
            if(updAcc && updGyr && updMag){
                inertialModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(inertialModel.toJSON().toString());
                updAcc = updGyr = updMag = false;
            }
        }
    };

    @Override
    protected void onDestroy(){
        super.onDestroy();

        Log.d("INFO", "MainActivity destroyed. Scheduling restart...");
        Intent restartIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(), 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10000, pendingIntent);

        unregisterReceiver(locationReceiver);
        unregisterReceiver(inertialReceiver);
        unregisterReceiver(cellphoneReceiver);
        unregisterReceiver(externalReceiver);
        stopServices();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }

        if (offlineFuture != null && !offlineFuture.isDone()) {
            offlineFuture.cancel(true);
        }

        buffer.closeExecutor();
    }

    @Override
    protected void onPause() { super.onPause(); }

    @Override
    protected void onResume() { super.onResume(); }

    /**
     * Decide si estamos en ventana de trabajo (realtime) o fuera (offline).
     */
    public void SchedulerOfflineDetect() {
        schedulerOffline = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!START_MONITORING || !bound) return;

                boolean offlineNow = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
                if (offlineNow) {
                    enterOffline();
                } else {
                    enterRealtime();
                }
            } catch (Throwable t) {
                // Si algún bug/IO rompe el tick, lo registramos pero NO matamos el scheduler
                Log.e("INFO", "Scheduler tick crashed; keeping it alive", t);
            }
        }, 0, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void enterRealtime() {
        if (!REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = true;
            Log.d("INFO", "Switch -> REALTIME");

            // Si había un upload offline, cancelarlo y recuperar conectividad
            if (offlineInProgress && offlineFuture != null && !offlineFuture.isDone()) {
                offlineFuture.cancel(true);
            }
            offlineInProgress = false;
            enableHotspot(true);
        }

        // Arrancar realtime una única vez
        if (bound && !realtimeStarted) {
            amqpService.startRealtime();
            realtimeStarted = true;
            Log.d("INFO", "Realtime started.");
        }

        // Mantener tus automatismos de relés en realtime
        //ScheduleOffLine.isDeviceConnected(IP_DRIVER);
        scheduler.execute(() -> ScheduleOffLine.isDeviceConnected(IP_DRIVER));
    }

    // ===== Helpers de conectividad Wi‑Fi =====
    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.net.Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
            return caps != null
                    && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && ni.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    private boolean waitForWifiConnected(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (isWifiConnected()) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }
    // =========================================

    private boolean hasPending(JSONDatabaseHelper buffer) {
        try {
            Integer c = buffer.getJsonCount().get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            return c != null && c > 0;
        } catch (Exception e) {
            // Si falló el conteo, preferimos intentar (fail-open)
            return true;
        }
    }

    private boolean isWifiEnabled() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        // WIFI_STATE_ENABLED = 3
        int state = wm.getWifiState();
        Log.d("INFO", "Wi-Fi state = " + state);
        return state == WifiManager.WIFI_STATE_ENABLED;
    }

    private void enterOffline() {
        if (REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = false;
            Log.d("INFO", "Switch -> OFFLINE");

            // Parar realtime si estaba activo
            if (bound && realtimeStarted) {
                amqpService.stopRealtime();
                realtimeStarted = false;
                Log.d("INFO", "Realtime stopped.");
            }

            // Preparativos (relés + hotspot OFF → Wi‑Fi ON)
            try {
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE2_ON);
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE1_ON);
            } catch (Exception e) {
                Log.e("ERROR", "Relay pre-actions failed", e);
            }
            enableHotspot(false);
        }

        if (!isWifiEnabled()) {
            Log.d("INFO", "Offline: Wi-Fi aún no ENABLED; reintentando toggle Hotspot OFF → Wi-Fi ON");
            enableHotspot(false);     // idempotente: si ya está off el tethering, no pasa nada
            return;                   // dejamos que el próximo tick vuelva a comprobar
        }

        if (!waitForWifiConnected(30_000)) {
            Log.w("INFO", "Wi-Fi no conectó en 30s; no inicio offline todavía.");
            return; // <- solo difiere cuando NO se logró conexión
        }

        if (bound && !offlineInProgress) {
            if (!hasPending(buffer)) {
                long now = System.currentTimeMillis();
                if (now - lastEmptyOfflineLogAt > 60_000) {
                    Log.d("INFO", "OFF: no hay datos pendientes; no lanzo upload.");
                    lastEmptyOfflineLogAt = now;
                }
                return; // no lanzamos nada; esperamos al próximo tick o a que aparezcan datos
            }
            offlineInProgress = true;
            offlineFuture = amqpService.sendOfflineBatches(buffer);
            Log.d("INFO", "Offline upload started.");
        }

        // Si el futuro ya terminó, limpia estado. NO cambies radios aquí.
        if (offlineInProgress && offlineFuture != null && offlineFuture.isDone()) {
            try {
                AmqpService.UploadStats stats = offlineFuture.get();
                Log.d("INFO", "Offline upload finished: " + (stats != null ? stats.toString() : "no stats"));
            } catch (Exception e) {
                Log.e("ERROR", "Error retrieving offline stats", e);
            }
            offlineInProgress = false;
            // Mantén Wi‑Fi ON durante la ventana offline
        }
    }

    // Callback consumido por AmqpService cada 1s para realtime
    @Override
    public JSONObject getCurrentData() {
        return concatJSON();
    }
}


/*package com.unicamp.moview_v1;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.threetenabp.AndroidThreeTen;

import org.json.JSONException;
import org.json.JSONObject;
import org.threeten.bp.LocalTime;
import org.threeten.bp.format.DateTimeFormatter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements AmqpService.ServiceCallbacks {

    // ======== Modelos / Vistas ========
    private InertialModel inertialModel;
    private InertialView inertialView;

    private LocationModel locationModel;
    private LocationView locationView;

    private DeviceModel deviceModel;
    private DeviceView deviceView;

    private ExternalModel externalModel;
    private ExternalView externalView;

    // ======== Persistencia local ========
    private JSONDatabaseHelper buffer;

    // ======== UI / permisos ========
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private Button btnStart;
    private boolean updAcc = false, updGyr = false, updMag = false;

    // ======== Servicio AMQP ========
    private AmqpService amqpService;
    private boolean bound = false;

    // Estado interno de ejecución (no negocio)
    private volatile boolean realtimeStarted = false;     // evita doble startRealtime()
    private volatile boolean offlineInProgress = false;   // evita dos flush offline a la vez
    private volatile Future<AmqpService.UploadStats> offlineFuture;

    // ======== Scheduler ========
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedulerTask;
    private static final int SCHEDULER_PERIOD_SECONDS = 30;

    // ======== Config/flags globales ========
    public static volatile boolean REAL_TIME_OPERATION = true; // RT=true / OFFLINE=false

    public static LocalTime INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE;

    public static int BATTERY_MAX, BATTERY_MIN, TEMP_MAX, TEMP_MIN;
    public static int LEVEL_BATTERY = -1, TEMPERATURE_BATTERY = -1;
    public static String DEVICE_ID, IP_DRIVER, IP_SERVER, PORT_SERVER, USER_SERVER, PASSWORD_SERVER;

    public static SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidThreeTen.init(this);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        resetSharedPreferences();
        loadSharedPreferences();

        TextView txt_view = this.findViewById(R.id.textview_device_id);
        txt_view.setText(DEVICE_ID + " - " + IP_DRIVER + " - " + IP_SERVER + ":" + PORT_SERVER);

        btnStart = this.findViewById(R.id.btn_start);
        btnStart.setEnabled(false);
        btnStart.setBackgroundColor(Color.GRAY);

        // Modelos / Vistas
        inertialModel  = new InertialModel();
        inertialView   = new InertialView(this);
        locationModel  = new LocationModel();
        locationView   = new LocationView(this);
        deviceModel    = new DeviceModel();
        deviceView     = new DeviceView(this);
        externalModel  = new ExternalModel();
        externalView   = new ExternalView(this);

        // BroadcastReceivers
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.mms.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_EXTERNAL_SENSORS");
        registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper(this);

        // --- Decide modo inicial por horario y deja radios coherentes ---
        boolean offlineNowAtStart = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
        REAL_TIME_OPERATION = !offlineNowAtStart;

        // Arranca AMQP (foreground) y haz bind para poder llamar startRealtime()/sendOfflineBatches()
        startAmqpServiceAndBind();

        // Sensores: se lanzan tras permiso
        startMonitoring();

        // Tu servidor TCP
        startServer();

        // Battery optimization UI
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);

        PowerManager pm = (PowerManager) this.getSystemService(POWER_SERVICE);
        Log.d("INFO", "Ignore battery optimizations: " +
                (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())));

        // Scheduler: dueño del modo
        SchedulerModeLoop();

        btnStart.setEnabled(true);
        btnStart.setBackgroundColor(Color.BLUE);
        btnStart.setOnClickListener(v -> updateViews());
    }

    // ======== SharedPreferences ========
    private void resetSharedPreferences(){
        sharedPreferences.edit().clear().apply();
    }

    private void loadSharedPreferences(){
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        INITIAL_TIME_OFFLINE = LocalTime.parse(sharedPreferences.getString("INITIAL_TIME_OFFLINE", "00:00"), fmt);
        FINAL_TIME_OFFLINE   = LocalTime.parse(sharedPreferences.getString("FINAL_TIME_OFFLINE",   "23:50"), fmt);

        DEVICE_ID       = sharedPreferences.getString("DEVICE_ID", "GIO5B97");
        IP_DRIVER       = sharedPreferences.getString("IP_DRIVER", "192.168.43.121");
        IP_SERVER       = sharedPreferences.getString("IP_SERVER", "143.106.8.17");
        PORT_SERVER     = sharedPreferences.getString("PORT_SERVER", "5672");
        USER_SERVER     = sharedPreferences.getString("USER_SERVER", "producer1");
        PASSWORD_SERVER = sharedPreferences.getString("PASSWORD_SERVER", "3e3z2IQiEDWBxNPgS09G");

        TEMP_MIN   = sharedPreferences.getInt("TEMP_MIN",30);
        TEMP_MAX   = sharedPreferences.getInt("TEMP_MAX",35);
        BATTERY_MIN= sharedPreferences.getInt("BATTERY_MIN",40);
        BATTERY_MAX= sharedPreferences.getInt("BATTERY_MAX",90);
    }

    private void updateViews(){
        locationView.update(locationModel.toString());
        deviceView.update(deviceModel.toString());
        externalView.update(externalModel.toString());
        inertialView.update(inertialModel.toString());
    }

    // ======== Servicio AMQP: start + bind ========
    private void startAmqpServiceAndBind() {
        ContextCompat.startForegroundService(this, new Intent(this, AmqpService.class));
        bindService(new Intent(this, AmqpService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            AmqpService.MyBinder binder = (AmqpService.MyBinder) service;
            amqpService = binder.getService();
            amqpService.setCallbacks(MainActivity.this);
            bound = true;
            Log.d("INFO", "AMQP bound");

            // Arranca el modo correcto inmediatamente
            //if (REAL_TIME_OPERATION) enterRealtime();
            //else                     enterOffline();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (amqpService != null) amqpService.setCallbacks(null);
            bound = false;
            realtimeStarted = false;
            Log.d("INFO", "AMQP unbound");
        }
    };

    // ======== Server TCP ========
    private void startServer() {
        ServerThread serverThread = new ServerThread(8888,this);
        serverThread.start();
    }

    // ======== Permisos sistema ========
    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            }
        }
    }

    // ======== Hotspot/Wi-Fi (toggle mínimo) ========
    *//**
     * Minimizado: solo cambia radios. Sin sleeps. La verificación real la hace
     * el scheduler con waitForWifiConnected()/isWifiConnected().
     *
     * enable = true  -> hotspot ON (Wi-Fi OFF)
     * enable = false -> hotspot OFF (Wi-Fi ON)
     *//*
    public void enableHotspot(boolean enable) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (wifiManager == null || cm == null) {
            Log.e("ERROR", "enableHotspot: WifiManager/ConnectivityManager null");
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            try {
                // < Android 8: API antigua por reflexión
                Method setWifiApEnabledMethod = wifiManager.getClass()
                        .getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);

                if (enable) {
                    // Hotspot ON: apaga Wi-Fi, enciende tethering
                    wifiManager.setWifiEnabled(false);
                    setWifiApEnabledMethod.invoke(wifiManager, null, true);
                } else {
                    // Hotspot OFF: apaga tethering, enciende Wi-Fi
                    setWifiApEnabledMethod.invoke(wifiManager, null, false);
                    wifiManager.setWifiEnabled(true);
                }
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                Log.e("ERROR", "Hotspot legacy toggle failed", e);
            }
            return;
        }

        try {
            // ≥ Android 8: start/stop tethering por reflexión
            @SuppressLint("SoonBlockedPrivateApi")
            Field iConnectivityManagerField = cm.getClass().getDeclaredField("mService");
            iConnectivityManagerField.setAccessible(true);
            Object iConnectivityManager = iConnectivityManagerField.get(cm);

            Method startTetheringMethod = iConnectivityManager.getClass()
                    .getMethod("startTethering", int.class, ResultReceiver.class, boolean.class);
            Method stopTetheringMethod  = iConnectivityManager.getClass()
                    .getMethod("stopTethering", int.class);

            if (enable) {
                wifiManager.setWifiEnabled(false);
                startTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE, null, true);
            } else {
                stopTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE);
                wifiManager.setWifiEnabled(true);
            }
        } catch (Exception e) {
            Log.e("ERROR", "WiFi Hotspot Error", e);
        }
    }

    // ======== Helpers de red para el scheduler ========
    private boolean isWifiConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
            return caps != null
                    && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    private boolean waitForWifiConnected(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (isWifiConnected()) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    private boolean isWifiEnabled() {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm != null && wm.isWifiEnabled();
    }

    private boolean waitForCellularConnected(long timeoutMs) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network nw = cm.getActiveNetwork();
                if (nw != null) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                    if (caps != null
                            && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                            && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return true;
                    }
                }
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected() && ni.getType() == android.net.ConnectivityManager.TYPE_MOBILE) {
                    return true;
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return false;
    }

    // ======== JSON helpers ========
    private void addJSON(JSONObject j2, JSONObject j1){
        if(j1 != null) {
            Iterator<String> keys = j1.keys();
            while (keys.hasNext()){
                String key = keys.next();
                try { j2.put(key, j1.get(key)); }
                catch (JSONException e) { Log.e("ERROR", "Crash adding JSON", e); }
            }
        }
    }

    public JSONObject removeKeysFromJSONObject(JSONObject jsonObject, String[] keysToRemove) {
        for (String key : keysToRemove) jsonObject.remove(key);
        return jsonObject;
    }

    private JSONObject concatJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "realtime");
            json.put("device_id", DEVICE_ID);
            json.put("timestamp_sys", System.currentTimeMillis());
            if(locationModel.isState())
                addJSON(json, removeKeysFromJSONObject(locationModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(inertialModel.isState())
                addJSON(json, removeKeysFromJSONObject(inertialModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(deviceModel.isState())
                addJSON(json, removeKeysFromJSONObject(deviceModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            for (Map.Entry<String, String> entry : externalModel.getAllDeviceMessages().entrySet()) {
                String type = entry.getKey();
                String message = entry.getValue();
                JSONObject messageJson = new JSONObject(message);
                messageJson.remove("type");
                json.put(type, messageJson);
            }
            externalModel.clearAllDeviceMessages();
            return json;
        } catch (JSONException e) {
            Log.e("ERROR", "Failed to parse JSON", e);
            return null;
        }
    }

    // ======== Permisos sensores / arranque ========
    private void startMonitoring() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSensorServices();
                Log.d("INFO", "Sensor services started");
            } else {
                Toast.makeText(this, "Permissions not granted. The app will not function properly", Toast.LENGTH_LONG).show();
                Log.e("ERROR", "Permissions not granted.");
            }
        }
    }

    private void startSensorServices() {
        ContextCompat.startForegroundService(this, new Intent(this, LocationService.class));
        ContextCompat.startForegroundService(this, new Intent(this, InertialService.class));
        ContextCompat.startForegroundService(this, new Intent(this, DeviceService.class));
    }

    private void stopServices() {
        stopService(new Intent(this, LocationService.class));
        stopService(new Intent(this, InertialService.class));
        stopService(new Intent(this, DeviceService.class));
        stopService(new Intent(this, AmqpService.class));
    }

    // ======== Receivers ========
    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override public void onReceive(Context context, Intent intent) {
            Location location = intent.getParcelableExtra("update_location");
            if (location == null) return;
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude((float) location.getLatitude());
            locationModel.setLongitude((float) location.getLongitude());
            locationModel.setBearing((float) location.getBearing());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude((float) location.getAltitude());
            locationModel.setAccuracy_hor((int) location.getAccuracy());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locationModel.setAccuracy_ver((int) location.getVerticalAccuracyMeters());
                locationModel.setAccuracy_bea((int) location.getBearingAccuracyDegrees());
                locationModel.setAccuracy_speed((int) location.getSpeedAccuracyMetersPerSecond());
            } else {
                locationModel.setAccuracy_ver(0);
                locationModel.setAccuracy_bea(0);
                locationModel.setAccuracy_speed(0);
            }
            locationModel.setState(true);
            if (REAL_TIME_OPERATION) buffer.insertJson(locationModel.toJSON().toString());
        }
    };

    private final BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            JSONObject last_msg_cellphone = deviceModel.toJSON();
            last_msg_cellphone.remove("timestamp_sys");
            deviceModel.setBattery(intent.getIntExtra("battery_cellphone", deviceModel.getBattery()));
            deviceModel.setTemperature(intent.getIntExtra("temperature_cellphone", deviceModel.getTemperature()));
            deviceModel.setRssi_device(intent.getIntExtra("signal_cellphone", deviceModel.getRssi_device()));
            deviceModel.setWifi_status(intent.getIntExtra("hotspot_cellphone", deviceModel.getWifi_status()));
            String networkType = intent.getStringExtra("network_cellphone");
            if(networkType == null) networkType = deviceModel.getNetwork_type();
            deviceModel.setNetwork_type(networkType);
            JSONObject actual_msg_cellphone = deviceModel.toJSON();
            actual_msg_cellphone.remove("timestamp_sys");
            if(!last_msg_cellphone.toString().equals(actual_msg_cellphone.toString())){
                deviceModel.setState(true);
                if(REAL_TIME_OPERATION) buffer.insertJson(deviceModel.toJSON().toString());
                LEVEL_BATTERY = deviceModel.getBattery();
                TEMPERATURE_BATTERY = deviceModel.getTemperature();
            }
        }
    };

    private final BroadcastReceiver externalReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String jsonString_external = intent.getStringExtra("update_external_sensors");
            if (jsonString_external != null) {
                try {
                    JSONObject jsonObject = new JSONObject(jsonString_external);
                    String type = jsonObject.getString("type");
                    externalModel.updateDeviceMessage(type, jsonString_external);
                    if(REAL_TIME_OPERATION) {
                        jsonObject.put("timestamp_sys", System.currentTimeMillis());
                        jsonObject.put("device_id", MainActivity.DEVICE_ID);
                        buffer.insertJson(jsonObject.toString());
                    }
                } catch (JSONException e) {
                    Log.e("ERROR", "Error parsing JSON from external sensors", e);
                }
            }
        }
    };

    private final BroadcastReceiver inertialReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            float[] values = intent.getFloatArrayExtra("update_inertial_sensors");
            int type = intent.getIntExtra("type_inertial_sensors", 0);
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER: inertialModel.setAccelerometerValues(values); updAcc = true; break;
                case Sensor.TYPE_GYROSCOPE:     inertialModel.setGyroscopeValues(values);     updGyr = true; break;
                case Sensor.TYPE_MAGNETIC_FIELD:inertialModel.setMagnetometerValues(values);  updMag = true; break;
            }
            if(updAcc && updGyr && updMag){
                inertialModel.setState(true);
                if(REAL_TIME_OPERATION) buffer.insertJson(inertialModel.toJSON().toString());
                updAcc = updGyr = updMag = false;
            }
        }
    };

    // ======== Ciclo de vida ========
    @Override
    protected void onDestroy(){
        super.onDestroy();

        Log.d("INFO", "MainActivity destroyed. Scheduling restart...");
        Intent restartIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(), 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, pendingIntent);

        unregisterReceiver(locationReceiver);
        unregisterReceiver(inertialReceiver);
        unregisterReceiver(cellphoneReceiver);
        unregisterReceiver(externalReceiver);
        stopServices();
        if (bound) { unbindService(serviceConnection); bound = false; }
        if (offlineFuture != null && !offlineFuture.isDone()) offlineFuture.cancel(true);
        if (schedulerTask != null) schedulerTask.cancel(true);
        buffer.closeExecutor();
    }

    // ======== Scheduler: decide modo por horario ========
    public void SchedulerModeLoop() {
        schedulerTask = scheduler.scheduleAtFixedRate(() -> {
            boolean offlineNow = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
            if (offlineNow) enterOffline();
            else            enterRealtime();
        }, 0, SCHEDULER_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    // ======== Transiciones ========
    private void enterRealtime() {
        // Si veníamos de OFFLINE, cancelar subida y poner hotspot ON
        if (!REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = true;

            if (offlineInProgress && offlineFuture != null && !offlineFuture.isDone()) {
                offlineFuture.cancel(true);
            }
            offlineInProgress = false;
        }

        // Asegurar radios para realtime: hotspot ON (Wi-Fi OFF)
        if (isWifiEnabled()) {
            Log.d("INFO", "RT: Wi-Fi estaba ON; activando hotspot (Wi-Fi OFF) para realtime.");
            enableHotspot(true);
        }

        // (Opcional recomendado) espera datos móviles validados para reducir timeouts AMQP
        if (!waitForCellularConnected(15_000)) {
            Log.w("INFO", "RT: datos móviles no validados aún; no se arranca startRealtime() en este tick.");
            return; // el scheduler reintentará en el próximo ciclo
        }

        // Arrancar realtime una única vez
        if (bound && !realtimeStarted) {
            amqpService.startRealtime();
            realtimeStarted = true;
            Log.d("INFO", "Realtime started.");
        }

        // Tus automatismos
        ScheduleOffLine.isDeviceConnected(IP_DRIVER);
    }

    private void enterOffline() {
        if (REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = false;

            // parar RT si estaba corriendo
            if (bound && realtimeStarted) {
                amqpService.stopRealtime();
                realtimeStarted = false;
                Log.d("INFO", "RT stopped");
            }

            // relés (tu lógica)
            try {
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE2_ON);
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE1_ON);
            } catch (Exception ignored) {}

            // hotspot OFF → Wi-Fi ON
            enableHotspot(false);

            // garantizar Wi-Fi listo para subir sin timeouts
            if (!waitForWifiConnected(30_000)) {
                Log.w("INFO", "Wi-Fi no listo; reintento en el próximo tick");
                return;
            }
        }

        // lanzar subida por lotes si no hay otra en curso
        if (bound && !offlineInProgress) {
            try {
                offlineInProgress = true;
                offlineFuture = amqpService.sendOfflineBatches(buffer);
                Log.d("INFO", "OFF upload started");
            } catch (Exception e) {
                offlineInProgress = false;
                Log.e("ERROR", "OFF start failed", e);
            }
        }

        // si terminó, limpia estado (el próximo tick relanza si quedan datos)
        if (offlineInProgress && offlineFuture != null && offlineFuture.isDone()) {
            try {
                AmqpService.UploadStats stats = offlineFuture.get();
                Log.d("INFO", "OFF finished: " + (stats != null ? stats.toString() : "no stats"));
            } catch (Exception ignored) {}
            offlineInProgress = false;
        }
    }

    // ======== Callback AMQP realtime (1/s) ========
    @Override
    public JSONObject getCurrentData() { return concatJSON(); }
}*/


/*    VERSION 2

package com.unicamp.moview_v1;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.threetenabp.AndroidThreeTen;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import androidx.core.util.Pair;
import org.threeten.bp.LocalTime;
import org.threeten.bp.format.DateTimeFormatter;

public class MainActivity extends AppCompatActivity implements AmqpService.ServiceCallbacks { // CHANGE: implementa la interfaz del nuevo AmqpService

    private InertialModel inertialModel;
    private InertialView inertialView;

    private LocationModel locationModel;
    private LocationView locationView;

    private DeviceModel deviceModel;
    private DeviceView deviceView;

    private ExternalModel externalModel;
    private ExternalView externalView;

    private JSONDatabaseHelper buffer;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private boolean updAcc = false, updGyr = false, updMag = false;
    private Button btnStart;

    private AmqpService amqpService;
    private boolean bound = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedulerOffline;

    public static boolean REAL_TIME_OPERATION = true, START_MONITORING = false;

    public static LocalTime FINAL_TIME_OFFLINE, INITIAL_TIME_OFFLINE;

    public static int BATTERY_MAX, BATTERY_MIN, TEMP_MAX, TEMP_MIN;
    public static int LEVEL_BATTERY = -1, TEMPERATURE_BATTERY = -1;
    public static String DEVICE_ID, IP_DRIVER, IP_SERVER, PORT_SERVER, USER_SERVER, PASSWORD_SERVER;

    public static SharedPreferences sharedPreferences;

    // CHANGE: control fino del ciclo realtime/offline
    private volatile boolean realtimeStarted = false;                 // evita arrancar realtime dos veces
    private volatile boolean offlineInProgress = false;               // evita lanzar 2 flush offline a la vez
    private volatile Future<AmqpService.UploadStats> offlineFuture;   // para poder cancelar si cambia el horario

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidThreeTen.init(this);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        resetSharedPreferences();
        loadSharedPreferences();
        saveSharedPreferences();
        Log.d("INFO", "TempMN: " + TEMP_MIN +" TempMM: " + TEMP_MAX);

        TextView txt_view = this.findViewById(R.id.textview_device_id);
        txt_view.setText(DEVICE_ID + " - " + IP_DRIVER + " - " + IP_SERVER + ":" + PORT_SERVER);

        btnStart = this.findViewById(R.id.btn_start);
        btnStart.setEnabled(false);
        btnStart.setBackgroundColor(Color.GRAY);

        inertialModel = new InertialModel();
        inertialView = new InertialView(this);

        locationModel = new LocationModel();
        locationView = new LocationView(this);

        deviceModel = new DeviceModel();
        deviceView = new DeviceView(this);

        externalModel = new ExternalModel();
        externalView = new ExternalView(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.mms.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_EXTERNAL_SENSORS");
        registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper(this);

        boolean offlineNowAtStart = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
        REAL_TIME_OPERATION = !offlineNowAtStart;
        if (offlineNowAtStart) {
            Log.d("INFO", "Startup in OFFLINE window -> disable hotspot, enable Wi-Fi");
            enableHotspot(false);                  // apaga tethering, activa Wi-Fi
            waitForWifiConnected(30_000);          // espera a que haya Wi-Fi real
        } else {
            Log.d("INFO", "Startup in REALTIME window -> enable hotspot");
            enableHotspot(true);                   // activa tethering
        }

        // CHANGE: iniciamos y hacemos bind al nuevo AmqpService; al conectar arrancamos realtime si corresponde
        bindService(new Intent(this, AmqpService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);

        PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
        if(powerManager.isIgnoringBatteryOptimizations(this.getPackageName()))
            Log.d("INFO", "Battery optimization permission is granted");
        else
            Log.e("ERROR", "Battery optimization permission not granted");

        enableHotspot(true);
        START_MONITORING = true;
        startMonitoring();
        startServer();

        // CHANGE: programamos el ciclo que decide realtime/offline y orquesta el envío por lotes
        SchedulerOfflineDetect();

        btnStart.setEnabled(true);
        btnStart.setBackgroundColor(Color.BLUE);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateViews();
            }
        });
    }

    private void resetSharedPreferences(){
        sharedPreferences.edit().clear().apply();
        Log.d("INFO", "SharedPreferences reseteado a valores por defecto");
    }

    private void loadSharedPreferences(){
        Log.d("INFO", "Update configurations");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        INITIAL_TIME_OFFLINE = LocalTime.parse(sharedPreferences.getString("INITIAL_TIME_OFFLINE", "00:00"), formatter);
        FINAL_TIME_OFFLINE = LocalTime.parse(sharedPreferences.getString("FINAL_TIME_OFFLINE", "23:50"), formatter);

        //JCL9G80 - 192.168.43.235 - producer2 - sVKV76zfoEvnAS8KCG9N
        //GIO5B97 - 192.168.43.121 - producer1 - 3e3z2IQiEDWBxNPgS09G
        //TEST - 192.168.43.97 - producer3 -

        DEVICE_ID = sharedPreferences.getString("DEVICE_ID", "GIO5B97");
        IP_DRIVER = sharedPreferences.getString("IP_DRIVER", "192.168.43.121");
        IP_SERVER = sharedPreferences.getString("IP_SERVER", "143.106.8.17");
        PORT_SERVER = sharedPreferences.getString("PORT_SERVER", "5672");
        USER_SERVER = sharedPreferences.getString("USER_SERVER", "producer1");
        PASSWORD_SERVER = sharedPreferences.getString("PASSWORD_SERVER", "3e3z2IQiEDWBxNPgS09G");
        TEMP_MIN = sharedPreferences.getInt("TEMP_MIN",30);
        TEMP_MAX = sharedPreferences.getInt("TEMP_MAX",35);
        BATTERY_MIN = sharedPreferences.getInt("BATTERY_MIN",40);
        BATTERY_MAX = sharedPreferences.getInt("BATTERY_MAX",90);
    }

    private void saveSharedPreferences(){
        SharedPreferences.Editor editor = sharedPreferences.edit();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        String initialTimeFormatted = INITIAL_TIME_OFFLINE.format(formatter);
        String finalTimeFormatted = FINAL_TIME_OFFLINE.format(formatter);

        editor.putString("INITIAL_TIME_OFFLINE", initialTimeFormatted);
        editor.putString("FINAL_TIME_OFFLINE", finalTimeFormatted);
        editor.putString("DEVICE_ID", DEVICE_ID);
        editor.putString("IP_DRIVER", IP_DRIVER);
        editor.putString("IP_SERVER", IP_SERVER);
        editor.putString("PORT_SERVER", PORT_SERVER);
        editor.putString("USER_SERVER", USER_SERVER);
        editor.putString("PASSWORD_SERVER", PASSWORD_SERVER);
        editor.putInt("TEMP_MIN", TEMP_MIN);
        editor.putInt("TEMP_MAX", TEMP_MAX);
        editor.putInt("BATTERY_MIN", BATTERY_MIN);
        editor.putInt("BATTERY_MAX", BATTERY_MAX);
        editor.commit();
    }

    private void updateViews(){
        locationView.update(locationModel.toString());
        deviceView.update(deviceModel.toString());
        externalView.update(externalModel.toString());
        inertialView.update(inertialModel.toString());
    }

    // CHANGE: conexión al AmqpService -> arrancamos realtime si estamos en ventana de tiempo
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AmqpService.MyBinder binder = (AmqpService.MyBinder) service;
            amqpService = binder.getService();
            amqpService.setCallbacks(MainActivity.this);
            bound = true;
            Log.d("INFO", "AMQP Service connected");

            // Arranca el modo correcto según la ventana actual
            boolean offlineNow = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
            if (offlineNow) {
                enterOffline();  // no duplicará si ya está en progreso
            } else {
                enterRealtime();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (amqpService != null) amqpService.setCallbacks(null);
            bound = false;
            realtimeStarted = false;
            Log.d("INFO", "AMQP Service disconnected");
        }
    };

    private void startServer() {
        ServerThread serverThread = new ServerThread(8888,this);
        serverThread.start();
    }

    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            }
        }
    }

    public void enableHotspot(boolean enable) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d("INFO", "Android version below 8");
            try {
                // Desactiva el Wi-Fi antes de habilitar el hotspot
                wifiManager.setWifiEnabled(!enable);
                // Accede al método 'setWifiApEnabled()' usando reflexión
                Method setWifiApEnabledMethod = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                // Habilita o deshabilita el hotspot
                setWifiApEnabledMethod.invoke(wifiManager, null, enable);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            Log.d("INFO", "Android version 8 or higher");
            // Para Android 8.0 y versiones posteriores, se requiere un método diferente
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                @SuppressLint("SoonBlockedPrivateApi") Field iConnectivityManagerField = connectivityManager.getClass().getDeclaredField("mService");
                iConnectivityManagerField.setAccessible(true);
                Object iConnectivityManager = iConnectivityManagerField.get(connectivityManager);

                Method startTetheringMethod = iConnectivityManager.getClass().getMethod("startTethering", int.class, ResultReceiver.class, boolean.class);

                if (enable) {
                    wifiManager.setWifiEnabled(false);
                    startTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE, null, true);
                } else {
                    Method stopTetheringMethod = iConnectivityManager.getClass().getMethod("stopTethering", int.class);
                    stopTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE);
                    Thread.sleep(15000);
                    wifiManager.setWifiEnabled(true);
                }
            } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException | InterruptedException e) {
                e.printStackTrace();
                Log.e("ERROR", "WiFi Hotspot Error");
            }
        }
    }

    private void addJSON(JSONObject j2, JSONObject j1){
        if(j1 != null) {
            Iterator<String> keys = j1.keys();
            while (keys.hasNext()){
                String key = keys.next();
                try {
                    j2.put(key, j1.get(key));
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Crash adding JSON");
                }
            }
        }
    }

    public JSONObject removeKeysFromJSONObject(JSONObject jsonObject, String[] keysToRemove) {
        for (String key : keysToRemove) {
            jsonObject.remove(key);
        }
        return jsonObject;
    }

    private JSONObject concatJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "realtime"); // CHANGE: en realtime enviamos un “paquete” consolidado
            json.put("device_id", DEVICE_ID);
            json.put("timestamp_sys", System.currentTimeMillis());
            if(locationModel.isState())
                addJSON(json, removeKeysFromJSONObject(locationModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(inertialModel.isState())
                addJSON(json, removeKeysFromJSONObject(inertialModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(deviceModel.isState())
                addJSON(json, removeKeysFromJSONObject(deviceModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            for (Map.Entry<String, String> entry : externalModel.getAllDeviceMessages().entrySet()) {
                String type = entry.getKey();
                String message = entry.getValue();
                JSONObject messageJson = new JSONObject(message);
                messageJson.remove("type");
                json.put(type, messageJson);
            }
            externalModel.clearAllDeviceMessages();
            return json;

        } catch (JSONException e) {
            e.printStackTrace();
            Log.e("ERROR", "Failed to parse JSON" + e);
        }
        return null;
    }

    private void startMonitoring() {
        requestLocationPermissions();
    }

    private void requestLocationPermissions() {
        ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServices();
                Log.d("INFO", "Services started");
            } else {
                Toast.makeText(this, "Permissions not granted. The app will not function properly", Toast.LENGTH_LONG).show();
                Log.e("ERROR", "Permissions not granted. The app will not function properly");
            }
        }
    }

    private void startServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        ContextCompat.startForegroundService(this, serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        ContextCompat.startForegroundService(this, serviceIntent3);

        // CHANGE: seguimos iniciando AmqpService en foreground; el startRealtime lo hacemos al conectar (serviceConnection)
        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        ContextCompat.startForegroundService(this, serviceIntent4);
    }

    private void stopServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        stopService(serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        stopService(serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        stopService(serviceIntent3);

        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        stopService(serviceIntent4);
    }

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            Location location = (Location) intent.getParcelableExtra("update_location");
            if (location == null) return;
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude((float) location.getLatitude());
            locationModel.setLongitude((float) location.getLongitude());
            locationModel.setBearing((float) location.getBearing());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude((float) location.getAltitude());
            locationModel.setAccuracy_hor((int) location.getAccuracy());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locationModel.setAccuracy_ver((int) location.getVerticalAccuracyMeters());
                locationModel.setAccuracy_bea((int) location.getBearingAccuracyDegrees());
                locationModel.setAccuracy_speed((int) location.getSpeedAccuracyMetersPerSecond());
            } else {
                locationModel.setAccuracy_ver(0);
                locationModel.setAccuracy_bea(0);
                locationModel.setAccuracy_speed(0);
            }

            locationModel.setState(true);
            if (REAL_TIME_OPERATION)
                buffer.insertJson(locationModel.toJSON().toString());
        }
    };

    private final BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            JSONObject last_msg_cellphone = deviceModel.toJSON();
            last_msg_cellphone.remove("timestamp_sys");
            deviceModel.setBattery(intent.getIntExtra("battery_cellphone", deviceModel.getBattery()));
            deviceModel.setTemperature(intent.getIntExtra("temperature_cellphone", deviceModel.getTemperature()));
            deviceModel.setRssi_device(intent.getIntExtra("signal_cellphone", deviceModel.getRssi_device()));
            deviceModel.setWifi_status(intent.getIntExtra("hotspot_cellphone", deviceModel.getWifi_status()));
            String networkType = intent.getStringExtra("network_cellphone");
            if(networkType == null)
                networkType = deviceModel.getNetwork_type();
            deviceModel.setNetwork_type(networkType);
            JSONObject actual_msg_cellphone = deviceModel.toJSON();
            actual_msg_cellphone.remove("timestamp_sys");
            if(!last_msg_cellphone.toString().equals(actual_msg_cellphone.toString())){
                deviceModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(deviceModel.toJSON().toString());
                LEVEL_BATTERY = deviceModel.getBattery();
                TEMPERATURE_BATTERY = deviceModel.getTemperature();
            }
        }
    };

    private final BroadcastReceiver externalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String jsonString_external = intent.getStringExtra("update_external_sensors");
            if (jsonString_external != null) {
                try {
                    JSONObject jsonObject = new JSONObject(jsonString_external);
                    String type = jsonObject.getString("type");
                    externalModel.updateDeviceMessage(type, jsonString_external);
                    if(REAL_TIME_OPERATION) {
                        jsonObject.put("timestamp_sys", System.currentTimeMillis());
                        jsonObject.put("device_id", MainActivity.DEVICE_ID);
                        buffer.insertJson(jsonObject.toString());
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Error parsing JSON from external sensors", e);
                }
            }
        }
    };

    private final BroadcastReceiver inertialReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            float[] values = intent.getFloatArrayExtra("update_inertial_sensors");
            int type = intent.getIntExtra("type_inertial_sensors", 0);
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    inertialModel.setAccelerometerValues(values);
                    updAcc = true;
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    inertialModel.setGyroscopeValues(values);
                    updGyr = true;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    inertialModel.setMagnetometerValues(values);
                    updMag = true;
                    break;
            }
            if(updAcc && updGyr && updMag){
                inertialModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(inertialModel.toJSON().toString());
                updAcc = updGyr = updMag = false;
            }
        }
    };

    @Override
    protected void onDestroy(){
        super.onDestroy();

        Log.d("INFO", "MainActivity destroyed. Scheduling restart...");
        Intent restartIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(), 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10000, pendingIntent);

        unregisterReceiver(locationReceiver);
        unregisterReceiver(inertialReceiver);
        unregisterReceiver(cellphoneReceiver);
        unregisterReceiver(externalReceiver);
        stopServices();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }

        // CHANGE: si había un flush en progreso, intentamos cancelarlo
        if (offlineFuture != null && !offlineFuture.isDone()) {
            offlineFuture.cancel(true);
        }

        buffer.closeExecutor();
    }

    @Override
    protected void onPause() { super.onPause(); }

    @Override
    protected void onResume() { super.onResume(); }

    *//**
     * CHANGE: Este programador decide si estamos en ventana de trabajo (realtime) o fuera (offline).
     * - En ventana realtime: aseguramos que Realtime esté corriendo.
     * - Fuera de ventana: detenemos Realtime, apagamos hotspot si así lo usas, y lanzamos el envío offline por lotes.
     *   Si vuelve a entrar a ventana de trabajo mientras se está subiendo, cancelamos el flush offline.
     *//*

    public void SchedulerOfflineDetect() {
        schedulerOffline = scheduler.scheduleAtFixedRate(() -> {
            if (!START_MONITORING || !bound) return;

            boolean offlineNow = ScheduleOffLine.isOfflineWindow(INITIAL_TIME_OFFLINE, FINAL_TIME_OFFLINE);
            if (offlineNow) {
                enterOffline();
            } else {
                enterRealtime();
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    private void enterRealtime() {
        if (!REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = true;
            Log.d("INFO", "Switch -> REALTIME");

            // Si había un upload offline, cancelarlo y recuperar conectividad
            if (offlineInProgress && offlineFuture != null && !offlineFuture.isDone()) {
                offlineFuture.cancel(true);
            }
            offlineInProgress = false;
            enableHotspot(true);
        }

        // Arrancar realtime una única vez
        if (bound && !realtimeStarted) {
            amqpService.startRealtime();
            realtimeStarted = true;
            Log.d("INFO", "Realtime started.");
        }

        // Mantener tus automatismos de relés en realtime
        ScheduleOffLine.isDeviceConnected(IP_DRIVER);
    }

    private void enterOffline() {
        if (REAL_TIME_OPERATION) {
            REAL_TIME_OPERATION = false;
            Log.d("INFO", "Switch -> OFFLINE");

            // Parar realtime si estaba activo
            if (bound && realtimeStarted) {
                amqpService.stopRealtime();
                realtimeStarted = false;
                Log.d("INFO", "Realtime stopped.");
            }

            // Preparativos de tu flujo (relés + hotspot)
            try {
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE2_ON);
                ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE1_ON);
            } catch (Exception e) {
                Log.e("ERROR", "Relay pre-actions failed", e);
            }
            enableHotspot(false);
        }

        // Lanzar (o relanzar) la subida offline en lotes si no está en progreso
        if (bound && !offlineInProgress) {
            try {
                offlineInProgress = true;
                offlineFuture = amqpService.sendOfflineBatches(buffer);
                Log.d("INFO", "Offline upload started.");

                // Opción: no esperamos bloqueando; el scheduler cada 60s revisa si terminó.
                // Si quieres, puedes añadir aquí un listener con another thread que haga .get() y loguee stats.

            } catch (Exception e) {
                offlineInProgress = false;
                Log.e("ERROR", "Failed to start offline upload", e);
            }
        }

        // Si el futuro ya terminó en una iteración previa, limpia estado y (si siguen quedando datos)
        // el próximo tick volverá a iniciar otro batch automáticamente.
        if (offlineInProgress && offlineFuture != null && offlineFuture.isDone()) {
            try {
                AmqpService.UploadStats stats = offlineFuture.get();
                Log.d("INFO", "Offline upload finished: " + (stats != null ? stats.toString() : "no stats"));
            } catch (Exception e) {
                Log.e("ERROR", "Error retrieving offline stats", e);
            }
            offlineInProgress = false;
            enableHotspot(true); // recuperar conectividad; la ventana sigue siendo offline, pero no pasa nada por tenerlo ON
        }
    }

    // CHANGE: este callback lo consume AmqpService para armar el paquete de realtime cada 1s
    @Override
    public JSONObject getCurrentData() {
        return concatJSON();
    }
}*/



/*  VERSION 1
package com.unicamp.moview_v1;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.jakewharton.threetenabp.AndroidThreeTen;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.threeten.bp.LocalTime;
import org.threeten.bp.format.DateTimeFormatter;


public class MainActivity extends AppCompatActivity implements ServiceCallbacks {
    private InertialModel inertialModel;
    private InertialView inertialView;

    private LocationModel locationModel;
    private LocationView locationView;

    private DeviceModel deviceModel;
    private DeviceView deviceView;

    private ExternalModel externalModel;
    private ExternalView externalView;

    private JSONDatabaseHelper buffer;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private boolean updAcc = false, updGyr = false, updMag = false;
    private Button btnStart;

    private AmqpService amqpService;
    private boolean bound = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedulerOffline;

    public static boolean REAL_TIME_OPERATION = true, START_MONITORING = false;

    public static LocalTime FINAL_TIME_WORK, INITIAL_TIME_WORK;

    public static int BATTERY_MAX, BATTERY_MIN, TEMP_MAX, TEMP_MIN;
    public static int LEVEL_BATTERY = -1, TEMPERATURE_BATTERY = -1;
    public static String DEVICE_ID, IP_DRIVER, IP_SERVER, PORT_SERVER, USER_SERVER, PASSWORD_SERVER;

    public static SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidThreeTen.init(this);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        //resetSharedPreferences();
        loadSharedPreferences();
        saveSharedPreferences();
        Log.d("INFO", "TempMN: " + TEMP_MIN +" TempMM: " + TEMP_MAX);

        TextView txt_view = this.findViewById(R.id.textview_device_id);
        txt_view.setText(DEVICE_ID + " - " + IP_DRIVER + " - " + IP_SERVER + ":" + PORT_SERVER);

        btnStart = this.findViewById(R.id.btn_start);
        btnStart.setEnabled(false);
        btnStart.setBackgroundColor(Color.GRAY);

        inertialModel = new InertialModel();
        inertialView = new InertialView(this);

        locationModel = new LocationModel();
        locationView = new LocationView(this);

        deviceModel = new DeviceModel();
        deviceView = new DeviceView(this);

        externalModel = new ExternalModel();
        externalView = new ExternalView(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.mms.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        filter.addAction("com.unicamp.mms.SEND_EXTERNAL_SENSORS");
        registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper(this);
        bindService(new Intent(this, AmqpService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);

        PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
        if(powerManager.isIgnoringBatteryOptimizations(this.getPackageName()))
            Log.d("INFO", "Battery optimization permission is granted");
        else
            Log.e("ERROR", "Battery optimization permission not granted");

        enableHotspot(true);
        START_MONITORING = true;
        startMonitoring();
        startServer();
        SchedulerOfflineDetect();

        btnStart.setEnabled(true);
        btnStart.setBackgroundColor(Color.BLUE);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updateViews();
            }
        });
    }

    private void resetSharedPreferences(){
        sharedPreferences.edit().clear().apply();
        Log.d("INFO", "SharedPreferences reseteado a valores por defecto");
    }

    private void loadSharedPreferences(){
        Log.d("INFO", "Update configurations");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        INITIAL_TIME_WORK = LocalTime.parse(sharedPreferences.getString("INITIAL_TIME_WORK", "00:00"), formatter);
        FINAL_TIME_WORK = LocalTime.parse(sharedPreferences.getString("FINAL_TIME_WORK", "23:50"), formatter);

        //JCL9G80 - 192.168.43.235 - producer2 - sVKV76zfoEvnAS8KCG9N
        //GIO5B97 - 192.168.43.121 - producer1 - 3e3z2IQiEDWBxNPgS09G
        //TEST - 192.168.43.97 - producer3 -

        DEVICE_ID = sharedPreferences.getString("DEVICE_ID", "GIO5B97");
        IP_DRIVER = sharedPreferences.getString("IP_DRIVER", "192.168.43.121");
        IP_SERVER = sharedPreferences.getString("IP_SERVER", "143.106.8.17");
        PORT_SERVER = sharedPreferences.getString("PORT_SERVER", "5672");
        USER_SERVER = sharedPreferences.getString("USER_SERVER", "producer1");
        PASSWORD_SERVER = sharedPreferences.getString("PASSWORD_SERVER", "3e3z2IQiEDWBxNPgS09G");
        TEMP_MIN = sharedPreferences.getInt("TEMP_MIN",30);
        TEMP_MAX = sharedPreferences.getInt("TEMP_MAX",35);
        BATTERY_MIN = sharedPreferences.getInt("BATTERY_MIN",40);
        BATTERY_MAX = sharedPreferences.getInt("BATTERY_MAX",90);
    }

    private void saveSharedPreferences(){
        SharedPreferences.Editor editor = sharedPreferences.edit();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        String initialTimeFormatted = INITIAL_TIME_WORK.format(formatter);
        String finalTimeFormatted = FINAL_TIME_WORK.format(formatter);

        editor.putString("INITIAL_TIME_WORK", initialTimeFormatted);
        editor.putString("FINAL_TIME_WORK", finalTimeFormatted);
        editor.putString("DEVICE_ID", DEVICE_ID);
        editor.putString("IP_DRIVER", IP_DRIVER);
        editor.putString("IP_SERVER", IP_SERVER);
        editor.putString("PORT_SERVER", PORT_SERVER);
        editor.putString("USER_SERVER", USER_SERVER);
        editor.putString("PASSWORD_SERVER", PASSWORD_SERVER);
        editor.putInt("TEMP_MIN", TEMP_MIN);
        editor.putInt("TEMP_MAX", TEMP_MAX);
        editor.putInt("BATTERY_MIN", BATTERY_MIN);
        editor.putInt("BATTERY_MAX", BATTERY_MAX);
        editor.commit();
    }

    private void updateViews(){
        locationView.update(locationModel.toString());
        deviceView.update(deviceModel.toString());
        externalView.update(externalModel.toString());
        inertialView.update(inertialModel.toString());
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AmqpService.MyBinder binder = (AmqpService.MyBinder) service;
            amqpService = binder.getService();
            amqpService.setCallbacks(MainActivity.this);
            bound = true;
            Log.d("INFO", "Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (amqpService != null) {
                amqpService.setCallbacks(null);
            }
            bound = false;
            Log.d("INFO", "Service disconnected");
        }
    };


    private void startServer() {
        ServerThread serverThread = new ServerThread(8888,this);
        serverThread.start();
    }

    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, 200);
            }
        }
    }

    public void enableHotspot(boolean enable) {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d("INFO", "Android version below 8");
            try {
                // Desactiva el Wi-Fi antes de habilitar el hotspot
                wifiManager.setWifiEnabled(!enable);
                // Accede al método 'setWifiApEnabled()' usando reflexión
                Method setWifiApEnabledMethod = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                // Habilita o deshabilita el hotspot
                setWifiApEnabledMethod.invoke(wifiManager, null, enable);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            Log.d("INFO", "Android version 8 or higher");
            // Para Android 8.0 y versiones posteriores, se requiere un método diferente
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                @SuppressLint("SoonBlockedPrivateApi") Field iConnectivityManagerField = connectivityManager.getClass().getDeclaredField("mService");
                iConnectivityManagerField.setAccessible(true);
                Object iConnectivityManager = iConnectivityManagerField.get(connectivityManager);

                Method startTetheringMethod = iConnectivityManager.getClass().getMethod("startTethering", int.class, ResultReceiver.class, boolean.class);

                if (enable) {
                    wifiManager.setWifiEnabled(false);
                    startTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE, null, true);
                } else {
                    Method stopTetheringMethod = iConnectivityManager.getClass().getMethod("stopTethering", int.class);
                    stopTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE);
                    Thread.sleep(15000);
                    wifiManager.setWifiEnabled(true);
                }
            } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException | InterruptedException e) {
                e.printStackTrace();
                Log.e("ERROR", "WiFi Hotspot Error");
            }
        }
    }

    private void addJSON(JSONObject j2, JSONObject j1){
        if(j1 != null) {
            Iterator<String> keys = j1.keys();
            while (keys.hasNext()){
                String key = keys.next();
                try {
                    j2.put(key, j1.get(key));
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Crash adding JSON");
                }
            }
        }
    }

    public JSONObject removeKeysFromJSONObject(JSONObject jsonObject, String[] keysToRemove) {
        for (String key : keysToRemove) {
            jsonObject.remove(key);
        }
        return jsonObject;
    }

    private JSONObject concatJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "realtime");
            json.put("device_id", DEVICE_ID);
            json.put("timestamp_sys", System.currentTimeMillis());
            if(locationModel.isState())
                addJSON(json, removeKeysFromJSONObject(locationModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(inertialModel.isState())
                addJSON(json, removeKeysFromJSONObject(inertialModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            if(deviceModel.isState())
                addJSON(json, removeKeysFromJSONObject(deviceModel.toJSON(), new String[]{"type", "device_id", "timestamp_sys"}));
            for (Map.Entry<String, String> entry : externalModel.getAllDeviceMessages().entrySet()) {
                String type = entry.getKey();
                String message = entry.getValue();
                JSONObject messageJson = new JSONObject(message);
                messageJson.remove("type");
                json.put(type, messageJson);
            }
            externalModel.clearAllDeviceMessages();
            return json;

        } catch (JSONException e) {
            e.printStackTrace();
            Log.e("ERROR", "Failed to parse JSON" + e);
        }
        return null;
    }

    private void startMonitoring() {
        requestLocationPermissions();
    }

    private void requestLocationPermissions() {
        ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startServices();
                Log.d("INFO", "Services started");
            } else {
                Toast.makeText(this, "Permissions not granted. The app will not function properly", Toast.LENGTH_LONG).show();
                Log.e("ERROR", "Permissions not granted. The app will not function properly");
            }
        }
    }

    private void startServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        ContextCompat.startForegroundService(this, serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        ContextCompat.startForegroundService(this, serviceIntent3);

        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        ContextCompat.startForegroundService(this, serviceIntent4);
    }

    private void stopServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        stopService(serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        stopService(serviceIntent2);

        Intent serviceIntent3 = new Intent(this, DeviceService.class);
        stopService(serviceIntent3);

        Intent serviceIntent4 = new Intent(this, AmqpService.class);
        stopService(serviceIntent4);
    }

    private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            Location location = (Location) intent.getParcelableExtra("update_location");
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude((float) location.getLatitude());
            locationModel.setLongitude((float) location.getLongitude());
            locationModel.setBearing((float) location.getBearing());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude((float) location.getAltitude());
            locationModel.setAccuracy_hor((int) location.getAccuracy());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                locationModel.setAccuracy_ver((int) location.getVerticalAccuracyMeters());
                locationModel.setAccuracy_bea((int) location.getBearingAccuracyDegrees());
                locationModel.setAccuracy_speed((int) location.getSpeedAccuracyMetersPerSecond());
            } else {
                locationModel.setAccuracy_ver(0);
                locationModel.setAccuracy_bea(0);
                locationModel.setAccuracy_speed(0);
            }

            locationModel.setState(true);
            if (REAL_TIME_OPERATION)
                buffer.insertJson(locationModel.toJSON().toString());
        }
    };

    private BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            JSONObject last_msg_cellphone = deviceModel.toJSON();
            last_msg_cellphone.remove("timestamp_sys");
            deviceModel.setBattery(intent.getIntExtra("battery_cellphone", deviceModel.getBattery()));
            deviceModel.setTemperature(intent.getIntExtra("temperature_cellphone", deviceModel.getTemperature()));
            deviceModel.setRssi_device(intent.getIntExtra("signal_cellphone", deviceModel.getRssi_device()));
            deviceModel.setWifi_status(intent.getIntExtra("hotspot_cellphone", deviceModel.getWifi_status()));
            String networkType = intent.getStringExtra("network_cellphone");
            if(networkType == null)
                networkType = deviceModel.getNetwork_type();
            deviceModel.setNetwork_type(networkType);
            JSONObject actual_msg_cellphone = deviceModel.toJSON();
            actual_msg_cellphone.remove("timestamp_sys");
            if(!last_msg_cellphone.toString().equals(actual_msg_cellphone.toString())){
                deviceModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(deviceModel.toJSON().toString());
                LEVEL_BATTERY = deviceModel.getBattery();
                TEMPERATURE_BATTERY = deviceModel.getTemperature();
            }
        }
    };

    private BroadcastReceiver externalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String jsonString_external = intent.getStringExtra("update_external_sensors");
            if (jsonString_external != null) {
                try {
                    JSONObject jsonObject = new JSONObject(jsonString_external);
                    String type = jsonObject.getString("type");
                    externalModel.updateDeviceMessage(type, jsonString_external);
                    if(REAL_TIME_OPERATION) {
                        jsonObject.put("timestamp_sys", System.currentTimeMillis());
                        jsonObject.put("device_id", MainActivity.DEVICE_ID);
                        buffer.insertJson(jsonObject.toString());
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("ERROR", "Error parsing JSON from external sensors", e);
                }
            }
        }
    };

    private BroadcastReceiver inertialReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            float[] values = intent.getFloatArrayExtra("update_inertial_sensors");
            int type = intent.getIntExtra("type_inertial_sensors", 0);
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    inertialModel.setAccelerometerValues(values);
                    updAcc = true;
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    inertialModel.setGyroscopeValues(values);
                    updGyr = true;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    inertialModel.setMagnetometerValues(values);
                    updMag = true;
                    break;
            }
            if(updAcc && updGyr && updMag){
                inertialModel.setState(true);
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(inertialModel.toJSON().toString());
                updAcc = updGyr = updMag = false;
            }
        }
    };

    @Override
    protected void onDestroy(){
        super.onDestroy();

        Log.d("INFO", "MainActivity destroyed. Scheduling restart...");
        Intent restartIntent = new Intent(getApplicationContext(), MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(), 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10000, pendingIntent);

        unregisterReceiver(locationReceiver);
        unregisterReceiver(inertialReceiver);
        unregisterReceiver(cellphoneReceiver);
        unregisterReceiver(externalReceiver);
        stopServices();
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }

        buffer.closeExecutor();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    public void SchedulerOfflineDetect() {
        schedulerOffline = scheduler.scheduleAtFixedRate(() -> {
            if (START_MONITORING) {
                REAL_TIME_OPERATION = ScheduleOffLine.isOverWorkDay(FINAL_TIME_WORK, INITIAL_TIME_WORK);
                Log.d("INFO", "Real-Time status: " + REAL_TIME_OPERATION);
                if (!REAL_TIME_OPERATION) {
                    try {
                        ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE2_ON);
                        ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE1_ON);
                        enableHotspot(false);
                        Future<Boolean> sendDataFuture = ScheduleOffLine.sendDataOfflineById(buffer);
                        while (!sendDataFuture.isDone() && !REAL_TIME_OPERATION) {
                            REAL_TIME_OPERATION = ScheduleOffLine.isOverWorkDay(FINAL_TIME_WORK, INITIAL_TIME_WORK);
                            Log.d("INFO", "Real-Time status: " + REAL_TIME_OPERATION);
                            Thread.sleep(60000);
                        }
                        enableHotspot(true);
                        REAL_TIME_OPERATION = true;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Log.e("ERROR", "Real-Time status: " + REAL_TIME_OPERATION);
                    }
                }
                else{
                    ScheduleOffLine.isDeviceConnected(IP_DRIVER);
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public JSONObject getCurrentData() {
        return concatJSON();
    }

}*/
