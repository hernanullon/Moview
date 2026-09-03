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

import org.threeten.bp.LocalTime;
import org.threeten.bp.ZoneId;
import org.threeten.bp.ZonedDateTime;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public class ScheduleOffLine {
    private static final int TIMEOUT = 3000;
    public static final byte[] RELE1_ON = { (byte) 0xA0, 0x01, 0x01, (byte) 0xA2 };
    public static final byte[] RELE1_OFF = { (byte) 0xA0, 0x01, 0x00, (byte) 0xA1 };
    public static final byte[] RELE2_ON = { (byte) 0xA0, 0x02, 0x01, (byte) 0xA3 };
    public static final byte[] RELE2_OFF = { (byte) 0xA0, 0x02, 0x00, (byte) 0xA2 };

    public static boolean isOfflineWindow(LocalTime start, LocalTime end) {
        ZonedDateTime nowZ = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
        LocalTime now = nowZ.toLocalTime().withSecond(0).withNano(0);
        LocalTime s = start.withSecond(0).withNano(0);
        LocalTime e = end.withSecond(0).withNano(0);

        final boolean crossesMidnight = s.isAfter(e);
        final boolean offlineNow = crossesMidnight
                ? (now.equals(s) || now.isAfter(s)) || now.isBefore(e)        // 22:00–06:00
                : ( (now.equals(s) || now.isAfter(s)) && now.isBefore(e) );   // 01:00–05:00

        Log.d("INFO", "Offline window " + s + "–" + e + " | now=" + now + " | offlineNow=" + offlineNow);
        return offlineNow;
    }

//    public static void isDeviceConnected(String ipAddress) {
//        try {
//            InetAddress inetAddress = InetAddress.getByName(ipAddress);
//            if(inetAddress.isReachable(TIMEOUT)){
//                select_actions_rele();
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    public static void isDeviceConnected(String ipAddress) {
        // Ejecuta el ping fuera del hilo principal para evitar NetworkOnMainThreadException
        new Thread(() -> {
            try {
                InetAddress inetAddress = InetAddress.getByName(ipAddress);
                if (inetAddress.isReachable(TIMEOUT)) {
                    select_actions_rele();
                }
            } catch (IOException e) {
                // Silencioso: no es crítico que falle el ping
            }
        }, "ESP-Ping").start();
    }



    public static void select_actions_rele(){
        if(MainActivity.REAL_TIME_OPERATION) {
            if (MainActivity.TEMPERATURE_BATTERY >= MainActivity.TEMP_MAX)
                sendTcpMessage(RELE2_ON);
            if (MainActivity.TEMPERATURE_BATTERY <= MainActivity.TEMP_MIN)
                sendTcpMessage(RELE2_OFF);
            if (MainActivity.LEVEL_BATTERY <= MainActivity.BATTERY_MIN)
                sendTcpMessage(RELE1_ON);
            if (MainActivity.LEVEL_BATTERY >= MainActivity.BATTERY_MAX)
                sendTcpMessage(RELE1_OFF);
        }
    }

    public static void sendTcpMessage(byte[] message) {
        Socket clientSocket = null;
        DataOutputStream outputStream = null;
        try {
            clientSocket = new Socket(MainActivity.IP_DRIVER, 8080);
            outputStream = new DataOutputStream(clientSocket.getOutputStream());
            outputStream.write(message);
            outputStream.flush();
            Thread.sleep(10000);
        } catch (IOException | InterruptedException e) {
            Log.d("INFO", "Unreachable ESP-01 device");
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



/*
    public static Future<Boolean> sendDataOfflineById(JSONDatabaseHelper buffer) {
        return Executors.newSingleThreadExecutor().submit(() -> {
            long startTime = System.currentTimeMillis();
            int packetsSent = 0;
            int failedAttempts = 0;

            try {
                while (!MainActivity.REAL_TIME_OPERATION) {
                    if (failedAttempts >= 10) {
                        Log.e("ERROR", "Demasiados intentos fallidos. Saliendo del ciclo.");
                        break;
                    }

                    Pair<JSONObject, Integer> jsonWithId = buffer.getFirstJsonWithId().get();
                    if (jsonWithId == null || jsonWithId.first == null)
                        break;

                    JSONObject jsonObject = jsonWithId.first;
                    int recordId = jsonWithId.second;

                    boolean sent = AmqpService.sendMessageAMQPWithConfirm(jsonObject);

                    if (sent) {
                        packetsSent++;
                        buffer.deleteRecordById(recordId);
                        failedAttempts = 0;
                    } else {
                        failedAttempts++;
                        Log.e("ERROR", "Fallo al enviar ID: " + recordId + ". Intento " + failedAttempts);
                        Thread.sleep(10000);
                    }
                }

                long totalTime = System.currentTimeMillis() - startTime;
                JSONObject summary = new JSONObject();
                summary.put("type", "system");
                summary.put("timestamp_sys", System.currentTimeMillis());
                summary.put("device_id", MainActivity.DEVICE_ID);
                summary.put("number_packets", packetsSent);
                summary.put("total_time", totalTime);

                AmqpService.sendMessageAMQPWithConfirm(summary);

            } catch (Exception e) {
                Log.e("ERROR", "Error inesperado en sendDataOfflineById", e);
            }

            return true;
        });
    }
*/

/*    public static boolean isOverWorkDay(LocalTime finalTimeWork, LocalTime initialTimeWork){
        ZonedDateTime time_now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
        LocalTime hnow = time_now.toLocalTime().withSecond(0).withNano(0);
        LocalTime hfinal = finalTimeWork.withSecond(0).withNano(0);
        LocalTime hinitial = initialTimeWork.withSecond(0).withNano(0);
        Log.d("INFO", "Work schedule: " + hinitial.toString() + " - " + hfinal.toString() + " Current time: " + hnow.toString());
        if (hnow.isAfter(hinitial) && hnow.isBefore(hfinal))
            return false;
        return true;
    }*/