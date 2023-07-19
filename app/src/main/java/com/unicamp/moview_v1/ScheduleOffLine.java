package com.unicamp.moview_v1;

import android.location.Location;
import android.util.Log;
import androidx.core.util.Pair;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class ScheduleOffLine {
    private static final int TIMEOUT = 3000;
    private static final byte[] RELE1_ON = { (byte) 0xA0, 0x01, 0x01, (byte) 0xA2 };
    private static final byte[] RELE1_OFF = { (byte) 0xA0, 0x01, 0x00, (byte) 0xA1 };
    private static final byte[] RELE2_ON = { (byte) 0xA0, 0x02, 0x01, (byte) 0xA3 };
    private static final byte[] RELE2_OFF = { (byte) 0xA0, 0x02, 0x00, (byte) 0xA2 };

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

    public static void isDeviceConnected(String ipAddress) {
        try {
            InetAddress inetAddress = InetAddress.getByName(ipAddress);
            if(inetAddress.isReachable(TIMEOUT)){
                select_actions_rele();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void select_actions_rele(){
        if(MainActivity.REAL_TIME_OPERATION) {
            if (MainActivity.TEMPERATURE_BATTERY > MainActivity.TEMP_MAX)
                sendTcpMessage(RELE2_ON);
            if (MainActivity.TEMPERATURE_BATTERY < MainActivity.TEMP_MIN)
                sendTcpMessage(RELE2_OFF);
            if (MainActivity.LEVEL_BATTERY < MainActivity.BATTERY_MIN)
                sendTcpMessage(RELE1_ON);
            if (MainActivity.LEVEL_BATTERY > MainActivity.BATTERY_MAX)
                sendTcpMessage(RELE1_OFF);

        }
    }

    private static void sendTcpMessage(byte[] message) {
        Socket clientSocket = null;
        DataOutputStream outputStream = null;
        try {
            Log.d("TAG", "Mensaje Preparado" + Arrays.toString(message));
            clientSocket = new Socket(MainActivity.IP_ADDRESS_RELE, 8080);
            outputStream = new DataOutputStream(clientSocket.getOutputStream());
            outputStream.write(message);
            outputStream.flush();
            Log.d("TAG", "Mensaje enviado");
            Thread.sleep(10000);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            Log.d("TAG", "Mensaje NO enviado: " + e);
        } finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
