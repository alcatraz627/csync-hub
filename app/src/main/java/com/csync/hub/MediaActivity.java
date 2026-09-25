package com.csync.hub;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Drive browser and player controls, independent of Chat. */
public final class MediaActivity extends AppCompatActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaClient client;
    private LinearLayout rows;
    private TextView status, nowPlaying, output;
    private EditText search;
    private SeekBar seek;
    private SurfaceView video;
    private JSONObject selected;
    private String driveId = "", driveLabel = "", path = "", target = "pi";
    private boolean audioOnly;
    private boolean seeking;
    private boolean videoMode;
    private boolean videoControlsVisible;
    private boolean screenActive = true;
    private boolean showingVideos;
    private boolean firstDriveLoad = true;
    private volatile long outputIntent;
    private final java.util.concurrent.atomic.AtomicBoolean stateInFlight = new java.util.concurrent.atomic.AtomicBoolean();
    private final ActivityResultLauncher<String> wallpaperPicker = registerForActivityResult(
        new ActivityResultContracts.GetContent(), uri -> { if (uri != null) uploadWallpaper(uri); });
    private final ActivityResultLauncher<String> mediaPicker = registerForActivityResult(
        new ActivityResultContracts.GetContent(), uri -> { if (uri != null) castPhoneFile(uri); });
    private final PhonePlaybackService.Observer phoneObserver = message -> {
        setStatus(message);
        PhonePlaybackService playback = PhonePlaybackService.current;
        if (playback != null && playback.item() != null)
            nowPlaying.setText("Phone: " + playback.item().optString("name"));
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        client = new MediaClient(Prefs.assistIp(this), Prefs.token(this));
        setContentView(R.layout.activity_media);
        rows = findViewById(R.id.media_rows);
        status = findViewById(R.id.media_status);
        nowPlaying = findViewById(R.id.media_now);
        output = findViewById(R.id.media_output);
        search = findViewById(R.id.media_search);
        seek = findViewById(R.id.media_seek);
        video = findViewById(R.id.media_video);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.media_bottom_nav);
        nav.setSelectedItemId(R.id.nav_media);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_media) return true;
            String destination = item.getItemId() == R.id.nav_share ? "share" :
                item.getItemId() == R.id.nav_chat ? "chat" :
                item.getItemId() == R.id.nav_more ? "more" : "home";
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("destination", destination));
            return false;
        });
        video.setOnClickListener(v -> { if (videoMode) setVideoControls(!videoControlsVisible); });
        findViewById(R.id.media_back).setOnClickListener(v -> {
            if (videoMode) exitVideoMode();
            else if (showingVideos) drives();
            else if (!path.isEmpty()) { int slash = path.lastIndexOf('/'); path = slash < 0 ? "" : path.substring(0, slash); browse(); }
            else if (!driveId.isEmpty()) { driveId = ""; drives(); }
            else finish();
        });
        findViewById(R.id.media_drives).setOnClickListener(v -> { driveId = ""; path = ""; drives(); });
        findViewById(R.id.media_files).setOnClickListener(v -> { selectTab(R.id.media_files); if (driveId.isEmpty()) drives(); else browse(); });
        findViewById(R.id.media_videos).setOnClickListener(v -> { selectTab(R.id.media_videos); videos(0, false); });
        findViewById(R.id.media_find).setOnClickListener(v -> find());
        findViewById(R.id.media_history).setOnClickListener(v -> { selectTab(R.id.media_history); history(); });
        findViewById(R.id.media_connections).setOnClickListener(v -> { selectTab(R.id.media_connections); connections(); });
        findViewById(R.id.media_actions_toggle).setOnClickListener(v -> {
            View actions = findViewById(R.id.media_actions);
            actions.setVisibility(actions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        findViewById(R.id.media_wallpaper).setOnClickListener(v -> wallpaperPicker.launch("image/*"));
        findViewById(R.id.media_cast_file).setOnClickListener(v -> mediaPicker.launch("*/*"));
        findViewById(R.id.media_cast_youtube).setOnClickListener(v -> castYoutube());
        findViewById(R.id.media_pause).setOnClickListener(v -> control("pause"));
        findViewById(R.id.media_resume).setOnClickListener(v -> control("resume"));
        findViewById(R.id.media_stop).setOnClickListener(v -> control("stop"));
        findViewById(R.id.media_mute).setOnClickListener(v -> mute());
        findViewById(R.id.media_volume).setOnClickListener(v -> chooseVolume());
        findViewById(R.id.media_settings).setOnClickListener(v -> chooseSetting());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; controlSeek(bar.getProgress()); }
        });
        video.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) { if (PhonePlaybackService.current != null) PhonePlaybackService.current.setSurface(holder); }
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            public void surfaceDestroyed(SurfaceHolder holder) { if (PhonePlaybackService.current != null) PhonePlaybackService.current.setSurface(null); }
        });
        drives();
        Intent incoming = getIntent();
        if (Intent.ACTION_SEND.equals(incoming.getAction())) {
            Uri stream = incoming.getParcelableExtra(Intent.EXTRA_STREAM);
            String link = incoming.getStringExtra(Intent.EXTRA_TEXT);
            if (stream != null) castPhoneFile(stream);
            else if (link != null) startYoutube(link.trim());
        }
        ui.postDelayed(this::refreshState, 1000);
    }

    private void selectTab(int selectedId) {
        int accent = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, getColor(R.color.coral));
        for (int id : new int[]{R.id.media_files, R.id.media_videos, R.id.media_history, R.id.media_connections}) {
            TextView tab = findViewById(id);
            tab.setTextColor(id == selectedId ? accent : getColor(R.color.dim));
            tab.setTypeface(null, id == selectedId ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface Work { JSONObject run() throws Exception; }
    private interface Show { void accept(JSONObject value) throws Exception; }

    private void uploadWallpaper(Uri uri) {
        setStatus("Preparing screen image…");
        request(() -> {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("Could not read selected image");
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new Exception("Could not decode selected image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 1920 ||
                   bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
            Bitmap bitmap;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("Could not read selected image");
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) throw new Exception("Could not decode selected image");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output);
                if (output.size() > 10 * 1024 * 1024) {
                    output.reset();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 60, output);
                }
            } finally { bitmap.recycle(); }
            return client.uploadWallpaper(output.toByteArray());
        }, result -> setStatus(result.optBoolean("sentToDisplay") ?
            "Screen image saved and sent to Pi" : "Screen image saved; Pi display is unavailable"));
    }

    private void castPhoneFile(Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (Exception error) { setStatus("Could not read the selected file: " + error.getMessage()); return; }
        if (name == null || name.isEmpty() || size <= 0) {
            setStatus("Choose a local media file whose name and size are available");
            return;
        }
        final String fileName = name;
        final long fileSize = size;
        final long intent = ++outputIntent;
        stopPhoneForNewPlayback();
        target = "pi";
        selected = null;
        setStatus("Sending " + fileName + " to Pi USB… 0%");
        request(intent, () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("Could not open selected media");
                return client.uploadMedia("sandisk", fileName, fileSize, input, sent -> {
                    int percent = (int) (sent * 100 / fileSize);
                    ui.post(() -> { if (screenActive && intent == outputIntent)
                        setStatus("Sending " + fileName + " to Pi USB… " + percent + "%"); });
                });
            }
        }, result -> {
            JSONObject item = result.getJSONObject("item");
            setStatus("Saved on Pi USB; opening on projector…");
            playPi(item);
        });
    }

    private void castYoutube() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("https://youtube.com/watch?v=...");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("Play YouTube on Pi")
            .setView(address).setNegativeButton("Cancel", null)
            .setPositiveButton("Play muted", (dialog, which) ->
                startYoutube(address.getText().toString().trim())).show();
    }

    private void startYoutube(String url) {
                final long intent = ++outputIntent;
                stopPhoneForNewPlayback();
                target = "pi";
                selected = null;
                setStatus("Opening YouTube on Pi…");
                request(intent, () -> {
                    if (intent != outputIntent) throw new java.util.concurrent.CancellationException();
                    JSONObject result = client.post("/v1/cast/youtube", new JSONObject().put("url", url));
                    if (intent != outputIntent) stopStalePiStart(result);
                    return result;
                }, result -> {
                    JSONObject player = result.getJSONObject("player");
                    nowPlaying.setText("Pi: " + player.optString("name", "YouTube video"));
                    output.setText("Pi projector · starts muted; choose Volume for sound");
                    setStatus("YouTube playing on Pi");
                });
    }

    private void request(Work work, Show show) {
        request(-1, work, show);
    }

    private void request(long intent, Work work, Show show) {
        new Thread(() -> {
            try {
                JSONObject result = work.run();
                ui.post(() -> {
                    if (!screenActive || (intent >= 0 && intent != outputIntent)) return;
                    try { show.accept(result); } catch (Exception error) { setStatus(error.getMessage()); }
                });
            } catch (Exception error) { ui.post(() -> {
                if (screenActive && (intent < 0 || intent == outputIntent)) setStatus(friendlyError(error));
            }); }
        }, "media-request").start();
    }

    private void stopStalePiStart(JSONObject result) {
        JSONObject player = result.optJSONObject("player");
        if (player == null) return;
        int revision = player.optInt("revision", -1);
        if (revision < 0) return;
        try {
            client.post("/v1/player/pi/commands", new JSONObject()
                .put("action", "stop").put("expectedRevision", revision));
        } catch (Exception ignored) { }
    }

    private String friendlyError(Exception error) {
        if (error instanceof java.net.UnknownHostException) return "Pi name not found. Check Tailscale and the saved Pi name.";
        if (error instanceof java.net.ConnectException) return "Media service is not answering on the Pi. Check its status.";
        if (error instanceof java.net.SocketTimeoutException) return "The Pi media service timed out. Check its connection and drive.";
        return error.getMessage() == null ? "Media request failed" : error.getMessage();
    }

    private void setStatus(String text) { status.setText(text == null ? "Media unavailable" : text); }
    private void clearRows(String title) { rows.removeAllViews(); setStatus(title); }

    private void row(String title, String detail, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(0), dp(14), dp(0), dp(14));
        TextView heading = new TextView(this);
        heading.setText(title); heading.setTextSize(16); heading.setTextColor(getColor(R.color.text));
        box.addView(heading);
        TextView sub = new TextView(this);
        sub.setText(detail); sub.setTextSize(12); sub.setTextColor(getColor(R.color.dim));
        box.addView(sub);
        box.setOnClickListener(click);
        rows.addView(box);
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.border));
        rows.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void drives() {
        showingVideos = false;
        long startingIntent = outputIntent;
        clearRows("Checking drives…");
        request(() -> client.get("/v1/drives"), result -> {
            String activeStatus = status.getText().toString();
            clearRows("Choose a drive");
            if (outputIntent != startingIntent) setStatus(activeStatus);
            syncPendingProgress();
            JSONArray values = result.getJSONArray("drives");
            JSONObject firstOnline = null;
            for (int i = 0; i < values.length(); i++) {
                JSONObject drive = values.getJSONObject(i);
                String id = drive.getString("id");
                boolean online = drive.optBoolean("online");
                if (online && firstOnline == null) firstOnline = drive;
                String label = drive.getString("label");
                row(label, online ? "Available · read-only" : "Disconnected · connect this drive",
                    v -> { if (online) { driveId = id; driveLabel = label; path = ""; browse(); } });
            }
            if (firstDriveLoad) {
                firstDriveLoad = false;
                if (firstOnline != null) {
                    driveId = firstOnline.getString("id");
                    driveLabel = firstOnline.getString("label");
                    path = "";
                    selectTab(R.id.media_files);
                    browse();
                }
            }
        });
    }

    private void browse() { browse(0, false); }

    private void browse(int offset, boolean append) {
        showingVideos = false;
        ((TextView) findViewById(R.id.media_drives)).setText("BROWSING  ·  " + driveLabel + "  ⌄");
        String requestedDrive = driveId, requestedPath = path;
        if (!append) clearRows("Opening " + path + "…");
        request(() -> client.get("/v1/items?driveId=" + MediaClient.enc(requestedDrive) +
            "&path=" + MediaClient.enc(requestedPath) + "&offset=" + offset), result -> {
            if (!requestedDrive.equals(driveId) || !requestedPath.equals(path)) return;
            if (!append) clearRows(path.isEmpty() ? "Drive files" : path);
            JSONArray items = result.getJSONArray("items");
            if (!append && items.length() == 0) row("No files here", "Choose another folder or search", v -> {});
            for (int i = 0; i < items.length(); i++) showItem(items.getJSONObject(i));
            if (!result.isNull("nextOffset")) {
                int next = result.getInt("nextOffset");
                row("Load more", "Continue browsing this folder", v -> {
                    rows.removeView((View) v);
                    browse(next, true);
                });
            }
        });
    }

    private void videos(int offset, boolean append) {
        showingVideos = true;
        driveId = "";
        path = "";
        if (!append) clearRows("Finding videos on connected drives…");
        request(() -> client.get("/v1/videos?offset=" + offset), result -> {
            if (!showingVideos) return;
            if (!append) clearRows("Videos · " + result.getInt("total") + " files");
            JSONArray items = result.getJSONArray("items");
            if (!append && items.length() == 0)
                row("No videos found", "Connect a drive or browse its folders", v -> {});
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                row("▶ " + item.optString("name"),
                    item.optString("driveId") + " · " + item.optString("relativePath"),
                    v -> chooseTarget(item));
            }
            if (!result.isNull("nextOffset")) {
                int next = result.getInt("nextOffset");
                row("Load more", "Continue listing videos", v -> {
                    rows.removeView((View) v);
                    videos(next, true);
                });
            }
        });
    }

    private void find() {
        String term = search.getText().toString().trim();
        if (term.isEmpty()) { setStatus("Enter a file name"); return; }
        clearRows("Searching…");
        request(() -> client.get("/v1/search?q=" + MediaClient.enc(term)),
            result -> showItems(result.getJSONArray("items"), result.optBoolean("truncated") ? "First results; narrow your search" : "Search results"));
    }

    private void showItems(JSONArray items, String title) throws Exception {
        clearRows(title);
        if (items.length() == 0) row("No files here", "Choose another folder or search", v -> {});
        for (int i = 0; i < items.length(); i++) {
            showItem(items.getJSONObject(i));
        }
    }

    private void showItem(JSONObject item) {
        boolean folder = item.optBoolean("directory");
        row((folder ? "📁 " : "▶ ") + item.optString("name"),
            item.optString("driveId") + (folder ? " · folder" : " · " + item.optString("mime")),
            v -> { if (folder) { driveId = item.optString("driveId"); path = item.optString("relativePath"); browse(); }
                   else chooseTarget(item); });
    }

    private void chooseTarget(JSONObject item) {
        new AlertDialog.Builder(this).setTitle(item.optString("name"))
            .setItems(new String[]{"Play on Pi projector", "Play on this phone", "Open in VLC on phone"}, (dialog, choice) -> {
                if (choice == 0) playPi(item);
                else if (choice == 1) playPhone(item, 0);
                else playVlc(item);
            }).show();
    }

    private void playVlc(JSONObject item) {
        ++outputIntent;
        stopPhoneForNewPlayback();
        try {
            Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".media")
                .appendPath("stream").appendPath(item.getString("id"))
                .appendQueryParameter("size", String.valueOf(item.getLong("size")))
                .appendQueryParameter("name", item.optString("name"))
                .appendQueryParameter("mime", item.optString("mime", "video/*")).build();
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,
                item.optString("mime", "video/*")).setPackage("org.videolan.vlc")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Pi media", uri));
            startActivity(intent);
            target = "vlc";
            selected = item;
            nowPlaying.setText("VLC: " + item.optString("name"));
            output.setText("VLC owns playback; use its pause, seek, and volume controls");
            setStatus("Streaming Pi file through csync to VLC");
        } catch (Exception error) { setStatus("VLC could not open this file: " + error.getMessage()); }
    }

    private void playPi(JSONObject item) {
        playPi(item, 0);
    }

    private void playPi(JSONObject item, int resumeMs) {
        final long intent = ++outputIntent;
        stopPhoneForNewPlayback();
        target = "pi";
        selected = item;
        request(intent, () -> {
            if (intent != outputIntent) throw new java.util.concurrent.CancellationException();
            JSONObject state = client.get("/v1/player/pi");
            if (intent != outputIntent) throw new java.util.concurrent.CancellationException();
            JSONObject result = client.post("/v1/player/pi/commands", new JSONObject().put("action", "play")
                .put("itemId", item.getString("id")).put("expectedRevision", state.getInt("revision")));
            if (intent != outputIntent) { stopStalePiStart(result); return result; }
            if (resumeMs > 0) {
                boolean resumed = false;
                for (int attempt = 0; attempt < 12; attempt++) {
                    if (intent != outputIntent) { stopStalePiStart(result); return result; }
                    Thread.sleep(250);
                    state = client.get("/v1/player/pi");
                    if (!state.optString("state").equals("playing")) continue;
                    try {
                        result = client.post("/v1/player/pi/commands", new JSONObject().put("action", "seek")
                            .put("positionMs", resumeMs).put("expectedRevision", state.getInt("revision")));
                        resumed = true;
                        break;
                    } catch (Exception error) {
                        if (attempt == 11) throw error;
                    }
                }
                if (!resumed) throw new Exception("Playback started, but resume seek did not apply");
            }
            if (intent != outputIntent) stopStalePiStart(result);
            return result;
        }, result -> { nowPlaying.setText("Pi: " + item.optString("name"));
            setStatus("Playing on Pi projector; starts muted. Choose Volume for sound."); });
    }

    private void playPhone(JSONObject item, int resumeMs) {
        final long intent = ++outputIntent;
        target = "phone";
        selected = item;
        setStatus("Switching playback to phone…");
        new Thread(() -> {
            try {
                JSONObject state = client.get("/v1/player/pi");
                if (intent == outputIntent && state.has("itemId") && !state.isNull("itemId"))
                    client.post("/v1/player/pi/commands", new JSONObject().put("action", "stop")
                        .put("expectedRevision", state.getInt("revision")));
            } catch (Exception ignored) { }
            ui.post(() -> {
                if (intent == outputIntent && screenActive) startPhone(item, resumeMs, intent);
            });
        }, "phone-output-handoff").start();
    }

    private void startPhone(JSONObject item, int resumeMs, long intent) {
        audioOnly = item.optString("mime").startsWith("audio/");
        if (audioOnly) exitVideoMode(); else enterVideoMode();
        nowPlaying.setText("Phone: " + item.optString("name"));
        output.setText(externalDisplayText());
        setStatus("Opening stream…");
        PhonePlaybackService.ensure(this);
        ui.postDelayed(new Runnable() {
            int attempts;
            @Override public void run() {
                if (intent != outputIntent) return;
                PhonePlaybackService playback = PhonePlaybackService.current;
                if (playback == null) {
                    if (++attempts < 20) ui.postDelayed(this, 100);
                    else setStatus("Phone playback service did not start");
                    return;
                }
                playback.attach(phoneObserver, audioOnly ? null : video.getHolder());
                playback.play(item, resumeMs);
            }
        }, 50);
    }

    private void stopPhoneForNewPlayback() {
        PhonePlaybackService playback = PhonePlaybackService.current;
        if (playback != null) playback.control("stop");
        else stopService(new Intent(this, PhonePlaybackService.class));
        exitVideoMode();
    }

    private String externalDisplayText() {
        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display[] displays = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return displays.length > 0 ? "Separate display detected; video plays there while this phone shows controls" :
            "No separate display detected; check whether your HDMI adapter mirrors this screen";
    }

    private void control(String action) {
        if ("vlc".equals(target)) { setStatus("Use VLC controls for this playback"); return; }
        if (action.equals("stop")) {
            ++outputIntent;
            stopPhoneForNewPlayback();
        }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (action.equals("stop")) { setStatus("Phone playback stopped"); return; }
            if (playback == null) { setStatus("Phone player is stopped"); return; }
            playback.control(action);
            return;
        }
        if (action.equals("pause") || action.equals("stop")) {
            request(() -> client.post("/v1/player/pi/immediate", new JSONObject().put("action", action)),
                result -> {
                    JSONObject state = result.getJSONObject("player");
                    nowPlaying.setText("Pi: " + state.optString("state"));
                    setStatus(action.equals("stop") ? "Pi playback stopped" : "Pi playback paused");
                });
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", action)
                .put("expectedRevision", state.getInt("revision")));
        }, result -> setStatus("Pi " + result.optString("status")));
    }

    private void mute() {
        if ("vlc".equals(target)) { setStatus("Use VLC to mute this playback"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) { setStatus("Phone player is stopped"); return; }
            playback.setting("volume", 0);
            return;
        }
        request(() -> client.post("/v1/player/pi/immediate", new JSONObject().put("action", "mute")),
            result -> setStatus("Pi muted"));
    }

    private void controlSeek(int positionMs) {
        if ("vlc".equals(target)) { setStatus("Use VLC to seek this file"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) { setStatus("Phone player is stopped"); return; }
            playback.seek(positionMs);
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", "seek")
                .put("positionMs", positionMs).put("expectedRevision", state.getInt("revision")));
        }, result -> setStatus("Pi seek " + result.optString("status")));
    }

    private void chooseSetting() {
        new AlertDialog.Builder(this).setTitle("Playback settings")
            .setItems(new String[]{"Volume", "Speed"}, (dialog, choice) -> {
                if (choice == 0) {
                    chooseVolume();
                } else {
                    double[] speeds = {0.5, 1.0, 1.25, 1.5, 2.0};
                    new AlertDialog.Builder(this).setTitle("Speed")
                        .setItems(new String[]{"0.5×", "1×", "1.25×", "1.5×", "2×"},
                            (next, index) -> changeSetting("speed", speeds[index])).show();
                }
            }).show();
    }

    private void chooseVolume() {
        int[] levels = {0, 10, 20, 40, 70, 100};
        new AlertDialog.Builder(this).setTitle("Volume")
            .setItems(new String[]{"Mute", "10%", "20%", "40%", "70%", "100%"},
                (dialog, index) -> changeSetting("volume", levels[index])).show();
    }

    private void changeSetting(String action, double value) {
        if ("vlc".equals(target)) { setStatus("Use VLC playback settings"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) { setStatus("Phone player is stopped"); return; }
            playback.setting(action, value);
            return;
        }
        if (action.equals("volume")) {
            request(() -> client.post("/v1/player/pi/immediate", new JSONObject()
                    .put("action", "volume").put("value", value)),
                result -> setStatus("Pi volume " + Math.round(value) + "%"));
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", action)
                .put("value", value).put("expectedRevision", state.getInt("revision")));
        }, result -> setStatus("Pi " + action + " " + value));
    }

    private void enterVideoMode() {
        videoMode = true;
        for (int id : new int[]{R.id.media_sections, R.id.media_search_line, R.id.media_status, R.id.media_list})
            findViewById(id).setVisibility(View.GONE);
        findViewById(R.id.media_drives).setVisibility(View.GONE);
        findViewById(R.id.media_actions_toggle).setVisibility(View.GONE);
        findViewById(R.id.media_actions).setVisibility(View.GONE);
        findViewById(R.id.media_bottom_nav).setVisibility(View.GONE);
        video.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        video.setVisibility(View.VISIBLE);
        setVideoControls(false);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private void setVideoControls(boolean visible) {
        if (!videoMode) return;
        videoControlsVisible = visible;
        findViewById(R.id.media_nav).setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.media_player_controls).setVisibility(visible ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(visible ? 0 :
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void exitVideoMode() {
        if (!videoMode) return;
        videoMode = false;
        findViewById(R.id.media_nav).setVisibility(View.VISIBLE);
        findViewById(R.id.media_player_controls).setVisibility(View.VISIBLE);
        findViewById(R.id.media_drives).setVisibility(View.VISIBLE);
        findViewById(R.id.media_actions_toggle).setVisibility(View.VISIBLE);
        findViewById(R.id.media_bottom_nav).setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(0);
        for (int id : new int[]{R.id.media_sections, R.id.media_search_line, R.id.media_status, R.id.media_list})
            findViewById(id).setVisibility(View.VISIBLE);
        video.setVisibility(View.GONE);
        video.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override public void onBackPressed() {
        if (videoMode) exitVideoMode(); else super.onBackPressed();
    }



    private void syncPendingProgress() {
        SharedPreferences prefs = getSharedPreferences("media_progress", MODE_PRIVATE);
        new Thread(() -> {
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) continue;
                String saved = (String) entry.getValue();
                try {
                    client.post("/v1/progress", new JSONObject(saved));
                    if (saved.equals(prefs.getString(entry.getKey(), null)))
                        prefs.edit().remove(entry.getKey()).apply();
                } catch (Exception error) {
                    if (error instanceof MediaClient.MediaException &&
                            ("SESSION_STALE".equals(((MediaClient.MediaException) error).code) ||
                             "PROGRESS_STALE".equals(((MediaClient.MediaException) error).code)))
                        prefs.edit().remove(entry.getKey()).apply();
                }
            }
        }, "media-progress-sync").start();
    }

    private void history() {
        try { showHistory(new JSONArray(), true); } catch (Exception ignored) {}
        request(() -> client.get("/v1/history"), result -> {
            JSONArray remote = result.getJSONArray("entries");
            SharedPreferences cache = getSharedPreferences("media_history", MODE_PRIVATE);
            SharedPreferences.Editor editor = cache.edit();
            for (int i = 0; i < remote.length(); i++) {
                JSONObject entry = remote.getJSONObject(i);
                String key = entry.optString("itemId") + "|" + entry.optString("target");
                if (!key.isEmpty()) {
                    JSONObject cached = new JSONObject(cache.getString(key, "{}"));
                    if (entry.optLong("generation") > cached.optLong("generation") ||
                            (entry.optLong("generation") == cached.optLong("generation") &&
                             entry.optLong("sequence") >= cached.optLong("sequence")))
                        editor.putString(key, entry.toString());
                }
            }
            editor.apply();
            showHistory(remote, false);
            syncPendingProgress();
        });
    }

    private void showHistory(JSONArray remote, boolean offline) throws Exception {
        clearRows(offline ? "Saved on this phone · syncing when connected" : "Recently played");
        Map<String, JSONObject> entries = new LinkedHashMap<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.getJSONObject(i);
            entries.put(item.optString("itemId") + "|" + item.optString("target"), item);
        }
        Map<String, Object> localHistory = new LinkedHashMap<>(getSharedPreferences("media_history", MODE_PRIVATE).getAll());
        localHistory.putAll(getSharedPreferences("media_progress", MODE_PRIVATE).getAll());
        for (Object raw : localHistory.values()) {
            if (!(raw instanceof String)) continue;
            try {
                JSONObject local = new JSONObject((String) raw);
                String key = local.optString("itemId") + "|" + local.optString("target");
                JSONObject known = entries.get(key);
                if (known == null || local.optLong("generation") > known.optLong("generation") ||
                        (local.optLong("generation") == known.optLong("generation") &&
                         local.optLong("sequence") > known.optLong("sequence")))
                    entries.put(key, local);
            } catch (Exception ignored) {}
        }
        if (entries.isEmpty()) row("No playback yet", "Play a file to save its position", v -> {});
        for (JSONObject entry : entries.values()) {
            row(entry.optString("name", "Saved media"),
                entry.optString("driveLabel") + " · " + entry.optString("target") + " · " +
                (entry.optBoolean("completed") ? "Finished" : "Resume at " + entry.optInt("positionMs") / 1000 + "s"), v -> {
                    JSONObject item = new JSONObject();
                    try { item.put("id", entry.getString("itemId")); item.put("name", entry.optString("name", "Saved media"));
                        item.put("mime", entry.optString("mime")); }
                    catch (Exception error) { setStatus(error.getMessage()); return; }
                    String destination = entry.optString("target", "pi");
                    int resumeAt = entry.optBoolean("completed") ? 0 : entry.optInt("positionMs");
                    new AlertDialog.Builder(this).setTitle(entry.optString("name", "Saved media"))
                        .setItems(new String[]{"Resume", "Start over"}, (dialog, choice) -> {
                            int position = choice == 0 ? resumeAt : 0;
                            if (destination.equals("phone")) playPhone(item, position); else playPi(item, position);
                        }).show();
                });
        }
    }

    private void connections() {
        String host = Prefs.assistIp(this);
        clearRows("File access on " + host);
        row("SMB USB drive", "smb://" + host + "/sandisk · Pi account", v -> copy("smb://" + host + "/sandisk"));
        row("SMB Elements", "smb://" + host + "/seagate-elements · available when attached", v -> copy("smb://" + host + "/seagate-elements"));
        row("SMB media", "smb://" + host + "/media · sign in with your Pi account", v -> copy("smb://" + host + "/media"));
        row("SMB files", "smb://" + host + "/files · sign in with your Pi account", v -> copy("smb://" + host + "/files"));
        row("FTP USB drive", "ftp://" + host + "/Media · Pi account", v -> copy("ftp://" + host + "/Media"));
        row("FTP Elements", "ftp://" + host + "/Elements · available when attached", v -> copy("ftp://" + host + "/Elements"));
        row("SFTP", "sftp://" + host + " · Tailscale SSH may ask for a browser check", v -> copy("sftp://" + host));
    }

    private void copy(String address) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Media address", address));
        setStatus("Copied " + address);
    }

    private void refreshState() {
        if (!screenActive) return;
        if ("vlc".equals(target)) {
            // VLC owns this session; its player state is not reported to the Pi.
        } else if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback != null && playback.hasPlayer() && !seeking) {
                findViewById(R.id.media_player_controls).setVisibility(View.VISIBLE);
                findViewById(R.id.media_pause).setVisibility(playback.playing() ? View.VISIBLE : View.GONE);
                findViewById(R.id.media_resume).setVisibility(playback.playing() ? View.GONE : View.VISIBLE);
                seek.setMax(Math.max(1, playback.duration()));
                seek.setProgress(playback.position());
            } else findViewById(R.id.media_player_controls).setVisibility(View.GONE);
        } else if (stateInFlight.compareAndSet(false, true)) {
            new Thread(() -> {
                try {
                    JSONObject state = client.get("/v1/player/pi");
                    ui.post(() -> {
                        if (!screenActive || !"pi".equals(target)) return;
                        String playerState = state.optString("state");
                        boolean active = playerState.equals("playing") || playerState.equals("paused") ||
                            playerState.equals("loading") || playerState.equals("buffering");
                        findViewById(R.id.media_player_controls).setVisibility(active ? View.VISIBLE : View.GONE);
                        findViewById(R.id.media_pause).setVisibility(playerState.equals("playing") ? View.VISIBLE : View.GONE);
                        findViewById(R.id.media_resume).setVisibility(playerState.equals("paused") ? View.VISIBLE : View.GONE);
                        nowPlaying.setText(state.optString("name", "Pi media"));
                        output.setText("Pi screen · " + playerState + " · volume " + state.optInt("volume", 0) + "%");
                        if (!seeking) {
                            seek.setMax(Math.max(1, state.optInt("durationMs", 1)));
                            seek.setProgress(state.optInt("positionMs"));
                        }
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (!screenActive || !"pi".equals(target)) return;
                        nowPlaying.setText("Pi: connection lost");
                        output.setText("Playback state unknown; check the Pi before reconnecting HDMI");
                        setStatus(friendlyError(error));
                    });
                } finally { stateInFlight.set(false); }
            }, "pi-player-state").start();
        }
        ui.postDelayed(this::refreshState, 2000);
    }





    @Override protected void onStart() {
        super.onStart();
        PhonePlaybackService playback = PhonePlaybackService.current;
        if (playback != null && playback.hasPlayer()) {
            target = "phone";
            JSONObject item = playback.item();
            audioOnly = item != null && item.optString("mime").startsWith("audio/");
            if (!audioOnly) enterVideoMode();
            playback.attach(phoneObserver, audioOnly ? null : video.getHolder());
        }
    }

    @Override protected void onStop() {
        PhonePlaybackService playback = PhonePlaybackService.current;
        if (playback != null) playback.detach(phoneObserver);
        super.onStop();
    }

    @Override protected void onDestroy() {
        screenActive = false;
        super.onDestroy();
    }
}
