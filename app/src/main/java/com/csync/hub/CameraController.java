package com.csync.hub;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/** Starts a Pi camera preview only while its tab is visible. */
final class CameraController {
    private final AppCompatActivity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView preview;
    private final TextView status;
    private final Button record;
    private final LinearLayout captures;
    private volatile boolean visible;
    private volatile HttpURLConnection stream;
    private boolean recording;
    private volatile String recordingError;
    private final AtomicBoolean statusInFlight = new AtomicBoolean();
    private MediaClient client;
    private final Runnable pollStatus = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            checkStatus();
            ui.postDelayed(this, 2000);
        }
    };

    CameraController(AppCompatActivity activity, View root) {
        this.activity = activity;
        preview = root.findViewById(R.id.camera_preview);
        status = root.findViewById(R.id.camera_status);
        record = root.findViewById(R.id.camera_record);
        captures = root.findViewById(R.id.camera_files);
        root.findViewById(R.id.camera_photo).setOnClickListener(v -> action("/v1/camera/photo", "Photo saved on Pi"));
        record.setOnClickListener(v -> {
            String path = recording ? "/v1/camera/record/stop" : "/v1/camera/record/start";
            action(path, recording ? "Recording saved on Pi" : "Recording on Pi");
        });
        root.findViewById(R.id.camera_captures).setOnClickListener(v -> loadCaptures());
    }

    void show() {
        if (visible) return;
        visible = true;
        client = new MediaClient(Prefs.assistIp(activity), Prefs.token(activity));
        status.setText("Connecting to Pi camera…");
        new Thread(this::readStream, "pi-camera-preview").start();
        loadCaptures();
        ui.post(pollStatus);
    }

    void hide() {
        if (!visible) return;
        visible = false;
        ui.removeCallbacks(pollStatus);
        HttpURLConnection open = stream;
        if (open != null) open.disconnect();
        preview.setImageDrawable(null);
        status.setText("Camera stream closed");
        if (recording) action("/v1/camera/record/stop", "Recording saved on Pi");
    }

    private void readStream() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(client.url("/v1/camera/stream")).openConnection();
            stream = connection;
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("X-Csync-Token", client.token());
            if (connection.getResponseCode() != 200) throw new Exception("Camera stream unavailable (" + connection.getResponseCode() + ")");
            DataInputStream input = new DataInputStream(connection.getInputStream());
            while (visible) {
                String line = input.readLine();
                if (line == null) break;
                if (!line.equals("--frame")) continue;
                int length = -1;
                while (!(line = input.readLine()).isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                }
                if (length < 1 || length > 4000000) throw new Exception("Camera sent an invalid frame");
                byte[] frame = new byte[length];
                input.readFully(frame);
                Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length);
                if (bitmap != null) ui.post(() -> { if (visible) {
                    preview.setImageBitmap(bitmap);
                    if (recordingError == null) status.setText(recording ? "Recording on Pi" : "Live Pi camera");
                } });
            }
        } catch (Exception error) {
            ui.post(() -> { if (visible) status.setText("Camera: " + error.getMessage()); });
        } finally {
            if (connection != null) connection.disconnect();
            if (stream == connection) stream = null;
        }
    }

    private void checkStatus() {
        MediaClient active = client;
        if (active == null || !statusInFlight.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject observed = active.get("/v1/camera/status");
                ui.post(() -> {
                    if (!visible) return;
                    recording = observed.optBoolean("recording");
                    recordingError = observed.optString("recordingError", "");
                    if (recordingError.isEmpty()) recordingError = null;
                    record.setText(recording ? "Stop" : "Record");
                    record.setEnabled(true);
                    if (recordingError != null) status.setText("Camera: " + recordingError);
                    else if (recording) status.setText("Recording on Pi");
                });
            } catch (Exception error) {
                ui.post(() -> { if (visible) {
                    record.setEnabled(false);
                    status.setText("Camera status unavailable: " + error.getMessage());
                } });
            } finally { statusInFlight.set(false); }
        }, "pi-camera-status").start();
    }

    private void action(String path, String success) {
        MediaClient active = client;
        if (active == null) return;
        status.setText(path.endsWith("stop") ? "Finishing recording…" : "Working…");
        new Thread(() -> {
            try {
                JSONObject result = active.post(path, new JSONObject());
                ui.post(() -> {
                    if (result.has("recording")) {
                        recording = result.optBoolean("recording");
                        record.setText(recording ? "Stop" : "Record");
                    }
                    status.setText(success + (result.has("name") ? ": " + result.optString("name") : ""));
                    loadCaptures();
                });
            } catch (Exception error) { ui.post(() -> {
                status.setText("Camera: " + error.getMessage());
                checkStatus();
            }); }
        }, "pi-camera-action").start();
    }

    private void loadCaptures() {
        MediaClient active = client;
        if (active == null) return;
        new Thread(() -> {
            try {
                JSONArray files = active.get("/v1/camera/captures").getJSONArray("captures");
                ui.post(() -> {
                    captures.removeAllViews();
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null) continue;
                        String name = file.optString("name");
                        TextView row = new TextView(activity);
                        row.setText(name + " · tap to save on phone");
                        row.setTextColor(activity.getColor(R.color.text));
                        row.setPadding(12, 12, 12, 12);
                        row.setOnClickListener(v -> download(name));
                        captures.addView(row);
                    }
                });
            } catch (Exception error) { ui.post(() -> { if (visible) status.setText("Captures: " + error.getMessage()); }); }
        }, "pi-camera-list").start();
    }

    private void download(String name) {
        MediaClient active = client;
        status.setText("Saving " + name + " to phone…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(active.url("/v1/camera/captures/" + MediaClient.enc(name))).openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("X-Csync-Token", active.token());
                if (connection.getResponseCode() != 200) throw new Exception("Download failed: " + connection.getResponseCode());
                String mime = name.endsWith(".jpg") ? "image/jpeg" : "video/mp4";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/csync");
                Uri saved = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new Exception("Phone storage unavailable");
                try (InputStream input = connection.getInputStream(); OutputStream out = activity.getContentResolver().openOutputStream(saved)) {
                    if (out == null) throw new Exception("Phone storage unavailable");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                ui.post(() -> {
                    status.setText("Saved to Downloads/csync: " + name);
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(saved, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { activity.startActivity(view); } catch (Exception ignored) {}
                });
            } catch (Exception error) { ui.post(() -> status.setText("Save failed: " + error.getMessage())); }
            finally { if (connection != null) connection.disconnect(); }
        }, "pi-camera-download").start();
    }
}
