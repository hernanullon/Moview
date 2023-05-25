package com.unicamp.moview_v1;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class SensorDataController implements SensorEventListener {
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor magnetometer;
    private SensorDataModel sensorDataModel;
    private SensorDataView sensorDataView;

    public SensorDataController(SensorManager sensorManager, SensorDataModel sensorDataModel, SensorDataView sensorDataView) {
        this.sensorManager = sensorManager;

        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        this.magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        this.sensorDataModel = sensorDataModel;
        this.sensorDataView = sensorDataView;
    }

    public void start() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void stop() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values;
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                sensorDataModel.setAccelerometerValues(values);
                break;
            case Sensor.TYPE_GYROSCOPE:
                sensorDataModel.setGyroscopeValues(values);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                sensorDataModel.setMagnetometerValues(values);
                break;
        }
        sensorDataView.update(sensorDataModel.toJSON());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
