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
    private int captureCount;
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
            "Photos and recordings", null, true);
        Kit.bindRow(root.findViewById(R.id.camera_show_display), Kit.Icon.DISPLAY, "Show on Pi screen",
            "The live picture", null, false);
        root.findViewById(R.id.camera_show_display).setOnClickListener(v -> showOnPiScreen());
        root.findViewById(R.id.camera_captures_row).setOnClickListener(v -> openCaptures());
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
        status.setText("Connecting");
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
        status.setText("The live picture is closed");
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
        Kit.Open open = id -> {
            boolean wasCaptures = showingCaptures;
            if (wasCaptures) closeCaptures();
            if (!"camera".equals(id)) activity.getOnBackPressedDispatcher().onBackPressed();
        };
        Kit.pageTop(top, showingCaptures ? "captures" : "camera", open);
        if (!showingCaptures) Kit.topAction(top, Kit.Icon.PHOTO, "Captures", v -> openCaptures());
        else Kit.topAction(top, R.drawable.csi_refresh, "Read the list again", v -> loadCaptures());
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
        status.setText("Connecting");
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
                            status.setText(recording ? "Recording" : "Live");
                    }
                });
            }
        } catch (Exception error) {
            ui.post(() -> {
                if (visible && !showingCaptures && generation == streamGeneration) {
                    previewReady = false;
                    updateControls();
                    status.setText("The camera cannot be reached. " + error.getMessage());
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
                        status.setText("The recording stopped. " + error);
                    else if (!actionInFlight && recording) status.setText("Recording");
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (visible) {
                        statusAvailable = false;
                        updateControls();
                        status.setText("The camera cannot be reached. " + error.getMessage());
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

    /** Put the live picture on the Pi screen and open that page, where Stop showing takes it down. */
    private void showOnPiScreen() {
        MediaClient active = client == null ? new MediaClient(Prefs.assistIp(activity), Prefs.token(activity)) : client;
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try { lit = active.post("/v1/display/camera", null).optBoolean("sentToDisplay"); }
            catch (Exception error) { problem = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String failure = problem;
            boolean shown = lit;
            ui.post(() -> {
                if (failure != null) { Kit.sheet(activity, "It was not shown", failure); return; }
                android.widget.Toast.makeText(activity, shown ? "Showing the camera on the Pi screen" : "Sent. The Pi screen is off",
                    android.widget.Toast.LENGTH_SHORT).show();
                activity.startActivity(new android.content.Intent(activity, MediaActivity.class).putExtra("player_target", "pi"));
            });
        }, "camera-to-screen").start();
    }

    private void action(String path) {
        if (actionInFlight || client == null) return;
        actionInFlight = true;
        boolean stop = path.endsWith("stop");
        status.setText(stop ? "Finishing the recording" : path.endsWith("start") ?
            "Starting to record" : "Taking a photo");
        updateControls();
        MediaClient active = client;
        new Thread(() -> {
            try {
                JSONObject result = active.post(path, new JSONObject());
                ui.post(() -> {
                    if (result.has("recording")) recording = result.optBoolean("recording");
                    actionInFlight = false;
                    updateControls();
                    if (visible) status.setText(stop ? "The recording is saved in Captures" :
                        path.endsWith("start") ? "Recording" : "The photo is saved in Captures");
                    if (showingCaptures) loadCaptures();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    actionInFlight = false;
                    updateControls();
                    if (visible) status.setText("It did not work. " + error.getMessage());
                    checkStatus();
                });
            }
        }, "pi-camera-action").start();
    }

    private void loadCaptures() {
        MediaClient active = client;
        if (active == null) return;
        int generation = ++listGeneration;
        sayCaptures("Looking on the Pi");
        new Thread(() -> {
            try {
                JSONArray files = active.get("/v1/camera/captures").getJSONArray("captures");
                ui.post(() -> {
                    if (generation != listGeneration) return;
                    captures.removeAllViews();
                    captureCount = 0;
                    // The Pi lists the newest first, so each day's captures arrive together.
                    String day = null;
                    LinearLayout group = null;
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject file = files.optJSONObject(i);
                        if (file == null) continue;
                        String name = file.optString("name");
                        if (!name.endsWith(".jpg") && !name.endsWith(".mp4") && !name.endsWith(".mjpeg")) continue;
                        String taken = captureDay(name);
                        if (group == null || !taken.equals(day)) {
                            day = taken;
                            Kit.label(captures, day);
                            group = Kit.group(captures);
                        }
                        long bytes = file.optLong("bytes");
                        View row = Kit.addRow(group);
                        Kit.bindRow(row, name.endsWith(".jpg") ? Kit.Icon.PHOTO : Kit.Icon.VIDEO, captureTitle(name),
                            captureDetail(name, bytes), null, true);
                        row.setOnClickListener(v -> showCapture(name, bytes));
                        captureCount++;
                    }
                    loadedCapturesWords();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (generation == listGeneration)
                        sayCaptures("The list could not be read from the Pi. " + error.getMessage());
                });
            }
        }, "pi-camera-list").start();
    }

    /** When a capture was taken, read from the time in its file name. Null when the name carries none. */
    private Date captureTime(String name) {
        java.util.regex.Matcher stamp = java.util.regex.Pattern.compile("(\\d{8}-\\d{6})").matcher(name);
        if (!stamp.find()) return null;
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
            input.setTimeZone(TimeZone.getTimeZone("UTC"));
            return input.parse(stamp.group(1));
        } catch (Exception unreadable) { return null; }
    }

    /** The day a capture belongs under: Today, Yesterday, or the date. */
    private String captureDay(String name) {
        Date taken = captureTime(name);
        if (taken == null) return "Earlier";
        if (android.text.format.DateUtils.isToday(taken.getTime())) return "Today";
        if (android.text.format.DateUtils.isToday(taken.getTime() + 86400000L)) return "Yesterday";
        return android.text.format.DateUtils.formatDateTime(activity, taken.getTime(),
            android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** What a capture is called: Photo or Recording for the camera's own, else the name of the file that was kept here. */
    private String captureTitle(String name) {
        int dot = name.lastIndexOf('.');
        // What is left of the name once the time and the camera's own prefix are taken away.
        String own = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').replaceAll("\\d{8}-\\d{6}", "")
            .replaceAll("(?i)\\b(photo|video|cap|vid|img)\\b", "").replaceAll("^[\\d\\s-]+|[\\d\\s-]+$", "");
        if (!own.isEmpty()) return own;
        return name.endsWith(".jpg") ? "Photo" : name.endsWith(".mjpeg") ? "Recording, not converted" : "Recording";
    }

    private String captureDetail(String name, long bytes) {
        Date taken = captureTime(name);
        String size = bytes > 0 ? android.text.format.Formatter.formatShortFileSize(activity, bytes) : null;
        String time = taken == null ? null : android.text.format.DateFormat.getTimeFormat(activity).format(taken);
        return time == null ? size : size == null ? time : time + " · " + size;
    }

    /** A capture named in a sentence, such as "Photo from 7:22 AM". */
    private String captureLabel(String name) {
        Date taken = captureTime(name);
        return taken == null ? captureTitle(name)
            : captureTitle(name) + " from " + android.text.format.DateFormat.getTimeFormat(activity).format(taken);
    }

    private void showCapture(String name, long bytes) {
        // A recording that was never converted is kept as a plain file: no player here or on the Pi screen reads it.
        ItemActions.Kind kind = name.endsWith(".jpg") ? ItemActions.Kind.IMAGE
            : name.endsWith(".mp4") ? ItemActions.Kind.VIDEO : ItemActions.Kind.DOCUMENT;
        ItemActions.Item item = new ItemActions.Item(kind, captureTitle(name));
        String detail = captureDetail(name, bytes);
        item.sub = captureDay(name) + (detail == null ? "" : ", " + detail);
        item.file = got -> fetchCapture(name, got);
        item.own.put(ItemActions.Act.SAVE, () -> download(name));
        item.more.add(new Kit.Action(R.drawable.csi_trash, "Delete", null, () ->
            Kit.confirm(activity, "Delete this " + (kind == ItemActions.Kind.IMAGE ? "photo" : "recording") + "?",
                "It is removed from the Pi.", R.drawable.csi_trash, "Delete", () -> deleteCapture(name)), true));
        ItemActions.sheet(activity, item);
    }

    private void deleteCapture(String name) {
        MediaClient active = client;
        sayCaptures("Deleting it from the Pi");
        new Thread(() -> {
            String problem = null;
            try { active.delete("/v1/camera/captures/" + MediaClient.enc(name), null); }
            catch (Exception error) { problem = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String failure = problem;
            ui.post(() -> {
                if (failure == null) { loadCaptures(); return; }
                loadedCapturesWords();
                Kit.sheet(activity, "It was not deleted", failure,
                    new Kit.Action(R.drawable.csi_refresh, "Try again", null, () -> deleteCapture(name)));
            });
        }, "pi-camera-delete").start();
    }

    private static String mime(String name) {
        return name.endsWith(".jpg") ? "image/jpeg" :
            name.endsWith(".mjpeg") ? "video/x-motion-jpeg" : "video/mp4";
    }

    /** Bring a capture from the Pi onto this phone, then hand it on as a file that can be read. */
    private void fetchCapture(String name, ItemActions.Got got) {
        if (captureTransferInFlight) return;
        captureTransferInFlight = true;
        sayCaptures("Getting " + captureLabel(name) + " from the Pi");
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
                    loadedCapturesWords();
                    got.file(uri, mime(name));
                });
            } catch (Exception error) {
                ui.post(() -> {
                    captureTransferInFlight = false;
                    sayCaptures("It could not be fetched from the Pi. " + error.getMessage());
                });
            } finally { if (connection != null) connection.disconnect(); }
        }, "pi-camera-fetch").start();
    }

    /** Put the count of captures back on the status line after a passing message. */
    private void loadedCapturesWords() {
        sayCaptures(captureCount == 0 ? "No captures yet. Photos and recordings from the Pi camera are kept here." : null);
    }

    /** Say what is happening to the captures, or clear the line with null once there is nothing to say. */
    private void sayCaptures(String words) {
        capturesStatus.setText(words);
        capturesStatus.setVisibility(words == null ? View.GONE : View.VISIBLE);
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
        sayCaptures("Saving " + captureLabel(name) + " on this phone");
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
                    loadedCapturesWords();
                    android.widget.Toast.makeText(activity, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                if (saved != null && chosenUri == null) {
                    try { activity.getContentResolver().delete(saved, null, null); }
                    catch (Exception ignored) { }
                }
                ui.post(() -> {
                    captureTransferInFlight = false;
                    sayCaptures("It was not saved. " + error.getMessage());
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
