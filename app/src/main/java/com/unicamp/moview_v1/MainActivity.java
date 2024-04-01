package com.unicamp.moview_v1;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.annotation.SuppressLint;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


public class MainActivity extends AppCompatActivity implements ServiceCallbacks {
    private InertialModel inertialModel;
    private InertialView inertialView;

    private LocationModel locationModel;
    private LocationView locationView;

    private CellphoneModel cellphoneModel;
    private CellphoneView cellphoneView;

    private ExternalModel externalModel;
    private ExternalView externalView;

    private JSONDatabaseHelper buffer;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private boolean updAcc = false, updGyr = false, updMag = false;
    private Button btnStart;

    private MqttService mqttService;
    private boolean bound = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> schedulerOffline;

    public static boolean REAL_TIME_OPERATION = true;
    public static boolean START_MONITORING = false;
    public static String DEVICE_ID;
    public static float LATITUDE_FINAL, LONGITUDE_FINAL;
    public static LocalTime FINAL_TIME_WORK, INITIAL_TIME_WORK;
    public static int BATTERY_MAX, BATTERY_MIN, TEMP_MAX, TEMP_MIN;
    public static int LEVEL_BATTERY = -1;
    public static int TEMPERATURE_BATTERY = -1;
    public static String IP_DRIVER;

    public static SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPreferences = getSharedPreferences("Config", MODE_PRIVATE);
        loadSharedPreferences();
        saveSharedPreferences();

        TextView txt_view = this.findViewById(R.id.textview_device_id);
        txt_view.setText(DEVICE_ID);

        btnStart = this.findViewById(R.id.btn_start);
        btnStart.setEnabled(false);
        btnStart.setBackgroundColor(Color.GRAY);

        inertialModel = new InertialModel();
        inertialView = new InertialView(this);

        locationModel = new LocationModel();
        locationView = new LocationView(this);

        cellphoneModel = new CellphoneModel();
        cellphoneView = new CellphoneView(this);

        externalModel = new ExternalModel();
        externalView = new ExternalView(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.moview_v1.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.moview_v1.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.moview_v1.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        filter.addAction("com.unicamp.moview_v1.SEND_EXTERNAL_SENSORS");
        registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper(this);
        bindService(new Intent(this, MqttService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);

        PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
        if(powerManager.isIgnoringBatteryOptimizations(this.getPackageName()))
            Log.d("TAG", "Si tiene el permiso...");
        else
            Log.d("TAG", "No tiene el permiso...");

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

    private void loadSharedPreferences(){
        Log.d("TAG", "UPDATE CONFIGURATIONS");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        Map<String, ?> allEntries = sharedPreferences.getAll();
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            Log.d("TAG", entry.getKey() + ": " + entry.getValue().toString());
        }
        INITIAL_TIME_WORK = LocalTime.parse(sharedPreferences.getString("INITIAL_TIME_WORK", "04:45"), formatter);
        FINAL_TIME_WORK = LocalTime.parse(sharedPreferences.getString("FINAL_TIME_WORK", "06:45"), formatter);
        DEVICE_ID = sharedPreferences.getString("DEVICE_ID", "MMSTEST");
        IP_DRIVER = sharedPreferences.getString("IP_DRIVER", "192.168.1.1");
        TEMP_MIN = sharedPreferences.getInt("TEMP_MIN",15);
        TEMP_MAX = sharedPreferences.getInt("TEMP_MAX",25);
        BATTERY_MIN = sharedPreferences.getInt("BATTERY_MIN",40);
        BATTERY_MAX = sharedPreferences.getInt("BATTERY_MAX",90);
    }

    private void saveSharedPreferences(){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        SharedPreferences.Editor editor = sharedPreferences.edit();
        String initialTimeFormatted = INITIAL_TIME_WORK.format(formatter);
        String finalTimeFormatted = FINAL_TIME_WORK.format(formatter);
        editor.putString("INITIAL_TIME_WORK", initialTimeFormatted);
        editor.putString("FINAL_TIME_WORK", finalTimeFormatted);
        editor.putString("DEVICE_ID", DEVICE_ID);
        editor.putString("IP_DRIVER", IP_DRIVER);
        editor.putInt("TEMP_MIN", TEMP_MIN);
        editor.putInt("TEMP_MAX", TEMP_MAX);
        editor.putInt("BATTERY_MIN", BATTERY_MIN);
        editor.putInt("BATTERY_MAX", BATTERY_MAX);
        editor.commit();
    }

    private void updateViews(){
        locationView.update(locationModel.toString());
        cellphoneView.update(cellphoneModel.toString());
        externalView.update(externalModel.toString());
        inertialView.update(inertialModel.toString());
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MqttService.MyBinder binder = (MqttService.MyBinder) service;
            mqttService = binder.getService();
            mqttService.setCallbacks(MainActivity.this);
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mqttService.setCallbacks(null);
            bound = false;
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
        // Comprueba si la versión de Android es anterior a Oreo (Android 8.0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d("TAG", "VERSION ANDROID < 8...");
            try {
                // Desactiva el Wi-Fi antes de habilitar el hotspot
                wifiManager.setWifiEnabled(!enable);
                // Accede al método 'setWifiApEnabled()' usando reflexión
                Method setWifiApEnabledMethod = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                // Habilita o deshabilita el hotspot
                setWifiApEnabledMethod.invoke(wifiManager, null, enable);
                Log.d("TAG", "VERSION ANDROID 8...");
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                e.printStackTrace();
            }
        } else {
            Log.d("TAG", "VERSION ANDROID 8...");
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
                Log.d("TAG", "ERROR WIFI - HOTSPOT");
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
                    Log.d("TAG", "CRASH ADD JSON..." + j1.toString());
                }
            }
        }
    }

    private JSONObject concatJSON() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", "realtime");
            json.put("device_id", DEVICE_ID);
            JSONObject smartphone = new JSONObject();
            addJSON(smartphone,  locationModel.toJSON());
            addJSON(smartphone,  inertialModel.toJSON());
            addJSON(smartphone,  cellphoneModel.toJSON());
            smartphone.remove("device_id");
            long timestamp_sys = (long) smartphone.remove("timestamp_sys");
            smartphone.remove("type");
            json.put("timestamp_sys", timestamp_sys);
            json.put("smartphone", smartphone);

            for (Map.Entry<String, String> entry : externalModel.getAllDeviceMessages().entrySet()) {
                String type = entry.getKey();
                String message = entry.getValue();
                JSONObject messageJson = new JSONObject(message);
                messageJson.remove("type");
                messageJson.remove("device_id");
                json.put(type, messageJson);
            }
            externalModel.clearAllDeviceMessages();
            return json;

        } catch (JSONException e) {
            e.printStackTrace();
            Log.d("TAG", "FAIL PARSE JSON" + e);
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
                Log.d("TAG", "Servicios iniciados...");
            } else {
                Toast.makeText(this, "Permisos no otorgados. La aplicación no funcionará correctamente.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        ContextCompat.startForegroundService(this, serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        ContextCompat.startForegroundService(this, serviceIntent2);

        Intent serviceIntent3 = new Intent(this, CellphoneService.class);
        ContextCompat.startForegroundService(this, serviceIntent3);

        Intent serviceIntent4 = new Intent(this, MqttService.class);
        ContextCompat.startForegroundService(this, serviceIntent4);
    }

    private void stopServices() {
        Intent serviceIntent1 = new Intent(this, LocationService.class);
        stopService(serviceIntent1);

        Intent serviceIntent2 = new Intent(this, InertialService.class);
        stopService(serviceIntent2);

        Intent serviceIntent3 = new Intent(this, CellphoneService.class);
        stopService(serviceIntent3);

        Intent serviceIntent4 = new Intent(this, MqttService.class);
        stopService(serviceIntent4);
    }

    private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Location location = (Location) intent.getParcelableExtra("update_location");
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude(location.getLatitude());
            locationModel.setLongitude(location.getLongitude());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude(location.getAltitude());
            locationModel.setAccuracy(location.getAccuracy());
            if(REAL_TIME_OPERATION)
                buffer.insertJson(locationModel.toJSON().toString());
        }
    };

    private BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int battery = intent.getIntExtra("battery_cellphone", cellphoneModel.getBattery());
            int temperature = intent.getIntExtra("temperature_cellphone", cellphoneModel.getTemperature());
            int signalLevel = intent.getIntExtra("signal_cellphone", cellphoneModel.getSignalStrength());
            int hotspot_enable = intent.getIntExtra("hotspot_cellphone", cellphoneModel.getWifi_status());
            String networkType = intent.getStringExtra("network_cellphone");
            if(networkType == null)
                networkType = cellphoneModel.getNetwork_type();

            JSONObject last_msg_cellphone = cellphoneModel.toJSON();
            cellphoneModel.setBattery(battery);
            cellphoneModel.setTemperature(temperature);
            cellphoneModel.setSignalStrength(signalLevel);
            cellphoneModel.setNetwork_type(networkType);
            cellphoneModel.setWifi_status(hotspot_enable);
            JSONObject actual_msg_cellphone = cellphoneModel.toJSON();

            if(!last_msg_cellphone.toString().equals(actual_msg_cellphone.toString())){
                try {
                    actual_msg_cellphone.put("timestamp_sys", System.currentTimeMillis());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                LEVEL_BATTERY = battery;
                TEMPERATURE_BATTERY = temperature;
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(actual_msg_cellphone.toString());
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
                    jsonObject.put("timestamp_sys", System.currentTimeMillis());
                    jsonObject.put("device_id", MainActivity.DEVICE_ID);
                    if(REAL_TIME_OPERATION)
                        buffer.insertJson(jsonObject.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                    Log.e("TAG", "Error parsing JSON from external sensors", e);
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
                if(REAL_TIME_OPERATION)
                    buffer.insertJson(inertialModel.toJSON().toString());
                updAcc = updGyr = updMag = false;
            }
        }
    };

    @Override
    protected void onDestroy(){
        super.onDestroy();
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
                Log.d("TAG", "Real Time: " + REAL_TIME_OPERATION);
                if (!REAL_TIME_OPERATION) {
                    try {
                        ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE2_ON);
                        ScheduleOffLine.sendTcpMessage(ScheduleOffLine.RELE1_ON);
                        enableHotspot(false);
                        Future<Boolean> sendDataFuture = ScheduleOffLine.sendDataOfflineById(buffer);
                        while (!sendDataFuture.isDone() && !REAL_TIME_OPERATION) {
                            REAL_TIME_OPERATION = ScheduleOffLine.isOverWorkDay(FINAL_TIME_WORK, INITIAL_TIME_WORK);
                            Thread.sleep(60000);
                        }
                        enableHotspot(true);
                        REAL_TIME_OPERATION = true;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                else{
                    ScheduleOffLine.isDeviceConnected(IP_DRIVER);
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public JSONObject getCurrentDataMqtt() {
        return concatJSON();
    }




}