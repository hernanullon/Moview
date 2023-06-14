package com.unicamp.moview_v1;

import android.location.Location;
import android.util.Log;

import androidx.core.util.Pair;

import org.json.JSONObject;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class ScheduleOffLine {

    public static Future<Boolean> sendDataOfflineById(JSONDatabaseHelper buffer) {
        return Executors.newSingleThreadExecutor().submit(() -> {
            long startTime = System.currentTimeMillis();
            int packetsSent = 0;
            Pair<JSONObject, Integer> jsonWithId;
            do {
                jsonWithId = buffer.getLastJsonWithId().get();
                JSONObject jsonObject = jsonWithId.first;
                if (jsonObject != null) {
                    jsonObject.put("device_id", MainActivity.DEVICE_ID);
                    MqttService.sendMessageMQTT(jsonObject);
                    packetsSent++;
                }
            } while (jsonWithId.second > 1 && !MainActivity.REAL_TIME_OPERATION);

            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            Log.d("TAG", "Paquetes enviados: " + packetsSent);
            Log.d("TAG", "Tiempo total de envío: " + totalTime + " ms");

            return true;
        });
    }


    public static Future<Boolean> sendDataOffline(JSONDatabaseHelper buffer) {
        return Executors.newSingleThreadExecutor().submit(() -> {
            long startTime = System.currentTimeMillis();
            int lastId = buffer.getJsonCount().get();
            Log.d("TAG", "Query Count: ");
            int packetsSent = 0;

            for (int currentId = 1; currentId <= lastId && !MainActivity.REAL_TIME_OPERATION; currentId++) {
                JSONObject jsonObject = buffer.getLastJson().get();
                if (jsonObject != null) {

                    jsonObject.put("device_id", MainActivity.DEVICE_ID);
                    MqttService.sendMessageMQTT(jsonObject);
                    //Log.d("TAG", "Descargado: " + jsonObject.toString());
                    packetsSent++;
                }
            }
            long endTime = System.currentTimeMillis();
            long totalTime = endTime - startTime;
            Log.d("TAG", "Paquetes enviados: " + packetsSent);
            Log.d("TAG", "Tiempo total de envío: " + totalTime + " ms");
            return true;
        });
    }

//    public static Future<Boolean> sendDataOffline(JSONDatabaseHelper buffer){
//        return Executors.newSingleThreadExecutor().submit(() -> {
//            Log.d("TAG", "Antes: ");
//            try {
//                int lastId = buffer.getJsonCount().get();
//                int currentId = 1;
//                Log.d("TAG", "Despues Count: ");
//                while (currentId <= lastId && !MainActivity.REAL_TIME_OPERATION) {
//                    JSONObject jsonObject = buffer.getLastJson().get();
//                    if (jsonObject != null) {
//                        // Enviar el elemento JSON a través de MQTT
//                        jsonObject.put("device_id", MainActivity.DEVICE_ID);
//                        Log.d("TAG", "Descargando: " + jsonObject.toString());
//                        MqttService.sendMessageMQTT(jsonObject);
//                        Log.d("TAG", "Enviado MQTT");
//                    }
//                    currentId++;
//                }
//                return true;
//            } catch (InterruptedException | ExecutionException e) {
//                return false;
//            }
//        });
//    }


    public static boolean isOverWorkDay(double lat1, double lon1, double lat2, double lon2, LocalTime finalTimeWork, LocalTime initialTimeWork){
        ZonedDateTime time_now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
        Location loc1 = new Location("");
        Location loc2 = new Location("");
        loc1.setLatitude(lat1); loc2.setLatitude(lat2);
        loc1.setLongitude(lon1); loc2.setLongitude(lon2);
        float distance = loc1.distanceTo(loc2);
        Log.d("TAG", "Distancia: " + String.valueOf(distance));
        if(distance <= 100) {
            LocalTime hnow = time_now.toLocalTime().withNano(0);
            LocalTime hfinal = finalTimeWork.withNano(0);
            LocalTime hinitial = initialTimeWork.withNano(0);
            Log.d("TAG", "H: " + hnow.toString() +" - "+ hfinal.toString()+" - " + hinitial.toString() );
//            if ((hnow.isAfter(hfinal) && hnow.isBefore(hinitial))) {
//                Log.d("TAG", "Real Time OFF" );
//                return false;
//            }
            if ((hnow.isAfter(hfinal) && hnow.isBefore(LocalTime.MAX)) ||
                    (hnow.isAfter(LocalTime.MIN) && hnow.isBefore(hinitial))) {
                Log.d("TAG", "Real Time OFF" );
                return false;
            }
        }
        return true;
    }

}
