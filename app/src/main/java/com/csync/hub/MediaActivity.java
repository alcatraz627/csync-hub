package com.csync.hub;

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
import android.view.ViewGroup;
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
    private static final int FILES = 0, VIDEOS = 1, HISTORY = 2, ACCESS = 3;
    private int activeTab = FILES;
    private boolean firstDriveLoad = true;
    private long listingIntent;
    private volatile long outputIntent;
    private int skipSeconds = 10;
    private final java.util.concurrent.atomic.AtomicBoolean stateInFlight = new java.util.concurrent.atomic.AtomicBoolean();
    private final ActivityResultLauncher<String> wallpaperPicker = registerForActivityResult(
        new ActivityResultContracts.GetContent(), uri -> { if (uri != null) uploadWallpaper(uri); });
    private final ActivityResultLauncher<String> mediaPicker = registerForActivityResult(
        new ActivityResultContracts.GetContent(), uri -> { if (uri != null) castPhoneFile(uri); });
    // Why playback on this phone failed and what it was, kept after the player has gone so the page can still say so.
    private String phoneProblem, phoneTitle = "";
    // A video takes the whole screen only once it has a picture to show; until then the player page says Loading.
    private boolean phoneWantsVideo;
    private final PhonePlaybackService.Observer phoneObserver = message -> {
        PhonePlaybackService playback = PhonePlaybackService.current;
        if (playback == null) return;
        if (playback.item() != null) {
            phoneTitle = playback.item().optString("name");
            nowPlaying.setText(displayMediaName(phoneTitle));
        }
        String state = playback.playbackState();
        phoneProblem = playback.problem();
        if (state.equals("playing") && phoneWantsVideo && !videoMode) {
            phoneWantsVideo = false;
            enterVideoMode();
        } else if ((state.equals("failed") || state.equals("finished")) && videoMode) {
            exitVideoMode();
            showFullPlayer();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        Appearance.apply(this);
        super.onCreate(saved);
        client = new MediaClient(Prefs.assistIp(this), Prefs.token(this));
        setContentView(R.layout.activity_media);
        Appearance.edgeToEdge(this, findViewById(R.id.media_bottom_nav));
        getOnBackPressedDispatcher().addCallback(this, backInApp);
        Kit.pullToRefresh(findViewById(R.id.media_list), () -> openTab(activeTab));
        findViewById(R.id.media_full_player).getViewTreeObserver().addOnPreDrawListener(() -> {
            backInApp.setEnabled(videoMode || fullPlayer);
            return true;
        });
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
        findViewById(R.id.media_find).setOnClickListener(v -> find());
        search.setOnEditorActionListener((v, action, event) -> { find(); return true; });
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
        findViewById(R.id.media_player_controls).setOnClickListener(v -> showFullPlayer());
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
        // Read where things were left before anything is played, so resuming does not depend on a visit to History.
        new Thread(() -> {
            try { keepPlaces(client.get("/v1/history").getJSONArray("entries")); } catch (Exception unreachable) { }
        }, "media-places").start();
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

    /** Draw the four views of Media with {@code tab} chosen. Showing a view's content is openTab's job. */
    private void selectTab(int tab) {
        activeTab = tab;
        Kit.tabs(findViewById(R.id.media_tabs),
            new int[]{Kit.Icon.FILES, Kit.Icon.VIDEO, Kit.Icon.HISTORY, Kit.Icon.ACCESS},
            new String[]{"Files", "Videos", "History", "Access"}, tab, this::openTab);
        // The search box and the line naming the drive belong to browsing, so they leave with Files.
        if (tab != FILES) {
            findViewById(R.id.media_search_line).setVisibility(View.GONE);
            findViewById(R.id.media_scope).setVisibility(View.GONE);
        }
        renderTop();
        findViewById(R.id.media_actions_toggle).setVisibility(View.GONE);
        findViewById(R.id.media_actions).setVisibility(View.GONE);
        findViewById(R.id.media_status).setVisibility(tab == HISTORY || tab == ACCESS ? View.GONE : View.VISIBLE);
        android.widget.ScrollView browser = findViewById(R.id.media_scroll);
        browser.post(() -> browser.smoothScrollTo(0, 0));
    }

    private void openTab(int tab) {
        selectTab(tab);
        if (tab == VIDEOS) videos(0, false);
        else if (tab == HISTORY) history();
        else if (tab == ACCESS) connections();
        else if (driveId.isEmpty()) drives();
        else browse();
    }

    /** Say which drive, and which folder in it, the list below is showing. */
    private void showScope() {
        TextView scope = findViewById(R.id.media_scope);
        scope.setText(path.isEmpty() ? driveLabel : driveLabel + "  /  " + path.replace("/", "  /  "));
        scope.setVisibility(driveLabel.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void openSearchItem(String itemId, String requestedDrive,
                                String relativePath, String query) {
        firstDriveLoad = false;
        selectTab(FILES);
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
        say("Sending the image to the Pi");
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
        }, result -> {
            toast(result.optBoolean("sentToDisplay") ? "Saved as the cover and shown on the Pi screen"
                : "Saved as the cover. The Pi screen is off");
            // Forget the old cover so the idle page reads the new one from the Pi.
            coverPicture = null;
            coverStored = false;
            if ("pi".equals(idleShown)) idleShown = null;
        });
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
        } catch (Exception error) { say("Could not read the selected file: " + error.getMessage()); return; }
        if (name == null || name.isEmpty() || size <= 0) {
            say("That file cannot be read from this phone. Choose another.");
            return;
        }
        // A picture or a PDF is shown, not played: the share page already knows how to put either on the screen.
        String type;
        try { type = getContentResolver().getType(uri); } catch (RuntimeException unreadable) { type = null; }
        ItemActions.Kind kind = ItemActions.kindOf(type);
        if (kind == ItemActions.Kind.IMAGE || (kind == ItemActions.Kind.DOCUMENT && ItemActions.isPdf(name, type))) {
            Intent show = new Intent(this, ShareActivity.class).setAction(Intent.ACTION_SEND)
                .setType(type == null ? "*/*" : type).putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(ShareActivity.PLACE, "pi").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            show.setClipData(android.content.ClipData.newUri(getContentResolver(), "File for the Pi screen", uri));
            startActivity(show);
            return;
        }
        final String fileName = name;
        final long fileSize = size;
        final long intent = ++outputIntent;
        stopPhoneForNewPlayback();
        target = "pi";
        selected = null;
        say("Sending " + fileName + " to Pi USB: 0%");
        request(intent, () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new Exception("Could not open selected media");
                return client.uploadMedia("sandisk", fileName, fileSize, input, sent -> {
                    int percent = (int) (sent * 100 / fileSize);
                    ui.post(() -> { if (screenActive && intent == outputIntent)
                        say("Sending " + fileName + " to Pi USB: " + percent + "%"); });
                });
            }
        }, result -> {
            JSONObject item = result.getJSONObject("item");
            say("Saved on Pi USB. Opening it on the Pi screen");
            playPi(item);
        });
    }

    private void castYoutube() {
        Kit.fieldSheet(this, "A link", "Plays on the Pi screen, muted to begin with", "Paste a YouTube link",
            "From YouTube or Instagram, use Share and choose Send to Pi screen.",
            Kit.Icon.DISPLAY, "Play on Pi screen", this::startYoutube);
    }

    private void startYoutube(String url) {
                final long intent = ++outputIntent;
                stopPhoneForNewPlayback();
                target = "pi";
                selected = null;
                say("Opening the link on the Pi screen");
                request(intent, () -> {
                    if (intent != outputIntent) throw new java.util.concurrent.CancellationException();
                    JSONObject result = client.post("/v1/cast/youtube", new JSONObject().put("url", url));
                    if (intent != outputIntent) stopStalePiStart(result);
                    return result;
                }, result -> {
                    showFullPlayer();
                    say("It starts muted. Use Volume for sound.");
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
                    if (listing >= 0) listDone();
                    if (!screenActive || (intent >= 0 && intent != outputIntent) ||
                            (listing >= 0 && listing != listingIntent)) return;
                    try { show.accept(result); } catch (Exception error) { say(error.getMessage()); }
                });
            } catch (Exception error) { ui.post(() -> {
                if (listing >= 0) listDone();
                if (screenActive && (intent < 0 || intent == outputIntent) &&
                        (listing < 0 || listing == listingIntent)) say(friendlyError(error));
            }); }
        }, "media-request").start();
    }

    /** A listing has answered, so a pull to refresh that asked for it is over. */
    private void listDone() {
        ((androidx.swiperefreshlayout.widget.SwipeRefreshLayout) findViewById(R.id.media_list)).setRefreshing(false);
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

    /** Name the list below. History and Access bring their own headings, so there the line stays away. */
    private void heading(String text) {
        status.setText(text);
        status.setVisibility(activeTab == HISTORY || activeTab == ACCESS || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private final Runnable unsay = () -> findViewById(R.id.media_say).setVisibility(View.GONE);

    /**
     * Tell the person what just happened or is happening. Over the lists it is a line above
     * them that leaves by itself; over the player, where that line is hidden, it is a toast.
     */
    private void say(String text) {
        if (text == null || text.isEmpty()) return;
        if (fullPlayer || videoMode) { toast(text); return; }
        TextView line = findViewById(R.id.media_say);
        line.setText(text);
        line.setVisibility(View.VISIBLE);
        ui.removeCallbacks(unsay);
        ui.postDelayed(unsay, 6000);
    }

    private void showFullPlayer() {
        if (videoMode) return;
        fullPlayer = true;
        hideKeyboard();
        findViewById(R.id.media_list).setVisibility(View.GONE);
        findViewById(R.id.media_full_player).setVisibility(View.VISIBLE);
        // The page carries the controls itself, so the row that leads to it steps aside.
        findViewById(R.id.media_player_controls).setVisibility(View.GONE);
        renderTop();
        if ("phone".equals(target) && PhonePlaybackService.current == null && phoneProblem == null)
            updateFullPlayer("This phone", "stopped", "", 0, 0, 0, 1);
        if (phoneProblem == null) ((TextView) findViewById(R.id.player_feedback)).setText("");
    }

    private void closeFullPlayer() {
        fullPlayer = false;
        findViewById(R.id.media_full_player).setVisibility(View.GONE);
        findViewById(R.id.media_list).setVisibility(View.VISIBLE);
        if ("pi".equals(target)) findViewById(R.id.media_player_controls).setVisibility(View.VISIBLE);
        selectTab(activeTab);
    }

    private static String playbackTime(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The readable name: no upload prefix, spaces for underscores and dots, and no file ending. The raw name is in File details. */
    static String displayMediaName(String name) {
        String readable = name.replaceFirst("^cast-[0-9a-f]{8,32}-", "");
        int dot = readable.lastIndexOf('.');
        boolean ending = dot > 0 && readable.substring(dot + 1).matches("[A-Za-z0-9]{2,4}");
        if (ending) readable = readable.substring(0, dot);
        // Only a file name written without spaces uses dots in their place; shown words keep their full stops.
        if (ending && readable.indexOf(' ') < 0) readable = readable.replace('.', ' ');
        readable = readable.replace('_', ' ').trim();
        // A downloaded film carries its picture size and encoder after the title; the title ends where they begin.
        java.util.regex.Matcher release = java.util.regex.Pattern.compile(
            "(?i)[ .(\\[]+(2160p|1080p|720p|480p|bluray|blu-ray|bdrip|brrip|web-?dl|webrip|hdtv|dvdrip|x26[45]|h ?26[45]|hevc)\\b")
            .matcher(readable);
        if (release.find() && release.start() > 2) readable = readable.substring(0, release.start()).trim();
        return readable;
    }

    /** Where a file lives, said shortly: the drive and the folder it sits in. */
    private String placeWords(JSONObject item) {
        String drive = driveLabels.getOrDefault(item.optString("driveId"), item.optString("driveId"));
        String[] steps = item.optString("relativePath").split("/");
        return steps.length < 2 ? drive : drive + " · " + steps[steps.length - 2].replace('_', ' ');
    }

    private static String sizeWords(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return Math.max(1, bytes >> 10) + " KB";
    }

    /** The facts about one file on a drive, including the raw name and path that the lists leave out. */
    private void fileDetails(JSONObject item) {
        Kit.sheet(this, displayMediaName(item.optString("name")), placeWords(item),
            new Kit.Action(Kit.Icon.FILE, "File name", item.optString("name"), () -> copy(item.optString("name"))),
            new Kit.Action(Kit.Icon.FOLDER, "Path", item.optString("relativePath"), () -> copy(item.optString("relativePath"))),
            new Kit.Action(R.drawable.csi_info, "Size", sizeWords(item.optLong("size")), () -> { }));
    }

    private void renderSkip() {
        findViewById(R.id.player_back10).setContentDescription("Rewind " + skipSeconds + " seconds");
        findViewById(R.id.player_forward10).setContentDescription("Forward " + skipSeconds + " seconds");
        ((TextView) findViewById(R.id.player_skip)).setText("Skip · " + skipSeconds + " seconds");
    }

    // Favorites live on this phone, keyed by the title shown in the player and on History.
    private String playerTitle = "";

    private java.util.Set<String> favorites() {
        // Favourites saved under the older names, which kept the file ending, still count.
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (String saved : getSharedPreferences("media_favorites", MODE_PRIVATE)
                .getStringSet("titles", new java.util.HashSet<>())) titles.add(displayMediaName(saved));
        return titles;
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

    /** What the player is doing, in the words a person would use, with where it is happening. */
    private static String stateWords(String outputName, String state, String problem) {
        String where = outputName.equals("Pi screen") ? "on the Pi screen" : "on this phone";
        switch (state) {
            case "playing": return "Playing " + where;
            case "paused": return "Paused " + where;
            case "loading": case "buffering": return "Loading " + where;
            case "finished": return "Finished " + where;
            case "showing": return "Showing " + where;
            case "offline": return "The Pi cannot be reached";
            default: return problem == null ? "Nothing is playing " + where : "It could not be played " + where;
        }
    }

    private static Kit.Status stateStatus(String state, String problem) {
        switch (state) {
            case "playing": case "showing": return Kit.Status.GOOD;
            case "loading": case "buffering": return Kit.Status.WARN;
            case "offline": return Kit.Status.BAD;
            case "paused": case "finished": return Kit.Status.IDLE;
            default: return problem == null ? Kit.Status.IDLE : Kit.Status.BAD;
        }
    }

    /**
     * The controls for something shown: Stop, and for a document the page it is on with a way to
     * turn it. Redrawn only when the page or the kind changes, so a poll does not flicker them.
     */
    private void drawShownControls(android.widget.FrameLayout frame, int[] pages) {
        String signature = pages == null ? "plain" : pages[0] + "/" + pages[1];
        if (signature.equals(frame.getTag())) return;
        frame.setTag(signature);
        frame.removeAllViews();
        android.widget.LinearLayout column = new android.widget.LinearLayout(this);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        if (pages != null) {
            android.widget.LinearLayout turn = new android.widget.LinearLayout(this);
            turn.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            turn.setGravity(android.view.Gravity.CENTER_VERTICAL);
            View previous = Kit.button(this, R.drawable.csi_back, "Previous", R.color.text, () -> control("previous"));
            View next = Kit.button(this, R.drawable.csi_forward, "Next", R.color.text, () -> control("next"));
            previous.setEnabled(pages[0] > 1);
            previous.setAlpha(pages[0] > 1 ? 1f : 0.45f);
            next.setEnabled(pages[0] < pages[1]);
            next.setAlpha(pages[0] < pages[1] ? 1f : 0.45f);
            TextView where = new TextView(this);
            where.setText("Page " + pages[0] + " of " + pages[1]);
            where.setTextColor(getColor(R.color.dim));
            where.setTextSize(13f);
            where.setGravity(android.view.Gravity.CENTER);
            turn.addView(previous, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            turn.addView(where, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            turn.addView(next, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            android.widget.LinearLayout.LayoutParams gap = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gap.bottomMargin = Kit.dp(this, 12);
            column.addView(turn, gap);
        }
        column.addView(Kit.button(this, R.drawable.csi_stop, "Stop showing", R.color.danger, () -> control("stop")));
        frame.addView(column);
    }

    private void updateFullPlayer(String outputName, String state, String title,
                                  int position, int duration, int volume, double speed) {
        updateFullPlayer(outputName, state, title, position, duration, volume, speed, null);
    }

    /** Fill the player page. {@code problem} is why playback failed, when it did. */
    private void updateFullPlayer(String outputName, String state, String title,
                                  int position, int duration, int volume, double speed, String problem) {
        boolean onPi = outputName.equals("Pi screen");
        boolean offline = state.equals("offline");
        boolean session = state.equals("playing") || state.equals("paused") || state.equals("loading") ||
            state.equals("buffering") || state.equals("showing") || state.equals("finished");
        // With no session the page offers ways to start one, never a row of controls that do nothing.
        boolean idle = !offline && !session && problem == null;
        showIdle(offline ? "offline" : !idle ? null : onPi ? "pi" : "phone");
        TextView heading = findViewById(R.id.player_title);
        TextView words = findViewById(R.id.player_target_state);
        if (offline || idle) {
            heading.setText(offline ? "Pi screen" : "Nothing is playing");
            words.setText(offline ? "Offline" : coverStored ? "Showing the cover" : "Stopped");
            Kit.setStatus(findViewById(R.id.player_dot), Kit.Status.IDLE);
            playerTitle = "";
            return;
        }
        words.setText(stateWords(outputName, state, problem));
        Kit.setStatus(findViewById(R.id.player_dot), stateStatus(state, problem));
        ((TextView) findViewById(R.id.player_feedback)).setText(problem == null ? "" : problem);
        heading.setText(title == null || title.isEmpty() ? outputName : displayMediaName(title));
        playerTitle = title == null ? "" : displayMediaName(title);
        renderFavorite();
        // Something shown (a picture, words, a slideshow) can only be stopped, so the rest is left out.
        boolean shownOnly = state.equals("showing");
        for (int id : new int[]{R.id.player_seek_block, R.id.player_settings_block, R.id.player_transport})
            findViewById(id).setVisibility(shownOnly ? View.GONE : View.VISIBLE);
        android.widget.FrameLayout stop = findViewById(R.id.player_shown_stop);
        stop.setVisibility(shownOnly ? View.VISIBLE : View.GONE);
        if (shownOnly) drawShownControls(stop, onPi ? shownPages : null);
        boolean showing = state.equals("playing") || state.equals("paused");
        ((TextView) findViewById(R.id.player_art)).setText(
            showing && onPi ? "The picture is on the Pi screen" :
            showing ? "The sound is on this phone" :
            state.equals("loading") || state.equals("buffering") ? "Loading" :
            state.equals("showing") ? "It is on the Pi screen" :
            state.equals("finished") ? "Finished" : "");
        findViewById(R.id.player_display_row).setVisibility(onPi ? View.VISIBLE : View.GONE);
        android.widget.ImageView pause = findViewById(R.id.player_pause);
        pause.setImageResource(state.equals("paused") ? R.drawable.csi_play : R.drawable.csi_pause);
        pause.setContentDescription(state.equals("paused") ? "Resume" : "Pause");
        boolean ready = state.equals("playing") || state.equals("paused");
        // Something shown (a picture, words, a slideshow) can be stopped but has no position to seek.
        boolean active = ready || state.equals("loading") || state.equals("buffering") || state.equals("showing");
        playerSeek.setEnabled(ready && duration > 0);
        playerSeek.setMax(Math.max(1, duration));
        if (!seeking) playerSeek.setProgress(Math.max(0, position));
        ((TextView) findViewById(R.id.player_position)).setText(playbackTime(position));
        ((TextView) findViewById(R.id.player_duration)).setText(duration > 0 ? playbackTime(duration) : "");
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

    // What the idle page last drew, so the two-second refresh does not rebuild it each time.
    private String idleShown;
    private boolean coverStored;
    // The page a document on the Pi screen is on and how many it has, or null when nothing paged is shown.
    private int[] shownPages;
    private Bitmap coverPicture;
    private String displayName;

    /**
     * Swap the player's controls for the page an output shows with no session, or back.
     * {@code kind} is "pi", "phone" or "offline"; null brings the controls back.
     */
    private void showIdle(String kind) {
        LinearLayout host = findViewById(R.id.player_idle);
        host.setVisibility(kind == null ? View.GONE : View.VISIBLE);
        findViewById(R.id.player_live).setVisibility(kind == null ? View.VISIBLE : View.GONE);
        // An empty phone output says so in its empty state, so a heading above it would say it twice.
        int headed = "phone".equals(kind) ? View.GONE : View.VISIBLE;
        findViewById(R.id.player_title).setVisibility(headed);
        findViewById(R.id.player_state_row).setVisibility(headed);
        if (java.util.Objects.equals(kind, idleShown)) return;
        idleShown = kind;
        host.removeAllViews();
        if (kind == null) return;
        if (kind.equals("phone")) {
            Kit.empty(host, Kit.Icon.DEVICE, "Nothing is playing on this phone", null,
                Kit.button(this, Kit.Icon.MEDIA, "Browse Media", R.color.text, this::closeFullPlayer));
        } else if (kind.equals("offline")) {
            Kit.empty(host, Kit.Icon.DISPLAY, "The Pi cannot be reached",
                "What it is showing is not known until it answers again.",
                Kit.button(this, R.drawable.csi_wifi, "Open Connection", R.color.text, () ->
                    startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("destination", "settings"))));
        } else piIdle(host);
    }

    /** The Pi screen with nothing on it: its cover, the ways to put something up, and the screen's own settings. */
    private void piIdle(LinearLayout host) {
        if (coverPicture != null) {
            android.widget.ImageView cover = new android.widget.ImageView(this);
            cover.setImageBitmap(coverPicture);
            cover.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            cover.setBackgroundResource(R.drawable.player_poster_bg);
            cover.setClipToOutline(true);
            cover.setContentDescription("The cover image on the Pi screen");
            LinearLayout.LayoutParams frame = new LinearLayout.LayoutParams(-1, dp(170));
            frame.topMargin = dp(12);
            host.addView(cover, frame);
        }
        java.util.List<Kit.Section> sections = new java.util.ArrayList<>();
        sections.add(new Kit.Section("From the Pi", java.util.Arrays.asList(
            new Kit.Action(Kit.Icon.MEDIA, "Browse Media", "Films, shows, photos", this::closeFullPlayer, true),
            new Kit.Action(Kit.Icon.PHOTO, "Photos as a slideshow", "Every image in a folder, in turn",
                this::chooseSlideshow, true),
            new Kit.Action(Kit.Icon.CAMERA, "The Pi camera", "Live picture", this::showCamera))));
        sections.add(new Kit.Section("From this phone", java.util.Arrays.asList(
            new Kit.Action(Kit.Icon.FILE, "A file", "A video, a song, a photo or a PDF kept on this phone",
                () -> mediaPicker.launch("*/*"), true),
            new Kit.Action(R.drawable.csi_link, "A link", "YouTube, or share a video from another app",
                this::castYoutube, true),
            new Kit.Action(Kit.Icon.NOTES, "A note", "Shown large, easy to read across a room",
                this::chooseNote, true))));
        sections.add(new Kit.Section("This screen", java.util.Arrays.asList(
            new Kit.Action(R.drawable.csi_image, "Cover image", "Shown when nothing is playing", this::coverSheet, true),
            new Kit.Action(Kit.Icon.DISPLAY, "Display", null, () -> DisplaySheet.open(this), true).value(displayName))));
        Kit.sections(host, sections, null);
        loadIdleFacts();
    }

    /** Read the cover and the screen's name from the Pi, and redraw the idle page when either is news. */
    private void loadIdleFacts() {
        new Thread(() -> {
            Bitmap picture = null;
            try {
                byte[] jpeg = client.getBytes("/v1/display/wallpaper/image", 10 * 1024 * 1024);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 1280) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
            } catch (Exception none) { }
            String name = DisplaySheet.current(client);
            Bitmap cover = picture;
            ui.post(() -> {
                if (!screenActive) return;
                boolean news = (cover != null) != coverStored || !java.util.Objects.equals(name, displayName);
                coverStored = cover != null;
                coverPicture = cover;
                displayName = name;
                // Dropping the record makes the next refresh draw the page again with what was just read.
                if (news && "pi".equals(idleShown)) idleShown = null;
            });
        }, "media-idle-facts").start();
    }

    /** Offer each folder at the top of a connected drive; the Pi says so when one has no photos. */
    private void chooseSlideshow() {
        new Thread(() -> {
            java.util.List<Kit.Action> folders = new java.util.ArrayList<>();
            String problem = null;
            try {
                JSONArray drives = client.get("/v1/drives").getJSONArray("drives");
                for (int i = 0; i < drives.length(); i++) {
                    JSONObject drive = drives.getJSONObject(i);
                    if (!drive.optBoolean("online")) continue;
                    String source = drive.getString("id"), label = drive.optString("label", source);
                    JSONArray items = client.get("/v1/items?driveId=" + MediaClient.enc(source) + "&path=&offset=0")
                        .getJSONArray("items");
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        if (!item.optBoolean("directory")) continue;
                        String name = item.optString("name");
                        folders.add(new Kit.Action(Kit.Icon.FOLDER, name, label,
                            () -> slideshow(source, item.optString("relativePath"), name)));
                    }
                }
            } catch (Exception error) { problem = friendlyError(error); }
            String failure = problem;
            ui.post(() -> {
                if (!screenActive) return;
                if (failure != null) Kit.sheet(this, "Photos as a slideshow", failure);
                else if (folders.isEmpty()) Kit.sheet(this, "Photos as a slideshow", "No connected drive has a folder to show.");
                else Kit.sheet(this, "Photos as a slideshow", "Choose a folder. Its photos show in turn on the Pi screen.",
                    folders.toArray(new Kit.Action[0]));
            });
        }, "media-slideshow-folders").start();
    }

    /** Offer the notes kept on the Pi, and put the chosen one up on its screen. */
    private void chooseNote() {
        new Thread(() -> {
            java.util.List<Kit.Action> notes = new java.util.ArrayList<>();
            String problem = null;
            try {
                JSONArray all = client.get("/v1/notes").getJSONArray("notes");
                for (int i = 0; i < all.length(); i++) {
                    JSONObject note = all.getJSONObject(i);
                    notes.add(new Kit.Action(Kit.Icon.NOTES, note.optString("title", "Note"), null,
                        () -> showNote(note.optString("id"))));
                }
            } catch (Exception error) { problem = friendlyError(error); }
            String failure = problem;
            ui.post(() -> {
                if (!screenActive) return;
                if (failure != null) Kit.sheet(this, "Show a note", failure);
                else if (notes.isEmpty()) Kit.sheet(this, "Show a note", "There are no notes on the Pi yet.");
                else Kit.sheet(this, "Show a note", "On the Pi screen", notes.toArray(new Kit.Action[0]));
            });
        }, "media-note-list").start();
    }

    /** Put the live camera picture on the Pi screen; the page turns into its Stop showing view as the Pi reports it. */
    private void showCamera() {
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try { lit = client.post("/v1/display/camera", null).optBoolean("sentToDisplay"); }
            catch (Exception error) { problem = friendlyError(error); }
            String failure = problem;
            boolean shown = lit;
            ui.post(() -> {
                if (!screenActive) return;
                if (failure != null) Kit.sheet(this, "It was not shown", failure);
                else toast(shown ? "Showing the camera on the Pi screen" : "Sent. The Pi screen is off");
            });
        }, "media-camera-show").start();
    }

    private void showNote(String id) {
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try {
                JSONObject note = client.get("/v1/notes/" + MediaClient.enc(id)).getJSONObject("note");
                String words = note.optString("body").trim();
                lit = client.post("/v1/display/show", new JSONObject().put("title", note.optString("title"))
                    .put("text", words.isEmpty() ? note.optString("title") : MediaClient.screenText(words)))
                    .optBoolean("sentToDisplay");
            } catch (Exception error) { problem = friendlyError(error); }
            String failure = problem;
            boolean shown = lit;
            ui.post(() -> {
                if (!screenActive) return;
                if (failure != null) Kit.sheet(this, "It was not shown", failure);
                else toast(shown ? "Showing on the Pi screen" : "Sent. The Pi screen is off");
            });
        }, "media-note-show").start();
    }

    /**
     * The cover as it is now, how it is framed on the screen in use, and the way to choose
     * another. Fit and Rotate are kept on the Pi with that screen, so a projector and a
     * monitor each remember their own.
     */
    private void coverSheet() {
        new Thread(() -> {
            JSONObject screen = DisplaySheet.screenInUse(client);
            ui.post(() -> { if (screenActive) drawCoverSheet(screen); });
        }, "media-cover-sheet").start();
    }

    private static final String[] FITS = {"cover", "contain", "stretch"};

    private void drawCoverSheet(JSONObject screen) {
        JSONObject kept = screen == null ? null : screen.optJSONObject("settings");
        String fit = kept == null ? "cover" : kept.optString("coverFit", "cover");
        int turn = kept == null ? 0 : kept.optInt("coverRotate");
        Kit.Sheet sheet = new Kit.Sheet(this, "Cover image",
            coverStored ? "Shown when nothing is playing" : "No cover is saved. The screen is blank when nothing is playing.");
        if (coverPicture != null) {
            android.widget.ImageView picture = new android.widget.ImageView(this);
            // The picture itself is turned, so the preview stays inside its frame whichever way it faces.
            android.graphics.Matrix quarter = new android.graphics.Matrix();
            quarter.postRotate(turn);
            picture.setImageBitmap(turn == 0 ? coverPicture : Bitmap.createBitmap(coverPicture, 0, 0,
                coverPicture.getWidth(), coverPicture.getHeight(), quarter, true));
            picture.setScaleType(fit.equals("stretch") ? android.widget.ImageView.ScaleType.FIT_XY
                : fit.equals("contain") ? android.widget.ImageView.ScaleType.FIT_CENTER
                : android.widget.ImageView.ScaleType.CENTER_CROP);
            picture.setBackgroundResource(R.drawable.player_poster_bg);
            picture.setClipToOutline(true);
            picture.setContentDescription("The cover, framed as the Pi screen shows it");
            sheet.rows.addView(picture, new LinearLayout.LayoutParams(-1, dp(190)));
        }
        if (screen != null) {
            Kit.label(sheet.rows, "Fit on " + screen.optString("name", "the Pi screen"));
            LinearLayout fits = new LinearLayout(this);
            sheet.rows.addView(fits, new LinearLayout.LayoutParams(-1, -2));
            Kit.segmented(fits, new int[]{R.drawable.csi_expand, R.drawable.csi_fit, Kit.Icon.DISPLAY},
                new String[]{"Cover", "Contain", "Stretch"}, java.util.Arrays.asList(FITS).indexOf(fit),
                index -> saveCoverFraming(sheet, screen, "coverFit", FITS[index]));
            LinearLayout group = Kit.group(sheet.rows);
            ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = dp(12);
            View rotate = Kit.addRow(group);
            Kit.bindRow(rotate, Kit.Icon.ROTATE, "Rotate", "A quarter turn each tap", turn + "°", false);
            rotate.setOnClickListener(v -> saveCoverFraming(sheet, screen, "coverRotate", (turn + 90) % 360));
        }
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
        below.topMargin = dp(12);
        sheet.rows.addView(Kit.button(this, R.drawable.csi_plus, "Choose an image", R.color.text, () -> {
            sheet.dialog.dismiss();
            wallpaperPicker.launch("image/*");
        }), below);
        sheet.show();
    }

    /** Send one framing change to the Pi and redraw the sheet from what it kept. */
    private void saveCoverFraming(Kit.Sheet sheet, JSONObject screen, String setting, Object value) {
        new Thread(() -> {
            JSONObject after = null;
            try {
                after = client.put("/v1/displays/" + MediaClient.enc(screen.optString("id")),
                    new JSONObject().put("settings", new JSONObject().put(setting, value))).optJSONObject("display");
            } catch (Exception refused) { }
            JSONObject saved = after;
            ui.post(() -> {
                if (!screenActive) return;
                sheet.dialog.dismiss();
                if (saved == null) Kit.sheet(this, "It was not saved", "The Pi did not take the change. Nothing was altered.");
                else drawCoverSheet(saved);
            });
        }, "media-cover-save").start();
    }

    private void hideKeyboard() {
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void toast(String words) {
        android.widget.Toast.makeText(this, words, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void clearRows(String title) {
        rows.removeAllViews();
        itemGroup = null;
        heading(title);
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

    /** The top bar: Media alone, or Media then the player. Back leaves the player and never walks folders. */
    private void renderTop() {
        View top = findViewById(R.id.media_top);
        if (fullPlayer || videoMode) {
            // The page is named for the output it shows, so the path reads Media, then Pi screen or This phone.
            Kit.pageTop(top, "pi".equals(target) ? "pi-screen" : "phone-player",
                id -> { if (videoMode) exitVideoMode(); else closeFullPlayer(); });
            return;
        }
        Kit.pageTop(top, "media", id -> { });
        if (activeTab != FILES) return;
        Kit.topAction(top, Kit.Icon.SEARCH, "Search this source", v -> {
            // The box appears when asked for, so the list starts higher; a second tap puts it away.
            View line = findViewById(R.id.media_search_line);
            boolean opening = line.getVisibility() != View.VISIBLE;
            line.setVisibility(opening ? View.VISIBLE : View.GONE);
            android.view.inputmethod.InputMethodManager keys =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (opening) { search.requestFocus(); keys.showSoftInput(search, 0); }
            else keys.hideSoftInputFromWindow(search.getWindowToken(), 0);
        });
        Kit.topAction(top, Kit.Icon.SOURCE, "Switch source", v -> chooseSource());
    }

    /** The drives to browse, each with whether it is connected. Picking one opens it. */
    private void chooseSource() {
        new Thread(() -> {
            JSONArray found = null;
            try { found = client.get("/v1/drives").getJSONArray("drives"); }
            catch (Exception unreachable) { }
            JSONArray drives = found;
            ui.post(() -> {
                if (!screenActive) return;
                if (drives == null) { Kit.sheet(this, "Choose a source", "The Pi cannot be reached, so its drives are not known."); return; }
                java.util.List<Kit.Action> choices = new java.util.ArrayList<>();
                for (int i = 0; i < drives.length(); i++) {
                    JSONObject drive = drives.optJSONObject(i);
                    if (drive == null) continue;
                    String id = drive.optString("id"), label = drive.optString("label", id);
                    boolean online = drive.optBoolean("online");
                    choices.add(new Kit.Action(id.equals(driveId) ? R.drawable.csi_check : Kit.Icon.FILES, label, null, () -> {
                        if (!online) { toast(label + " is disconnected"); return; }
                        driveId = id; driveLabel = label; path = "";
                        selectTab(FILES);
                        browse();
                    }).value(online ? "Connected" : "Disconnected"));
                }
                Kit.sheet(this, "Choose a source", null, choices.toArray(new Kit.Action[0]));
            });
        }, "media-sources").start();
    }

    private void drives() {
        long listing = ++listingIntent;
        showingVideos = false;
        driveId = "";
        driveLabel = "";
        path = "";
        selectTab(FILES);
        search.setHint("Search a connected drive");
        showScope();
        clearRows("Checking drives");
        listingRequest(listing, () -> client.get("/v1/drives"), result -> {
            clearRows("Drives");
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
                if (itemGroup == null) itemGroup = Kit.group(rows);
                View row = Kit.addRow(itemGroup);
                Kit.bindRow(row, Kit.Icon.FILES, label, null, null, online);
                Kit.rowStatus(row, online ? Kit.Status.GOOD : Kit.Status.IDLE, online ? "Connected" : "Disconnected");
                if (online) row.setOnClickListener(v -> { driveId = id; driveLabel = label; path = ""; browse(); });
                else row.setClickable(false);
            }
            if (firstDriveLoad) {
                firstDriveLoad = false;
                if (firstOnline != null) {
                    driveId = firstOnline.getString("id");
                    driveLabel = firstOnline.getString("label");
                    path = "";
                    selectTab(FILES);
                    browse();
                }
            }
        });
    }

    private void browse() { browse(0, false); }

    private void browse(int offset, boolean append) {
        long listing = ++listingIntent;
        showingVideos = false;
        search.setHint("Search connected media");
        showScope();
        String requestedDrive = driveId, requestedPath = path;
        if (!append) clearRows("Opening " + path);
        listingRequest(listing, () -> client.get("/v1/items?driveId=" + MediaClient.enc(requestedDrive) +
            "&path=" + MediaClient.enc(requestedPath) + "&offset=" + offset), result -> {
            if (!requestedDrive.equals(driveId) || !requestedPath.equals(path)) return;
            JSONArray items = result.getJSONArray("items");
            int slash = path.lastIndexOf('/');
            String parent = slash < 0 ? "" : path.substring(0, slash);
            String above = parent.isEmpty() ? driveLabel : parent.substring(parent.lastIndexOf('/') + 1);
            Runnable up = () -> { path = parent; browse(); };
            if (!append) {
                listedFolder = listedFile = false;
                // The Pi lists folders first, so the first thing decides which heading the list opens under.
                clearRows(items.length() == 0 ? "" : items.getJSONObject(0).optBoolean("directory") ? "Folders" : "Files");
                if (items.length() == 0) Kit.empty(rows, Kit.Icon.FOLDER, "This folder is empty", null,
                    path.isEmpty() ? null : Kit.button(this, R.drawable.csi_back, "Up to " + above, R.color.text, up));
                else if (!path.isEmpty()) row(R.drawable.csi_back, "Up", above, v -> up.run());
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                boolean folder = item.optBoolean("directory");
                if (!folder && listedFolder && !listedFile) { itemGroup = null; Kit.label(rows, "Files"); }
                if (folder) listedFolder = true; else listedFile = true;
                showItem(item, false);
            }
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
            int total = result.getInt("total");
            if (!append) clearRows(total == 0 ? "" : (total == 1 ? "1 video" : total + " videos") + " on every connected drive");
            JSONArray items = result.getJSONArray("items");
            if (!append && items.length() == 0)
                Kit.empty(rows, Kit.Icon.VIDEO, "No videos on the connected drives", "Videos in any folder of a drive are listed here.",
                    Kit.button(this, Kit.Icon.FILES, "Browse Files", R.color.text, () -> openTab(FILES)));
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                mediaItemRow(item, false, placeWords(item));
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
        if (term.isEmpty()) { say("Type part of a file name first"); return; }
        hideKeyboard();
        clearRows("Searching");
        listingRequest(listing, () -> client.get("/v1/search?q=" + MediaClient.enc(term)),
            result -> showItems(result.getJSONArray("items"), result.optBoolean("truncated") ? "First results; narrow your search" : "Search results"));
    }

    private void showItems(JSONArray items, String title) throws Exception {
        clearRows(items.length() == 0 ? "" : title);
        if (items.length() == 0) Kit.empty(rows, Kit.Icon.SEARCH, "Nothing matches \"" + search.getText().toString().trim() + "\"",
            "Every connected drive was searched.", null);
        for (int i = 0; i < items.length(); i++) {
            showItem(items.getJSONObject(i), true);
        }
    }

    /**
     * One file or folder in a list. While browsing, the line under the name says how much is in
     * it; among search results it says where the thing lives, since they come from anywhere.
     */
    private void showItem(JSONObject item, boolean found) {
        boolean folder = item.optBoolean("directory");
        String detail;
        if (found) detail = folder ? "Folder in " + placeWords(item) : placeWords(item);
        else if (!folder) detail = sizeWords(item.optLong("size"));
        else if (item.has("count")) detail = item.optInt("count") == 1 ? "1 item" : item.optInt("count") + " items";
        else detail = null;
        mediaItemRow(item, folder, detail);
    }

    // Whether the list being filled has shown a folder or a file yet, which decides when the Files heading goes in.
    private boolean listedFolder, listedFile;

    private void mediaItemRow(JSONObject item, boolean folder, String detail) {
        if (itemGroup == null) itemGroup = Kit.group(rows);
        View row = Kit.addRow(itemGroup);
        String mime = item.optString("mime");
        String name = folder ? item.optString("name") : displayMediaName(item.optString("name"));
        Kit.bindRow(row, folder ? Kit.Icon.FOLDER : mime.startsWith("image/") ? Kit.Icon.PHOTO
            : mime.startsWith("video/") ? Kit.Icon.VIDEO : Kit.Icon.FILE, name, detail, null, true);
        Runnable open = () -> {
            if (folder) {
                driveId = item.optString("driveId");
                driveLabel = driveLabels.getOrDefault(driveId, driveId);
                path = item.optString("relativePath");
                browse();
            } else chooseTarget(item);
        };
        row.setOnClickListener(v -> open.run());
        // A file's row already opens its choices. A folder's row opens the folder, so its choices get their own button.
        if (folder) Kit.rowAction(row, R.drawable.csi_menu, "What to do with the folder " + name, v ->
            Kit.sheet(this, name, detail == null ? "Folder" : "Folder, " + detail,
                new Kit.Action(Kit.Icon.FOLDER, "Open", null, open),
                new Kit.Action(Kit.Icon.DISPLAY, "Photos as a slideshow", "Every image in this folder, in turn, on the Pi screen",
                    () -> slideshow(item.optString("driveId"), item.optString("relativePath"), name)),
                new Kit.Action(R.drawable.csi_copy, "Copy path", item.optString("relativePath"),
                    () -> copy(item.optString("relativePath")))));
    }

    /** Show a folder's photos on the Pi screen one after another. The Pi says so when the folder has none. */
    private void slideshow(String drive, String folder, String name) {
        new Thread(() -> {
            String problem = null;
            boolean lit = false;
            try {
                lit = client.post("/v1/display/slideshow", new JSONObject().put("driveId", drive).put("path", folder))
                    .optBoolean("sentToDisplay");
            } catch (Exception error) { problem = error.getMessage() == null ? "The Pi did not answer." : error.getMessage(); }
            String failure = problem;
            boolean shown = lit;
            runOnUiThread(() -> {
                // The line above the list names the list, so what happened is said in a toast instead.
                if (failure != null) Kit.sheet(this, "The slideshow did not start", failure);
                else toast(shown ? "Showing the photos in " + name + " on the Pi screen" : "Sent. The Pi screen is off");
            });
        }, "media-slideshow").start();
    }

    private void chooseTarget(JSONObject item) {
        // The shared list names the choices. A drive file plays by its id on the Pi, so this page does the playing itself.
        String mime = item.optString("mime", "application/octet-stream");
        ItemActions.Kind kind = ItemActions.kindOf(mime);
        ItemActions.Item file = new ItemActions.Item(kind, displayMediaName(item.optString("name")));
        file.sub = placeWords(item);
        file.file = got -> {
            try { got.file(mediaUri(item), mime); }
            catch (org.json.JSONException incomplete) { say("This file cannot be read from the Pi"); }
        };
        // With "Resume where I stopped" on, a file played before carries on from where it was left on that output.
        int onPi = savedPlace(item, "pi"), onPhone = savedPlace(item, "phone");
        file.own.put(ItemActions.Act.PLAY_PI, () -> playPi(item, onPi));
        file.own.put(ItemActions.Act.PLAY_PHONE, () -> playPhone(item, onPhone));
        file.own.put(ItemActions.Act.VLC, () -> playVlc(item));
        file.notes.put(ItemActions.Act.PLAY_PI, onPi > 0 ? "From " + playbackTime(onPi) + ", muted" : "Starts muted");
        if (onPhone > 0) file.notes.put(ItemActions.Act.PLAY_PHONE, "From " + playbackTime(onPhone));
        file.notes.put(ItemActions.Act.VLC, "Hands the file to VLC on this phone");
        if (kind == ItemActions.Kind.IMAGE) {
            file.own.put(ItemActions.Act.SHOW_PI, () -> playPi(item));
            file.own.put(ItemActions.Act.OPEN, () -> playPhone(item, 0));
        }
        file.more.add(new Kit.Action(R.drawable.csi_info, "File details", null, () -> fileDetails(item), true));
        ItemActions.sheet(this, file);
    }

    /** Where this file was left on an output, in milliseconds, or 0 when it starts from the beginning. */
    private int savedPlace(JSONObject item, String output) {
        if (!getSharedPreferences("player_controls", MODE_PRIVATE).getBoolean("resume", true)) return 0;
        String saved = getSharedPreferences("media_history", MODE_PRIVATE)
            .getString(item.optString("id") + "|" + output, null);
        if (saved == null) return 0;
        try {
            JSONObject entry = new JSONObject(saved);
            // A place within the first few seconds is as good as the beginning.
            int place = entry.optBoolean("completed") ? 0 : entry.optInt("positionMs");
            return place < 5000 ? 0 : place;
        } catch (org.json.JSONException unreadable) { return 0; }
    }

    private Uri mediaUri(JSONObject item) throws org.json.JSONException {
        return new Uri.Builder().scheme("content").authority(getPackageName() + ".media")
            .appendPath("stream").appendPath(item.getString("id"))
            .appendQueryParameter("size", String.valueOf(item.getLong("size")))
            .appendQueryParameter("name", item.optString("name"))
            .appendQueryParameter("mime", item.optString("mime", "application/octet-stream"))
            .build();
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
        } catch (Exception error) { say("VLC could not open this file: " + error.getMessage()); }
    }

    private void playPi(JSONObject item) {
        playPi(item, 0);
    }

    private void playPi(JSONObject item, int resumeMs) {
        final long intent = ++outputIntent;
        stopPhoneForNewPlayback();
        target = "pi";
        selected = item;
        // The Loop setting is a wish for every new playback; the Pi takes it once the file is playing.
        loopWhenPlaying = getSharedPreferences("player_controls", MODE_PRIVATE).getBoolean("loop", false);
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
        }, result -> { nowPlaying.setText(displayMediaName(item.optString("name")));
            showFullPlayer();
            say("It starts muted. Use Volume for sound."); });
    }

    private void playPhone(JSONObject item, int resumeMs) {
        final long intent = ++outputIntent;
        target = "phone";
        selected = item;
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
        phoneProblem = null;
        phoneTitle = item.optString("name");
        phoneWantsVideo = !audioOnly;
        exitVideoMode();
        showFullPlayer();
        updateFullPlayer("This phone", "loading", phoneTitle, 0, 0, 0, 1);
        nowPlaying.setText(displayMediaName(item.optString("name")));
        output.setText(externalDisplayText());
        PhonePlaybackService.ensure(this);
        ui.postDelayed(new Runnable() {
            int attempts;
            @Override public void run() {
                if (intent != outputIntent) return;
                PhonePlaybackService playback = PhonePlaybackService.current;
                if (playback == null) {
                    if (++attempts < 20) ui.postDelayed(this, 100);
                    else say("Playback on this phone did not start. Try again.");
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
        if ("vlc".equals(target)) { say("Use VLC controls for this playback"); return; }
        Kit.tick(findViewById(R.id.media_full_player));
        if (action.equals("stop")) {
            // A Rotate or Loop tap still waiting to apply must not reach a stopped player.
            ui.removeCallbacks(commitRotation);
            ui.removeCallbacks(commitLoop);
            pendingRotation = -1;
            pendingLoop = null;
            loopWhenPlaying = false;
            ++outputIntent;
            stopPhoneForNewPlayback();
        }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (action.equals("stop") || playback == null) return;
            playback.control(action);
            return;
        }
        if (action.equals("pause") || action.equals("stop")) {
            request(() -> client.post("/v1/player/pi/immediate", new JSONObject().put("action", action)),
                result -> { });
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", action)
                .put("expectedRevision", state.getInt("revision")));
        }, this::sayIfRefused);
    }

    /** A command the Pi takes shows in the player by itself; only one it turns down needs saying. */
    private void sayIfRefused(JSONObject result) {
        if (result.has("status") && !"applied".equals(result.optString("status")))
            say("The Pi did not take that, so nothing changed.");
    }

    private void mute() {
        if ("vlc".equals(target)) { say("Use VLC to mute this playback"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) return;
            playback.setting("volume", 0);
            return;
        }
        request(() -> client.post("/v1/player/pi/immediate", new JSONObject().put("action", "mute")),
            result -> say("Muted on the Pi screen"));
    }

    private void controlSeek(int positionMs) {
        if ("vlc".equals(target)) { say("Use VLC to seek this file"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) return;
            playback.seek(positionMs);
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", "seek")
                .put("positionMs", positionMs).put("expectedRevision", state.getInt("revision")));
        }, this::sayIfRefused);
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
    private boolean loopWhenPlaying;
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
                say("The Pi did not take that, so nothing changed.");
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
            rotate.setText(ready ? "Rotate · " + shownRotation + "°" : "Rotate");
        if (pendingLoop == null)
            loop.setText(ready ? "Loop · " + (shownLoop ? "On" : "Off") : "Loop");
        rotate.setEnabled(ready);
        loop.setEnabled(ready);
        rotate.setAlpha(ready ? 1f : 0.45f);
        loop.setAlpha(ready ? 1f : 0.45f);
    }

    private void changeSetting(String action, double value) {
        if ("vlc".equals(target)) { say("Use VLC playback settings"); return; }
        if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback == null) return;
            playback.setting(action, value);
            return;
        }
        if (action.equals("volume")) {
            request(() -> client.post("/v1/player/pi/immediate", new JSONObject()
                    .put("action", "volume").put("value", value)),
                result -> { });
            return;
        }
        request(() -> {
            JSONObject state = client.get("/v1/player/pi");
            return client.post("/v1/player/pi/commands", new JSONObject().put("action", action)
                .put("value", value).put("expectedRevision", state.getInt("revision")));
        }, this::sayIfRefused);
    }

    private void enterVideoMode() {
        fullPlayer = false;
        findViewById(R.id.media_full_player).setVisibility(View.GONE);
        videoMode = true;
        renderTop();
        for (int id : new int[]{R.id.media_search_line, R.id.media_list})
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
        findViewById(R.id.media_list).setVisibility(View.VISIBLE);
        selectTab(activeTab);
        video.setVisibility(View.GONE);
        video.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // Full screen is a mode of the player page, so Back returns to that page, not past it. With
    // nothing above the list, the callback is off and the system's back preview leaves the page.
    private final androidx.activity.OnBackPressedCallback backInApp = new androidx.activity.OnBackPressedCallback(false) {
        @Override public void handleOnBackProgressed(androidx.activity.BackEventCompat event) {
            if (!videoMode) Kit.peekBack(findViewById(R.id.media_full_player), event.getProgress());
        }
        @Override public void handleOnBackCancelled() { Kit.peekBack(findViewById(R.id.media_full_player), 0f); }
        @Override public void handleOnBackPressed() {
            Kit.peekBack(findViewById(R.id.media_full_player), 0f);
            if (videoMode) { exitVideoMode(); showFullPlayer(); }
            else if (fullPlayer) closeFullPlayer();
        }
    };



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
            keepPlaces(remote);
            showHistory(remote, false);
            syncPendingProgress();
        });
    }

    /** Keep the Pi's record of where each thing was left on this phone, where the newer of the two copies wins. */
    private void keepPlaces(JSONArray remote) throws org.json.JSONException {
        SharedPreferences cache = getSharedPreferences("media_history", MODE_PRIVATE);
        SharedPreferences.Editor editor = cache.edit();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject entry = remote.getJSONObject(i);
            String key = entry.optString("itemId") + "|" + entry.optString("target");
            JSONObject cached = new JSONObject(cache.getString(key, "{}"));
            if (entry.optLong("generation") > cached.optLong("generation") ||
                    (entry.optLong("generation") == cached.optLong("generation") &&
                     entry.optLong("sequence") >= cached.optLong("sequence")))
                editor.putString(key, entry.toString());
        }
        editor.apply();
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
            Kit.empty(rows, Kit.Icon.HISTORY, "Nothing played yet", "Your place in everything you play is kept here.",
                Kit.button(this, Kit.Icon.FILES, "Browse Files", R.color.text, () -> openTab(FILES)));
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
        String clock = String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
        String progress = canResume ? "stopped at " + clock : entry.optBoolean("completed") ? "finished" : "not started";
        Kit.bindRow(row, favorites().contains(name) ? R.drawable.csi_favorite : Kit.Icon.HISTORY, name,
            destination + " · " + progress, null, true);
        String from = source;
        row.setOnClickListener(v -> {
            JSONObject item = new JSONObject();
            try { item.put("id", entry.getString("itemId")); item.put("name", entry.optString("name", "Saved media"));
                item.put("mime", entry.optString("mime")); }
            catch (Exception error) { say("This item can no longer be played."); return; }
            boolean onPhone = "phone".equals(entry.optString("target", "pi"));
            int resumeAt = entry.optBoolean("completed") ? 0 : entry.optInt("positionMs");
            // Each choice names where it plays, so resuming never lands on an output nobody chose.
            String here = onPhone ? "this phone" : "Pi screen", other = onPhone ? "Pi screen" : "this phone";
            int hereIcon = onPhone ? Kit.Icon.DEVICE : Kit.Icon.DISPLAY, otherIcon = onPhone ? Kit.Icon.DISPLAY : Kit.Icon.DEVICE;
            java.util.List<Kit.Action> choices = new java.util.ArrayList<>();
            if (canResume) choices.add(new Kit.Action(R.drawable.csi_play, "Resume on " + here, "From " + clock,
                () -> { if (onPhone) playPhone(item, resumeAt); else playPi(item, resumeAt); }));
            choices.add(new Kit.Action(R.drawable.csi_rewind, (canResume ? "Start over on " : "Play on ") + here, null,
                () -> { if (onPhone) playPhone(item, 0); else playPi(item, 0); }));
            choices.add(new Kit.Action(otherIcon, (canResume ? "Resume on " : "Play on ") + other, canResume ? "From " + clock : null,
                () -> { if (onPhone) playPi(item, resumeAt); else playPhone(item, resumeAt); }));
            Kit.sheet(this, name, from + " · " + progress, choices.toArray(new Kit.Action[0]));
        });
    }

    private void connections() {
        long listing = ++listingIntent;
        String host = Prefs.assistIp(this);
        clearRows("");
        // The Pi shares each drive under its own name, which is not the name the drive is shown by.
        accessSection("Open the drives from a computer");
        LinearLayout primary = accessGroup();
        accessAddress(primary, "Pi USB over SMB", "smb://" + host + "/sandisk");
        accessAddress(primary, "Pi USB over FTP", "ftp://" + host + "/Media");
        accessAddress(primary, "Elements over SMB", "smb://" + host + "/seagate-elements");
        accessAddress(primary, "Elements over FTP", "ftp://" + host + "/Elements");
        accessSection("Drives");
        LinearLayout sources = accessGroup();
        View checking = Kit.addRow(sources);
        Kit.bindRow(checking, Kit.Icon.FILES, "Pi drives", null, null, false);
        Kit.rowStatus(checking, Kit.Status.WARN, "Checking");
        checking.setClickable(false);
        accessSection("Other folders the Pi shares");
        LinearLayout extra = accessGroup();
        accessAddress(extra, "Media folder over SMB", "smb://" + host + "/media");
        accessAddress(extra, "Files folder over SMB", "smb://" + host + "/files");
        accessAddress(extra, "The whole Pi over SFTP", "sftp://" + host);
        listingRequest(listing, () -> {
            try { return client.get("/v1/drives"); }
            catch (Exception error) { return new JSONObject().put("error", friendlyError(error)); }
        }, result -> {
            sources.removeAllViews();
            JSONArray drives = result.optJSONArray("drives");
            if (drives == null || drives.length() == 0) {
                View row = Kit.addRow(sources);
                Kit.bindRow(row, Kit.Icon.FILES, "The Pi's drives are not known",
                    result.optString("error", "The Pi did not list any drive."), null, false);
                Kit.rowAction(row, R.drawable.csi_refresh, "Check the drives again", v -> connections());
                row.setOnClickListener(v -> connections());
                return;
            }
            for (int i = 0; i < drives.length(); i++) {
                JSONObject drive = drives.optJSONObject(i);
                if (drive == null) continue;
                String label = drive.optString("label", drive.optString("id", "Drive"));
                boolean online = drive.optBoolean("online");
                View row = Kit.addRow(sources);
                Kit.bindRow(row, Kit.Icon.FILES, label, null, null, online);
                Kit.rowStatus(row, online ? Kit.Status.GOOD : Kit.Status.IDLE, online ? "Connected" : "Disconnected");
                if (!online) { row.setClickable(false); continue; }
                row.setOnClickListener(v -> {
                    driveId = drive.optString("id");
                    driveLabel = label;
                    path = "";
                    selectTab(FILES);
                    browse();
                });
            }
        });
    }

    private void accessSection(String title) {
        Kit.label(rows, title);
    }

    private LinearLayout accessGroup() {
        return Kit.group(rows);
    }

    /** An address to open a drive from a computer. Tapping the row or its button copies it. */
    private void accessAddress(LinearLayout group, String title, String address) {
        View row = Kit.addRow(group);
        Kit.bindRow(row, Kit.Icon.ACCESS, title, address, null, false);
        Kit.rowAction(row, R.drawable.csi_copy, "Copy the address of " + title, v -> copy(address));
        row.setOnClickListener(v -> copy(address));
    }

    private void copy(String words) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("csync", words));
        // Android 13 and later show their own note when something is copied, so a second one is left out there.
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Copied");
    }

    private void refreshState() {
        if (!screenActive) return;
        if ("vlc".equals(target)) {
            // VLC owns this session; its player state is not reported to the Pi.
        } else if ("phone".equals(target)) {
            PhonePlaybackService playback = PhonePlaybackService.current;
            if (playback != null && playback.hasPlayer() && !seeking) {
                findViewById(R.id.media_player_controls).setVisibility(fullPlayer ? View.GONE : View.VISIBLE);
                for (int id : new int[]{R.id.media_stop, R.id.media_mute, R.id.media_settings})
                    findViewById(id).setVisibility(View.VISIBLE);
                String phoneState = playback.playbackState();
                findViewById(R.id.media_pause).setVisibility(phoneState.equals("playing") ? View.VISIBLE : View.GONE);
                findViewById(R.id.media_resume).setVisibility(phoneState.equals("paused") ? View.VISIBLE : View.GONE);
                output.setText(stateWords("This phone", phoneState, null));
                seek.setMax(Math.max(1, playback.duration()));
                seek.setProgress(playback.position());
                JSONObject item = playback.item();
                updateFullPlayer("This phone", phoneState,
                    item == null ? "Phone media" : item.optString("name", "Phone media"),
                    playback.position(), playback.duration(), playback.volume(), playback.speed());
                showPiDisplayState(new JSONObject());
            } else {
                findViewById(R.id.media_player_controls).setVisibility(View.GONE);
                if (phoneProblem == null) updateFullPlayer("This phone", "stopped", "", 0, 0, 0, 1);
                else updateFullPlayer("This phone", "failed", phoneTitle, 0, 0, 0, 1,
                    phoneProblem + " Try VLC on this phone, or the Pi screen.");
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
                            playerState.equals("loading") || playerState.equals("buffering") ||
                            playerState.equals("showing");
                        // On Media the Pi screen's row stays when it is idle: it is the way in to that page.
                        // The row is the way in to the Pi screen's page, so it stays while idle and leaves on that page.
                        findViewById(R.id.media_player_controls).setVisibility(fullPlayer || videoMode ? View.GONE : View.VISIBLE);
                        boolean plays = active && !playerState.equals("showing");
                        findViewById(R.id.media_pause).setVisibility(playerState.equals("playing") ? View.VISIBLE : View.GONE);
                        findViewById(R.id.media_resume).setVisibility(playerState.equals("paused") ? View.VISIBLE : View.GONE);
                        findViewById(R.id.media_stop).setVisibility(active ? View.VISIBLE : View.GONE);
                        // Sound and speed belong to something that plays, not to a picture or words held on the screen.
                        findViewById(R.id.media_mute).setVisibility(plays ? View.VISIBLE : View.GONE);
                        findViewById(R.id.media_settings).setVisibility(plays ? View.VISIBLE : View.GONE);
                        String problem = state.optString("error").isEmpty() ? null : state.optString("error");
                        nowPlaying.setText(active ? displayMediaName(state.optString("name", "Pi media")) : "Pi screen");
                        output.setText(plays ? stateWords("Pi screen", playerState, problem) + " · volume " + state.optInt("volume", 0) + "%"
                            : stateWords("Pi screen", playerState, problem));
                        shownPages = "document".equals(state.optString("kind")) && state.optInt("count") > 0
                            ? new int[]{state.optInt("page", 1), state.optInt("count")} : null;
                        updateFullPlayer("Pi screen", playerState, state.optString("name", ""),
                            state.optInt("positionMs"), state.optInt("durationMs"),
                            state.optInt("volume", 0), state.optDouble("speed", 1), problem);
                        showPiDisplayState(state);
                        if (loopWhenPlaying && playerState.equals("playing")) {
                            loopWhenPlaying = false;
                            if (!state.optBoolean("loop")) changePiDisplay("loop", true);
                        }
                        if (!seeking) {
                            seek.setMax(Math.max(1, state.optInt("durationMs", 1)));
                            seek.setProgress(state.optInt("positionMs"));
                        }
                    });
                } catch (Exception error) {
                    ui.post(() -> {
                        if (!screenActive || !"pi".equals(target)) return;
                        nowPlaying.setText("The Pi cannot be reached");
                        output.setText("Check the Pi before using the controls");
                        updateFullPlayer("Pi screen", "offline", "", 0, 0, 0, 1);
                        showPiDisplayState(new JSONObject());
                        ((TextView) findViewById(R.id.player_feedback)).setText(
                            "The Pi stopped answering, so what it is playing is not known. Check it before using the controls.");
                    });
                } finally { stateInFlight.set(false); }
            }, "pi-player-state").start();
        }
        ui.postDelayed(this::refreshState, 2000);
    }





    @Override protected void onStart() {
        super.onStart();
        // Settings may have changed the skip length while this page was away.
        skipSeconds = getSharedPreferences("player_controls", MODE_PRIVATE).getInt("skip_seconds", 10);
        renderSkip();
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
