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
import android.content.pm.PackageManager;
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

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;


public class MainActivity extends AppCompatActivity implements ServiceCallbacks {
    private SensorDataModel sensorDataModel;
    private SensorDataView sensorDataView;

    private LocationModel locationModel;
    private LocationView locationView;

    private CellphoneDataModel cellphoneDataModel;
    private CellphoneDataView cellphoneDataView;

    private ExternalDataModel CANModel;
    private ExternalDataModel ClimaticModel;


    private JSONDatabaseHelper3 buffer;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 101;
    private boolean updAcc = false, updGyr = false, updMag = false;

    private Button btnStart;
    private TextView count_txt;

    public boolean isHotspotEnabled = true;
    private MqttService mqttService;
    private boolean bound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        btnStart = this.findViewById(R.id.btn_start);

        sensorDataModel = new SensorDataModel();
        sensorDataView = new SensorDataView(this);

        locationModel = new LocationModel();
        locationView = new LocationView(this);

        cellphoneDataModel = new CellphoneDataModel(-999,-999,-999);
        cellphoneDataView = new CellphoneDataView(this);

        CANModel = new ExternalDataModel(null);
        ClimaticModel = new ExternalDataModel(null);

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.unicamp.moview_v1.SEND_LOCATION");
        registerReceiver(locationReceiver, filter);
        filter.addAction("com.unicamp.moview_v1.SEND_INERTIAL_SENSORS");
        registerReceiver(inertialReceiver, filter);
        filter.addAction("com.unicamp.moview_v1.SEND_CELLPHONE_SENSORS");
        registerReceiver(cellphoneReceiver, filter);
        //filter.addAction("com.unicamp.moview_v1.SEND_EXTERNAL_SENSORS");
        //registerReceiver(externalReceiver, filter);

        requestWriteSettingsPermission();

        buffer = new JSONDatabaseHelper3(this);
        bindService(new Intent(this, MqttService.class), serviceConnection, Context.BIND_AUTO_CREATE);

        if (isHotspotEnabled) {
            enableHotspot(true);
            Log.d("TAG", "Hotspot ACTIVADO...");
        } else {
            enableHotspot(false);
        }

        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);

        PowerManager powerManager = (PowerManager) this.getSystemService(POWER_SERVICE);
        if(powerManager.isIgnoringBatteryOptimizations(this.getPackageName()))
            Log.d("TAG", "Si tiene el permiso...");
        else
            Log.d("TAG", "No tiene el permiso...");


        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StartMonitoring();
                startServer();
            }
        });
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
        // Comprueba si la versión de Android es anterior a Oreo (Android 8.0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
            // Para Android 8.0 y versiones posteriores, se requiere un método diferente
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            try {
                @SuppressLint("SoonBlockedPrivateApi") Field iConnectivityManagerField = connectivityManager.getClass().getDeclaredField("mService");
                iConnectivityManagerField.setAccessible(true);
                Object iConnectivityManager = iConnectivityManagerField.get(connectivityManager);

                Method startTetheringMethod = iConnectivityManager.getClass().getMethod("startTethering", int.class, ResultReceiver.class, boolean.class);

                if (enable) {
                    // Habilita el hotspot
                    startTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE, null, true);
                } else {
                    // Deshabilita el hotspot
                    Method stopTetheringMethod = iConnectivityManager.getClass().getMethod("stopTethering", int.class);
                    stopTetheringMethod.invoke(iConnectivityManager, ConnectivityManager.TYPE_MOBILE);
                }
            } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                e.printStackTrace();
            }
        }
    }

    private void addJSON(JSONObject j2, JSONObject j1){
        if(j1 != null) {
            Iterator<String> keys = j1.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    j2.put(key, j1.get(key));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private JSONObject concatJSON() {
        try {
            JSONObject result = new JSONObject();
            JSONObject json1 = locationModel.toJSON();
            JSONObject json2 = sensorDataModel.toJSON();
            JSONObject json3 = cellphoneDataModel.toJSON();
            //JSONObject json4 = CANModel.toJSON();
            //Log.d("TAG", "Add JSON..." + CANModel.getMessage());
            addJSON(result,  json1);
            addJSON(result,  json2);
            addJSON(result,  json3);
            //addJSON(result,  json4);

            result.put("type", "realtime");
            //Log.d("TAG", "Creating JSON..." + result);
            return result;
        } catch (JSONException e) {
            e.printStackTrace();
            Log.d("TAG", "Creating JSON..." + e);
        }
        return null;
    }

    private void StartMonitoring() {
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
        Intent serviceIntent1 = new Intent(this, GPSService.class);
        ContextCompat.startForegroundService(this, serviceIntent1);

        Intent serviceIntent2 = new Intent(this, SensorsService.class);
        ContextCompat.startForegroundService(this, serviceIntent2);

        Intent serviceIntent3 = new Intent(this, CellphoneService.class);
        ContextCompat.startForegroundService(this, serviceIntent3);

        Intent serviceIntent4 = new Intent(this, MqttService.class);
        ContextCompat.startForegroundService(this, serviceIntent4);
    }

    private void stopServices() {
        Intent serviceIntent1 = new Intent(this, GPSService.class);
        stopService(serviceIntent1);

        Intent serviceIntent2 = new Intent(this, SensorsService.class);
        stopService(serviceIntent2);

        Intent serviceIntent3 = new Intent(this, CellphoneService.class);
        stopService(serviceIntent3);

        Intent serviceIntent4 = new Intent(this, MqttService.class);
        stopService(serviceIntent4);
    }


    private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.d("TAG", "Location Service ON");
            Location location = (Location) intent.getParcelableExtra("update_location");
            locationModel.setTimestamp(location.getTime());
            locationModel.setLatitude(location.getLatitude());
            locationModel.setLongitude(location.getLongitude());
            locationModel.setSpeed(location.getSpeed());
            locationModel.setAltitude(location.getAltitude());
            JSONObject msg_gps = locationModel.toJSON();
            //locationView.update(msg_gps);
            buffer.insertJson(msg_gps.toString());
        }
    };

    private BroadcastReceiver cellphoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.d("TAG", "Cellphone Service ON");
            int battery = intent.getIntExtra("battery_cellphone", cellphoneDataModel.getBattery());
            int temperature = intent.getIntExtra("temperature_cellphone", cellphoneDataModel.getTemperature());
            cellphoneDataModel.setBattery(battery);
            cellphoneDataModel.setTemperature(temperature);
            JSONObject msg_cellphone = cellphoneDataModel.toJSON();

            cellphoneDataView.update(msg_cellphone);
            buffer.insertJson(msg_cellphone.toString());
        }
    };

    private BroadcastReceiver externalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.d("TAG", "External Service ON");
            String message_external = intent.getStringExtra("update_external_sensors");
            if (message_external != null) {
                //Log.d("TAG", "Message: " + message_external);
                CANModel.setMessage(message_external);
                buffer.insertJson(message_external);
            }
        }
    };

    private BroadcastReceiver inertialReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.d("TAG", "Inertial Service ON");
            float[] values = intent.getFloatArrayExtra("update_inertial_sensors");
            int type = intent.getIntExtra("type_inertial_sensors", 0);
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    sensorDataModel.setAccelerometerValues(values);
                    updAcc = true;
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    sensorDataModel.setGyroscopeValues(values);
                    updGyr = true;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    sensorDataModel.setMagnetometerValues(values);
                    updMag = true;
                    break;
            }
            if(updAcc && updGyr && updMag){
                JSONObject msg_sensor = sensorDataModel.toJSON();
                //sensorDataView.update(msg_sensor);
                buffer.insertJson(msg_sensor.toString());
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
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public JSONObject getCurrentDataMqtt() {
        //Log.d("TAG", "MQTT Service ON");
        return concatJSON();
    }
}