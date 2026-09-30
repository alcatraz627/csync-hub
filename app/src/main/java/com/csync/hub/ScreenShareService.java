package com.csync.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;

/**
 * Put this phone's screen, or one app on it, on the Pi screen.
 *
 * Android's own capture dialog decides whether the whole screen or a single app is shared.
 * Frames are taken from a virtual display, turned into small JPEGs, and sent to the Pi in
 * one long request; the Pi draws each as it arrives. Sharing ends when the Pi stops the
 * screen (the request is closed from that end), when the notification's Stop is tapped, or
 * when the projection is taken away by the system.
 */
public final class ScreenShareService extends Service {
    private static final String CHANNEL = "csync";
    private static final int NOTIFICATION = 7301;
    private static final String ACTION_STOP = "com.csync.hub.SCREEN_SHARE_STOP";
    // A modest size and rate keep the tailnet comfortable; the Pi screen is far from the eye.
    private static final int LONG_SIDE = 960;
    private static final int FRAME_MS = 125;

    static volatile boolean running;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Thread sender;
    private volatile boolean stopping;

    static void start(Context c, int resultCode, Intent data) {
        Intent intent = new Intent(c, ScreenShareService.class)
            .putExtra("code", resultCode).putExtra("data", data);
        androidx.core.content.ContextCompat.startForegroundService(c, intent);
    }

    static void stop(Context c) {
        c.startService(new Intent(c, ScreenShareService.class).setAction(ACTION_STOP));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { end(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        ensureChannel();
        Notification notice = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.csi_screen)
            .setContentTitle("Sharing this screen with the Pi")
            .setContentText("Stop from here, or from the Pi screen page.")
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop",
                PendingIntent.getService(this, 1, new Intent(this, ScreenShareService.class).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIFICATION, notice);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent data = intent.getParcelableExtra("data");
        projection = data == null ? null : manager.getMediaProjection(intent.getIntExtra("code", 0), data);
        if (projection == null) { end(); return START_NOT_STICKY; }
        running = true;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { end(); }
        }, null);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels, height = metrics.heightPixels;
        float scale = Math.min(1f, LONG_SIDE / (float) Math.max(width, height));
        int w = Math.max(2, Math.round(width * scale) / 2 * 2), h = Math.max(2, Math.round(height * scale) / 2 * 2);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("csync-screen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        sender = new Thread(this::send, "screen-share");
        sender.start();
        return START_NOT_STICKY;
    }

    /** Take the latest frame every FRAME_MS and write it as a JPEG; any failure ends the sharing. */
    private void send() {
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + host + ":8792/v1/display/screen?name="
                + MediaClient.enc(Prefs.deviceName(this) + " screen")).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(0);
            connection.setRequestProperty("X-Csync-Token", token);
            connection.setRequestProperty("Content-Type", "video/x-motion-jpeg");
            connection.setDoOutput(true);
            connection.setChunkedStreamingMode(0);
            try (OutputStream out = connection.getOutputStream()) {
                Bitmap canvas = null;
                ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                while (!stopping) {
                    long started = System.currentTimeMillis();
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        try {
                            Image.Plane plane = image.getPlanes()[0];
                            ByteBuffer buffer = plane.getBuffer();
                            int stride = plane.getRowStride() / plane.getPixelStride();
                            if (canvas == null || canvas.getWidth() != stride)
                                canvas = Bitmap.createBitmap(stride, image.getHeight(), Bitmap.Config.ARGB_8888);
                            canvas.copyPixelsFromBuffer(buffer);
                            Bitmap frame = stride == image.getWidth() ? canvas
                                : Bitmap.createBitmap(canvas, 0, 0, image.getWidth(), image.getHeight());
                            jpeg.reset();
                            frame.compress(Bitmap.CompressFormat.JPEG, 60, jpeg);
                            out.write(jpeg.toByteArray());
                            out.flush();
                        } finally { image.close(); }
                    }
                    long wait = FRAME_MS - (System.currentTimeMillis() - started);
                    if (wait > 0) Thread.sleep(wait);
                }
            }
            connection.getResponseCode();
        } catch (Exception ended) {
            // The Pi closed the request (Stop showing), the network went, or the loop was told to stop.
        } finally {
            if (connection != null) connection.disconnect();
            end();
        }
    }

    private void end() {
        stopping = true;
        running = false;
        if (display != null) { display.release(); display = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (reader != null) { reader.close(); reader = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        end();
        super.onDestroy();
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "csync", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
