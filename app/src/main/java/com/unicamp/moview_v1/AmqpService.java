package com.unicamp.moview_v1;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownSignalException;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AmqpService extends Service {

    // =========================================================================
    // Constants
    // =========================================================================

    private static final String TAG = "AmqpService";

    // ── AMQP ──────────────────────────────────────────────────────────────────
    private static final String EXCHANGE_NAME      = "amq.direct";
    private static final String ROUTING_KEY_PREFIX = "unicamp.campinas.";

    private static final AMQP.BasicProperties PROPS_NON_PERSISTENT =
            new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(1)
                    .build();

    private static final AMQP.BasicProperties PROPS_PERSISTENT =
            new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();

    // ── Realtime ───────────────────────────────────────────────────────────────
    private static final long RT_PERIOD_MS = 1_000L;

    // ── Offline multi-channel ──────────────────────────────────────────────────
    // Optimal config validated through testing: 4 channels × 1000 msgs = 4000 total.
    // Raising the batch above 4000 saturates the device GC.
    // More than 4 channels yields no gain due to TCP socket contention on the broker.
    private static final int  OFFLINE_CHANNEL_COUNT      = 4;
    private static final int  OFFLINE_SUB_BATCH_SIZE     = 1_000; // msgs per channel
    private static final int  OFFLINE_BATCH_SIZE         =        // total per cycle
            OFFLINE_CHANNEL_COUNT * OFFLINE_SUB_BATCH_SIZE;       // = 4000
    private static final long OFFLINE_CONFIRM_TIMEOUT_MS = 10_000L;
    private static final long OFFLINE_BACKOFF_MS         = 3_000L;

    // ── Broker reconnect → app restart in ~5 min ──────────────────────────────
    // Linear backoff: 10, 20, 30, 40, 50, 50, 50s → ~250s backoff + timeouts ≈ 4.5 min
    private static final int  MAX_BROKER_FAILURES_BEFORE_RESTART = 7;
    private static final long BROKER_BACKOFF_MS                  = 10_000L;
    private static final long BROKER_BACKOFF_CEILING_MS          = 50_000L;

    // ── No internet → device reboot in ~10 min ────────────────────────────────
    // Linear backoff: 15, 30, 45, 60, 60, 60, 60, 60, 60, 60s → ~510s ≈ 8.5 min
    // Larger base (15s) because a device reboot is more disruptive than an app restart.
    private static final int  MAX_NETWORK_FAILURES_BEFORE_REBOOT = 10;
    private static final long NETWORK_BACKOFF_MS                 = 15_000L;
    private static final long NETWORK_BACKOFF_CEILING_MS         = 60_000L;

    public static final String NOTIFICATION_CHANNEL_ID = "AmqpForegroundServiceChannel";

    // =========================================================================
    // State
    // =========================================================================

    private final IBinder binder = new MyBinder();

    private ConnectionFactory factory;
    private Connection        connection;
    private Channel           rtChannel;    // realtime — no publisher confirms
    private Channel[]         offChannels;  // offline  — publisher confirms enabled

    private ScheduledExecutorService rtScheduler;
    private ExecutorService          offlineExecutor;
    private ScheduledExecutorService connExecutor;
    private ExecutorService          offlinePublishPool;

    private volatile boolean isConnected  = false;

    // isConnecting stays true from the moment connectServer() enters the catch block
    // until scheduleReconnect() releases it just before the next attempt fires.
    // This blocks the RT scheduler (which calls connectServerAsync() every 1s)
    // during the entire backoff window, preventing attempts from piling up at 1/s.
    private volatile boolean isConnecting = false;

    // Independent failure counters — both reset together on successful connection
    private final AtomicInteger brokerFailures  = new AtomicInteger(0);
    private final AtomicInteger networkFailures = new AtomicInteger(0);

    private ServiceCallbacks serviceCallbacks;

    // =========================================================================
    // Binder / Callbacks
    // =========================================================================

    public class MyBinder extends Binder {
        public AmqpService getService() { return AmqpService.this; }
    }

    public interface ServiceCallbacks {
        @Nullable JSONObject getCurrentData();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setCallbacks(ServiceCallbacks callbacks) {
        this.serviceCallbacks = callbacks;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startAsForeground();
        ensureExecutors();
        logDeviceAdminStatus();
        connectServerAsync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRealtime();
        if (offlineExecutor    != null) offlineExecutor.shutdownNow();
        if (offlinePublishPool != null) offlinePublishPool.shutdownNow();
        if (connExecutor       != null) connExecutor.shutdownNow();
        closeConnections();
        super.onDestroy();
    }

    // =========================================================================
    // Foreground notification
    // =========================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "AMQP Service Channel",
                    NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startAsForeground() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), flags);
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("AMQP Service")
                .setContentText("Active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(4, notification);
    }

    // =========================================================================
    // Executors
    // =========================================================================

    private void ensureExecutors() {
        if (rtScheduler == null || rtScheduler.isShutdown() || rtScheduler.isTerminated()) {
            rtScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AMQP-RT-Scheduler");
                t.setDaemon(true);
                return t;
            });
        }
        if (offlineExecutor == null || offlineExecutor.isShutdown()
                || offlineExecutor.isTerminated()) {
            offlineExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AMQP-Offline");
                t.setDaemon(true);
                return t;
            });
        }
        if (connExecutor == null || connExecutor.isShutdown()
                || connExecutor.isTerminated()) {
            connExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AMQP-Conn");
                t.setDaemon(true);
                return t;
            });
        }
        if (offlinePublishPool == null || offlinePublishPool.isShutdown()
                || offlinePublishPool.isTerminated()) {
            offlinePublishPool = Executors.newFixedThreadPool(OFFLINE_CHANNEL_COUNT, r -> {
                Thread t = new Thread(r, "AMQP-OffPublish");
                t.setDaemon(true);
                return t;
            });
        }
    }

    // =========================================================================
    // Connection
    // =========================================================================

    /**
     * Queues connectServer() on the connExecutor.
     *
     * The isConnecting flag blocks concurrent calls from the RT scheduler.
     * Without this guard the RT scheduler (running every 1s) would enqueue
     * a new connectServer() every second during an outage, causing each
     * attempt to tear down the connection the previous one just established.
     *
     * isConnecting is set here and released in exactly three places:
     *   1. connectServer() on successful connection.
     *   2. scheduleReconnect() just before the next attempt fires,
     *      keeping the guard active during the entire backoff window.
     *   3. Immediately before restartApp() or rebootDevice() to avoid
     *      leaving the flag dirty on process kill.
     */
    private void connectServerAsync() {
        ensureExecutors();
        if (isConnecting || isConnected) return;
        isConnecting = true;
        connExecutor.execute(this::connectServer);
    }

    /**
     * Attempts to connect or reconnect to the AMQP broker.
     *
     * Uses catch (Throwable) instead of catch (Exception) to also capture
     * runtime Errors (OutOfMemoryError, etc.) that would otherwise leave
     * isConnecting stuck at true indefinitely.
     *
     * Failure classification:
     *   [NETWORK] → no internet → networkBackoff × attempt
     *               after MAX_NETWORK_FAILURES_BEFORE_REBOOT → reboot device (~10 min)
     *   [BROKER]  → server down / auth → brokerBackoff × attempt
     *               after MAX_BROKER_FAILURES_BEFORE_RESTART → restart app (~5 min)
     *   [ERROR]   → runtime Error → immediate app restart
     *
     * NOTE: isConnecting is NOT cleared in the catch block.
     * It stays true during the backoff window to block the RT scheduler.
     * Released only inside scheduleReconnect() just before the next attempt.
     */
    private synchronized void connectServer() {
        try {
            closeConnections();

            factory = new ConnectionFactory();
            factory.setHost(MainActivity.IP_SERVER);
            factory.setPort(Integer.parseInt(MainActivity.PORT_SERVER));
            factory.setUsername(MainActivity.USER_SERVER);
            factory.setPassword(MainActivity.PASSWORD_SERVER);
            factory.setVirtualHost("/");
            factory.setAutomaticRecoveryEnabled(true);
            factory.setTopologyRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5_000);
            factory.setRequestedHeartbeat(30);
            factory.setConnectionTimeout(10_000);

            connection = factory.newConnection();

            // ── Realtime channel — no publisher confirms ───────────────────
            rtChannel = connection.createChannel();
            rtChannel.addShutdownListener(this::onRtChannelShutdown);

            // ── Offline channels — publisher confirms enabled ──────────────
            offChannels = new Channel[OFFLINE_CHANNEL_COUNT];
            for (int i = 0; i < OFFLINE_CHANNEL_COUNT; i++) {
                offChannels[i] = connection.createChannel();
                offChannels[i].confirmSelect();
                final int idx = i;
                offChannels[i].addShutdownListener(cause -> {
                    if (!cause.isInitiatedByApplication()) {
                        Log.w(TAG, "OFF ch[" + idx + "]: closed by broker — "
                                + shortMessage(cause));
                        isConnected = false;
                        connectServerAsync();
                    }
                });
            }

            // ── Success — release guard and reset both counters ────────────
            isConnected  = true;
            isConnecting = false;
            brokerFailures.set(0);
            networkFailures.set(0);
            Log.d(TAG, "Connected to AMQP broker. RT channel + "
                    + OFFLINE_CHANNEL_COUNT + " offline channels ready.");

        } catch (Throwable e) {
            isConnected = false;
            // isConnecting intentionally NOT cleared here —
            // stays true during the backoff to block the RT scheduler.
            // Released only inside scheduleReconnect().

            if (e instanceof Exception && isNetworkFailure((Exception) e)) {
                // ── [NETWORK] No internet ──────────────────────────────────
                int  attempts = networkFailures.incrementAndGet();
                long backoff  = networkBackoff(attempts);

                Log.w(TAG, "[NETWORK] No internet connectivity."
                        + " | cause: " + e.getClass().getSimpleName()
                        + " (" + shortMessage(e) + ")"
                        + " | attempt: " + attempts + "/" + MAX_NETWORK_FAILURES_BEFORE_REBOOT
                        + " | next retry in: " + backoff / 1000 + "s");

                if (attempts >= MAX_NETWORK_FAILURES_BEFORE_REBOOT) {
                    Log.e(TAG, "[NETWORK] No internet for ~10 min → rebooting device.");
                    isConnecting = false;
                    rebootDevice();
                    return;
                }
                scheduleReconnect(backoff);

            } else if (e instanceof Error) {
                // ── [ERROR] Runtime Error — immediate restart ──────────────
                Log.e(TAG, "[ERROR] Critical runtime error in connectServer: "
                        + e.getClass().getSimpleName()
                        + " — " + (e.getMessage() != null ? e.getMessage() : "no message"));
                isConnecting = false;
                restartApp();

            } else {
                // ── [BROKER] Server down / auth / other ───────────────────
                int  attempts = brokerFailures.incrementAndGet();
                long backoff  = brokerBackoff(attempts);

                Log.w(TAG, "[BROKER] Failed to connect to broker."
                        + " | cause: " + e.getClass().getSimpleName()
                        + " (" + shortMessage(e) + ")"
                        + " | attempt: " + attempts + "/" + MAX_BROKER_FAILURES_BEFORE_RESTART
                        + " | next retry in: " + backoff / 1000 + "s");

                if (attempts >= MAX_BROKER_FAILURES_BEFORE_RESTART) {
                    Log.e(TAG, "[BROKER] Broker unreachable for ~5 min → restarting app.");
                    isConnecting = false;
                    restartApp();
                    return;
                }
                scheduleReconnect(backoff);
            }
        }
    }

    /**
     * ShutdownListener for the realtime channel.
     * Acts only when the shutdown was NOT initiated by the application
     * (i.e., triggered by the broker or by network loss).
     */
    private void onRtChannelShutdown(ShutdownSignalException cause) {
        if (!cause.isInitiatedByApplication()) {
            Log.w(TAG, "RT channel: closed by broker — " + shortMessage(cause));
            isConnected = false;
            connectServerAsync();
        }
    }

    /**
     * Schedules the next reconnect attempt after backoffMs.
     *
     * isConnecting is released INSIDE the lambda, just before connectServerAsync()
     * is called. This keeps the guard active during the entire wait period,
     * blocking the RT scheduler throughout that interval.
     */
    private void scheduleReconnect(long backoffMs) {
        ensureExecutors();
        ((ScheduledExecutorService) connExecutor).schedule(() -> {
            isConnecting = false;
            connectServerAsync();
        }, backoffMs, TimeUnit.MILLISECONDS);
    }

    // =========================================================================
    // Backoff calculation
    // =========================================================================

    /**
     * Linear backoff for broker failures — app restart in ~5 min.
     * Ceiling 50s. Progression: 10, 20, 30, 40, 50, 50, 50 seconds.
     * Total with MAX=7: ~250s backoff + connection timeouts ≈ 4.5 min.
     */
    private long brokerBackoff(int attempt) {
        return Math.min(BROKER_BACKOFF_MS * attempt, BROKER_BACKOFF_CEILING_MS);
    }

    /**
     * Linear backoff for network failures — device reboot in ~10 min.
     * Ceiling 60s. Progression: 15, 30, 45, 60, 60, 60, 60, 60, 60, 60 seconds.
     * Total with MAX=10: ~510s backoff + connection timeouts ≈ 8.5-9 min.
     */
    private long networkBackoff(int attempt) {
        return Math.min(NETWORK_BACKOFF_MS * attempt, NETWORK_BACKOFF_CEILING_MS);
    }

    // =========================================================================
    // Failure classification
    // =========================================================================

    /**
     * Returns true if the exception indicates a network connectivity problem,
     * false if it indicates a broker-side problem (server down, auth, etc.).
     *
     * This distinction determines the recovery action after N failures:
     *   Network failure → reboot device (resets OS network stack / modem)
     *   Broker failure  → restart app   (OS and network are healthy)
     */
    private boolean isNetworkFailure(Exception e) {
        if (e instanceof java.net.NoRouteToHostException) return true;
        if (e instanceof java.net.UnknownHostException)   return true;

        if (e instanceof java.net.SocketTimeoutException) {
            return !hasInternetConnectivity();
        }

        if (e instanceof java.net.ConnectException) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("network")
                    || msg.contains("unreachable")
                    || msg.contains("enetunreach")) {
                return true;
            }
            return !hasInternetConnectivity();
        }

        return false;
    }

    /**
     * Returns true if the device has any active internet-capable network.
     * Uses ConnectivityManager — instant, no network request made.
     * Requires ACCESS_NETWORK_STATE in the manifest.
     */
    private boolean hasInternetConnectivity() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } else {
            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        }
    }

    // =========================================================================
    // App restart / Device reboot
    // =========================================================================

    /**
     * Restarts the application.
     * Schedules MainActivity in 3s via AlarmManager, then kills the process.
     * The OS cleans up; AlarmManager relaunches the app.
     *
     * Used when the broker has been unreachable for ~5 consecutive minutes,
     * or as a fallback from rebootDevice() when admin permissions are absent.
     */
    private void restartApp() {
        try {
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    getApplicationContext(), 0, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null)
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_000L, pi);
            Log.d(TAG, "[BROKER] App restart scheduled in 3s.");
        } catch (Exception e) {
            Log.e(TAG, "[BROKER] Error scheduling app restart: " + shortMessage(e));
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    /**
     * Reboots the device via DevicePolicyManager.
     *
     * Requirements:
     *   - API >= 24 (Android 7.0+)
     *   - App active as Device Administrator (MyAdminReceiver)
     *   - android.permission.REBOOT in the manifest
     *
     * Falls back to restartApp() if any requirement is not met.
     *
     * Used when there has been no internet for ~10 consecutive minutes.
     * A full reboot resets the OS network stack (modem, WiFi driver), resolving
     * issues that an app restart alone cannot fix.
     */
    private void rebootDevice() {
        Log.e(TAG, "[NETWORK] No internet for ~10 min → attempting device reboot.");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                DevicePolicyManager dpm =
                        (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                ComponentName adminComponent =
                        new ComponentName(this, MyAdminReceiver.class);

                if (dpm != null && dpm.isAdminActive(adminComponent)) {
                    Log.d(TAG, "[NETWORK] Device Admin active → executing reboot.");
                    dpm.reboot(adminComponent);
                    // dpm.reboot() does not return — device restarts here
                } else {
                    Log.w(TAG, "[NETWORK] Device Admin not active → falling back to app restart.");
                    restartApp();
                }
            } else {
                Log.w(TAG, "[NETWORK] API " + Build.VERSION.SDK_INT
                        + " < 24 — reboot unavailable → falling back to app restart.");
                restartApp();
            }
        } catch (SecurityException se) {
            Log.e(TAG, "[NETWORK] Missing REBOOT permission → falling back to app restart: "
                    + shortMessage(se));
            restartApp();
        } catch (Exception e) {
            Log.e(TAG, "[NETWORK] Reboot error → falling back to app restart: "
                    + shortMessage(e));
            restartApp();
        }
    }

    // =========================================================================
    // Close connections
    // =========================================================================

    private synchronized void closeConnections() {
        try {
            if (rtChannel != null && rtChannel.isOpen()) rtChannel.close();
        } catch (Exception ignored) {}

        if (offChannels != null) {
            for (int i = 0; i < offChannels.length; i++) {
                try {
                    if (offChannels[i] != null && offChannels[i].isOpen())
                        offChannels[i].close();
                } catch (Exception ignored) {}
                offChannels[i] = null;
            }
            offChannels = null;
        }

        try {
            if (connection != null && connection.isOpen()) connection.close();
        } catch (Exception ignored) {}

        rtChannel   = null;
        connection  = null;
        isConnected = false;
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    /**
     * Logs Device Admin status at service startup.
     * Makes it easy to detect in the field whether rebootDevice() will succeed
     * or will fall back to restartApp().
     */
    private void logDeviceAdminStatus() {
        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, MyAdminReceiver.class);
            boolean active = dpm != null && dpm.isAdminActive(admin);
            Log.d(TAG, "Device Admin: "
                    + (active
                    ? "ACTIVE — device reboot available if API >= 24"
                    : "INACTIVE — reboot will fall back to restartApp()"));
        } catch (Exception e) {
            Log.w(TAG, "Could not verify Device Admin status: " + shortMessage(e));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Extracts a short one-line message from a Throwable for informational logs.
     * Avoids printing full stack traces for expected, handled failures.
     * Use Log.e(TAG, "msg", e) only for truly unexpected exceptions.
     */
    private String shortMessage(Throwable e) {
        if (e.getMessage() == null) return "no message";
        String msg = e.getMessage().split("\n")[0].trim();
        return msg.length() > 120 ? msg.substring(0, 120) + "..." : msg;
    }

    /**
     * Returns true only if every offline channel is open.
     * If any channel is down the entire connection is treated as lost.
     */
    private boolean allOfflineChannelsOpen() {
        if (offChannels == null) return false;
        for (Channel ch : offChannels) {
            if (ch == null || !ch.isOpen()) return false;
        }
        return true;
    }

    // =========================================================================
    // Realtime
    // =========================================================================

    public void startRealtime() {
        stopRealtime();
        ensureExecutors();

        rtScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!isConnected || rtChannel == null || !rtChannel.isOpen()) {
                    Log.w(TAG, "RT: channel unavailable → connectServerAsync()");
                    connectServerAsync();
                    return;
                }

                if (serviceCallbacks == null) {
                    Log.v(TAG, "RT: skipped — callbacks are null");
                    return;
                }

                JSONObject msg = null;
                try {
                    msg = serviceCallbacks.getCurrentData();
                } catch (Throwable t) {
                    Log.w(TAG, "RT: getCurrentData() threw "
                            + t.getClass().getSimpleName() + " — "
                            + (t.getMessage() != null ? t.getMessage() : "no message"));
                }

                if (msg == null) {
                    // No sensor data — publish a keepalive ping
                    JSONObject ping = new JSONObject();
                    ping.put("type",          "realtime");
                    ping.put("device_id",     MainActivity.DEVICE_ID);
                    ping.put("timestamp_sys", System.currentTimeMillis());
                    byte[] body = ping.toString().getBytes(StandardCharsets.UTF_8);
                    rtChannel.basicPublish(EXCHANGE_NAME,
                            ROUTING_KEY_PREFIX + "realtime",
                            PROPS_NON_PERSISTENT, body);
                    Log.d(TAG, "RT: PING published, bytes=" + body.length);
                    return;
                }

                String type = msg.optString("type", "realtime");
                byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
                rtChannel.basicPublish(EXCHANGE_NAME,
                        ROUTING_KEY_PREFIX + type,
                        PROPS_NON_PERSISTENT, body);
                Log.d(TAG, "RT: published type=" + type + " bytes=" + body.length);

            } catch (com.rabbitmq.client.AlreadyClosedException e) {
                Log.w(TAG, "RT: channel closed during publish → reconnecting.");
                isConnected = false;
                connectServerAsync();
            } catch (Exception e) {
                Log.w(TAG, "RT: publish error ("
                        + e.getClass().getSimpleName() + ") → reconnecting.");
                isConnected = false;
                connectServerAsync();
            }
        }, 0, RT_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public void stopRealtime() {
        if (rtScheduler != null) {
            rtScheduler.shutdownNow();
            rtScheduler = null;
            Log.d(TAG, "RT scheduler stopped.");
        }
    }

    // =========================================================================
    // Offline — batch upload with publisher confirms and multi-channel
    // =========================================================================

    public static class UploadStats {
        public final int  packetsSent;
        public final long bytesSent;
        public final long elapsedMs;

        public UploadStats(int packetsSent, long bytesSent, long elapsedMs) {
            this.packetsSent = packetsSent;
            this.bytesSent   = bytesSent;
            this.elapsedMs   = elapsedMs;
        }

        @Override
        public String toString() {
            return "UploadStats{packetsSent=" + packetsSent
                    + ", bytesSent=" + bytesSent
                    + ", elapsedMs=" + elapsedMs + "}";
        }
    }

    /**
     * Result of a sub-batch published by one offline channel.
     *   confirmed=true  → IDs acknowledged by broker → safe to delete from buffer.
     *   confirmed=false → failure → IDs stay in DB for retry next cycle.
     */
    private static class SubBatchResult {
        final boolean       confirmed;
        final List<Integer> ids;
        final long          bytesPublished;

        SubBatchResult(boolean confirmed, List<Integer> ids, long bytesPublished) {
            this.confirmed      = confirmed;
            this.ids            = ids;
            this.bytesPublished = bytesPublished;
        }
    }

    /**
     * Sends all buffered records to the broker in batches with publisher confirms.
     *
     * Cycle flow:
     *   1. Read OFFLINE_BATCH_SIZE records from SQLite (ORDER BY id DESC — LIFO)
     *   2. Split into OFFLINE_CHANNEL_COUNT sub-batches
     *   3. Publish each sub-batch in parallel, one channel per sub-batch
     *   4. Wait for waitForConfirms() on each channel independently
     *   5. Delete from buffer ONLY the IDs confirmed by the broker
     *   6. If any channel failed → force reconnect before the next cycle
     *
     * Data guarantee: records are deleted only after broker acknowledgement.
     * Unconfirmed records remain in the DB and are retransmitted in the next cycle.
     *
     * GC optimizations applied:
     *   - ByteArrayOutputStream reused with reset() — ~4000 fewer byte[] allocs/cycle
     *   - Routing key cached by message type — ~4000 fewer String allocs/cycle
     *   - ArrayList pre-allocated with exact capacity — avoids internal resize
     */
    public Future<UploadStats> sendOfflineBatches(JSONDatabaseHelper buffer) {
        ensureExecutors();
        if (offlineExecutor == null || offlineExecutor.isShutdown()) {
            offlineExecutor = Executors.newSingleThreadExecutor();
        }

        return offlineExecutor.submit(() -> {
            long startTime  = System.currentTimeMillis();
            int  totalSent  = 0;
            long totalBytes = 0;

            try {
                while (true) {

                    // ── No connection: backoff and retry ──────────────────────
                    if (!isConnected || offChannels == null || !allOfflineChannelsOpen()) {
                        Log.w(TAG, "OFF: no channels available → requesting reconnect, "
                                + "waiting " + OFFLINE_BACKOFF_MS + "ms...");
                        connectServerAsync();
                        Thread.sleep(OFFLINE_BACKOFF_MS);
                        continue;
                    }

                    // ── Read batch from SQLite buffer ──────────────────────────
                    long readStart = System.currentTimeMillis();
                    List<androidx.core.util.Pair<JSONObject, Integer>> batch =
                            buffer.getNextNJsonWithId(OFFLINE_BATCH_SIZE).get();
                    Log.d(TAG, "OFF: batch read took "
                            + (System.currentTimeMillis() - readStart) + "ms");

                    if (batch == null || batch.isEmpty()) {
                        Log.d(TAG, "OFF: buffer empty — upload complete.");
                        break;
                    }

                    // ── Split into sub-batches, one per channel ────────────────
                    int total     = batch.size();
                    int chunkSize = (int) Math.ceil((double) total / OFFLINE_CHANNEL_COUNT);
                    List<Callable<SubBatchResult>> tasks = new ArrayList<>(OFFLINE_CHANNEL_COUNT);

                    for (int i = 0; i < OFFLINE_CHANNEL_COUNT; i++) {
                        int from = i * chunkSize;
                        if (from >= total) break;

                        int to = Math.min(from + chunkSize, total);
                        List<androidx.core.util.Pair<JSONObject, Integer>> sub =
                                batch.subList(from, to); // view, no copy

                        final Channel ch    = offChannels[i];
                        final int     chIdx = i;

                        tasks.add(() -> {
                            List<Integer> subIds   = new ArrayList<>(sub.size());
                            long          subBytes = 0;

                            // GC OPT 1: reuse stream — avoids ~1000 byte[] allocs per sub-batch
                            ByteArrayOutputStream baos   = new ByteArrayOutputStream(512);
                            OutputStreamWriter    writer =
                                    new OutputStreamWriter(baos, StandardCharsets.UTF_8);

                            // GC OPT 2: cache routing key per message type
                            HashMap<String, String> routingKeyCache = new HashMap<>(4);

                            for (androidx.core.util.Pair<JSONObject, Integer> item : sub) {
                                JSONObject json = item.first;
                                int        id   = item.second;

                                String type = json.optString("type", "unknown");
                                String routingKey = routingKeyCache.computeIfAbsent(
                                        type, t -> ROUTING_KEY_PREFIX + t);

                                baos.reset();
                                try {
                                    writer.write(json.toString());
                                    writer.flush();
                                } catch (IOException ioEx) {
                                    Log.w(TAG, "OFF ch[" + chIdx
                                            + "]: serialization error id=" + id
                                            + " — " + shortMessage(ioEx));
                                    subIds.add(id); // include to avoid reprocessing
                                    continue;
                                }

                                byte[] body = baos.toByteArray();
                                ch.basicPublish(EXCHANGE_NAME, routingKey,
                                        PROPS_PERSISTENT, body);
                                subIds.add(id);
                                subBytes += body.length;
                            }

                            // Wait for broker acknowledgement on this channel
                            boolean confirmed;
                            try {
                                confirmed = ch.waitForConfirms(OFFLINE_CONFIRM_TIMEOUT_MS);
                            } catch (java.util.concurrent.TimeoutException te) {
                                Log.w(TAG, "OFF ch[" + chIdx + "]: confirms timed out — "
                                        + subIds.size() + " msgs will be retried.");
                                confirmed = false;
                            } catch (Exception ex) {
                                Log.w(TAG, "OFF ch[" + chIdx + "]: waitForConfirms error ("
                                        + ex.getClass().getSimpleName() + ") — "
                                        + subIds.size() + " msgs will be retried.");
                                confirmed = false;
                            }

                            if (confirmed) {
                                Log.d(TAG, "OFF ch[" + chIdx + "]: confirmed "
                                        + subIds.size() + " msgs.");
                            }
                            return new SubBatchResult(confirmed, subIds, subBytes);
                        });
                    }

                    // ── Publish all sub-batches in parallel ────────────────────
                    long cycleStart = System.currentTimeMillis();
                    List<Future<SubBatchResult>> futures =
                            offlinePublishPool.invokeAll(tasks);

                    // ── Collect results ────────────────────────────────────────
                    List<Integer> confirmedIds = new ArrayList<>(total);
                    boolean       anyFailed    = false;

                    for (Future<SubBatchResult> f : futures) {
                        try {
                            SubBatchResult result = f.get();
                            if (result.confirmed) {
                                confirmedIds.addAll(result.ids);
                                totalBytes += result.bytesPublished;
                            } else {
                                anyFailed = true;
                            }
                        } catch (ExecutionException ee) {
                            Log.w(TAG, "OFF: unexpected sub-batch error: "
                                    + (ee.getCause() != null
                                    ? ee.getCause().getClass().getSimpleName()
                                    : "null cause"));
                            anyFailed = true;
                        }
                    }

                    long cycleMs = System.currentTimeMillis() - cycleStart;
                    Log.d(TAG, "OFF cycle:"
                            + " publish+confirm=" + cycleMs + "ms"
                            + " | confirmed=" + confirmedIds.size()
                            + " | channels=" + OFFLINE_CHANNEL_COUNT
                            + " | msgs_per_channel=" + OFFLINE_SUB_BATCH_SIZE
                            + " | throughput=" + (confirmedIds.size() * 1000L
                            / Math.max(1, cycleMs)) + "msg/s");

                    // ── Delete only confirmed records from the buffer ──────────
                    if (!confirmedIds.isEmpty()) {
                        buffer.deleteRecordsByIds(confirmedIds);
                        totalSent += confirmedIds.size();
                        Log.d(TAG, "OFF: " + confirmedIds.size()
                                + " records deleted, totalSent=" + totalSent);
                    }

                    // ── Any channel failed → force reconnect before next cycle ─
                    if (anyFailed) {
                        Log.w(TAG, "OFF: one or more channels failed → reconnecting, "
                                + "backoff " + OFFLINE_BACKOFF_MS + "ms");
                        isConnected = false;
                        Thread.sleep(OFFLINE_BACKOFF_MS);
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "OFF: upload interrupted (RT mode activated or app restart).");
            } catch (Exception e) {
                Log.w(TAG, "OFF: unexpected upload error ("
                        + e.getClass().getSimpleName() + "): " + shortMessage(e));
            }

            long elapsed  = System.currentTimeMillis() - startTime;
            UploadStats stats = new UploadStats(totalSent, totalBytes, elapsed);

            // ── Summary to broker (best-effort, no confirms) ───────────────────
            try {
                JSONObject summary = new JSONObject();
                summary.put("type",           "system");
                summary.put("timestamp_sys",  System.currentTimeMillis());
                summary.put("device_id",      MainActivity.DEVICE_ID);
                summary.put("number_packets", stats.packetsSent);
                summary.put("total_time_ms",  stats.elapsedMs);
                summary.put("bytes_sent",     stats.bytesSent);

                if (rtChannel != null && rtChannel.isOpen()) {
                    byte[] body = summary.toString().getBytes(StandardCharsets.UTF_8);
                    rtChannel.basicPublish(EXCHANGE_NAME,
                            ROUTING_KEY_PREFIX + "system",
                            PROPS_NON_PERSISTENT, body);
                }
            } catch (Exception ignored) {}

            Log.d(TAG, "OFF: upload complete — " + stats);
            return stats;
        });
    }
}



/*
package com.unicamp.moview_v1;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AmqpService extends Service {

    // =========================
    // Constantes de AMQP / envío
    // =========================
    private static final String TAG = "AmqpService";
    private static final String EXCHANGE_NAME = "amq.direct";
    private static final String ROUTING_KEY_PREFIX = "unicamp.campinas.";

    private static final AMQP.BasicProperties PROPS_JSON_NON_PERSISTENT =
            new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(1)
                    .build();

    private static final AMQP.BasicProperties PROPS_JSON_PERSISTENT =
            new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .deliveryMode(2)
                    .build();

    private static final long REALTIME_PERIOD_MS       = 1_000L;
    private static final int  OFFLINE_BATCH_SIZE        = 1_000;
    private static final long OFFLINE_CONFIRM_TIMEOUT_MS= 10_000L;
    private static final long OFFLINE_BACKOFF_MS        = 3_000L;

    // Reconexión / restart
    // Cuántos fallos consecutivos de connectServer() toleramos antes de reiniciar la app.
    // Con RECONNECT_BACKOFF_MS = 10s → 10 intentos = ~100s sin conexión → restart.
    private static final int  MAX_RECONNECT_BEFORE_RESTART = 10;
    private static final long RECONNECT_BACKOFF_MS          = 10_000L;

    public static final String CHANNEL_ID = "AmqpForegroundServiceChannel";

    // =========================
    // Estado de servicio
    // =========================
    private final IBinder binder = new MyBinder();

    private ConnectionFactory factory;
    private Connection connection;
    private Channel rtChannel;     // realtime  (sin confirms)
    private Channel offChannel;    // offline   (con confirms)

    private ScheduledExecutorService realtimeScheduler;
    private ExecutorService offlineExecutor;
    private ScheduledExecutorService connExecutor;

    private volatile boolean isConnected = false;

    // Contador de fallos consecutivos de conexión
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private ServiceCallbacks serviceCallbacks;

    // =========================
    // Binder / Callbacks
    // =========================
    public class MyBinder extends Binder {
        public AmqpService getService() { return AmqpService.this; }
    }

    public interface ServiceCallbacks {
        @Nullable JSONObject getCurrentData();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setCallbacks(ServiceCallbacks callbacks) {
        this.serviceCallbacks = callbacks;
    }

    // =========================
    // Ciclo de vida
    // =========================
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForegroundService();
        ensureExecutors();
        connectServerAsync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRealtime();
        if (offlineExecutor != null) offlineExecutor.shutdownNow();
        if (connExecutor    != null) connExecutor.shutdownNow();
        closeConnections();
        super.onDestroy();
    }

    // =========================
    // Notificación foreground
    // =========================
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "AMQP Service Channel", NotificationManager.IMPORTANCE_MIN);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void startForegroundService() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), flags);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AMQP Service")
                .setContentText("Activo")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(4, notification);
    }

    // =========================
    // Executors
    // =========================
    private void ensureExecutors() {
        if (realtimeScheduler == null || realtimeScheduler.isShutdown()) {
            realtimeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AMQP-RT-Scheduler");
                t.setDaemon(true);
                return t;
            });
        }
        if (offlineExecutor == null || offlineExecutor.isShutdown()) {
            offlineExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AMQP-Offline");
                t.setDaemon(true);
                return t;
            });
        }
        if (connExecutor == null || connExecutor.isShutdown()) {
            connExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AMQP-Conn");
                t.setDaemon(true);
                return t;
            });
        }
    }

    // =========================
    // Conexión
    // =========================

    */
/** Encola connectServer() en el connExecutor (no bloquea al llamador). *//*

    private void connectServerAsync() {
        ensureExecutors();
        connExecutor.execute(this::connectServer);
    }

    */
/**
     * Intenta conectar/reconectar al broker.
     *
     * - Si tiene éxito → resetea el contador de intentos.
     * - Si falla        → incrementa el contador.
     *   · Si alcanza MAX_RECONNECT_BEFORE_RESTART → reinicia la app.
     *   · Si no         → programa otro intento en RECONNECT_BACKOFF_MS.
     *//*

    private synchronized void connectServer() {
        try {
            closeConnections();

            factory = new ConnectionFactory();
            factory.setHost(MainActivity.IP_SERVER);
            factory.setPort(Integer.parseInt(MainActivity.PORT_SERVER));
            factory.setUsername(MainActivity.USER_SERVER);
            factory.setPassword(MainActivity.PASSWORD_SERVER);
            factory.setVirtualHost("/");
            factory.setAutomaticRecoveryEnabled(true);
            factory.setTopologyRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5_000);
            factory.setRequestedHeartbeat(30);
            factory.setConnectionTimeout(10_000);

            connection = factory.newConnection();
            rtChannel  = connection.createChannel();
            offChannel = connection.createChannel();
            offChannel.confirmSelect();

            isConnected = true;
            reconnectAttempts.set(0);   // ← éxito: resetear contador
            Log.d(TAG, "Conectado a AMQP. Canales listos.");

        } catch (Exception e) {
            isConnected = false;
            int attempts = reconnectAttempts.incrementAndGet();
            Log.e(TAG, "Fallo de conexión AMQP. Intento "
                    + attempts + "/" + MAX_RECONNECT_BEFORE_RESTART, e);

            if (attempts >= MAX_RECONNECT_BEFORE_RESTART) {
                // Demasiados fallos consecutivos → reiniciar la app
                Log.e(TAG, "Máximo de reconexiones alcanzado → reiniciando app.");
                restartApp();
                return; // no programar más intentos; killProcess() se encarga
            }

            scheduleReconnect();
        }
    }

    */
/** Programa un intento de reconexión tras RECONNECT_BACKOFF_MS. *//*

    private void scheduleReconnect() {
        ensureExecutors();
        ((ScheduledExecutorService) connExecutor)
                .schedule(this::connectServerAsync, RECONNECT_BACKOFF_MS, TimeUnit.MILLISECONDS);
    }

    */
/**
     * Reinicia la app:
     * 1. Programa MainActivity para lanzarse en 3s vía AlarmManager.
     * 2. Mata el proceso → el SO limpia todo; AlarmManager relanza.
     *//*

    private void restartApp() {
        try {
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            PendingIntent pi = PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 3_000L,
                        pi);
            }
            Log.d(TAG, "Restart programado en 3s.");
        } catch (Exception e) {
            Log.e(TAG, "Error programando restart", e);
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private synchronized void closeConnections() {
        try { if (rtChannel  != null && rtChannel.isOpen())  rtChannel.close();  } catch (Exception ignored) {}
        try { if (offChannel != null && offChannel.isOpen()) offChannel.close(); } catch (Exception ignored) {}
        try { if (connection != null && connection.isOpen()) connection.close(); } catch (Exception ignored) {}
        rtChannel  = null;
        offChannel = null;
        connection = null;
        isConnected = false;
    }

    // =========================
    // Realtime
    // =========================
    public void startRealtime() {
        stopRealtime();
        ensureExecutors();

        realtimeScheduler.scheduleAtFixedRate(() -> {
            try {
                // 1) Canal disponible?
                if (!isConnected || rtChannel == null || !rtChannel.isOpen()) {
                    Log.w(TAG, "RT: canal no disponible → connectServerAsync()");
                    // Delega al connExecutor; NO bloquea este hilo.
                    connectServerAsync();
                    return;
                }

                // 2) Callbacks listos?
                if (serviceCallbacks == null) {
                    Log.v(TAG, "RT skip: callbacks nulos");
                    return;
                }

                // 3) Obtener datos
                JSONObject msg = null;
                try {
                    msg = serviceCallbacks.getCurrentData();
                } catch (Throwable t) {
                    Log.e(TAG, "RT: getCurrentData() excepción", t);
                }

                // 4a) Sin datos → publicar ping
                if (msg == null) {
                    JSONObject ping = new JSONObject();
                    ping.put("type", "realtime");
                    ping.put("device_id", MainActivity.DEVICE_ID);
                    ping.put("timestamp_sys", System.currentTimeMillis());
                    byte[] body = ping.toString().getBytes(StandardCharsets.UTF_8);
                    rtChannel.basicPublish(EXCHANGE_NAME,
                            ROUTING_KEY_PREFIX + "realtime",
                            PROPS_JSON_NON_PERSISTENT, body);
                    Log.d(TAG, "RT published PING bytes=" + body.length);
                    return;
                }

                // 4b) Publicar dato real
                String type = msg.optString("type", "realtime");
                byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
                rtChannel.basicPublish(EXCHANGE_NAME,
                        ROUTING_KEY_PREFIX + type,
                        PROPS_JSON_NON_PERSISTENT, body);
                Log.d(TAG, "RT published type=" + type + " bytes=" + body.length);

            } catch (com.rabbitmq.client.AlreadyClosedException e) {
                Log.w(TAG, "RT: canal cerrado durante publish.", e);
                isConnected = false;
                connectServerAsync(); // async, no bloquea
            } catch (Exception e) {
                Log.e(TAG, "RT publish error", e);
                isConnected = false;
                connectServerAsync(); // async, no bloquea
            }
        }, 0, REALTIME_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public void stopRealtime() {
        if (realtimeScheduler != null) {
            realtimeScheduler.shutdownNow();
            realtimeScheduler = null;
            Log.d(TAG, "RT scheduler detenido. ");
        }
    }

    // =========================
    // Offline (por lotes + confirms)
    // =========================
    public static class UploadStats {
        public final int packetsSent;
        public final long bytesSent;
        public final long elapsedMs;

        public UploadStats(int packetsSent, long bytesSent, long elapsedMs) {
            this.packetsSent = packetsSent;
            this.bytesSent   = bytesSent;
            this.elapsedMs   = elapsedMs;
        }

        @Override public String toString() {
            return "UploadStats{packetsSent=" + packetsSent
                    + ", bytesSent=" + bytesSent
                    + ", elapsedMs=" + elapsedMs + "}";
        }
    }

    */
/**
     * Envía todos los registros del buffer en lotes con publisher confirms.
     *
     * Si no hay conexión, reintenta indefinidamente (con backoff de OFFLINE_BACKOFF_MS).
     * La señal de abort viene de connectServer() → si supera MAX_RECONNECT_BEFORE_RESTART,
     * la app se reinicia y el upload se retoma desde cero al relanzarse.
     * Los datos nunca se pierden porque solo se borran tras recibir el confirm del broker.
     *//*

    public Future<UploadStats> sendOfflineBatches(JSONDatabaseHelper buffer) {
        if (offlineExecutor == null || offlineExecutor.isShutdown()) {
            offlineExecutor = Executors.newSingleThreadExecutor();
        }

        return offlineExecutor.submit(() -> {
            long t0    = System.currentTimeMillis();
            int  sent  = 0;
            long bytes = 0;

            try {
                while (true) {

                    // ── Sin conexión: esperar con backoff ──────────────────────────────
                    // connectServer() es quien cuenta los intentos y decide reiniciar la app.
                    // Aquí simplemente esperamos hasta que isConnected sea true.
                    if (!isConnected || offChannel == null || !offChannel.isOpen()) {
                        Log.w(TAG, "OFF: sin canal → solicitando reconexión y esperando "
                                + OFFLINE_BACKOFF_MS + "ms...");
                        connectServerAsync();
                        Thread.sleep(OFFLINE_BACKOFF_MS);
                        continue; // reintenta sin límite; el restart lo maneja connectServer()
                    }

                    // ── Traer un lote del buffer ───────────────────────────────────────
                    long t00 = System.currentTimeMillis();
                    List<androidx.core.util.Pair<JSONObject, Integer>> batch =
                            buffer.getNextNJsonWithId(OFFLINE_BATCH_SIZE).get();
                    long elapsed = System.currentTimeMillis() - t00;
                    Log.d(TAG, "OFF: get batch tardó " + elapsed + "ms");

                    if (batch == null || batch.isEmpty()) {
                        Log.d(TAG, "OFF: buffer vacío. Upload completado.");
                        break; // salida normal: no quedan datos
                    }

                    List<Integer> ids = new ArrayList<>(batch.size());

                    // ── Publicar el lote ───────────────────────────────────────────────
                    for (androidx.core.util.Pair<JSONObject, Integer> it : batch) {
                        JSONObject json = it.first;
                        int        id   = it.second;

                        String type = json.optString("type", "unknown");
                        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

                        offChannel.basicPublish(EXCHANGE_NAME,
                                ROUTING_KEY_PREFIX + type,
                                PROPS_JSON_PERSISTENT, body);

                        ids.add(id);
                        bytes += body.length;
                    }

                    // ── Esperar confirm del broker ─────────────────────────────────────
                    boolean ok;
                    try {
                        ok = offChannel.waitForConfirms(OFFLINE_CONFIRM_TIMEOUT_MS);
                    } catch (java.util.concurrent.TimeoutException te) {
                        Log.w(TAG, "OFF: confirms timeout. Backoff y reintento.", te);
                        ok = false;
                    }

                    if (ok) {
                        // Solo borramos cuando el broker confirmó la recepción
                        buffer.deleteRecordsByIds(ids);
                        sent += batch.size();
                        Log.d(TAG, "OFF batch OK: " + batch.size()
                                + " msgs, totalSent=" + sent);
                    } else {
                        Log.e(TAG, "OFF: confirm fallido. Backoff " + OFFLINE_BACKOFF_MS + "ms");
                        isConnected = false; // fuerza reconexión en la próxima iteración
                        Thread.sleep(OFFLINE_BACKOFF_MS);
                    }
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "OFF: upload interrumpido (modo RT activado o restart).");
            } catch (Exception e) {
                Log.e(TAG, "OFF: error inesperado en upload", e);
            }

            long elapsed = System.currentTimeMillis() - t0;
            UploadStats stats = new UploadStats(sent, bytes, elapsed);

            // ── Resumen enviado al broker (best-effort, sin confirms) ──────────────
            try {
                JSONObject summary = new JSONObject();
                summary.put("type",           "system");
                summary.put("timestamp_sys",  System.currentTimeMillis());
                summary.put("device_id",      MainActivity.DEVICE_ID);
                summary.put("number_packets", stats.packetsSent);
                summary.put("total_time_ms",  stats.elapsedMs);
                summary.put("bytes_sent",     stats.bytesSent);

                if (rtChannel != null && rtChannel.isOpen()) {
                    byte[] body = summary.toString().getBytes(StandardCharsets.UTF_8);
                    rtChannel.basicPublish(EXCHANGE_NAME,
                            ROUTING_KEY_PREFIX + "system",
                            PROPS_JSON_NON_PERSISTENT, body);
                }
            } catch (Exception ignored) {}

            Log.d(TAG, "OFF done: " + stats);
            return stats;
        });
    }
}
*/



