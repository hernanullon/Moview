package com.unicamp.moview_v1;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

public class LocationController implements LocationListener {
    private Context context;
    private LocationManager locationManager;
    private LocationModel locationModel;
    private LocationView locationView;

    public LocationController(Context context,LocationManager locationManager,LocationModel locationModel, LocationView locationView) {
        this.context = context;
        this.locationModel = locationModel;
        this.locationView = locationView;
        this.locationManager = locationManager;
    }


    public void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } else {
            // Manejar el caso en el que no se conceda el permiso
        }
    }

    public void stopLocationUpdates() {
        locationManager.removeUpdates(this);
    }

    @Override
    public void onLocationChanged(Location location) {
        locationModel.setTimestamp(location.getTime());
        locationModel.setLatitude(location.getLatitude());
        locationModel.setLongitude(location.getLongitude());
        locationModel.setSpeed(location.getSpeed());
        locationModel.setAltitude(location.getAltitude());

        locationView.update(locationModel.toJSON());
    }


    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }


}
