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
    public static final byte[] RELE1_ON = { (byte) 0xA0, 0x01, 0x01, (byte) 0xA2 };
    public static final byte[] RELE1_OFF = { (byte) 0xA0, 0x01, 0x00, (byte) 0xA1 };
    public static final byte[] RELE2_ON = { (byte) 0xA0, 0x02, 0x01, (byte) 0xA3 };
    public static final byte[] RELE2_OFF = { (byte) 0xA0, 0x02, 0x00, (byte) 0xA2 };

    public static Future<Boolean> sendDataOfflineById(JSONDatabaseHelper buffer) {
        return Executors.newSingleThreadExecutor().submit(() -> {
            long startTime = System.currentTimeMillis();
            int packetsSent = 0;
            Pair<JSONObject, Integer> jsonWithId;
            ZonedDateTime time_now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
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

            JSONObject jsonResume = new JSONObject();
            jsonResume.put("type","offline_data");
            jsonResume.put("timestamp_sys",time_now.toLocalTime().withNano(0));
            jsonResume.put("device_id","B-Test");
            jsonResume.put("number_packets",packetsSent);
            jsonResume.put("total_time",totalTime);
            MqttService.sendMessageMQTT(jsonResume);

            return true;
        });
    }

    //Error: A 400 Bad Request error occurred: {"error":"unable to parse 'inertial,device_id=MMS-001': missing fields"}
    //{"topic":"unicamp/onibus/inertial","payload":{"timestamp_sys":1711733032008,"device_id":"MMS-001","type":"inertial","accX":1.290474772453308,"accY":2.786850690841675,"accZ":9.346963882446289,"gyrX":-0.04337143152952194,"gyrY":0.0555887371301651,"gyrZ":0.05803219974040985,"magX":-65.5199966430664,"magY":82.0199966430664,"magZ":57.959999084472656},"qos":0,"retain":false,"_msgid":"345f97a6bb19089e"}

    public static boolean isOverWorkDay(LocalTime finalTimeWork, LocalTime initialTimeWork){
        ZonedDateTime time_now = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
        LocalTime hnow = time_now.toLocalTime().withSecond(0).withNano(0);
        LocalTime hfinal = finalTimeWork.withSecond(0).withNano(0);
        LocalTime hinitial = initialTimeWork.withSecond(0).withNano(0);
        Log.d("TAG", "H: " + hinitial.toString() + " - " + hnow.toString() + " - " + hfinal.toString()  );
        if (hnow.isAfter(hinitial) && hnow.isBefore(hfinal)){
            Log.d("TAG", "Real Time OFF" );
            return false;
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

    public static void select_actions_rele(){
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

    public static void sendTcpMessage(byte[] message) {
        Socket clientSocket = null;
        DataOutputStream outputStream = null;
        try {
            Log.d("TAG", "Mensaje Preparado" + Arrays.toString(message));
            clientSocket = new Socket(MainActivity.IP_DRIVER, 8080);
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
