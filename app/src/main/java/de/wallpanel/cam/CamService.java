package de.wallpanel.cam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CamService extends Service {
    static final String TAG = "WallpanelCam";
    static final String ACTION_RECONNECT = "de.wallpanel.cam.RECONNECT";
    private static final String CHANNEL = "cam";
    private static final int NOTIF_ID = 8;

    interface Listener {
        void onStatus(String status, String error);
    }

    public final class LocalBinder extends Binder {
        CamService service() {
            return CamService.this;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "wallpanel-mqtt");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "wallpanel-ctrl");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean commandSeen = new AtomicBoolean();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final LocalBinder binder = new LocalBinder();
    private volatile String statusText = "Starte";
    private volatile String errorText = "";

    private MqttAsyncClient client;
    private volatile boolean running;
    private volatile int generation;
    private volatile int connectGen;
    private volatile boolean wanted;
    private volatile boolean pipelineUp;
    private AvcEncoder encoder;
    private CameraPipe camera;
    private GlFrame gl;
    private RtspServer rtsp;

    private final Runnable retry = () -> control.execute(() -> {
        if (running && wanted && !pipelineUp) {
            setWanted(true);
        }
    });

    private final MqttCallbackExtended callback = new MqttCallbackExtended() {
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            io.execute(CamService.this::onConnected);
        }

        @Override
        public void connectionLost(Throwable cause) {
            Log.w(TAG, "connection lost: " + scrub(cause == null ? "" : cause.getMessage()));
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            if (!Payloads.COMMAND.equals(topic)) {
                return;
            }
            String body = new String(message.getPayload(), StandardCharsets.UTF_8);
            Boolean on = Payloads.parseOnOff(body);
            if (on == null) {
                Log.w(TAG, "ignoriere befehl");
                return;
            }
            commandSeen.set(true);
            boolean value = on;
            Log.i(TAG, "befehl " + (value ? "ON" : "OFF"));
            control.execute(() -> setWanted(value));
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        startInForeground(false);
        io.execute(this::connectNow);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground(pipelineUp);
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            reconnect();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        listener.onStatus(statusText, errorText);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void reconnect() {
        generation++;
        setStatus("Verbinde…", "");
        io.execute(this::connectNow);
        control.execute(this::restartPipelineIfOn);
    }

    @Override
    public void onDestroy() {
        running = false;
        wanted = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        try {
            control.submit(this::stopPipeline).get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "stop: " + scrub(e.getMessage()));
        }
        publish(Payloads.AVAILABILITY, "offline", true, true);
        closeClient();
        io.shutdownNow();
        control.shutdownNow();
        super.onDestroy();
    }

    private void setWanted(boolean on) {
        wanted = on;
        main.removeCallbacks(retry);
        if (!on) {
            stopPipeline();
            publish(Payloads.STATE, Payloads.statePayload(false), true, false);
            show(false);
            return;
        }
        if (pipelineUp) {
            publish(Payloads.STATE, Payloads.statePayload(true), true, false);
            show(true);
            return;
        }
        boolean ok = startPipeline();
        publish(Payloads.STATE, Payloads.statePayload(ok), true, false);
        show(ok);
        if (!ok && wanted) {
            main.postDelayed(retry, 5_000);
        }
    }

    private void restartPipelineIfOn() {
        if (!running || !wanted) {
            return;
        }
        boolean ok = startPipeline();
        publish(Payloads.STATE, Payloads.statePayload(ok), true, false);
        show(ok);
        if (!ok && wanted) {
            main.postDelayed(retry, 5_000);
        }
    }

    private boolean startPipeline() {
        stopPipeline();
        int fps = Prefs.load(this).fps;
        rtsp = new RtspServer(fps);
        encoder = new AvcEncoder(fps);
        try {
            Surface encoderSurface = encoder.start(new AvcEncoder.Listener() {
                @Override
                public void onCodecConfig(byte[] annexb) {
                    RtspServer server = rtsp;
                    if (server != null) {
                        server.onCodecConfig(annexb);
                    }
                }

                @Override
                public void onAccessUnit(byte[] annexb, long presentationTimeUs, boolean key) {
                    RtspServer server = rtsp;
                    if (server != null) {
                        server.onAccessUnit(annexb, presentationTimeUs);
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "encoder: " + message);
                }
            });
            Surface cameraSurface = encoderSurface;
            if (FrameOrientation.needsGl()) {
                gl = new GlFrame();
                cameraSurface = gl.start(encoderSurface, fps);
                if (cameraSurface == null) {
                    Log.w(TAG, "gl: " + gl.error());
                    stopPipeline();
                    return false;
                }
            }
            camera = new CameraPipe();
            if (!camera.open(this, cameraSurface)) {
                Log.w(TAG, "camera: " + camera.error());
                stopPipeline();
                return false;
            }
            encoder.requestSync();
            if (!rtsp.awaitParameterSets(3000)) {
                Log.w(TAG, "keine SPS/PPS");
            }
            rtsp.start();
            pipelineUp = true;
            Log.i(TAG, "pipeline on " + fps + " fps");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "pipeline: " + scrub(e.getMessage()));
            stopPipeline();
            return false;
        }
    }

    private void stopPipeline() {
        pipelineUp = false;
        CameraPipe currentCamera = camera;
        camera = null;
        if (currentCamera != null) {
            currentCamera.close();
        }
        GlFrame currentGl = gl;
        gl = null;
        if (currentGl != null) {
            currentGl.release();
        }
        AvcEncoder currentEncoder = encoder;
        encoder = null;
        if (currentEncoder != null) {
            currentEncoder.stop();
        }
        RtspServer currentRtsp = rtsp;
        rtsp = null;
        if (currentRtsp != null) {
            currentRtsp.stop();
        }
    }

    private void connectNow() {
        if (!running) {
            return;
        }
        final int gen = generation;
        closeClient();
        if (gen != generation || !running) {
            return;
        }
        Prefs.Config cfg = Prefs.load(this);
        setStatus("Verbinde…", "");
        String uri = "tcp://" + cfg.host + ":" + cfg.port;
        try {
            MqttAsyncClient created = new MqttAsyncClient(uri, clientId(), new MemoryPersistence());
            created.setCallback(callback);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(15);
            options.setMaxReconnectDelay(30_000);
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setWill(
                    Payloads.AVAILABILITY,
                    "offline".getBytes(StandardCharsets.UTF_8),
                    1,
                    true);
            if (!cfg.user.isEmpty()) {
                options.setUserName(cfg.user);
                options.setPassword(cfg.password.toCharArray());
            }
            if (gen != generation || !running) {
                created.close();
                return;
            }
            client = created;
            created.connect(options).waitForCompletion(15_000);
            if (gen != generation || !running) {
                closeClient();
            }
        } catch (Exception e) {
            if (gen != generation || !running) {
                return;
            }
            String message = scrub(explain(e));
            Log.w(TAG, "connect failed: " + message);
            setStatus("Nicht verbunden", message);
            scheduleRetry(gen);
        }
    }

    private void onConnected() {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        final int ticket = ++connectGen;
        commandSeen.set(false);
        try {
            current.subscribe(Payloads.COMMAND, 1).waitForCompletion(5_000);
            publish(Payloads.DISCOVERY, Payloads.discovery(BuildConfig.VERSION_NAME), true, false);
            publish(Payloads.AVAILABILITY, "online", true, false);
            if (pipelineUp) {
                publish(Payloads.STATE, Payloads.statePayload(true), true, false);
            }
            Log.i(TAG, "MQTT bereit");
            setStatus("Verbunden", "");
        } catch (Exception e) {
            String message = scrub(explain(e));
            Log.w(TAG, "setup failed: " + message);
            setStatus("Nicht verbunden", message);
        }
        main.postDelayed(() -> io.execute(() -> {
            if (!running || ticket != connectGen || commandSeen.get()) {
                return;
            }
            Log.i(TAG, "kein behaltenes kommando, bleibe AUS");
            publish(Payloads.STATE, Payloads.statePayload(false), true, false);
            control.execute(() -> {
                if (!commandSeen.get()) {
                    setWanted(false);
                }
            });
        }), 1_000);
    }

    private void scheduleRetry(int gen) {
        main.postDelayed(() -> {
            if (!running || gen != generation) {
                return;
            }
            io.execute(this::connectNow);
        }, 5_000);
    }

    private void publish(String topic, String payload, boolean retain, boolean wait) {
        MqttAsyncClient current = client;
        if (current == null || !current.isConnected()) {
            return;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            message.setRetained(retain);
            if (wait) {
                current.publish(topic, message).waitForCompletion(2_000);
            } else {
                current.publish(topic, message);
            }
        } catch (Exception e) {
            Log.w(TAG, "publish failed: " + scrub(e.getMessage()));
        }
    }

    private void closeClient() {
        MqttAsyncClient current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnect().waitForCompletion(1_000);
            }
        } catch (Exception ignored) {
        }
        try {
            current.close();
        } catch (Exception ignored) {
        }
    }

    private String clientId() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.isEmpty()) {
            androidId = "panel";
        }
        String suffix = androidId.length() > 12 ? androidId.substring(androidId.length() - 12) : androidId;
        return "wpcam-" + suffix;
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Wallpanel Kamera", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("RTSP-Kamera des Wallpanels");
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startInForeground(boolean on) {
        Notification notification = notification(on);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (Build.VERSION.SDK_INT >= 30) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                }
                try {
                    startForeground(NOTIF_ID, notification, type);
                } catch (SecurityException cameraType) {
                    Log.w(TAG, "foreground camera type denied");
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Vordergrunddienst nicht erlaubt: " + scrub(e.getMessage()));
        }
    }

    private void show(boolean on) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIF_ID, notification(on));
        } catch (SecurityException e) {
            Log.w(TAG, "notify failed");
        }
    }

    private Notification notification(boolean on) {
        Intent open = new Intent(this, SettingsActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Wallpanel Kamera")
                .setContentText(on ? "Kamera an, " + Prefs.load(this).fps + " fps" : "Kamera aus")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .build();
    }

    private void setStatus(String status, String error) {
        statusText = status;
        errorText = error == null ? "" : error;
        String statusNow = statusText;
        String errorNow = errorText;
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onStatus(statusNow, errorNow);
            }
        });
    }

    private String scrub(String message) {
        if (message == null) {
            return "";
        }
        String pass = Prefs.load(this).password;
        if (pass != null && pass.length() >= 4 && message.contains(pass)) {
            return message.replace(pass, "***");
        }
        return message;
    }

    static String explain(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MqttException mqtt) {
                int code = mqtt.getReasonCode();
                if (code == 4 || code == 5) {
                    return "Anmeldung abgelehnt";
                }
                String message = mqtt.getMessage() == null ? "" : mqtt.getMessage();
                return "MQTT " + code + (message.isEmpty() ? "" : ": " + message);
            }
            current = current.getCause();
        }
        if (error.getMessage() == null || error.getMessage().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getMessage();
    }
}
