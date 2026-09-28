package com.csync.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the on-demand Pi preview and its saved Captures child page. */
final class CameraController {
    private final AppCompatActivity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ImageView preview;
    private final TextView status;
    private final TextView capturesStatus;
    private final View top;
    private final View cameraPage;
    private final View capturesPage;
    private final ImageButton photo;
    private final ImageButton record;
    private final LinearLayout captures;
    private final OnBackPressedCallback capturesBack;
    private final ActivityResultLauncher<Intent> legacyCapturePicker;
    private volatile boolean visible;
    private volatile HttpURLConnection stream;
    private volatile int streamGeneration;
    private volatile int listGeneration;
    private boolean showingCaptures;
    private boolean recording;
    private boolean statusAvailable;
    private boolean previewReady;
    private boolean actionInFlight;
    private boolean captureTransferInFlight;
    private String pendingLegacyCapture;
    private MediaClient client;
    private final AtomicBoolean statusInFlight = new AtomicBoolean();
    private final Runnable pollStatus = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            checkStatus();
            ui.postDelayed(this, 2000);
        }
    };

    CameraController(AppCompatActivity activity, View root) {
        this.activity = activity;
        legacyCapturePicker = activity.registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                String name = pendingLegacyCapture;
                if (name == null) name = activity.getSharedPreferences("csync_camera", Context.MODE_PRIVATE)
                    .getString("pending_capture", null);
                pendingLegacyCapture = null;
                activity.getSharedPreferences("csync_camera", Context.MODE_PRIVATE).edit()
                    .remove("pending_capture").apply();
                Intent data = result.getData();
                if (name != null && result.getResultCode() == Activity.RESULT_OK &&
                    data != null && data.getData() != null) downloadToUri(name, data.getData());
            });
        preview = root.findViewById(R.id.camera_preview);
        status = root.findViewById(R.id.camera_status);
        capturesStatus = root.findViewById(R.id.captures_status);
        top = root.findViewById(R.id.camera_top);
        cameraPage = root.findViewById(R.id.camera_page);
        capturesPage = root.findViewById(R.id.captures_page);
        photo = root.findViewById(R.id.camera_photo);
        record = root.findViewById(R.id.camera_record);
        captures = root.findViewById(R.id.camera_files);
        photo.setOnClickListener(v -> action("/v1/camera/photo"));
        record.setOnClickListener(v -> action(recording ? "/v1/camera/record/stop" : "/v1/camera/record/start"));
        root.findViewById(R.id.camera_captures).setOnClickListener(v -> openCaptures());
        Kit.bindRow(root.findViewById(R.id.camera_captures_row), Kit.Icon.PHOTO, "Captures",
            "Photos and recordings from the Pi", null, true);
        Kit.bindRow(root.findViewById(R.id.camera_show_display), Kit.Icon.DISPLAY, "Pi display",
            "Open screen and playback controls", null, true);
        Kit.bindRow(root.findViewById(R.id.captures_storage), R.drawable.csi_refresh, "Saved captures",
            "Pi camera folder · refresh list", null, false);
        root.findViewById(R.id.camera_captures_row).setOnClickListener(v -> openCaptures());
        root.findViewById(R.id.captures_storage).setOnClickListener(v -> loadCaptures());
        capturesBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { closeCaptures(); }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, capturesBack);
    }

    void show() {
        if (visible) return;
        visible = true;
        client = new MediaClient(Prefs.assistIp(activity), Prefs.token(activity));
        renderPage();
        status.setText("Connecting to Pi camera");
        statusAvailable = false;
        updateControls();
        if (!showingCaptures) startPreview();
        if (showingCaptures) loadCaptures();
        ui.post(pollStatus);
    }

    void hide() {
        if (!visible) return;
        visible = false;
        closePreview();
        ++listGeneration;
        ui.removeCallbacks(pollStatus);
        status.setText("Camera stream closed");
        capturesBack.setEnabled(false);
        // The Pi finishes a recording when the stream's final listener unsubscribes.
    }

    boolean closeChildPage() {
        if (!showingCaptures) return false;
        closeCaptures();
        return true;
    }

    private void renderPage() {
        cameraPage.setVisibility(showingCaptures ? View.GONE : View.VISIBLE);
        capturesPage.setVisibility(showingCaptures ? View.VISIBLE : View.GONE);
        Kit.Crumb home = new Kit.Crumb(Kit.Icon.HOME, "Home", activity::onBackPressed);
        if (showingCaptures) {
            Kit.pageTop(top, this::closeCaptures, home,
                new Kit.Crumb(Kit.Icon.CAMERA, "Camera", this::closeCaptures),
                new Kit.Crumb(Kit.Icon.PHOTO, "Captures", null));
        } else {
            Kit.pageTop(top, activity::onBackPressed, home, new Kit.Crumb(Kit.Icon.CAMERA, "Camera", null));
            Kit.topAction(top, Kit.Icon.PHOTO, "Open Pi captures", v -> openCaptures());
        }
        capturesBack.setEnabled(visible && showingCaptures);
    }

    private void openCaptures() {
        if (showingCaptures) return;
        showingCaptures = true;
        closePreview();
        renderPage();
        loadCaptures();
    }

    private void closeCaptures() {
        showingCaptures = false;
        renderPage();
        if (visible) startPreview();
    }

    private void startPreview() {
        int generation = ++streamGeneration;
        previewReady = false;
        updateControls();
        status.setText("Connecting to Pi camera");
        new Thread(() -> readStream(generation), "pi-camera-preview").start();
    }

    private void closePreview() {
        ++streamGeneration;
        previewReady = false;
        updateControls();
        HttpURLConnection open = stream;
        if (open != null) open.disconnect();
        stream = null;
        preview.setImageDrawable(null);
    }

    private void readStream(int generation) {
        HttpURLConnection connection = null;
        try {
            if (!visible || showingCaptures || generation != streamGeneration) return;
            MediaClient active = client;
            connection = (HttpURLConnection) new URL(active.url("/v1/camera/stream")).openConnection();
            stream = connection;
            if (!visible || showingCaptures || generation != streamGeneration) return;
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("X-Csync-Token", active.token());
            int response = connection.getResponseCode();
            if (response != 200) throw new Exception("Stream unavailable (" + response + ")");
            DataInputStream input = new DataInputStream(connection.getInputStream());
            while (visible && !showingCaptures && generation == streamGeneration) {
                String line = input.readLine();
                if (line == null) break;
                if (!line.equals("--frame")) continue;
                int length = -1;
                while ((line = input.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                        length = Integer.parseInt(line.substring(15).trim());
                }
                if (length < 1 || length > 4000000) throw new Exception("Invalid camera frame");
                byte[] frame = new byte[length];
                input.readFully(frame);
                Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length);
                if (bitmap != null) ui.post(() -> {
                    if (visible && !showingCaptures && generation == streamGeneration) {
                        preview.setImageBitmap(bitmap);
                        previewReady = true;
                        updateControls();
                        if (!actionInFlight && statusAvailable)
                            status.setText(recording ? "Recording on Pi" : "Preview live");
                    }
                });
            }
        } catch (Exception error) {
            ui.post(() -> {
                if (visible && !showingCaptures && generation == streamGeneration) {
                    previewReady = false;
                    updateControls();
                    status.setText("Camera unavailable: " + error.getMessage());
                }
            });
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
                    if (!actionInFlight) recording = observed.optBoolean("recording");
                    String error = observed.optString("recordingError", "");
                    statusAvailable = true;
                    updateControls();
                    if (!actionInFlight && !error.isEmpty() && !"null".equals(error))
                        status.setText("Camera: " + error);
                    else if (!actionInFlight && recording) status.setText("Recording on Pi");
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (visible) {
                        statusAvailable = false;
                        updateControls();
                        status.setText("Camera status unavailable: " + error.getMessage());
                    }
                });
            } finally { statusInFlight.set(false); }
        }, "pi-camera-status").start();
    }

    private void updateControls() {
        photo.setEnabled(visible && previewReady && statusAvailable && !actionInFlight);
        record.setEnabled(visible && statusAvailable && !actionInFlight &&
            (recording || previewReady));
        record.setContentDescription(recording ? "Stop recording" : "Start recording");
        record.setImageResource(recording ? R.drawable.csi_stop : R.drawable.camera_record_dot);
    }

    private void action(String path) {
        if (actionInFlight || client == null) return;
        actionInFlight = true;
        boolean stop = path.endsWith("stop");
        status.setText(stop ? "Finishing recording on Pi" : path.endsWith("start") ?
            "Starting recording on Pi" : "Taking photo on Pi");
        updateControls();
        MediaClient active = client;
        new Thread(() -> {
            try {
                JSONObject result = active.post(path, new JSONObject());
                ui.post(() -> {
                    if (result.has("recording")) recording = result.optBoolean("recording");
                    actionInFlight = false;
                    updateControls();
                    if (visible) status.setText(stop ? "Recording saved on Pi: " + result.optString("name") :
                        path.endsWith("start") ? "Recording on Pi" : "Photo saved on Pi: " + result.optString("name"));
                    if (showingCaptures) loadCaptures();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    actionInFlight = false;
                    updateControls();
                    if (visible) status.setText("Camera: " + error.getMessage());
                    checkStatus();
                });
            }
        }, "pi-camera-action").start();
    }

    private void loadCaptures() {
        MediaClient active = client;
        if (active == null) return;
        int generation = ++listGeneration;
        capturesStatus.setVisibility(View.VISIBLE);
        capturesStatus.setText("Loading captures from Pi");
        new Thread(() -> {
            try {
                JSONArray files = active.get("/v1/camera/captures").getJSONArray("captures");
                ui.post(() -> {
                    if (generation != listGeneration) return;
                    captures.removeAllViews();
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null) continue;
                        String name = file.optString("name");
                        if (!name.endsWith(".jpg") && !name.endsWith(".mp4") && !name.endsWith(".mjpeg")) continue;
                        addCaptureRow(name, file.optLong("bytes"));
                    }
                    int count = captures.getChildCount() / 2;
                    capturesStatus.setText(count == 0 ? "No captures on Pi yet" :
                        count + " saved on Pi · tap one for actions");
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (generation == listGeneration) capturesStatus.setText("Captures unavailable: " + error.getMessage() + " · tap Saved captures to retry");
                });
            }
        }, "pi-camera-list").start();
    }

    private void addCaptureRow(String name, long bytes) {
        boolean photoFile = name.endsWith(".jpg");
        boolean raw = name.endsWith(".mjpeg");
        View row = Kit.addRow(captures);
        String detail = (raw ? "Raw MJPEG · " : photoFile ? "Photo · " : "Video · ") +
            (bytes > 0 ? android.text.format.Formatter.formatShortFileSize(activity, bytes) : "saved on Pi");
        Kit.bindRow(row, photoFile ? Kit.Icon.PHOTO : Kit.Icon.VIDEO, captureLabel(name), detail, null, true);
        row.setOnClickListener(v -> showCapture(name));
    }

    private String captureLabel(String name) {
        boolean photoFile = name.endsWith(".jpg");
        String type = photoFile ? "Photo" : "Recording";
        try {
            String stamp = name.substring(name.indexOf('-') + 1, name.indexOf('-', name.indexOf('-') + 1) + 7);
            SimpleDateFormat input = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            input.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = input.parse(stamp);
            return type + " · " + android.text.format.DateFormat.getTimeFormat(activity).format(date);
        } catch (Exception ignored) { return type + " · " + name; }
    }

    private void showCapture(String name) {
        boolean raw = name.endsWith(".mjpeg");
        String[] actions = raw ? new String[]{"Save raw MJPEG", "Open in compatible app", "Share raw MJPEG"} :
            new String[]{"Preview / open", "Share", "Save to phone"};
        new AlertDialog.Builder(activity).setTitle(raw ? captureLabel(name) + " · Raw MJPEG" : captureLabel(name))
            .setItems(actions, (dialog, which) -> {
                if (raw) {
                    if (which == 0) download(name);
                    else transferForAction(name, which == 1);
                } else if (which == 2) download(name);
                else transferForAction(name, which == 0);
            }).show();
    }

    private static String mime(String name) {
        return name.endsWith(".jpg") ? "image/jpeg" :
            name.endsWith(".mjpeg") ? "video/x-motion-jpeg" : "video/mp4";
    }

    private void transferForAction(String name, boolean open) {
        if (captureTransferInFlight) return;
        captureTransferInFlight = true;
        capturesStatus.setText("Preparing " + name);
        MediaClient active = client;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                File folder = new File(activity.getCacheDir(), "share");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new Exception("Phone cache unavailable");
                File file = new File(folder, name);
                connection = captureConnection(active, name);
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".share", file);
                ui.post(() -> {
                    captureTransferInFlight = false;
                    capturesStatus.setText("Ready: " + captureLabel(name));
                    launchCapture(uri, name, open, file);
                });
            } catch (Exception error) {
                ui.post(() -> {
                    captureTransferInFlight = false;
                    capturesStatus.setText("Could not prepare capture: " + error.getMessage());
                });
            } finally { if (connection != null) connection.disconnect(); }
        }, "pi-camera-open-share").start();
    }

    private void launchCapture(Uri uri, String name, boolean open, File file) {
        if (open && name.endsWith(".jpg")) {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                capturesStatus.setText("Photo preview unavailable; use Open in another app");
                return;
            }
            ImageView image = new ImageView(activity);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            image.setContentDescription("Preview of " + name);
            new AlertDialog.Builder(activity).setTitle(captureLabel(name))
                .setView(image).setPositiveButton("Open in app", (dialog, which) ->
                    launchExternal(uri, name, true))
                .setNegativeButton("Close", null).show();
            return;
        }
        launchExternal(uri, name, open);
    }

    private void launchExternal(Uri uri, String name, boolean open) {
        String type = mime(name);
        Intent intent = open ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type) :
            new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(activity.getContentResolver(), name, uri));
        try { activity.startActivity(open ? intent : Intent.createChooser(intent, "Share capture")); }
        catch (Exception error) { capturesStatus.setText("No app can open " + type + " on this phone"); }
    }

    private HttpURLConnection captureConnection(MediaClient active, String name) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(active.url("/v1/camera/captures/" + MediaClient.enc(name))).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("X-Csync-Token", active.token());
        if (connection.getResponseCode() != 200) {
            int response = connection.getResponseCode();
            connection.disconnect();
            throw new Exception(response == 404 ? "Capture no longer exists on Pi" : "Pi returned " + response);
        }
        return connection;
    }

    private void download(String name) {
        if (Build.VERSION.SDK_INT < 29) {
            pendingLegacyCapture = name;
            activity.getSharedPreferences("csync_camera", Context.MODE_PRIVATE).edit()
                .putString("pending_capture", name).apply();
            Intent choose = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(mime(name))
                .putExtra(Intent.EXTRA_TITLE, name);
            legacyCapturePicker.launch(choose);
        } else downloadToUri(name, null);
    }

    private void downloadToUri(String name, Uri chosenUri) {
        if (captureTransferInFlight) return;
        captureTransferInFlight = true;
        MediaClient active = client;
        capturesStatus.setText("Saving " + name + " to phone");
        new Thread(() -> {
            HttpURLConnection connection = null;
            Uri saved = chosenUri;
            try {
                connection = captureConnection(active, name);
                if (saved == null) saved = createPendingDownload(name, mime(name));
                if (saved == null) throw new Exception("Phone storage unavailable");
                try (InputStream input = connection.getInputStream(); OutputStream out = activity.getContentResolver().openOutputStream(saved)) {
                    if (out == null) throw new Exception("Phone storage unavailable");
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                if (chosenUri == null) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (activity.getContentResolver().update(saved, ready, null, null) != 1)
                        throw new Exception("Could not finish saving capture");
                }
                ui.post(() -> {
                    captureTransferInFlight = false;
                    capturesStatus.setText("Saved to phone: " + name);
                });
            } catch (Exception error) {
                if (saved != null && chosenUri == null) {
                    try { activity.getContentResolver().delete(saved, null, null); }
                    catch (Exception ignored) { }
                }
                ui.post(() -> {
                    captureTransferInFlight = false;
                    capturesStatus.setText("Save failed: " + error.getMessage());
                });
            } finally { if (connection != null) connection.disconnect(); }
        }, "pi-camera-download").start();
    }

    @RequiresApi(29)
    private Uri createPendingDownload(String name, String type) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, type);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/csync");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        return activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }

    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f); }
}
