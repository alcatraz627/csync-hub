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
import android.widget.HorizontalScrollView;
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

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaClient client;
    private LinearLayout rows;
    private LinearLayout itemGroup;
    private TextView status, nowPlaying, output;
    private EditText search;
    private SeekBar seek, playerSeek;
    private SurfaceView video;
    private JSONObject selected;
    private String driveId = "", driveLabel = "", path = "", target = "pi";
    private final Map<String, String> driveLabels = new HashMap<>();
    private boolean audioOnly;
    private boolean seeking;
    private boolean videoMode;
    private boolean fullPlayer;
    private boolean videoControlsVisible;
    private boolean screenActive = true;
    private boolean openedFromSearch;
    private boolean showingVideos;
    private int activeTab = R.id.media_files;
    private boolean firstDriveLoad = true;
    private long listingIntent;
    private volatile long outputIntent;
    private int skipSeconds = 10;
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
        Appearance.apply(this);
        super.onCreate(saved);
        client = new MediaClient(Prefs.assistIp(this), Prefs.token(this));
        setContentView(R.layout.activity_media);
        Appearance.applySystemBars(this);
        String requestedTarget = getIntent().getStringExtra("player_target");
        openedFromSearch = getIntent().hasExtra("search_item_id");
        if ("phone".equals(requestedTarget) || "pi".equals(requestedTarget)) target = requestedTarget;
        rows = findViewById(R.id.media_rows);
        status = findViewById(R.id.media_status);
        nowPlaying = findViewById(R.id.media_now);
        output = findViewById(R.id.media_output);
        search = findViewById(R.id.media_search);
        seek = findViewById(R.id.media_seek);
        playerSeek = findViewById(R.id.player_seek);
        skipSeconds = getSharedPreferences("player_controls", MODE_PRIVATE).getInt("skip_seconds", 10);
        renderSkip();
        video = findViewById(R.id.media_video);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.media_bottom_nav);
        nav.setBackgroundColor(getColor(R.color.surface));
        nav.setElevation(0f);
        int navAccent = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, getColor(R.color.coral));
        nav.setItemActiveIndicatorColor(android.content.res.ColorStateList.valueOf(
            androidx.core.graphics.ColorUtils.blendARGB(getColor(R.color.surface), navAccent, 0.13f)));
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
        renderTop();
        findViewById(R.id.media_files).setOnClickListener(v -> { selectTab(R.id.media_files); if (driveId.isEmpty()) drives(); else browse(); });
        findViewById(R.id.media_videos).setOnClickListener(v -> { selectTab(R.id.media_videos); videos(0, false); });
        findViewById(R.id.media_find).setOnClickListener(v -> find());
        findViewById(R.id.media_history).setOnClickListener(v -> { selectTab(R.id.media_history); history(); });
        findViewById(R.id.media_connections).setOnClickListener(v -> { selectTab(R.id.media_connections); connections(); });
        findViewById(R.id.media_actions_toggle).setOnClickListener(v -> {
            View actions = findViewById(R.id.media_actions);
            boolean opening = actions.getVisibility() != View.VISIBLE;
            actions.setVisibility(opening ? View.VISIBLE : View.GONE);
            findViewById(R.id.media_actions_chevron).setRotation(opening ? 180f : 0f);
            v.setContentDescription("From phone or YouTube, " + (opening ? "expanded" : "collapsed"));
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
        findViewById(R.id.media_now).setOnClickListener(v -> showFullPlayer());
        findViewById(R.id.player_pause).setOnClickListener(v ->
            control("Resume".contentEquals(v.getContentDescription()) ? "resume" : "pause"));
        findViewById(R.id.player_skip_choice).setOnClickListener(v -> chooseSkip());
        findViewById(R.id.player_favorite).setOnClickListener(v -> toggleFavorite());
        findViewById(R.id.player_stop).setOnClickListener(v -> control("stop"));
        findViewById(R.id.player_back10).setOnClickListener(v ->
            controlSeek(Math.max(0, playerSeek.getProgress() - skipSeconds * 1000)));
        findViewById(R.id.player_forward10).setOnClickListener(v ->
            controlSeek(Math.min(playerSeek.getMax(), playerSeek.getProgress() + skipSeconds * 1000)));
        findViewById(R.id.player_volume).setOnClickListener(v -> chooseVolume());
        findViewById(R.id.player_speed).setOnClickListener(v -> chooseSpeed());
        findViewById(R.id.player_rotate).setOnClickListener(v -> chooseRotation());
        findViewById(R.id.player_loop).setOnClickListener(v -> chooseLoop());
        findViewById(R.id.player_skip).setOnClickListener(v -> chooseSkip());
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {}
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; controlSeek(bar.getProgress()); }
        });
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
        String searchItemId = getIntent().getStringExtra("search_item_id");
        if (searchItemId == null) drives();
        else openSearchItem(searchItemId,
            getIntent().getStringExtra("search_drive_id"),
            getIntent().getStringExtra("search_relative_path"),
            getIntent().getStringExtra("search_query"));
        if (requestedTarget != null) showFullPlayer();
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
        activeTab = selectedId;
        String title = selectedId == R.id.media_videos ? "Videos" :
            selectedId == R.id.media_history ? "History" :
            selectedId == R.id.media_connections ? "Access" : "Files";
        ((TextView) findViewById(R.id.media_heading)).setText(title);
        TextView subtitle = findViewById(R.id.media_subtitle);
        subtitle.setText(selectedId == R.id.media_videos ? "Videos on connected drives" :
            selectedId == R.id.media_history ? "Your position is saved even when a drive is absent." :
            selectedId == R.id.media_connections ? "Open Pi files from another device" :
            "Search connected media, then choose where a file plays.");
        findViewById(R.id.media_search_line).setVisibility(
            selectedId == R.id.media_files ? View.VISIBLE : View.GONE);
        renderTop();
        findViewById(R.id.media_actions_toggle).setVisibility(View.GONE);
        findViewById(R.id.media_actions).setVisibility(View.GONE);
        findViewById(R.id.media_status).setVisibility(
            selectedId == R.id.media_history || selectedId == R.id.media_connections ? View.GONE : View.VISIBLE);
        int accent = com.google.android.material.color.MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorPrimary, getColor(R.color.coral));
        for (int id : new int[]{R.id.media_files, R.id.media_videos, R.id.media_history, R.id.media_connections}) {
            TextView tab = findViewById(id);
            tab.setTextColor(id == selectedId ? accent : getColor(R.color.dim));
            tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                id == selectedId ? accent : getColor(R.color.dim)));
            tab.setTypeface(null, id == selectedId ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            if (id == selectedId) {
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setColor(getColor(R.color.surface));
                pill.setCornerRadius(dp(9));
                tab.setBackground(pill);
            } else {
                android.util.TypedValue ripple = new android.util.TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
                tab.setBackgroundResource(ripple.resourceId);
            }
        }
        HorizontalScrollView sections = findViewById(R.id.media_sections);
        TextView selected = findViewById(selectedId);
        sections.post(() -> sections.smoothScrollTo(
            Math.max(0, selected.getLeft() - (sections.getWidth() - selected.getWidth()) / 2), 0));
        android.widget.ScrollView browser = findViewById(R.id.media_list);
        browser.post(() -> browser.smoothScrollTo(0, 0));
    }

    private void openSearchItem(String itemId, String requestedDrive,
                                String relativePath, String query) {
        firstDriveLoad = false;
        selectTab(R.id.media_files);
        search.setText(query == null ? "" : query);
        long listing = ++listingIntent;
        clearRows("Finding selected item");
        if (itemId.isEmpty() || requestedDrive == null || requestedDrive.isEmpty() ||
                relativePath == null || relativePath.isEmpty()) {
            showMissingSearchItem("The selected result has no file identity");
            return;
        }
        int slash = relativePath.lastIndexOf('/');
        String parentPath = slash < 0 ? "" : relativePath.substring(0, slash);
        listingRequest(listing, () -> {
            JSONObject result = new JSONObject();
            try {
                JSONArray drives = client.get("/v1/drives").getJSONArray("drives");
                for (int i = 0; i < drives.length(); i++) {
                    JSONObject drive = drives.getJSONObject(i);
                    if (requestedDrive.equals(drive.optString("id"))) {
                        result.put("driveLabel", drive.optString("label", requestedDrive));
                        break;
                    }
                }
                int offset = 0;
                while (true) {
                    JSONObject page = client.get("/v1/items?driveId=" + MediaClient.enc(requestedDrive) +
                        "&path=" + MediaClient.enc(parentPath) + "&offset=" + offset);
                    JSONArray items = page.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (itemId.equals(item.optString("id")) &&
                                relativePath.equals(item.optString("relativePath"))) {
                            result.put("item", item);
                            return result;
                        }
                    }
                    if (page.isNull("nextOffset")) break;
                    int next = page.getInt("nextOffset");
                    if (next <= offset) break;
                    offset = next;
                }
            } catch (Exception error) {
                result.put("error", friendlyError(error));
            }
            return result;
        }, result -> {
            JSONObject item = result.optJSONObject("item");
            if (item == null) {
                showMissingSearchItem(result.optString("error", "The file changed or was removed"));
                return;
            }
            driveId = requestedDrive;
            driveLabel = result.optString("driveLabel", requestedDrive);
            driveLabels.put(driveId, driveLabel);
            if (item.optBoolean("directory")) {
                path = relativePath;
                browse();
            } else {
                path = parentPath;
                search.setHint("Search " + driveLabel);
                showItems(new JSONArray().put(item), "Selected file");
                chooseTarget(item);
            }
        });
    }

    private void showMissingSearchItem(String reason) {
        clearRows("Selected item unavailable");
        row(R.drawable.csi_alert, "Cannot open this result", reason, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface Work { JSONObject run() throws Exception; }
    private interface Show { void accept(JSONObject value) throws Exception; }

    private void uploadWallpaper(Uri uri) {
        setStatus("Preparing screen image");
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
        setStatus("Sending " + fileName + " to Pi USB: 0%");
        request(intent, () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("Could not open selected media");
                return client.uploadMedia("sandisk", fileName, fileSize, input, sent -> {
                    int percent = (int) (sent * 100 / fileSize);
                    ui.post(() -> { if (screenActive && intent == outputIntent)
                        setStatus("Sending " + fileName + " to Pi USB: " + percent + "%"); });
                });
            }
        }, result -> {
            JSONObject item = result.getJSONObject("item");
            setStatus("Saved on Pi USB; opening on projector");
            playPi(item);
        });
    }

    private void castYoutube() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("Paste a YouTube link");
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
                setStatus("Opening YouTube on Pi");
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
        request(-1, -1, work, show);
    }

    private void request(long intent, Work work, Show show) {
        request(intent, -1, work, show);
    }

    private void listingRequest(long listing, Work work, Show show) {
        request(-1, listing, work, show);
    }

    private void request(long intent, long listing, Work work, Show show) {
        new Thread(() -> {
            try {
                JSONObject result = work.run();
                ui.post(() -> {
                    if (!screenActive || (intent >= 0 && intent != outputIntent) ||
                            (listing >= 0 && listing != listingIntent)) return;
                    try { show.accept(result); } catch (Exception error) { setStatus(error.getMessage()); }
                });
            } catch (Exception error) { ui.post(() -> {
                if (screenActive && (intent < 0 || intent == outputIntent) &&
                        (listing < 0 || listing == listingIntent)) setStatus(friendlyError(error));
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

    private void showFullPlayer() {
        if (videoMode) return;
        fullPlayer = true;
        findViewById(R.id.media_list).setVisibility(View.GONE);
        findViewById(R.id.media_full_player).setVisibility(View.VISIBLE);
        renderTop();
        if ("phone".equals(target) && PhonePlaybackService.current == null)
            updateFullPlayer("This phone", "stopped", "", 0, 0, 0, 1);
        ((TextView) findViewById(R.id.player_feedback)).setText("");
    }

    private void closeFullPlayer() {
        fullPlayer = false;
        findViewById(R.id.media_full_player).setVisibility(View.GONE);
        findViewById(R.id.media_list).setVisibility(View.VISIBLE);
        selectTab(activeTab);
    }

    private static String playbackTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String displayMediaName(String name) {
        return name.replaceFirst("^cast-[0-9a-f]{8,32}-", "").replace('_', ' ');
    }

    private void renderSkip() {
        findViewById(R.id.player_back10).setContentDescription("Rewind " + skipSeconds + " seconds");
        findViewById(R.id.player_forward10).setContentDescription("Forward " + skipSeconds + " seconds");
        ((TextView) findViewById(R.id.player_skip)).setText("Skip · " + skipSeconds + " seconds");
    }

    // Favorites live on this phone, keyed by the title shown in the player and on History.
    private String playerTitle = "";

    private java.util.Set<String> favorites() {
        return new java.util.HashSet<>(getSharedPreferences("media_favorites", MODE_PRIVATE)
            .getStringSet("titles", new java.util.HashSet<>()));
    }

    private void toggleFavorite() {
        if (playerTitle.isEmpty()) return;
        java.util.Set<String> all = favorites();
        if (!all.remove(playerTitle)) all.add(playerTitle);
        getSharedPreferences("media_favorites", MODE_PRIVATE).edit().putStringSet("titles", all).apply();
        renderFavorite();
    }

    private void renderFavorite() {
        android.widget.ImageView favorite = findViewById(R.id.player_favorite);
        boolean on = !playerTitle.isEmpty() && favorites().contains(playerTitle);
        favorite.setImageTintList(android.content.res.ColorStateList.valueOf(on
            ? com.google.android.material.color.MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, getColor(R.color.coral))
            : getColor(R.color.text)));
        favorite.setContentDescription(on ? "Remove favorite" : "Favorite");
        favorite.setEnabled(!playerTitle.isEmpty());
        favorite.setAlpha(playerTitle.isEmpty() ? 0.45f : 1f);
    }

    private void updateFullPlayer(String outputName, String state, String title,
                                  int position, int duration, int volume, double speed) {
        ((TextView) findViewById(R.id.player_target_state)).setText(outputName + " · " + state);
        if (!state.equals("offline"))
            ((TextView) findViewById(R.id.player_feedback)).setText("");
        ((TextView) findViewById(R.id.player_title)).setText(
            title == null || title.isEmpty() ? "No media selected" : displayMediaName(title));
        playerTitle = title == null ? "" : displayMediaName(title);
        renderFavorite();
        String poster = state.equals("idle") || state.equals("stopped") ? "Display idle" :
            outputName + " · " + state + "\nPreview unavailable on this screen";
        ((TextView) findViewById(R.id.player_art)).setText(poster);
        android.widget.ImageView pause = findViewById(R.id.player_pause);
        pause.setImageResource(state.equals("paused") ? R.drawable.csi_play : R.drawable.csi_pause);
        pause.setContentDescription(state.equals("paused") ? "Resume" : "Pause");
        boolean ready = state.equals("playing") || state.equals("paused");
        boolean active = ready || state.equals("loading") || state.equals("buffering");
        playerSeek.setEnabled(ready && duration > 0);
        playerSeek.setMax(Math.max(1, duration));
        if (!seeking) playerSeek.setProgress(Math.max(0, position));
        ((TextView) findViewById(R.id.player_position)).setText(playbackTime(position));
        ((TextView) findViewById(R.id.player_duration)).setText(duration > 0 ? playbackTime(duration) : "--:--");
        lastVolume = volume;
        lastSpeed = speed;
        ((TextView) findViewById(R.id.player_volume)).setText("Volume · " + volume + "%");
        ((TextView) findViewById(R.id.player_speed)).setText("Speed · " + speed + "×");
        for (int id : new int[]{R.id.player_pause, R.id.player_stop, R.id.player_back10,
                R.id.player_forward10, R.id.player_volume, R.id.player_speed}) {
            View control = findViewById(id);
            boolean enabled = id == R.id.player_stop ? active :
                (id == R.id.player_back10 || id == R.id.player_forward10)
                    ? ready && duration > 0 : ready;
            control.setEnabled(enabled);
            control.setAlpha(enabled ? 1f : 0.45f);
        }
    }
    private void clearRows(String title) {
        rows.removeAllViews();
        itemGroup = null;
        setStatus(title);
    }

    /** A row in the current list card: drives, empty states and "load more". A null click means it only informs. */
    private void row(int icon, String title, String detail, View.OnClickListener click) {
        if (itemGroup == null) itemGroup = Kit.group(rows);
        LinearLayout group = itemGroup;
        View row = Kit.addRow(group);
        Kit.bindRow(row, icon, title, detail, null, click != null);
        if (click != null) row.setOnClickListener(v -> click.onClick(group));
        else row.setClickable(false);
    }

    /** The top bar: Home, then Media or Player. Back leaves the current level and never walks folders. */
    private void renderTop() {
        View top = findViewById(R.id.media_top);
        Runnable home = () -> startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", "home"));
        if (fullPlayer || videoMode) {
            Kit.pageTop(top, () -> { if (videoMode) exitVideoMode(); else closeFullPlayer(); },
                new Kit.Crumb(Kit.Icon.HOME, "Home", home),
                new Kit.Crumb(Kit.Icon.MEDIA, "Media", this::closeFullPlayer),
                new Kit.Crumb(R.drawable.csi_play, "Player", null));
            return;
        }
        Kit.pageTop(top, this::finish, new Kit.Crumb(Kit.Icon.HOME, "Home", home),
            new Kit.Crumb(Kit.Icon.MEDIA, "Media", null));
        if (activeTab != R.id.media_files) return;
        Kit.topAction(top, Kit.Icon.SEARCH, "Search this source", v -> {
            findViewById(R.id.media_search_line).setVisibility(View.VISIBLE);
            search.requestFocus();
        });
        Kit.topAction(top, Kit.Icon.SOURCE, "Switch source", v -> Kit.sheet(this, "Choose a source", null,
            new Kit.Action(Kit.Icon.FILES, "Pi drives", "Browse storage connected to the Pi",
                () -> { driveId = ""; path = ""; drives(); }),
            new Kit.Action(Kit.Icon.DEVICE, "A file on this phone", "Play it on the Pi screen",
                () -> mediaPicker.launch("*/*")),
            new Kit.Action(Kit.Icon.VIDEO, "A YouTube link", "Play it on the Pi screen", this::castYoutube),
            new Kit.Action(Kit.Icon.PHOTO, "Pi screen image", "Choose the picture the Pi shows when idle",
                () -> wallpaperPicker.launch("image/*"))));
    }

    private void drives() {
        long listing = ++listingIntent;
        showingVideos = false;
        driveId = "";
        driveLabel = "";
        path = "";
        selectTab(R.id.media_files);
        search.setHint("Search a connected drive");
        ((TextView) findViewById(R.id.media_subtitle)).setText("Search connected media, then choose where a file plays.");
        long startingIntent = outputIntent;
        clearRows("Checking drives");
        listingRequest(listing, () -> client.get("/v1/drives"), result -> {
            String activeStatus = status.getText().toString();
            clearRows("Choose a drive");
            if (outputIntent != startingIntent) setStatus(activeStatus);
            syncPendingProgress();
            JSONArray values = result.getJSONArray("drives");
            JSONObject firstOnline = null;
            driveLabels.clear();
            for (int i = 0; i < values.length(); i++) {
                JSONObject drive = values.getJSONObject(i);
                String id = drive.getString("id");
                boolean online = drive.optBoolean("online");
                if (online && firstOnline == null) firstOnline = drive;
                String label = drive.getString("label");
                driveLabels.put(id, label);
                row(Kit.Icon.FILES, label, online ? "Available · read-only" : "Disconnected · connect this drive",
                    online ? v -> { driveId = id; driveLabel = label; path = ""; browse(); } : null);
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
        long listing = ++listingIntent;
        showingVideos = false;
        search.setHint("Search " + driveLabel);
        search.setHint("Search connected media");
        ((TextView) findViewById(R.id.media_subtitle)).setText("Browsing " + driveLabel + (path.isEmpty() ? "" : " · " + path));
        String requestedDrive = driveId, requestedPath = path;
        if (!append) clearRows("Opening " + path);
        listingRequest(listing, () -> client.get("/v1/items?driveId=" + MediaClient.enc(requestedDrive) +
            "&path=" + MediaClient.enc(requestedPath) + "&offset=" + offset), result -> {
            if (!requestedDrive.equals(driveId) || !requestedPath.equals(path)) return;
            JSONArray items = result.getJSONArray("items");
            if (!append) {
                boolean foldersOnly = items.length() > 0;
                for (int i = 0; i < items.length(); i++)
                    foldersOnly &= items.getJSONObject(i).optBoolean("directory");
                clearRows(path.isEmpty() && foldersOnly ? "Folders" : path.isEmpty() ? "Files" : path);
            }
            if (!append && !path.isEmpty()) {
                int slash = path.lastIndexOf('/');
                String parent = slash < 0 ? "" : path.substring(0, slash);
                row(R.drawable.csi_back, "Up", parent.isEmpty() ? driveLabel : parent,
                    v -> { path = parent; browse(); });
            }
            if (!append && items.length() == 0) row(R.drawable.csi_info, "No files here", "Choose another folder or search", null);
            for (int i = 0; i < items.length(); i++) showItem(items.getJSONObject(i));
            if (!result.isNull("nextOffset")) {
                int next = result.getInt("nextOffset");
                row(R.drawable.csi_expand, "Load more", "Continue browsing this folder", v -> {
                    rows.removeView((View) v);
                    browse(next, true);
                });
            }
        });
    }

    private void videos(int offset, boolean append) {
        long listing = ++listingIntent;
        showingVideos = true;
        driveId = "";
        path = "";
        if (!append) clearRows("Finding videos on connected drives");
        listingRequest(listing, () -> client.get("/v1/videos?offset=" + offset), result -> {
            if (!showingVideos) return;
            if (!append) clearRows("Videos · " + result.getInt("total") + " files");
            JSONArray items = result.getJSONArray("items");
            if (!append && items.length() == 0)
                row(R.drawable.csi_info, "No videos found", "Connect a drive or browse its folders", null);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String sourceId = item.optString("driveId");
                mediaItemRow(item, false,
                    driveLabels.getOrDefault(sourceId, sourceId) + " · " + item.optString("relativePath"));
            }
            if (!result.isNull("nextOffset")) {
                int next = result.getInt("nextOffset");
                row(R.drawable.csi_expand, "Load more", "Continue listing videos", v -> {
                    rows.removeView((View) v);
                    videos(next, true);
                });
            }
        });
    }

    private void find() {
        long listing = ++listingIntent;
        String term = search.getText().toString().trim();
        if (term.isEmpty()) { setStatus("Enter a file name"); return; }
        clearRows("Searching");
        listingRequest(listing, () -> client.get("/v1/search?q=" + MediaClient.enc(term)),
            result -> showItems(result.getJSONArray("items"), result.optBoolean("truncated") ? "First results; narrow your search" : "Search results"));
    }

    private void showItems(JSONArray items, String title) throws Exception {
        clearRows(title);
        if (items.length() == 0) row(R.drawable.csi_info, "No matches", "Try another file name or connect a drive", null);
        for (int i = 0; i < items.length(); i++) {
            showItem(items.getJSONObject(i));
        }
    }

    private void showItem(JSONObject item) {
        boolean folder = item.optBoolean("directory");
        String sourceId = item.optString("driveId");
        String mime = item.optString("mime");
        String kind = folder ? "Folder" : mime.startsWith("video/") ? "Video" :
            mime.startsWith("image/") ? "Photo" : mime.startsWith("audio/") ? "Audio" : "File";
        mediaItemRow(item, folder, kind + " · " + driveLabels.getOrDefault(sourceId, sourceId));
    }

    private void mediaItemRow(JSONObject item, boolean folder, String detail) {
        if (itemGroup == null) itemGroup = Kit.group(rows);
        View row = Kit.addRow(itemGroup);
        String mime = item.optString("mime");
        String name = folder ? item.optString("name") : displayMediaName(item.optString("name"));
        Kit.bindRow(row, folder ? Kit.Icon.FOLDER : mime.startsWith("image/") ? Kit.Icon.PHOTO
            : mime.startsWith("video/") ? Kit.Icon.VIDEO : Kit.Icon.FILE, name, detail, null, false);
        Runnable open = () -> {
            if (folder) {
                driveId = item.optString("driveId");
                driveLabel = driveLabels.getOrDefault(driveId, driveId);
                path = item.optString("relativePath");
                browse();
            } else chooseTarget(item);
        };
        row.setOnClickListener(v -> open.run());
        Kit.rowAction(row, R.drawable.csi_menu, "Actions for " + name, v -> {
            if (folder) Kit.sheet(this, name, "Folder",
                new Kit.Action(Kit.Icon.FOLDER, "Open", null, open),
                new Kit.Action(R.drawable.csi_copy, "Copy path", item.optString("relativePath"),
                    () -> copy(item.optString("relativePath"))));
            else chooseTarget(item);
        });
    }

    private void chooseTarget(JSONObject item) {
        Kit.sheet(this, displayMediaName(item.optString("name")), "Choose where it plays",
            new Kit.Action(Kit.Icon.DISPLAY, "Pi screen", "Play on the connected display", () -> playPi(item)),
            new Kit.Action(Kit.Icon.DEVICE, "This phone", "Play here", () -> playPhone(item, 0)),
            new Kit.Action(R.drawable.csi_play, "VLC on this phone", "Open in the VLC app", () -> playVlc(item)),
            new Kit.Action(Kit.Icon.SHARE, "Share", "Send the file to another app", () -> shareFile(item)));
    }

    private Uri mediaUri(JSONObject item) throws org.json.JSONException {
        return new Uri.Builder().scheme("content").authority(getPackageName() + ".media")
            .appendPath("stream").appendPath(item.getString("id"))
            .appendQueryParameter("size", String.valueOf(item.getLong("size")))
            .appendQueryParameter("name", item.optString("name"))
            .appendQueryParameter("mime", item.optString("mime", "application/octet-stream"))
            .build();
    }

    private void shareFile(JSONObject item) {
        try {
            Uri uri = mediaUri(item);
            Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.optString("mime", "application/octet-stream"))
                .putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(android.content.ClipData.newRawUri(item.optString("name"), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(send, "Share Pi file");
            chooser.setClipData(send.getClipData());
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception error) {
            setStatus("Could not share " + item.optString("name") + ": " + error.getMessage());
        }
    }

    private void playVlc(JSONObject item) {
        ++outputIntent;
        stopPhoneForNewPlayback();
        try {
            Uri uri = mediaUri(item);
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
            showFullPlayer();
            setStatus("Playing on Pi projector; starts muted. Choose Volume for sound."); });
    }

    private void playPhone(JSONObject item, int resumeMs) {
        final long intent = ++outputIntent;
        target = "phone";
        selected = item;
        setStatus("Switching playback to phone");
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
        if (audioOnly) { exitVideoMode(); showFullPlayer(); } else enterVideoMode();
        nowPlaying.setText("Phone: " + item.optString("name"));
        output.setText(externalDisplayText());
        setStatus("Opening stream");
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
            // A Rotate or Loop tap still waiting to apply must not reach a stopped player.
            ui.removeCallbacks(commitRotation);
            ui.removeCallbacks(commitLoop);
            pendingRotation = -1;
            pendingLoop = null;
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
        Kit.sheet(this, "Playback settings", null,
            new Kit.Action(Kit.Icon.VOLUME, "Volume", lastVolume + "%", this::chooseVolume),
            new Kit.Action(Kit.Icon.SPEED, "Speed", lastSpeed + "×", this::chooseSpeed),
            new Kit.Action(R.drawable.csi_sliders, "Skip length", skipSeconds + " seconds", this::chooseSkip));
    }

    private void chooseSkip() {
        int[] choices = {5, 10, 15, 30};
        Kit.Action[] actions = new Kit.Action[choices.length];
        for (int i = 0; i < choices.length; i++) {
            int seconds = choices[i];
            actions[i] = new Kit.Action(seconds == skipSeconds ? R.drawable.csi_check : R.drawable.csi_fastforward,
                seconds + " seconds", null, () -> {
                    skipSeconds = seconds;
                    getSharedPreferences("player_controls", MODE_PRIVATE).edit()
                        .putInt("skip_seconds", skipSeconds).apply();
                    renderSkip();
                });
        }
        Kit.sheet(this, "Skip length", "Rewind and Forward jump by this much", actions);
    }

    private int lastVolume;
    private double lastSpeed = 1;

    private void chooseSpeed() {
        Kit.sliderSheet(this, "Speed", 0.5f, 2f, 0.25f, (float) lastSpeed, v -> v + "×",
            v -> changeSetting("speed", v));
    }

    private void chooseVolume() {
        Kit.sliderSheet(this, "Volume", 0f, 100f, 5f, lastVolume, v -> Math.round(v) + "%",
            v -> changeSetting("volume", Math.round(v)));
    }

    // Rotate and Loop change on tap and reach the Pi only after taps stop for two seconds,
    // so stepping from 0° to 270° sends one command instead of three.
    private int pendingRotation = -1;
    private Boolean pendingLoop;
    private int shownRotation;
    private boolean shownLoop;
    private final Runnable commitRotation = () -> {
        if (pendingRotation >= 0) changePiDisplay("rotate", pendingRotation);
        pendingRotation = -1;
    };
    private final Runnable commitLoop = () -> {
        if (pendingLoop != null) changePiDisplay("loop", pendingLoop);
        pendingLoop = null;
    };

    private void chooseRotation() {
        int from = pendingRotation >= 0 ? pendingRotation : shownRotation;
        pendingRotation = (from + 90) % 360;
        ((TextView) findViewById(R.id.player_rotate)).setText("Rotate · " + pendingRotation + "° · applying");
        ui.removeCallbacks(commitRotation);
        ui.postDelayed(commitRotation, 2000);
    }

    private void chooseLoop() {
        boolean from = pendingLoop != null ? pendingLoop : shownLoop;
        pendingLoop = !from;
        ((TextView) findViewById(R.id.player_loop)).setText("Loop · " + (pendingLoop ? "On" : "Off") + " · applying");
        ui.removeCallbacks(commitLoop);
        ui.postDelayed(commitLoop, 2000);
    }

    private void changePiDisplay(String action, Object value) {
        if (!"pi".equals(target)) return;
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", action)
                .put("value", value).put("expectedRevision", state.getInt("revision")));
        }, result -> {
            if (!"applied".equals(result.optString("status"))) {
                setStatus("Pi did not apply " + action);
                return;
            }
            JSONObject state = result.optJSONObject("player");
            if (state != null) showPiDisplayState(state);
        });
    }

    private void showPiDisplayState(JSONObject state) {
        boolean ready = "pi".equals(target) && ("playing".equals(state.optString("state")) ||
            "paused".equals(state.optString("state")));
        TextView rotate = findViewById(R.id.player_rotate);
        TextView loop = findViewById(R.id.player_loop);
        shownRotation = state.optInt("rotation", 0);
        shownLoop = state.optBoolean("loop");
        if (pendingRotation < 0)
            rotate.setText(ready ? "Rotate · " + shownRotation + "°" : "Rotate · unavailable");
        if (pendingLoop == null)
            loop.setText(ready ? "Loop · " + (shownLoop ? "On" : "Off") : "Loop · unavailable");
        rotate.setEnabled(ready);
        loop.setEnabled(ready);
        rotate.setAlpha(ready ? 1f : 0.45f);
        loop.setAlpha(ready ? 1f : 0.45f);
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
        fullPlayer = false;
        findViewById(R.id.media_full_player).setVisibility(View.GONE);
        videoMode = true;
        renderTop();
        for (int id : new int[]{R.id.media_sections, R.id.media_search_line, R.id.media_status, R.id.media_list})
            findViewById(id).setVisibility(View.GONE);
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
        findViewById(R.id.media_top).setVisibility(visible ? View.VISIBLE : View.GONE);
        findViewById(R.id.media_player_controls).setVisibility(visible ? View.VISIBLE : View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(visible ? Appearance.systemBarFlags(this) :
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void exitVideoMode() {
        if (!videoMode) return;
        videoMode = false;
        findViewById(R.id.media_top).setVisibility(View.VISIBLE);
        findViewById(R.id.media_player_controls).setVisibility(View.VISIBLE);
        findViewById(R.id.media_actions_toggle).setVisibility(View.GONE);
        findViewById(R.id.media_bottom_nav).setVisibility(View.VISIBLE);
        Appearance.applySystemBars(this);
        for (int id : new int[]{R.id.media_sections, R.id.media_search_line, R.id.media_status, R.id.media_list})
            findViewById(id).setVisibility(View.VISIBLE);
        selectTab(activeTab);
        video.setVisibility(View.GONE);
        video.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override public void onBackPressed() {
        if (videoMode) exitVideoMode();
        else if (fullPlayer) closeFullPlayer();
        else super.onBackPressed();
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
        long listing = ++listingIntent;
        try { showHistory(new JSONArray(), true); } catch (Exception ignored) {}
        listingRequest(listing, () -> client.get("/v1/history"), result -> {
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
        clearRows(offline ? "Saved on this phone" : "History");
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
        if (entries.isEmpty()) {
            row(Kit.Icon.HISTORY, "No playback yet", "Play a file to save its position", null);
            return;
        }
        java.util.List<JSONObject> sorted = new java.util.ArrayList<>(entries.values());
        sorted.sort((a, b) -> Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt")));
        boolean hasContinue = false;
        for (JSONObject entry : sorted) {
            if (!entry.optBoolean("completed") && entry.optInt("positionMs") > 0) {
                if (!hasContinue) { historySection("Continue"); hasContinue = true; }
                historyRow(entry, true);
            }
        }
        boolean hasEarlier = false;
        for (JSONObject entry : sorted) {
            if (entry.optBoolean("completed") || entry.optInt("positionMs") <= 0) {
                if (!hasEarlier) {
                    historySection("Earlier");
                    hasEarlier = true;
                }
                historyRow(entry, false);
            }
        }
    }

    private void historySection(String title) {
        itemGroup = null;
        Kit.label(rows, title);
    }

    private void historyRow(JSONObject entry, boolean canResume) {
        if (itemGroup == null) itemGroup = Kit.group(rows);
        View row = Kit.addRow(itemGroup);
        String name = displayMediaName(entry.optString("name", "Saved media"));
        String source = entry.optString("driveLabel");
        if (source.isEmpty()) source = "Saved media";
        String destination = "phone".equals(entry.optString("target")) ? "This phone" : "Pi screen";
        int seconds = Math.max(0, entry.optInt("positionMs")) / 1000;
        String progress = canResume ? String.format(java.util.Locale.US, "Resume at %d:%02d", seconds / 60, seconds % 60)
            : entry.optBoolean("completed") ? "Finished" : "Saved at start";
        Kit.bindRow(row, favorites().contains(name) ? R.drawable.csi_favorite : Kit.Icon.HISTORY, name,
            source + " · " + destination + " · " + progress,
            canResume ? "Resume" : null, true);
        row.setOnClickListener(v -> {
            JSONObject item = new JSONObject();
            try { item.put("id", entry.getString("itemId")); item.put("name", entry.optString("name", "Saved media"));
                item.put("mime", entry.optString("mime")); }
            catch (Exception error) { setStatus(error.getMessage()); return; }
            String playbackTarget = entry.optString("target", "pi");
            int resumeAt = entry.optBoolean("completed") ? 0 : entry.optInt("positionMs");
            Runnable resume = () -> { if (playbackTarget.equals("phone")) playPhone(item, resumeAt); else playPi(item, resumeAt); };
            Runnable restart = () -> { if (playbackTarget.equals("phone")) playPhone(item, 0); else playPi(item, 0); };
            Kit.sheet(this, name, destination,
                new Kit.Action(R.drawable.csi_play, "Resume", progress, resume),
                new Kit.Action(R.drawable.csi_rewind, "Start over", null, restart));
        });
    }

    private void connections() {
        long listing = ++listingIntent;
        String host = Prefs.assistIp(this);
        clearRows("");
        accessSection("Pi USB access");
        LinearLayout primary = accessGroup();
        accessAddress(primary, "SMB address", "smb://" + host + "/sandisk");
        accessAddress(primary, "FTP address", "ftp://" + host + "/Media");
        accessSection("Sources");
        LinearLayout sources = accessGroup();
        accessRow(sources, "Checking Pi drives", "Reading mounted storage", Kit.Icon.FILES,
            R.drawable.csi_forward, "Check Pi drives", v -> connections());
        accessSection("Connection");
        LinearLayout connection = accessGroup();
        accessRow(connection, "Check Pi media service", "Checking reachability and drive mounts",
            Kit.Icon.ACCESS, R.drawable.csi_forward, "Recheck Pi media service",
            v -> connections());
        accessSection("Other Pi addresses");
        LinearLayout extra = accessGroup();
        accessAddress(extra, "SMB media", "smb://" + host + "/media");
        accessAddress(extra, "SMB files", "smb://" + host + "/files");
        accessAddress(extra, "SMB Elements", "smb://" + host + "/seagate-elements");
        accessAddress(extra, "FTP Elements", "ftp://" + host + "/Elements");
        accessAddress(extra, "SFTP", "sftp://" + host);
        listingRequest(listing, () -> {
            try { return client.get("/v1/drives"); }
            catch (Exception error) { return new JSONObject().put("error", friendlyError(error)); }
        }, result -> {
            sources.removeAllViews();
            JSONArray drives = result.optJSONArray("drives");
            if (drives == null || drives.length() == 0) {
                accessRow(sources, "Pi drives unavailable", result.optString("error", "No drive status reported"),
                    Kit.Icon.FILES, R.drawable.csi_forward, "Retry drive status",
                    v -> connections());
            } else {
                for (int i = 0; i < drives.length(); i++) {
                    JSONObject drive = drives.optJSONObject(i);
                    if (drive == null) continue;
                    String label = drive.optString("label", drive.optString("id", "Drive"));
                    String detail = drive.optBoolean("online") ? "Attached · available to browse" :
                        "Disconnected · saved history remains";
                    accessRow(sources, label, detail, Kit.Icon.FILES,
                        R.drawable.csi_forward, "Browse " + label, v -> {
                            driveId = drive.optString("id");
                            driveLabel = label;
                            path = "";
                            selectTab(R.id.media_files);
                            browse();
                        });
                }
            }
            connection.removeAllViews();
            String detail = result.has("error") ? result.optString("error") :
                "Pi media reachable · SMB and FTP sign-in not checked";
            accessRow(connection, "Check Pi media service", detail, Kit.Icon.ACCESS,
                R.drawable.csi_forward, "Recheck Pi media service", v -> connections());
        });
    }

    private void accessSection(String title) {
        Kit.label(rows, title);
    }

    private LinearLayout accessGroup() {
        return Kit.group(rows);
    }

    private void accessAddress(LinearLayout group, String title, String address) {
        accessRow(group, title, address, Kit.Icon.ACCESS, R.drawable.csi_copy, "Copy " + title + ": " + address,
            v -> { copy(address); android.widget.Toast.makeText(this, "Copied address",
                android.widget.Toast.LENGTH_SHORT).show(); });
    }

    /** An Access row. A copy action shows as a bordered button; anything else opens, so it gets a chevron. */
    private void accessRow(LinearLayout group, String title, String detail, int iconId,
                           int actionId, String description, View.OnClickListener click) {
        View row = Kit.addRow(group);
        boolean copies = actionId == R.drawable.csi_copy;
        Kit.bindRow(row, iconId, title, detail, null, !copies);
        if (copies) Kit.rowAction(row, actionId, description, click);
        row.setOnClickListener(click);
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
                String phoneState = playback.playbackState();
                findViewById(R.id.media_pause).setVisibility(phoneState.equals("playing") ? View.VISIBLE : View.GONE);
                findViewById(R.id.media_resume).setVisibility(phoneState.equals("paused") ? View.VISIBLE : View.GONE);
                output.setText("This phone · " + phoneState);
                seek.setMax(Math.max(1, playback.duration()));
                seek.setProgress(playback.position());
                JSONObject item = playback.item();
                updateFullPlayer("This phone", phoneState,
                    item == null ? "Phone media" : item.optString("name", "Phone media"),
                    playback.position(), playback.duration(), playback.volume(), playback.speed());
                showPiDisplayState(new JSONObject());
            } else {
                findViewById(R.id.media_player_controls).setVisibility(View.GONE);
                updateFullPlayer("This phone", "stopped", "", 0, 0, 0, 1);
                showPiDisplayState(new JSONObject());
            }
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
                        nowPlaying.setText(displayMediaName(state.optString("name", "Pi media")));
                        output.setText("Pi screen · " + playerState + " · volume " + state.optInt("volume", 0) + "%");
                        updateFullPlayer("Pi screen", playerState, state.optString("name", ""),
                            state.optInt("positionMs"), state.optInt("durationMs"),
                            state.optInt("volume", 0), state.optDouble("speed", 1));
                        showPiDisplayState(state);
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
                        updateFullPlayer("Pi screen", "offline", "Playback status unavailable", 0, 0, 0, 1);
                        showPiDisplayState(new JSONObject());
                        ((TextView) findViewById(R.id.player_feedback)).setText(
                            "Pi connection lost · check the Pi before using playback controls");
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
        if (playback != null && playback.hasPlayer() && !"pi".equals(target)) {
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
