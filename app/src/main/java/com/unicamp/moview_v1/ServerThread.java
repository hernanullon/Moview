package com.unicamp.moview_v1;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

public class ServerThread extends Thread {
    private final int port;
    private final Context context;

    public ServerThread(int port, Context context) {
        this.port = port;
        this.context = context.getApplicationContext();
    }

    @Override
    public void run() {
        try {
            ServerSocket serverSocket = new ServerSocket(port);
            while (!Thread.currentThread().isInterrupted()) {
                Socket socket = serverSocket.accept();
                InetAddress inetAddress = socket.getInetAddress();
                //Log.d("TAG", "Nuevo dispositivo conectado " + inetAddress.toString());
                if(!inetAddress.toString().equals(MainActivity.IP_DRIVER)) {
                    NetworkThread networkThread = new NetworkThread(socket, port, context);
                    networkThread.start();
                }
            }
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
