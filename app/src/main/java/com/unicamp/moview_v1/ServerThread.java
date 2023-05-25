package com.unicamp.moview_v1;

import android.content.Context;
import android.os.Handler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

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
                NetworkThread networkThread = new NetworkThread(socket, context);
                networkThread.start();
            }
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}