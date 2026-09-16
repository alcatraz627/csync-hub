package com.csync.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * The receiver half of the mesh on the phone: a small HTTP server, run as a
 * foreground service so Android keeps it alive, that accepts text, files, and
 * images from other peers into the app's inbox. Same contract as the desktop
 * agent (/whoami, /send), so any peer can send here without special-casing Android.
 */
public class MeshService extends Service {

    private static final String CHANNEL = "csync";
    private static final int NOTE_ID = 7001;
    static volatile boolean running = false;
    static volatile String boundInfo = "";

    private Server server;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTE_ID, buildNote("csync receiving", "Ready for shares over the tailnet"));
        String host = MeshClient.tailnetIP(); // tailnet-only when up, else all interfaces
        try {
            server = new Server(host, MeshClient.PORT);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            running = true;
            boundInfo = (host == null ? "0.0.0.0" : host) + ":" + MeshClient.PORT;
            Log.i("csynchub", "receiver listening on " + boundInfo);
        } catch (Throwable e) {
            Log.e("csynchub", "receiver failed to start", e);
            boundInfo = "failed: " + e.getMessage();
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private class Server extends NanoHTTPD {
        Server(String host, int port) { super(host, port); }

        @Override
        public Response serve(IHTTPSession s) {
            String uri = s.getUri();
            Map<String, String> h = s.getHeaders(); // NanoHTTPD lowercases header names

            if ("/whoami".equals(uri)) {
                return json("{\"name\":\"" + Prefs.deviceName(MeshService.this)
                        + "\",\"platform\":\"android\",\"version\":\"0.1.0\","
                        + "\"caps\":[\"text\",\"file\",\"image\"]}");
            }

            String token = Prefs.token(MeshService.this);
            String got = h.get("x-csync-token");
            if (token.isEmpty() || got == null || !got.equals(token)) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized");
            }

            if ("/peers".equals(uri)) {
                return json("[]"); // the phone has no tailscale CLI; discover via a desktop peer
            }

            if ("/send".equals(uri) && s.getMethod() == Method.POST) {
                return handleSend(s, h);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
        }

        private Response handleSend(IHTTPSession s, Map<String, String> h) {
            try {
                String from = sanitize(h.get("x-csync-from"), "unknown");
                String kind = val(h.get("x-csync-kind"), "file");
                String ts = String.valueOf(System.currentTimeMillis());
                String name = sanitize(h.get("x-csync-name"),
                        kind.equals("text") ? "text-" + ts + ".txt" : "item-" + ts);

                int len = 0;
                try { len = Integer.parseInt(val(h.get("content-length"), "0")); } catch (Exception ignore) {}

                File dir = new File(getExternalFilesDir("inbox"), from);
                dir.mkdirs();
                File dest = new File(dir, name);

                InputStream in = s.getInputStream();
                byte[] body = new byte[Math.max(len, 0)];
                int off = 0;
                while (off < len) {
                    int r = in.read(body, off, len - off);
                    if (r < 0) break;
                    off += r;
                }
                FileOutputStream fo = new FileOutputStream(dest);
                fo.write(body, 0, off);
                fo.close();

                if (kind.equals("text")) {
                    final String text = new String(body, 0, off, "UTF-8");
                    main.post(new Runnable() {
                        public void run() { copyToClipboard(text); }
                    });
                }
                main.post(new Runnable() {
                    public void run() { toast("csync: " + kind + " from " + from); }
                });

                return json("{\"ok\":true,\"saved\":\"" + dest.getAbsolutePath() + "\",\"bytes\":" + off + "}");
            } catch (Throwable e) {
                Log.e("csynchub", "receive failed", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: " + e.getMessage());
            }
        }

        private Response json(String body) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", body);
        }
    }

    private static String val(String s, String fallback) {
        return (s == null || s.trim().isEmpty()) ? fallback : s.trim();
    }

    private static String sanitize(String s, String fallback) {
        if (s == null) return fallback;
        s = new File(s.trim()).getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return fallback;
        return s;
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("csync", text));
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private Notification buildNote(String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "csync",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            return new Notification.Builder(this, CHANNEL)
                    .setContentTitle(title).setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).build();
        }
        return new Notification.Builder(this)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).build();
    }
}
