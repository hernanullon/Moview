package com.unicamp.moview_v1;
import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class MyAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        Toast.makeText(context, "Administrador habilitado", Toast.LENGTH_SHORT).show();
        System.out.println("Administrador del dispositivo habilitado");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Toast.makeText(context, "Administrador deshabilitado", Toast.LENGTH_SHORT).show();
        System.out.println("Administrador del dispositivo deshabilitado");
    }
}
