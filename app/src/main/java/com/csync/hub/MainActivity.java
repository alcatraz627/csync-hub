package com.csync.hub;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** The csync hub: Home, Share, Chat, and More pages plus the Media activity. */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(Appearance.wrap(base));
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View pageHome, pageShare, pageChat, pageTools, pageSettings, pageCamera, pageMore;
    private CameraController cameraController;
    private MediaMiniPlayer miniPlayer;
    private int current = 0;
    private int moreDetail;
    private int settingsDetail;
    private int toolsDetail;
    private boolean resumed;

    // system page (Shizuku top)
    private static final int SHIZUKU_REQ = 1001;
    private TextView sysStatus, sysOutput;
    private boolean sysLooping;

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                public void onRequestPermissionResult(int code, int grant) {
                    if (grant == PackageManager.PERMISSION_GRANTED) startSysLoop();
                    else sysStatus.setText("Shizuku permission denied. Open Shizuku, allow this app, then reopen.");
                }
            };
    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                public void onBinderReceived() { if (current == 3 && toolsDetail == 1) ensureShizuku(); }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Appearance.apply(this);
        if (Build.VERSION.SDK_INT >= 31) {
            // Let the launch animation leave by growing and fading, so Home arrives under it instead of cutting in.
            // Home is usually ready before the mesh has finished drawing, so wait out the rest of it (under a second).
            getSplashScreen().setOnExitAnimationListener(splash -> {
                long left = 0;
                if (splash.getIconAnimationStart() != null && splash.getIconAnimationDuration() != null) {
                    long ran = java.time.Duration.between(splash.getIconAnimationStart(), java.time.Instant.now()).toMillis();
                    // The phone's animation speed setting stretches or removes the drawing, so the wait follows it.
                    float speed = Build.VERSION.SDK_INT >= 33 ? android.animation.ValueAnimator.getDurationScale() : 1f;
                    long whole = (long) (splash.getIconAnimationDuration().toMillis() * speed);
                    left = Math.max(0, Math.min(5000, whole - ran));
                }
                splash.animate().alpha(0f).scaleX(1.18f).scaleY(1.18f).setStartDelay(left).setDuration(260)
                    .withEndAction(splash::remove).start();
            });
        }
        setContentView(R.layout.activity_main);
        Appearance.applySystemBars(this);
        FrameLayout content = findViewById(R.id.content);
        LayoutInflater inf = LayoutInflater.from(this);
        pageHome = inf.inflate(R.layout.page_home_v2, content, false);
        pageShare = inf.inflate(R.layout.page_share_v2, content, false);
        pageChat = inf.inflate(R.layout.page_chat, content, false);
        pageTools = inf.inflate(R.layout.page_tools_v2, content, false);
        pageSettings = inf.inflate(R.layout.page_settings, content, false);
        pageCamera = inf.inflate(R.layout.page_camera_v2, content, false);
        pageMore = inf.inflate(R.layout.page_more_v2, content, false);
        content.addView(pageHome);
        content.addView(pageShare);
        content.addView(pageChat);
        content.addView(pageTools);
        content.addView(pageSettings);
        content.addView(pageCamera);
        content.addView(pageMore);
        cameraController = new CameraController(this, pageCamera);
        miniPlayer = new MediaMiniPlayer(this);

        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        nav.setBackgroundColor(col(R.color.surface));
        nav.setElevation(0f);
        // While the keyboard is up the bar steps aside, so typing gets the whole height above the keys.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            boolean typing = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            nav.setVisibility(typing ? View.GONE : View.VISIBLE);
            // In a conversation the large title steps aside too, unless it is the title being typed.
            if (pageChat != null && chatTitleEdit != null) {
                boolean renaming = chatTitleEdit.getVisibility() == View.VISIBLE;
                pageChat.findViewById(R.id.chat_heading).setVisibility(
                    typing && chatConvoMode && !renaming ? View.GONE : View.VISIBLE);
            }
            return androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets);
        });
        nav.setItemActiveIndicatorColor(android.content.res.ColorStateList.valueOf(
            androidx.core.graphics.ColorUtils.blendARGB(col(R.color.surface), accent(), 0.13f)));
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) show(0);
            else if (id == R.id.nav_media) { startActivity(new Intent(this, MediaActivity.class)); return false; }
            else if (id == R.id.nav_share) show(1);
            else if (id == R.id.nav_chat) show(2);
            else if (id == R.id.nav_more) show(6);
            return true;
        });

        setupHomePage();
        setupSystemPage();
        setupSharePage();
        setupChatPage();
        searchResultChat = b != null && b.getBoolean("search_result_chat");
        setupSettingsPage();
        setupConnectionCard();
        setupAssistantCard();

        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2002);
        }
        int[] navIds = {R.id.nav_home, R.id.nav_share, R.id.nav_chat, R.id.nav_more};
        int start = b != null ? b.getInt("tab", 0) : tabFromIntent(getIntent());
        int selected = start == 1 ? 1 : start == 2 ? 2 : start >= 3 ? 3 : 0;
        nav.setSelectedItemId(navIds[selected]);
        if (start >= 3 && start <= 5) show(start);
        if (b != null && start == 4 && b.getInt("settings_detail") != 0)
            revealSettingsDetail(b.getInt("settings_detail"));
        if (b != null && start == 3 && b.getInt("tools_detail") != 0)
            showToolsDetail(b.getInt("tools_detail"));
        if (b != null && start == 6 && b.getInt("more_detail") != 0)
            showMoreDetail(b.getInt("more_detail"));
        if (b != null && b.getBoolean("chat_convo")) {
            String id = b.getString("chat_session");
            org.json.JSONObject entry = id == null ? null : convEntry(id);
            if (id == null) newConversation();
            else openConversation(id, entry == null ? b.getString("chat_title", "New chat") :
                entry.optString("title"));
            chatInput.setText(b.getString("chat_draft", ""));
        }
        acceptSearchDestination(getIntent());
        takeShareAction(getIntent());
    }

    // ---- quick sends asked for from a Quick Settings tile ----

    private String pendingShareAction;

    private void takeShareAction(Intent intent) {
        if (intent == null || intent.getStringExtra("share_action") == null) return;
        pendingShareAction = intent.getStringExtra("share_action");
        intent.removeExtra("share_action");
        if (hasWindowFocus()) runShareAction();
    }

    /** Android hands over the clipboard only once this window has focus, so the send waits for that. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingShareAction != null) runShareAction();
    }

    private void runShareAction() {
        String action = pendingShareAction;
        pendingShareAction = null;
        if ("clipboard".equals(action)) sendToSelected(clipboardText(), false);
        else if ("photo".equals(action)) sendLastPhoto();
    }

    private static String photoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? "android.permission.READ_MEDIA_IMAGES"
            : "android.permission.READ_EXTERNAL_STORAGE";
    }

    /** Attach the newest photo on this phone and send it to the chosen device. Asks to see photos the first time. */
    private void sendLastPhoto() {
        if (checkSelfPermission(photoPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{photoPermission()}, 2003);
            return;
        }
        android.net.Uri photos = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        try (android.database.Cursor newest = getContentResolver().query(photos,
                new String[]{android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DISPLAY_NAME},
                null, null, android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (newest == null || !newest.moveToFirst()) { toast("There are no photos on this phone"); return; }
            shareFileUri = android.content.ContentUris.withAppendedId(photos, newest.getLong(0));
            shareFileName = newest.getString(1) == null ? "Photo" : newest.getString(1);
        } catch (Exception error) {
            toast("The newest photo could not be read");
            return;
        }
        bindShareFile();
        sendSelectedFile();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 2003) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) sendLastPhoto();
        else toast("csync needs to see your photos to send the newest one");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = tabFromIntent(intent);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        nav.setSelectedItemId(tab == 1 ? R.id.nav_share : tab == 2 ? R.id.nav_chat :
            tab >= 3 ? R.id.nav_more : R.id.nav_home);
        show(tab);
        acceptChatDraft(intent);
        acceptSearchDestination(intent);
        takeShareAction(intent);
    }

    private int tabFromIntent(Intent intent) {
        if (intent == null) return 0;
        String destination = intent.getStringExtra("destination");
        if (destination == null) return 0;
        switch (destination) {
            case "share": return 1;
            case "chat": case "chat-new": return 2;
            case "tools": return 3;
            case "camera": return 5;
            case "more": return 6;
            default: return 0;
        }
    }

    // Keep the open surface across a theme or accent change, which recreates the
    // Activity; without this the app would snap back to Home on every toggle.
    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("tab", current);
        b.putInt("settings_detail", settingsDetail);
        b.putInt("tools_detail", toolsDetail);
        b.putInt("more_detail", moreDetail);
        b.putBoolean("search_result_chat", searchResultChat);
        if (chatConvoMode && chatInput != null) {
            b.putBoolean("chat_convo", true);
            b.putString("chat_draft", chatInput.getText().toString());
            b.putString("chat_session", chatSession);
            b.putString("chat_title", chatTitle.getText().toString());
        }
    }

    private void show(int page) {
        if (current == 5 && page != 5) cameraController.hide();
        current = page;
        pageHome.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageShare.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageChat.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(page == 4 ? View.VISIBLE : View.GONE);
        pageCamera.setVisibility(page == 5 ? View.VISIBLE : View.GONE);
        pageMore.setVisibility(page == 6 ? View.VISIBLE : View.GONE);
        if (page == 6) showMoreDetail(0);
        if (page == 0) refreshHome();
        if (page == 1) refreshShare();
        if (page == 2) { warmChat(); if (!chatConvoMode) { renderHistoryList(); refreshAgentStatus(); } }
        if (page == 3) {
            showToolsDetail(0);
            refreshToolsHealth();
            AppUpdater.refreshStatus(this, pageTools.findViewById(R.id.tools_update_status));
        }
        if (page == 4) { closeSettingsDetail(); refreshConnection(); refreshAssistant(); }
        if (page == 5 && resumed) cameraController.show();
    }

    // Wake the tailnet path to the assistant so the first message is not the cold
    // start that would otherwise time out.
    private void warmChat() {
        final String ip = Prefs.assistIp(this);
        if (ip.isEmpty()) return;
        new Thread(() -> MeshClient.warmUp(ip, MeshClient.ASSIST_PORT)).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        acceptChatDraft(getIntent());
        ChatService.uiForeground = true;
        android.content.IntentFilter f = new android.content.IntentFilter(ChatService.ACTION_REPLY);
        androidx.core.content.ContextCompat.registerReceiver(this, chatReceiver, f,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        // A reply that arrived while we were away is waiting in the service stash.
        if (ChatService.stashedTurns != null || ChatService.stashedError != null) {
            String ss = ChatService.stashedSession;
            ChatService.stashedTurns = null; ChatService.stashedError = null; ChatService.stashedSession = null;
            if (chatConvoMode && ss != null && ss.equals(chatSession)) {
                renderTranscript(ChatStore.transcript(this, chatSession)); scrollDown();
            } else if (!chatConvoMode) {
                renderHistoryList();
            }
        }
        // A reply that ended while the app was away could not hand over to the messages waiting behind it.
        for (String waiting : new java.util.ArrayList<>(chatQueue.keySet())) nextQueued(waiting);
        if (chatConvoMode && chatSession != null) {
            if (!ChatService.running.contains(chatSession)) clearWriting();
            updateSendButton();
        }
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(7002);
        if (current == 3 && toolsDetail == 1) ensureShizuku();
        if (current == 3) AppUpdater.refreshStatus(this,
            pageTools.findViewById(R.id.tools_update_status));
        if (current == 5) cameraController.show();
        miniPlayer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        cameraController.hide();
        miniPlayer.stop();
        ChatService.uiForeground = false;
        try { unregisterReceiver(chatReceiver); } catch (Throwable ignore) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
    }

    // ---------------- home page (status + capabilities) ----------------

    private LinearLayout homeCaps;

    private void setupHomePage() {
        homeCaps = pageMore.findViewById(R.id.home_caps);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        View top = pageHome.findViewById(R.id.home_top);
        Kit.pageTop(top, "home", this::openPlace);
        Kit.topAction(top, Kit.Icon.SEARCH, "Search", v -> openHomeSearch());
        Kit.bindSection(pageHome.findViewById(R.id.home_pickup_head), 0, "Pick up",
            pageHome.findViewById(R.id.home_pickup_content));
        Kit.bindSection(pageHome.findViewById(R.id.home_cap_head), 0, "Capabilities",
            pageHome.findViewById(R.id.home_cap_grid));
        Kit.bindSection(pageHome.findViewById(R.id.home_devices_head), 0, "Send to a device",
            pageHome.findViewById(R.id.home_devices));
        // Home is about what to do next, so the device list starts folded.
        pageHome.findViewById(R.id.home_devices_head).performClick();
        pageHome.findViewById(R.id.home_share).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_share));
        pageHome.findViewById(R.id.home_chat).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_chat));
        pageHome.findViewById(R.id.home_cap_media).setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
        pageHome.findViewById(R.id.home_camera).setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(5); });
        pageHome.findViewById(R.id.home_notes).setOnClickListener(v ->
            startActivity(new Intent(this, NotesActivity.class)));
        pageHome.findViewById(R.id.home_display).setOnClickListener(v ->
            startActivity(new Intent(this, MediaActivity.class).putExtra("player_target", "pi")));
        pageHome.findViewById(R.id.home_tools).setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(3); });
        pageHome.findViewById(R.id.home_recent).setOnClickListener(v -> {
            JSONArray history = ChatStore.index(this);
            JSONObject recent = history.optJSONObject(0);
            if (recent != null) { nav.setSelectedItemId(R.id.nav_chat); openConversation(recent.optString("id"), recent.optString("title")); }
            else { nav.setSelectedItemId(R.id.nav_chat); newConversation(); }
        });
        bindHomeStatus(null, false, null);
        if (getResources().getConfiguration().screenWidthDp < 320 ||
                getResources().getConfiguration().fontScale > 1.2f) {
            LinearLayout grid = pageHome.findViewById(R.id.home_cap_grid);
            for (int rowIndex = 0; rowIndex < grid.getChildCount(); rowIndex++) {
                LinearLayout row = (LinearLayout) grid.getChildAt(rowIndex);
                row.setOrientation(LinearLayout.VERTICAL);
                for (int column = 0; column < row.getChildCount(); column++) {
                    View card = row.getChildAt(column);
                    if (!(card instanceof LinearLayout)) { card.setVisibility(View.GONE); continue; }
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    params.setMargins(dp(4), dp(4), dp(4), dp(4));
                    card.setLayoutParams(params);
                }
            }
        }
        pageMore.findViewById(R.id.more_camera).setOnClickListener(v -> show(5));
        pageMore.findViewById(R.id.more_tools).setOnClickListener(v -> show(3));
        pageMore.findViewById(R.id.more_notes).setOnClickListener(v ->
            startActivity(new Intent(this, NotesActivity.class)));
        pageMore.findViewById(R.id.more_settings).setOnClickListener(v -> show(4));
        pageMore.findViewById(R.id.more_about).setOnClickListener(v -> showMoreDetail(2));
        pageCamera.findViewById(R.id.camera_show_display).setOnClickListener(v ->
            startActivity(new Intent(this, MediaActivity.class).putExtra("player_target", "pi")));
        showMoreDetail(0);
        showToolsDetail(0);
        closeSettingsDetail();
        pageMore.findViewById(R.id.more_assistant_tools).setOnClickListener(v -> showMoreDetail(1));
        pageTools.findViewById(R.id.tools_media).setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
        pageTools.findViewById(R.id.tools_process_route).setOnClickListener(v -> showToolsDetail(1));
        pageTools.findViewById(R.id.tools_widgets_route).setOnClickListener(v -> showToolsDetail(2));
        pageTools.findViewById(R.id.tools_update).setOnClickListener(v ->
            AppUpdater.start(this, pageTools.findViewById(R.id.tools_update_status)));
        pageTools.findViewById(R.id.tools_media_health_row).setOnClickListener(v ->
            showToolsStatus("Pi media service", R.id.tools_media_status));
        pageTools.findViewById(R.id.tools_power_health_row).setOnClickListener(v ->
            showToolsStatus("Pi power", R.id.tools_power_status));
        // The app shows what exists. This row described a feature that is not built, so it stays out of sight.
        pageTools.findViewById(R.id.tools_performance_row).setVisibility(View.GONE);
    }

    /**
     * The Widgets page: every widget, tile, shortcut and share entry the app offers.
     * A widget or tile that is not on the phone yet is added from its row; Android asks before it places one.
     */
    private void renderWidgetsPage() {
        LinearLayout page = pageTools.findViewById(R.id.tools_widget_section);
        page.removeAllViews();
        android.appwidget.AppWidgetManager widgets = android.appwidget.AppWidgetManager.getInstance(this);

        Kit.label(page, "Launcher widgets");
        LinearLayout group = Kit.group(page);
        Object[][] launcher = {
            {Kit.Icon.DISPLAY, "Media remote", "What the Pi screen is playing, with Pause and Stop", HubWidgets.MediaRemote.class},
            {Kit.Icon.TOOLS, "Pi status", "Whether the Pi is online, its power and its drives", HubWidgets.PiStatus.class},
            {Kit.Icon.CAMERA, "Camera glance", "The latest photo from the Pi camera", HubWidgets.CameraGlance.class},
            {Kit.Icon.CHAT, "Ask the Pi", "Opens a new conversation", HubWidgets.Ask.class},
            {Kit.Icon.PHOTO, "xkcd", "A comic, changed every hour", XkcdWidgetProvider.class}};
        for (Object[] w : launcher) {
            android.content.ComponentName provider = new android.content.ComponentName(this, (Class<?>) w[3]);
            boolean added = widgets.getAppWidgetIds(provider).length > 0;
            View row = Kit.addRow(group);
            Kit.bindRow(row, (Integer) w[0], (String) w[1], (String) w[2], null, false);
            Kit.rowStatus(row, added ? Kit.Status.GOOD : Kit.Status.IDLE, added ? "Added" : "Not added");
            row.setOnClickListener(v -> {
                if (widgets.isRequestPinAppWidgetSupported()) widgets.requestPinAppWidget(provider, null, null);
                else Kit.sheet(this, (String) w[1], "Press and hold an empty part of the home screen, choose Widgets, then csync.");
            });
        }

        Kit.label(page, "Quick Settings tiles");
        group = Kit.group(page);
        Object[][] tiles = {
            {Kit.Icon.DISPLAY, "Pi screen", "Stops what is playing, or opens Media", HubTiles.Screen.class, R.drawable.ti_screen},
            {Kit.Icon.CAMERA, "Pi camera", "Opens the live picture", HubTiles.Camera.class, R.drawable.ti_camera},
            {R.drawable.csi_clipboard, "Clipboard", "Sends the clipboard to your last device", HubTiles.Clipboard.class, R.drawable.ti_clipboard},
            {Kit.Icon.PHOTO, "Last photo", "Sends your newest photo to your last device", HubTiles.Photo.class, R.drawable.ti_photo}};
        for (Object[] t : tiles) {
            View row = Kit.addRow(group);
            Kit.bindRow(row, (Integer) t[0], (String) t[1], (String) t[2], null, false);
            row.setOnClickListener(v -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                        new android.content.ComponentName(this, (Class<?>) t[3]), (String) t[1],
                        android.graphics.drawable.Icon.createWithResource(this, (Integer) t[4]), getMainExecutor(), result -> { });
                } else {
                    Kit.sheet(this, (String) t[1], "Pull down Quick Settings, tap the pencil, and drag this tile into place.");
                }
            });
        }

        Kit.label(page, "App shortcuts");
        group = Kit.group(page);
        String[][] shortcuts = {{"New chat", "A new conversation with the Pi assistant"},
            {"Send to a device", "Opens Share with your last device chosen"}, {"Pi camera, live", "Opens the live picture"}};
        int[] shortcutIcons = {Kit.Icon.CHAT, Kit.Icon.SHARE, Kit.Icon.CAMERA};
        for (int i = 0; i < shortcuts.length; i++)
            Kit.bindRow(Kit.addRow(group), shortcutIcons[i], shortcuts[i][0], shortcuts[i][1], null, false);
        TextView how = new TextView(this);
        how.setText("Press and hold the csync icon on the home screen.");
        how.setTextColor(col(R.color.dim));
        how.setTextSize(12);
        how.setPadding(dp(4), dp(8), 0, 0);
        page.addView(how);

        Kit.label(page, "In the share menu of other apps");
        group = Kit.group(page);
        Kit.bindRow(Kit.addRow(group), Kit.Icon.SHARE, "csync", "Asks where the item goes", null, false);
    }

    private void showToolsStatus(String title, int statusId) {
        String status = ((TextView) pageTools.findViewById(statusId)).getText().toString();
        Kit.sheet(this, title, status);
    }

    private void showMoreDetail(int detail) {
        moreDetail = detail;
        pageMore.findViewById(R.id.more_overview).setVisibility(detail == 0 ? View.VISIBLE : View.GONE);
        pageMore.findViewById(R.id.more_guide).setVisibility(detail == 1 ? View.VISIBLE : View.GONE);
        pageMore.findViewById(R.id.more_help).setVisibility(detail == 2 ? View.VISIBLE : View.GONE);
        Kit.pageTop(pageMore.findViewById(R.id.more_crumb), detail == 0 ? "more" : detail == 1 ? "guide" : "help", this::openPlace);
        if (detail == 2) {
            String version;
            try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception error) { version = "unknown"; }
            ((TextView) pageMore.findViewById(R.id.more_version)).setText("csync " + version);
        }
        if (detail == 1) refreshCapabilities();
    }

    private void showToolsDetail(int detail) {
        toolsDetail = detail;
        boolean overview = detail == 0;
        for (int id : new int[]{R.id.tools_health, R.id.tools_service_heading,
                R.id.tools_service_rows, R.id.tools_utility_heading, R.id.tools_utility_rows})
            pageTools.findViewById(id).setVisibility(overview ? View.VISIBLE : View.GONE);
        pageTools.findViewById(R.id.tools_process_section).setVisibility(detail == 1 ? View.VISIBLE : View.GONE);
        pageTools.findViewById(R.id.tools_widget_section).setVisibility(detail == 2 ? View.VISIBLE : View.GONE);
        pageTools.findViewById(R.id.tools_title).setVisibility(View.GONE);
        pageTools.findViewById(R.id.tools_subtitle).setVisibility(View.GONE);
        ((TextView) pageTools.findViewById(R.id.tools_title)).setText(detail == 1 ?
            "Observe before acting" : detail == 2 ? "Useful shortcuts" : "Tools");
        ((TextView) pageTools.findViewById(R.id.tools_subtitle)).setText(detail == 1 ?
            "Live phone samples need Shizuku." : detail == 2 ?
            "Widgets, tiles, shortcuts and the share menu." :
            "Pi service health and phone utilities.");
        View top = pageTools.findViewById(R.id.tools_back);
        Kit.pageTop(top, overview ? "tools" : detail == 1 ? "process" : "widgets", this::openPlace);
        if (overview) Kit.topAction(top, R.drawable.csi_refresh, "Check again", v -> refreshToolsHealth());
        ((android.widget.ScrollView) pageTools.findViewById(R.id.tools_scroll)).scrollTo(0, 0);
        if (detail == 1 && resumed) ensureShizuku();
        if (detail == 2) renderWidgetsPage();
    }

    /** Open the Connection page, the one child of Settings. Everything else in Settings folds in place. */
    private void revealSettingsDetail(int id) {
        if (id != R.id.settings_connection_detail) { closeSettingsDetail(); return; }
        settingsDetail = id;
        pageSettings.findViewById(R.id.settings_overview).setVisibility(View.GONE);
        pageSettings.findViewById(id).setVisibility(View.VISIBLE);
        Kit.pageTop(pageSettings.findViewById(R.id.settings_back), "connection", this::openPlace);
        refreshConnection();
        pageSettings.findViewById(R.id.settings_scroll).post(() ->
            ((android.widget.ScrollView) pageSettings.findViewById(R.id.settings_scroll))
                .scrollTo(0, 0));
    }

    private void closeSettingsDetail() {
        settingsDetail = 0;
        pageSettings.findViewById(R.id.settings_connection_detail).setVisibility(View.GONE);
        pageSettings.findViewById(R.id.settings_overview).setVisibility(View.VISIBLE);
        Kit.pageTop(pageSettings.findViewById(R.id.settings_back), "settings", this::openPlace);
    }

    /** Go to a place in the map. The top bar's path and Back both come through here. */
    private void openPlace(String id) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        switch (id) {
            case "home": nav.setSelectedItemId(R.id.nav_home); break;
            case "media": startActivity(new Intent(this, MediaActivity.class)); break;
            case "share":
                if (current != 1) nav.setSelectedItemId(R.id.nav_share);
                setShareMode(false);
                break;
            case "chat":
                if (chatConvoMode && searchResultChat) { finish(); break; }
                if (current != 2) nav.setSelectedItemId(R.id.nav_chat);
                showChatList();
                break;
            case "more":
                if (current == 6) showMoreDetail(0); else show(6);
                break;
            case "tools": show(3); break;
            case "settings": show(4); break;
            case "camera": show(5); break;
            default: break;
        }
    }

    private void refreshToolsHealth() {
        TextView headline = pageTools.findViewById(R.id.tools_health_headline);
        TextView detail = pageTools.findViewById(R.id.tools_health_detail);
        TextView mediaStatus = pageTools.findViewById(R.id.tools_media_status);
        TextView powerStatus = pageTools.findViewById(R.id.tools_power_status);
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) {
            headline.setText("Connect your Pi");
            detail.setText("Set the Pi address and mesh token in Settings");
            mediaStatus.setText("Connection not configured");
            powerStatus.setText("No Pi diagnostic sample");
            return;
        }
        headline.setText("Checking Pi");
        detail.setText("Reading service and power state");
        new Thread(() -> {
            boolean healthy = false;
            JSONObject power = null;
            try {
                MediaClient client = new MediaClient(host, token);
                healthy = client.get("/v1/health").optBoolean("ok");
                power = client.get("/v1/diagnostics").optJSONObject("power");
            } catch (Exception ignored) { }
            final boolean mediaReady = healthy;
            final JSONObject currentPower = power;
            ui.post(() -> {
                mediaStatus.setText(mediaReady ? "Authenticated media API reachable" : "Media API unavailable");
                if (currentPower == null) {
                    headline.setText(mediaReady ? "Pi media is reachable" : "Pi is unavailable");
                    detail.setText("Power diagnostics could not be read");
                    powerStatus.setText("No current power sample");
                    return;
                }
                boolean lowNow = currentPower.optBoolean("underVoltageNow");
                boolean lowEarlier = currentPower.optBoolean("underVoltageSinceBoot");
                headline.setText(lowNow ? "One issue to check" : "Pi services are ready");
                detail.setText(lowNow ? "Undervoltage is happening now; recheck power before playback." :
                    lowEarlier ? "Undervoltage was recorded since boot" : "No undervoltage reported");
                String fix = currentPower.isNull("fix") ? "" : currentPower.optString("fix", "").trim();
                String powerSummary = lowNow ? "Undervoltage now" :
                    lowEarlier ? "Past undervoltage" : "No undervoltage reported by Pi";
                powerStatus.setText(fix.isEmpty() ? powerSummary : powerSummary + " · " + fix);
                ((android.widget.ImageView) pageTools.findViewById(R.id.tools_power_icon))
                    .setColorFilter(col(lowNow || lowEarlier ? R.color.warn : R.color.online));
            });
        }, "csync-tools-health").start();
    }

    @Override public void onBackPressed() {
        if (current == 2 && chatConvoMode && chatSuggest.isOpen()) chatSuggest.close();
        else if (current == 2 && chatConvoMode && chatFind.isOpen()) chatFind.close();
        else if (current == 2 && chatConvoMode) openPlace("chat");
        else if (current == 1 && shareInboxMode) setShareMode(false);
        else if (current == 6 && moreDetail != 0) showMoreDetail(0);
        else if (current == 4 && settingsDetail != 0) closeSettingsDetail();
        else if (current == 3 && toolsDetail != 0) showToolsDetail(0);
        else if (current == 5) {
            if (!cameraController.closeChildPage()) show(6);
        }
        else if (current == 3 || current == 4) show(6);
        else if (current != 0) openPlace("home");
        else super.onBackPressed();
    }

    // Probe reachability and pull the assistant's tool list, off the UI thread.
    // A slow "no" reads the same as a fast one, so the dots use the short probe.
    private void refreshHome() {
        final String home = Prefs.homeIp(this), assist = Prefs.assistIp(this), token = Prefs.token(this);
        JSONArray history = ChatStore.index(this);
        JSONObject recent = history.optJSONObject(0);
        if (recent == null) Kit.bindRow(pageHome.findViewById(R.id.home_recent), Kit.Icon.CHAT,
            "Start a conversation", "Ask the Pi assistant", null, true);
        else Kit.bindRow(pageHome.findViewById(R.id.home_recent), Kit.Icon.CHAT,
            recent.optString("title", "Chat"), "Conversation · " + relTime(recent.optLong("updated")), null, true);
        new Thread(() -> {
            final boolean mac = !home.isEmpty() && MeshClient.reachable(home, MeshClient.PORT);
            final boolean pi = !assist.isEmpty() && MeshClient.reachable(assist, MeshClient.ASSIST_PORT);
            boolean mediaReady = false;
            if (!assist.isEmpty() && !token.isEmpty()) {
                try { mediaReady = new MediaClient(assist, token).get("/v1/health").optBoolean("ok"); }
                catch (Exception ignored) { }
            }
            final boolean media = mediaReady;
            ui.post(() -> bindHomeStatus(pi, media, mac));
        }).start();
        refreshCapabilities();
    }

    /**
     * Fill Home's lead line, capability cards and device rows. A null reading means it
     * is still being checked. The words are the status vocabulary of the app model.
     */
    private void bindHomeStatus(Boolean pi, boolean media, Boolean mac) {
        boolean checking = pi == null;
        boolean piUp = !checking && (pi || media);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        // Two rows that each name a thing and say how it is: the Pi, and this app with its version and any update.
        LinearLayout statusBox = pageHome.findViewById(R.id.home_status);
        statusBox.removeAllViews();
        LinearLayout statusGroup = Kit.group(statusBox);
        View hub = Kit.addRow(statusGroup);
        Kit.bindRow(hub, R.drawable.csi_tools, "Raspberry Pi", Prefs.assistIp(this), null, true);
        Kit.rowStatus(hub, checking ? Kit.Status.WARN : piUp ? Kit.Status.GOOD : Kit.Status.IDLE,
            checking ? "Checking" : piUp ? "Online" : "Offline");
        hub.setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(3); });
        View app = Kit.addRow(statusGroup);
        Kit.bindRow(app, R.drawable.csi_download, "csync " + appVersion(), "Checking for an update", null, true);
        app.setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(3); });
        AppUpdater.refreshStatus(this, app.findViewById(R.id.kit_sub));

        Kit.Status onPi = checking ? Kit.Status.WARN : media ? Kit.Status.GOOD : Kit.Status.IDLE;
        String piWords = checking ? "Checking" : media ? "Ready" : "Offline";
        Kit.bindArea(pageHome.findViewById(R.id.home_cap_media), Kit.Icon.MEDIA, "Media", onPi, piWords);
        Kit.bindArea(pageHome.findViewById(R.id.home_chat), Kit.Icon.CHAT, "Chat",
            checking ? Kit.Status.WARN : pi ? Kit.Status.GOOD : Kit.Status.IDLE,
            checking ? "Checking" : pi ? "Ready" : "Offline");
        Kit.bindArea(pageHome.findViewById(R.id.home_display), Kit.Icon.DISPLAY, "Pi screen", onPi, piWords);
        Kit.bindArea(pageHome.findViewById(R.id.home_camera), Kit.Icon.CAMERA, "Pi camera", onPi, piWords);
        Kit.bindArea(pageHome.findViewById(R.id.home_notes), Kit.Icon.NOTES, "Notes", onPi, piWords);
        Kit.bindArea(pageHome.findViewById(R.id.home_tools), Kit.Icon.TOOLS, "Tools", onPi, piWords);

        // Devices: every named peer this phone knows, online ones before offline. Tapping one picks it as the recipient.
        LinearLayout box = pageHome.findViewById(R.id.home_devices);
        box.removeAllViews();
        LinearLayout group = Kit.group(box);
        JSONArray roster = PeerStore.load(this);
        String self = Prefs.deviceName(this);
        int online = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; roster != null && i < roster.length(); i++) {
                JSONObject peer = roster.optJSONObject(i);
                if (peer == null || self.equals(peer.optString("name"))) continue;
                boolean up = peer.optBoolean("online");
                if (up != (pass == 0)) continue;
                if (up) online++;
                String name = peer.optString("name");
                View row = Kit.addRow(group);
                Kit.bindRow(row, Kit.Icon.DEVICE, name, null, null, true);
                Kit.rowStatus(row, up ? Kit.Status.GOOD : Kit.Status.IDLE, up ? "Online" : "Offline");
                row.setOnClickListener(v -> {
                    PeerStore.select(this, name);
                    nav.setSelectedItemId(R.id.nav_share);
                });
            }
        }
        Kit.bindArea(pageHome.findViewById(R.id.home_share), Kit.Icon.SHARE, "Share",
            online > 0 ? Kit.Status.GOOD : Kit.Status.IDLE,
            online == 0 ? "No device online" : online == 1 ? "1 device online" : online + " devices online");
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { return ""; }
    }

    private void refreshCapabilities() {
        final String assist = Prefs.assistIp(this), token = Prefs.token(this);
        if (assist.isEmpty() || token.isEmpty()) { renderCaps(null); return; }
        new Thread(() -> {
            JSONArray caps = null;
            try { caps = MeshClient.capabilities(assist, token); } catch (Throwable ignore) {}
            final JSONArray result = caps;
            ui.post(() -> renderCaps(result));
        }, "assistant-capabilities").start();
    }

    private void openHomeSearch() {
        startActivity(new Intent(this, SearchActivity.class));
    }

    private String inboxRevealPath;
    private boolean searchResultChat;

    private void acceptSearchDestination(Intent intent) {
        if (intent == null) return;
        String conversationId = intent.getStringExtra("conversation_id");
        if (conversationId != null) {
            intent.removeExtra("conversation_id");
            searchResultChat = true;
            JSONObject entry = convEntry(conversationId);
            if (entry == null) {
                showChatList();
                toast("This conversation is no longer saved");
            } else openConversation(conversationId, entry.optString("title"));
        }
        String inboxPath = intent.getStringExtra("inbox_file_path");
        if (inboxPath != null) {
            intent.removeExtra("inbox_file_path");
            try {
                java.io.File root = getExternalFilesDir("inbox");
                java.io.File file = new java.io.File(inboxPath).getCanonicalFile();
                String prefix = root == null ? "" : root.getCanonicalPath() + java.io.File.separator;
                if (prefix.isEmpty() || !file.getPath().startsWith(prefix) || !file.isFile())
                    throw new java.io.IOException("File is missing from the inbox");
                inboxRevealPath = file.getPath();
                setShareMode(true);
                renderInbox();
                shareStatus.setText("Received file: " + file.getName());
            } catch (java.io.IOException error) {
                inboxRevealPath = null;
                shareStatus.setText("This received file is no longer available");
            }
        }
        String recipient = intent.getStringExtra("recipient_name");
        if (recipient != null) {
            intent.removeExtra("recipient_name");
            boolean found = false;
            JSONArray roster = PeerStore.load(this);
            for (int i = 0; i < roster.length(); i++) {
                JSONObject peer = roster.optJSONObject(i);
                if (peer != null && recipient.equals(peer.optString("name"))) {
                    found = true;
                    break;
                }
            }
            setShareMode(false);
            if (found) {
                PeerStore.select(this, recipient);
                renderPeers(roster);
                refreshShareHeading();
                shareStatus.setText("Recipient selected: " + recipient);
            } else shareStatus.setText("This device is no longer in the saved roster. Scan devices.");
        }
    }

    private void setDot(View dot, boolean up) {
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                col(up ? R.color.online : R.color.offline)));
    }

    private void renderCaps(JSONArray caps) {
        homeCaps.removeAllViews();
        if (caps == null) { addCapRow("Assistant unreachable", ""); return; }
        if (caps.length() == 0) { addCapRow("No tools advertised", ""); return; }
        for (int i = 0; i < caps.length(); i++) {
            JSONObject t = caps.optJSONObject(i);
            if (t == null) continue;
            addCapRow(prettyName(t.optString("name")), t.optString("description"));
        }
    }

    private void addCapRow(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.card_bg);
        TextView b = new TextView(this);
        b.setText(title); b.setTextColor(col(R.color.text)); b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(b);
        if (desc != null && !desc.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(desc); s.setTextColor(col(R.color.dim)); s.setTextSize(13);
            LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(-1, -2);
            copy.topMargin = dp(4);
            row.addView(s, copy);
        }
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.bottomMargin = dp(8);
        homeCaps.addView(row, card);
    }

    // home_health -> "Home health"; list_devices -> "List devices".
    private String prettyName(String raw) {
        if (raw == null || raw.isEmpty()) return "(tool)";
        String s = raw.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------- settings page (appearance controls) ----------------

    private void setupSettingsPage() {
        LinearLayout group =
                pageSettings.findViewById(R.id.set_theme_group);
        styleAppearanceGroup(group);
        String mode = Prefs.themeMode(this);
        selectAppearance(group, "system".equals(mode) ? R.id.set_theme_system :
            "dark".equals(mode) ? R.id.set_theme_dark : R.id.set_theme_light);
        for (int i = 0; i < group.getChildCount(); i++) {
            View button = group.getChildAt(i);
            button.setOnClickListener(v -> {
                int checkedId = v.getId();
                selectAppearance(group, checkedId);
                String choice = checkedId == R.id.set_theme_system ? "system" :
                    checkedId == R.id.set_theme_dark ? "dark" : "light";
                if (!choice.equals(Prefs.themeMode(this))) {
                    Prefs.saveThemeMode(this, choice);
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode("system".equals(choice)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        : "dark".equals(choice) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
                }
            });
        }
        LinearLayout sizeGroup =
            pageSettings.findViewById(R.id.set_text_size_group);
        styleAppearanceGroup(sizeGroup);
        String size = Prefs.textSize(this);
        selectAppearance(sizeGroup, "sm".equals(size) ? R.id.set_text_sm :
            "lg".equals(size) ? R.id.set_text_lg : R.id.set_text_md);
        for (int i = 0; i < sizeGroup.getChildCount(); i++) {
            View button = sizeGroup.getChildAt(i);
            button.setOnClickListener(v -> {
                int checkedId = v.getId();
                selectAppearance(sizeGroup, checkedId);
                String choice = checkedId == R.id.set_text_sm ? "sm" :
                    checkedId == R.id.set_text_lg ? "lg" : "md";
                if (!choice.equals(Prefs.textSize(this))) {
                    Prefs.saveTextSize(this, choice);
                    recreate();
                }
            });
        }
        wireAccent(R.id.set_accent_coral, "coral", R.color.coral);
        wireAccent(R.id.set_accent_teal, "teal", R.color.teal);
        wireAccent(R.id.set_accent_violet, "violet", R.color.violet);
        wireAccent(R.id.set_accent_rust, "rust", R.color.rust);
        wireAccent(R.id.set_accent_blue, "blue", R.color.blue);
        wireAccent(R.id.set_accent_leaf, "leaf", R.color.leaf);
        wireAccent(R.id.set_accent_rose, "rose", R.color.rose);
        View custom = pageSettings.findViewById(R.id.set_accent_custom);
        paintCustomSwatch(custom, "custom".equals(Prefs.accent(this)));
        ((android.widget.ImageView) custom).setImageTintList(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        custom.setOnClickListener(v -> showCustomAccentPicker());
        renderIconChoices();
    }

    /** The row of launcher icons under Appearance. The chosen one carries a ring and its name in the accent. */
    private void renderIconChoices() {
        LinearLayout row = pageSettings.findViewById(R.id.set_icon_row);
        row.removeAllViews();
        String now = IconChoice.current(this);
        for (int i = 0; i < IconChoice.NAMES.length; i++) {
            final String name = IconChoice.NAMES[i];
            boolean picked = name.equals(now);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(6), dp(6), dp(6), dp(6));
            android.widget.ImageView art = new android.widget.ImageView(this);
            art.setImageResource(IconChoice.ART[i]);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setCornerRadius(dp(20));
            ring.setStroke(dp(2), picked ? accent() : android.graphics.Color.TRANSPARENT);
            art.setBackground(ring);
            art.setPadding(dp(5), dp(5), dp(5), dp(5));
            cell.addView(art, new LinearLayout.LayoutParams(dp(66), dp(66)));
            TextView label = new TextView(this);
            label.setText(name);
            label.setTextSize(12);
            label.setTextColor(picked ? accent() : col(R.color.dim));
            label.setGravity(android.view.Gravity.CENTER);
            label.setPadding(0, dp(4), 0, 0);
            cell.addView(label);
            cell.setContentDescription(name + " icon, " + IconChoice.ABOUT[i] + (picked ? ", chosen" : ""));
            cell.setOnClickListener(v -> {
                if (name.equals(IconChoice.current(this))) return;
                IconChoice.use(this, name);
                renderIconChoices();
                toast(name + " is now the app icon");
            });
            row.addView(cell);
        }
    }

    private void styleAppearanceGroup(LinearLayout group) {
        group.setBackground(bg(col(R.color.surface2), dp(13)));
        group.setPadding(dp(4), dp(4), dp(4), dp(4));
        group.setClipToPadding(false);
        int[][] states = {new int[]{android.R.attr.state_selected}, new int[]{}};
        android.content.res.ColorStateList fill = new android.content.res.ColorStateList(
            states, new int[]{col(R.color.surface), android.graphics.Color.TRANSPARENT});
        android.content.res.ColorStateList ink = new android.content.res.ColorStateList(
            states, new int[]{accent(), col(R.color.dim)});
        for (int i = 0; i < group.getChildCount(); i++) {
            com.google.android.material.button.MaterialButton button =
                (com.google.android.material.button.MaterialButton) group.getChildAt(i);
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setStrokeWidth(0);
            button.setCornerRadius(dp(10));
            button.setBackgroundTintList(fill);
            button.setTextColor(ink);
            button.setIconTint(ink);
            button.setIconSize(dp(15));
            button.setIconPadding(dp(4));
            button.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        }
    }

    private void selectAppearance(LinearLayout group, int selectedId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setSelected(group.getChildAt(i).getId() == selectedId);
        }
    }

    private void wireAccent(int viewId, String name, int colorRes) {
        View sw = pageSettings.findViewById(viewId);
        paintAccentSwatch(sw, col(colorRes), name.equals(Prefs.accent(this)));
        sw.setOnClickListener(v -> {
            if (!name.equals(Prefs.accent(this))) { Prefs.saveAccent(this, name); recreate(); }
        });
    }

    private void paintAccentSwatch(View sw, int color, boolean selected) {
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(col(R.color.bg));
        ring.setStroke(dp(2), selected ? col(R.color.text) : android.graphics.Color.TRANSPARENT);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        fill.setColor(color);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{ring, fill});
        layers.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
        sw.setBackground(layers);
        sw.setSelected(selected);
    }

    private void paintCustomSwatch(View sw, boolean selected) {
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(col(R.color.bg));
        ring.setStroke(dp(2), selected ? col(R.color.text) : android.graphics.Color.TRANSPARENT);
        android.graphics.drawable.GradientDrawable rainbow = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{0xFFE4572E, 0xFFF3AE30, 0xFF278A63, 0xFF2C6DB5, 0xFF8657C8});
        rainbow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{ring, rainbow});
        layers.setLayerInset(1, dp(4), dp(4), dp(4), dp(4));
        sw.setBackground(layers);
        sw.setSelected(selected);
    }

    private void showCustomAccentPicker() {
        int initial = Prefs.customAccent(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(20), dp(8), dp(20), dp(4));
        View preview = new View(this);
        rows.addView(preview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        int[] channels = {android.graphics.Color.red(initial),
            android.graphics.Color.green(initial), android.graphics.Color.blue(initial)};
        String[] labels = {"Red", "Green", "Blue"};
        for (int i = 0; i < channels.length; i++) {
            final int channel = i;
            TextView label = new TextView(this);
            label.setText(labels[i]);
            rows.addView(label);
            android.widget.SeekBar slider = new android.widget.SeekBar(this);
            slider.setMax(255);
            slider.setProgress(channels[i]);
            slider.setContentDescription(labels[i] + " color channel");
            slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) {
                    channels[channel] = value;
                    preview.setBackgroundColor(android.graphics.Color.rgb(channels[0], channels[1], channels[2]));
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
                @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
            });
            rows.addView(slider);
        }
        preview.setBackgroundColor(initial);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Custom primary color")
            .setView(rows)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply color", (dialog, which) -> {
                Prefs.saveCustomAccent(this,
                    android.graphics.Color.rgb(channels[0], channels[1], channels[2]));
                recreate();
            }).show();
    }

    // ---------------- system page (Shizuku top) ----------------

    private void setupSystemPage() {
        sysStatus = pageTools.findViewById(R.id.sys_status);
        sysOutput = pageTools.findViewById(R.id.sys_output);
        sysOutput.setTypeface(android.graphics.Typeface.MONOSPACE);
        ((Button) pageTools.findViewById(R.id.sys_refresh)).setOnClickListener(v -> sysTickOnce());
    }

    private void ensureShizuku() {
        if (!Shizuku.pingBinder()) {
            sysStatus.setText("Shizuku is not running.\nStart it via ADB, then reopen this app.");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) startSysLoop();
        else { sysStatus.setText("Requesting Shizuku permission"); Shizuku.requestPermission(SHIZUKU_REQ); }
    }

    private void startSysLoop() {
        sysStatus.setText("Live: top CPU and memory users, refreshing every 3s");
        if (!sysLooping) { sysLooping = true; sysLoop(); }
    }

    private void sysLoop() {
        if (!resumed || current != 3 || toolsDetail != 1) { sysLooping = false; return; }
        sysTickOnce();
        ui.postDelayed(this::sysLoop, 3000);
    }

    private void sysTickOnce() {
        new Thread(() -> {
            final String text = runTop();
            ui.post(() -> sysOutput.setText(text));
        }).start();
    }

    private String runTop() {
        String raw = rawTop();
        try { return formatStats(raw); }
        catch (Throwable e) { return raw; }
    }

    private String rawTop() {
        BufferedReader r = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null,
                    new String[]{"sh", "-c", "top -b -n 1 -m 15 -s 6"}, null, null);
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Throwable e) {
            return "error reading processes:\n" + e;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignore) {}
        }
    }

    // Turn toybox top output into a compact summary (RAM, CPU, tasks) and an
    // aligned process table. Falls back to raw output if the format shifts.
    private String formatStats(String raw) {
        String[] lines = raw.split("\n");
        String memLine = "", cpuLine = "", tasksLine = "";
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("Tasks:")) tasksLine = l;
            else if (l.startsWith("Mem:")) memLine = l;
            else if (l.contains("%cpu")) cpuLine = l;
            else if (l.startsWith("PID") && l.contains("%CPU")) { headerIdx = i; break; }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(memSummary(memLine)).append("\n");
        sb.append(cpuSummary(cpuLine));
        String tasks = matchOne(tasksLine, "Tasks:\\s+(\\d+)");
        if (!tasks.isEmpty()) sb.append("   ").append(tasks).append(" tasks");
        sb.append("\n\n");
        sb.append(String.format("%-22s %5s %5s %6s\n", "process", "cpu%", "mem%", "res"));
        if (headerIdx >= 0) {
            for (int i = headerIdx + 1; i < lines.length; i++) {
                String[] f = lines[i].trim().split("\\s+");
                if (f.length < 12) continue;
                String name = f[11];
                if (name.length() > 22) name = name.substring(name.length() - 22);
                sb.append(String.format("%-22s %5s %5s %6s\n", name, f[8], f[9], f[5]));
            }
        }
        return sb.toString().trim();
    }

    private String memSummary(String l) {
        try {
            long total = matchMB(l, "total"), used = matchMB(l, "used");
            int pct = total > 0 ? (int) Math.round(used * 100.0 / total) : 0;
            return String.format("RAM %s / %s (%d%%)", gb(used), gb(total), pct);
        } catch (Throwable e) { return "RAM ?"; }
    }
    private String cpuSummary(String l) {
        try {
            double t = Double.parseDouble(matchOne(l, "(\\d+)%cpu"));
            double idle = Double.parseDouble(matchOne(l, "(\\d+)%idle"));
            int pct = t > 0 ? (int) Math.round((t - idle) * 100.0 / t) : 0;
            return String.format("CPU %d%%", pct);
        } catch (Throwable e) { return "CPU ?"; }
    }
    private long matchMB(String l, String key) {
        String v = matchOne(l, "(\\d+)M\\s+" + key);
        return v.isEmpty() ? 0 : Long.parseLong(v);
    }
    private String gb(long mb) { return mb >= 1024 ? String.format("%.1fG", mb / 1024.0) : mb + "M"; }
    private String matchOne(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s == null ? "" : s);
        return m.find() ? m.group(1) : "";
    }

    // ---------------- share page (send to a named peer, inbox) ----------------

    private EditText shareText;
    private LinearLayout shareInbox;
    private boolean shareInboxMode;
    private TextView shareStatus;
    private android.net.Uri shareFileUri;
    private String shareFileName = "";
    private final androidx.activity.result.ActivityResultLauncher<String[]> shareFilePicker =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            shareFileUri = uri;
            shareFileName = displayName(uri);
            bindShareFile();
            pageShare.findViewById(R.id.share_file_send).setVisibility(View.VISIBLE);
        });

    private void setupSharePage() {
        shareText = pageShare.findViewById(R.id.share_text);
        shareInbox = pageShare.findViewById(R.id.share_inbox);
        shareStatus = pageShare.findViewById(R.id.share_status);
        pageShare.findViewById(R.id.share_file_pick).setOnClickListener(v -> shareFilePicker.launch(new String[]{"*/*"}));
        pageShare.findViewById(R.id.share_file_send).setOnClickListener(v -> sendSelectedFile());
        pageShare.findViewById(R.id.share_send).setOnClickListener(v -> sendToSelected(shareText.getText().toString(), true));
        pageShare.findViewById(R.id.share_paste).setOnClickListener(v -> sendToSelected(clipboardText(), false));
        bindShareFile();
        setShareMode(false);
    }

    /** Compose and Inbox are two modes of the Share tab; each has its own crumb, and back leaves Inbox for Compose. */
    private void setShareMode(boolean inbox) {
        shareInboxMode = inbox;
        pageShare.findViewById(R.id.share_compose).setVisibility(inbox ? View.GONE : View.VISIBLE);
        pageShare.findViewById(R.id.share_inbox_section).setVisibility(inbox ? View.VISIBLE : View.GONE);
        View top = pageShare.findViewById(R.id.share_top);
        if (inbox) {
            Kit.pageTop(top, "received", this::openPlace);
            ((TextView) pageShare.findViewById(R.id.share_heading)).setText("Received");
            ((TextView) pageShare.findViewById(R.id.share_sub)).setText("Open an item, or share it on.");
            renderInbox();
            return;
        }
        Kit.pageTop(top, "share", this::openPlace);
        Kit.topAction(top, Kit.Icon.DEVICE, "Choose who receives", v -> openRecipientSheet());
        Kit.topAction(top, R.drawable.csi_download, "Received", v -> setShareMode(true));
        ((TextView) pageShare.findViewById(R.id.share_sub)).setText(
            "Your recipient stays visible while you choose what to send.");
        refreshShareHeading();
    }

    private void bindShareFile() {
        View row = pageShare.findViewById(R.id.share_file_pick);
        Kit.bindRow(row, Kit.Icon.FOLDER, shareFileName.isEmpty() ? "Choose a file" : shareFileName,
            shareFileName.isEmpty() ? "Images, videos, and documents" : "Ready to send", null, true);
    }

    // Show the stored roster and inbox immediately, then refresh online state in
    // the background so the picker is never blank while a scan runs.
    private void refreshShare() {
        renderPeers(PeerStore.load(this));
        renderInbox();
        renderSentHistory();
        refreshShareHeading();
        if (!Prefs.token(this).isEmpty()) scanPeers();
    }

    private void refreshShareHeading() {
        String selected = PeerStore.selected(this);
        TextView heading = pageShare.findViewById(R.id.share_heading);
        heading.setVisibility(shareInboxMode ? View.GONE : View.VISIBLE);
        if (shareInboxMode) return;
        heading.setText(
            selected.isEmpty() ? "Choose a recipient" : "Send to " + selected);
    }

    private String displayName(android.net.Uri uri) {
        String fallback = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Exception ignored) { }
        return fallback == null || fallback.isEmpty() ? "Selected file" : fallback;
    }

    private void sendSelectedFile() {
        final android.net.Uri uri = shareFileUri;
        final String name = shareFileName;
        final String target = PeerStore.selected(this);
        final String token = Prefs.token(this);
        if (uri == null) { toast("Choose a file first"); return; }
        if (target.isEmpty()) { toast("Pick a device first"); return; }
        if (token.isEmpty()) { toast("Set the token in Settings"); return; }
        shareStatus.setText("Sending " + name + " to " + target);
        new Thread(() -> {
            String result;
            boolean delivered = false;
            String kind = "file";
            try (java.io.InputStream input = getContentResolver().openInputStream(uri);
                 java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                if (input == null) throw new Exception("The selected file cannot be opened");
                byte[] block = new byte[65536];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (bytes.size() + count > 20 * 1024 * 1024)
                        throw new Exception("Choose a file under 20 MB");
                    bytes.write(block, 0, count);
                }
                String mime = getContentResolver().getType(uri);
                String lowerName = name.toLowerCase(java.util.Locale.ROOT);
                if ((mime != null && mime.startsWith("image/")) ||
                    lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp")) kind = "image";
                MeshClient.send(target, token, Prefs.deviceName(this), kind, name, bytes.toByteArray());
                result = "Sent " + name + " to " + target;
                delivered = true;
            } catch (Exception error) {
                result = "Failed to send " + name + ": " + error.getMessage();
            }
            final String message = result;
            final boolean sent = delivered;
            final String sentKind = kind;
            ui.post(() -> {
                shareStatus.setText(message);
                recordSent(name, sentKind, target, sent);
                if (sent && uri.equals(shareFileUri)) {
                    shareFileUri = null;
                    shareFileName = "";
                    bindShareFile();
                    pageShare.findViewById(R.id.share_file_send).setVisibility(View.GONE);
                }
            });
        }, "share-file").start();
    }

    private void recordSent(String name, String kind, String target, boolean delivered) {
        android.content.SharedPreferences prefs = getSharedPreferences("csync_share", MODE_PRIVATE);
        JSONArray prior;
        try { prior = new JSONArray(prefs.getString("sent", "[]")); }
        catch (Exception ignored) { prior = new JSONArray(); }
        JSONArray next = new JSONArray();
        JSONObject entry = new JSONObject();
        try {
            entry.put("name", name);
            entry.put("kind", kind);
            entry.put("target", target);
            entry.put("delivered", delivered);
            entry.put("at", System.currentTimeMillis());
        } catch (Exception ignored) { }
        next.put(entry);
        for (int i = 0; i < Math.min(prior.length(), 49); i++) next.put(prior.optJSONObject(i));
        prefs.edit().putString("sent", next.toString()).apply();
        renderSentHistory();
    }

    private void renderSentHistory() {
        LinearLayout container = pageShare.findViewById(R.id.share_sent);
        container.removeAllViews();
        JSONArray entries;
        try { entries = new JSONArray(getSharedPreferences("csync_share", MODE_PRIVATE).getString("sent", "[]")); }
        catch (Exception ignored) { entries = new JSONArray(); }
        LinearLayout group = Kit.group(container);
        if (entries.length() == 0) {
            View row = Kit.addRow(group);
            Kit.bindRow(row, Kit.Icon.HISTORY, "No sends yet", "Texts and files you send appear here", null, false);
            return;
        }
        for (int i = 0; i < Math.min(entries.length(), 10); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("kind", "file");
            View row = Kit.addRow(group);
            Kit.bindRow(row, "text".equals(kind) ? R.drawable.csi_text : "image".equals(kind) ? Kit.Icon.PHOTO : Kit.Icon.FILE,
                item.optString("name"),
                (kind.isEmpty() ? "File" : Character.toUpperCase(kind.charAt(0)) + kind.substring(1)) + " · "
                    + item.optString("target") + " · " + relTime(item.optLong("at")) + " · "
                    + (item.optBoolean("delivered") ? "Delivered" : "Failed"),
                null, false);
        }
    }

    private void scanPeers() {
        final String home = Prefs.homeIp(this), assist = Prefs.assistIp(this);
        final String token = Prefs.token(this);
        if (token.isEmpty() || (home.isEmpty() && assist.isEmpty())) {
            shareStatus.setText("Set the Pi address and token in Settings to scan devices");
            return;
        }
        shareStatus.setText("Scanning devices");
        new Thread(() -> {
            try {
                JSONArray scanned;
                try { scanned = MeshClient.peers(home.isEmpty() ? assist : home, token); }
                catch (Exception first) {
                    if (home.isEmpty() || assist.isEmpty() || home.equals(assist)) throw first;
                    scanned = MeshClient.peers(assist, token);
                }
                final JSONArray merged = PeerStore.mergeScan(this, scanned);
                ui.post(() -> { renderPeers(merged); shareStatus.setText(""); });
            } catch (Throwable e) {
                final String msg = e.getMessage();
                ui.post(() -> shareStatus.setText("scan failed: " + msg));
            }
        }).start();
    }

    /** The roster lives in PeerStore; the page shows only the chosen name, and the drawer lists everyone. */
    private void renderPeers(JSONArray roster) {
        refreshShareHeading();
    }

    private void openRecipientSheet() {
        JSONArray roster = PeerStore.load(this);
        String selected = PeerStore.selected(this);
        java.util.List<Kit.Action> actions = new java.util.ArrayList<>();
        for (int i = 0; roster != null && i < roster.length(); i++) {
            JSONObject o = roster.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("name");
            boolean online = o.optBoolean("online");
            String platform = o.optString("platform");
            actions.add(new Kit.Action(name.equals(selected) ? R.drawable.csi_check : Kit.Icon.DEVICE, name,
                (platform.isEmpty() ? "" : platform + " · ") + (online ? "Online" : "Offline"), () -> {
                    PeerStore.select(this, name);
                    refreshShareHeading();
                }));
        }
        actions.add(new Kit.Action(R.drawable.csi_refresh, "Scan for devices", "Refresh who is online", this::scanPeers));
        Kit.sheet(this, "Send to", selected.isEmpty() ? "Pick a device" : "Now: " + selected,
            actions.toArray(new Kit.Action[0]));
    }

    private void sendToSelected(final String text, boolean fromComposer) {
        if (text == null || text.isEmpty()) { toast("Nothing to send"); return; }
        final String target = PeerStore.selected(this), token = Prefs.token(this);
        if (target.isEmpty()) { toast("Pick a device first"); return; }
        if (token.isEmpty()) { toast("Set the token in Settings"); return; }
        final String from = Prefs.deviceName(this);
        shareStatus.setText("Sending to " + target);
        new Thread(() -> {
            String result;
            boolean sent = false;
            try {
                MeshClient.send(target, token, from, "text", "shared.txt", text.getBytes("UTF-8"));
                result = "sent to " + target;
                sent = true;
            } catch (Throwable e) {
                result = "failed: " + e.getMessage();
            }
            final String r = result;
            final boolean delivered = sent;
            ui.post(() -> {
                shareStatus.setText(r);
                recordSent(text.length() > 36 ? text.substring(0, 36) : text, "text", target, delivered);
                if (delivered && fromComposer && text.contentEquals(shareText.getText())) shareText.setText("");
            });
        }).start();
    }

    // List what has arrived in the app's inbox: files under inbox/<from>/<name>.
    private LinearLayout inboxGroup;

    private void renderInbox() {
        shareInbox.removeAllViews();
        inboxGroup = null;
        java.io.File inbox = getExternalFilesDir("inbox");
        java.io.File[] senders = (inbox != null && inbox.exists()) ? inbox.listFiles() : null;
        boolean any = false;
        if (senders != null) {
            for (java.io.File sender : senders) {
                java.io.File[] files = sender.listFiles();
                if (files == null) continue;
                for (java.io.File f : files) {
                    any = true;
                    if (inboxGroup == null) inboxGroup = Kit.group(shareInbox);
                    View row = Kit.addRow(inboxGroup);
                    String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
                    boolean image = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
                    Kit.bindRow(row, image ? Kit.Icon.PHOTO : Kit.Icon.FILE, f.getName(),
                        "From " + sender.getName() + " · " + relTime(f.lastModified()), null, true);
                    row.setOnClickListener(v -> showInboxActions(f));
                    try {
                        if (f.getCanonicalPath().equals(inboxRevealPath))
                            row.post(() -> row.requestRectangleOnScreen(
                                new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true));
                    } catch (java.io.IOException ignored) { }
                }
            }
        }
        if (!any) Kit.bindRow(Kit.addRow(Kit.group(shareInbox)), R.drawable.csi_download,
            "Nothing received yet", "Files other devices send you appear here", null, false);
    }

    private void showInboxActions(java.io.File file) {
        if (!file.isFile()) {
            toast("This received file is no longer available");
            renderInbox();
            return;
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(file.getName());
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            extension.toLowerCase(java.util.Locale.ROOT));
        if (mime == null) mime = "application/octet-stream";
        String type = mime;
        boolean textFile = type.startsWith("text/") || type.equals("application/json");
        String[] choices = textFile ? new String[]{"Open", "Share", "Copy text"} :
            new String[]{"Open", "Share"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(choices, (dialog, which) -> {
                if (which == 2) { copyFile(file); return; }
                try {
                    Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".share", file);
                    Intent intent;
                    if (which == 0) {
                        intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type);
                    } else {
                        intent = new Intent(Intent.ACTION_SEND).setType(type)
                            .putExtra(Intent.EXTRA_STREAM, uri);
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent,
                        which == 0 ? "Open received file" : "Share received file"));
                } catch (Exception error) {
                    toast(which == 0 ? "No app can open this file" : "Cannot share this file");
                }
            }).show();
    }

    private void copyFile(java.io.File f) {
        try {
            if (f.length() > (1 << 20)) {
                toast("This text file is too large to copy");
                return;
            }
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                while (off < data.length) {
                    int count = in.read(data, off, data.length - off);
                    if (count < 0) throw new java.io.IOException("File changed while copying");
                    off += count;
                }
            }
            String text = new String(data, "UTF-8");
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("csync", text));
            toast("Copied " + f.getName());
        } catch (Throwable e) {
            toast("Can't copy: " + e.getMessage());
        }
    }

    // ---------------- settings: connection card ----------------

    private EditText connHome, connToken, connAssist;
    private TextView connStatus;

    private void setupConnectionCard() {
        connHome = pageSettings.findViewById(R.id.set_home);
        connAssist = pageSettings.findViewById(R.id.set_assist);
        connToken = pageSettings.findViewById(R.id.set_token);
        connStatus = pageSettings.findViewById(R.id.set_conn_status);
        connHome.setText(Prefs.homeIp(this));
        connAssist.setText(Prefs.assistIp(this));
        connToken.setText(Prefs.token(this));
        pageSettings.findViewById(R.id.set_save).setOnClickListener(v -> {
            Prefs.save(this, connHome.getText().toString(), connToken.getText().toString());
            Prefs.saveAssistIp(this, connAssist.getText().toString());
            toast("Connection saved");
            refreshConnection();
        });
        Kit.bindSection(pageSettings.findViewById(R.id.set_sec_playback), 0, "Playback",
            pageSettings.findViewById(R.id.set_playback_body));
        Kit.bindSection(pageSettings.findViewById(R.id.set_sec_assistant), 0, "Assistant",
            pageSettings.findViewById(R.id.set_assistant_body));
        Kit.bindSection(pageSettings.findViewById(R.id.set_sec_appearance), 0, "Appearance",
            pageSettings.findViewById(R.id.set_appearance_body));
        LinearLayout playback = Kit.group(pageSettings.findViewById(R.id.set_playback_body));
        View player = Kit.addRow(playback);
        Kit.bindRow(player, Kit.Icon.DISPLAY, "Pi screen player", "Volume, speed and what is playing", null, true);
        player.setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class).putExtra("player_target", "pi")));
        refreshConnection();
    }

    /** Whether the Pi answers, shown on the Settings row and as the Connection page's heading. */
    private void refreshConnection() {
        if (connStatus == null) return;
        LinearLayout holder = pageSettings.findViewById(R.id.set_connection_group);
        holder.removeAllViews();
        final View row = Kit.addRow(Kit.group(holder));
        final String pi = Prefs.assistIp(this);
        Kit.bindRow(row, R.drawable.csi_wifi, "Connection", pi.isEmpty() ? "Add your Raspberry Pi" : pi, null, true);
        Kit.rowStatus(row, Kit.Status.WARN, "Checking");
        row.setOnClickListener(v -> revealSettingsDetail(R.id.settings_connection_detail));
        connStatus.setText("Checking the Raspberry Pi");

        LinearLayout receive = pageSettings.findViewById(R.id.set_receive_group);
        receive.removeAllViews();
        LinearLayout group = Kit.group(receive);
        View receiving = Kit.addRow(group);
        Kit.bindRow(receiving, R.drawable.csi_download, "Receive on this phone",
            MeshService.running ? "Other devices can send to it" : "Tap to let other devices send to it", null, false);
        Kit.rowStatus(receiving, MeshService.running ? Kit.Status.GOOD : Kit.Status.IDLE, MeshService.running ? "Ready" : "Stopped");
        receiving.setOnClickListener(v -> toggleReceiver());
        View name = Kit.addRow(group);
        String tail = MeshClient.tailnetIP();
        Kit.bindRow(name, Kit.Icon.DEVICE, Prefs.deviceName(this), "This phone's name on your network", tail, false);

        if (pi.isEmpty()) {
            Kit.rowStatus(row, Kit.Status.IDLE, "Not set up");
            connStatus.setText("Add your Raspberry Pi below");
            return;
        }
        new Thread(() -> {
            boolean up = MeshClient.reachable(pi, MeshClient.ASSIST_PORT);
            ui.post(() -> {
                Kit.rowStatus(row, up ? Kit.Status.GOOD : Kit.Status.IDLE, up ? "Connected" : "Offline");
                connStatus.setText(up ? "Connected over Tailscale" : "The Raspberry Pi cannot be reached");
            });
        }, "settings-connection").start();
    }

    private void toggleReceiver() {
        Intent svc = new Intent(this, MeshService.class);
        if (MeshService.running) {
            stopService(svc);
        } else {
            if (Prefs.token(this).isEmpty()) { toast("Save the access token first"); return; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        }
        ui.postDelayed(this::refreshConnection, 400);
    }

    // ---------------- settings: the assistant's providers, model and thinking ----------------

    private JSONObject providerData;
    private JSONArray assistantTools;

    private void setupAssistantCard() { renderAssistantRows(null); }

    private void refreshAssistant() {
        final String assist = Prefs.assistIp(this), token = Prefs.token(this);
        if (assist.isEmpty() || token.isEmpty()) { renderAssistantRows("Add the Pi and its token in Connection"); return; }
        new Thread(() -> {
            JSONObject data = null; JSONArray caps = null;
            try { data = MeshClient.providers(assist, token); } catch (Throwable ignore) { }
            try { caps = MeshClient.capabilities(assist, token); } catch (Throwable ignore) { }
            final JSONObject d = data; final JSONArray c = caps;
            ui.post(() -> {
                if (d != null) providerData = d;
                assistantTools = c;
                renderAssistantRows(d == null ? "The Pi assistant cannot be reached" : null);
            });
        }, "settings-assistant").start();
    }

    /**
     * The Assistant group: the model new conversations use, each provider with whether this Pi can
     * chat through it, and whether the assistant may run commands on the Pi.
     */
    private void renderAssistantRows(String problem) {
        LinearLayout body = pageSettings.findViewById(R.id.set_assistant_body);
        body.removeAllViews();
        LinearLayout group = Kit.group(body);
        JSONObject active = providerData == null ? null : providerData.optJSONObject("active");
        String model = active == null ? "" : active.optString("model");
        String effort = active == null ? "" : active.optString("effort");
        View pick = Kit.addRow(group);
        Kit.bindRow(pick, R.drawable.csi_speed, "Model", problem != null ? problem : "For new conversations",
            model.isEmpty() ? null : model + (effort.isEmpty() ? "" : " " + effort), providerData != null);
        if (providerData != null) pick.setOnClickListener(v -> openModelSheet("default"));
        else pick.setAlpha(0.6f);

        JSONArray providers = providerData == null ? null : providerData.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            boolean ready = chatSupported(p);
            View row = Kit.addRow(group);
            JSONArray models = p.optJSONArray("models");
            int count = models == null ? 0 : models.length();
            Kit.bindRow(row, Kit.Icon.CHAT, p.optString("label"),
                ready ? count + (count == 1 ? " model" : " models") : p.optString("reason", "Not set up on this Pi"), null, false);
            Kit.rowStatus(row, ready ? Kit.Status.GOOD : Kit.Status.IDLE, ready ? "Ready" : "Not set up");
        }

        if (assistantTools != null) {
            boolean allowed = false;
            for (int i = 0; i < assistantTools.length(); i++) {
                JSONObject tool = assistantTools.optJSONObject(i);
                if (tool != null && "run_command".equals(tool.optString("name"))) allowed = true;
            }
            View commands = Kit.addRow(group);
            Kit.bindRow(commands, Kit.Icon.TOOLS, "Pi commands", "Set on the Pi", null, false);
            Kit.rowStatus(commands, allowed ? Kit.Status.GOOD : Kit.Status.IDLE, allowed ? "Allowed" : "Not set up");
        }
    }

    private boolean chatSupported(JSONObject provider) {
        return provider != null && provider.optBoolean("chatSupported");
    }

    /** The provider whose list holds this model, or null when no provider lists it. */
    private JSONObject providerOf(String model) {
        JSONArray providers = providerData == null ? null : providerData.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            JSONArray models = p == null ? null : p.optJSONArray("models");
            for (int j = 0; models != null && j < models.length(); j++)
                if (model.equals(models.optString(j))) return p;
        }
        return null;
    }

    /**
     * One sheet for choosing a model and how hard it thinks. Scope "default" sets what new
     * conversations use, saved on the Pi. Scope "chat" sets it for the open conversation only.
     * Rows only select; nothing changes until Save, which stays in view under the list.
     */
    private void openModelSheet(String scope) {
        final boolean forChat = "chat".equals(scope);
        if (forChat && chatSession == null) return;
        if (providerData == null) {
            final String assist = Prefs.assistIp(this), token = Prefs.token(this);
            new Thread(() -> {
                JSONObject data = null;
                try { data = MeshClient.providers(assist, token); } catch (Throwable ignore) { }
                final JSONObject d = data;
                ui.post(() -> {
                    if (d == null) { toast("The Pi assistant cannot be reached"); return; }
                    providerData = d;
                    openModelSheet(scope);
                });
            }, "model-sheet").start();
            return;
        }
        JSONObject active = providerData.optJSONObject("active");
        final String piModel = active == null ? "" : active.optString("model");
        final String piEffort = active == null ? "" : active.optString("effort");
        JSONObject entry = forChat ? convEntry(chatSession) : null;
        // An empty choice in a conversation means "follow the Pi's default".
        final String[] choice = {forChat ? (entry == null ? "" : entry.optString("model")) : piModel,
            forChat ? (entry == null ? "" : entry.optString("effort")) : piEffort};
        // Only the provider that holds the current choice starts open, so the chosen model is on screen at once.
        final java.util.Set<String> open = new java.util.HashSet<>();
        JSONObject holder = providerOf(choice[0].isEmpty() ? piModel : choice[0]);
        if (holder != null) open.add(holder.optString("id"));

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View sheet = getLayoutInflater().inflate(R.layout.kit_sheet, null, false);
        ((TextView) sheet.findViewById(R.id.kit_title)).setText(forChat ? "Model for this conversation" : "Model for new conversations");
        sheet.findViewById(R.id.kit_sub).setVisibility(View.GONE);
        LinearLayout rows = sheet.findViewById(R.id.kit_rows);
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(20), 0, dp(20), dp(12));

        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            footer.removeAllViews();
            if (forChat) {
                LinearLayout follow = Kit.group(rows);
                View row = Kit.addRow(follow);
                Kit.bindRow(row, choice[0].isEmpty() ? R.drawable.csi_check : R.drawable.csi_speed,
                    "The Pi's default", piModel.isEmpty() ? null : piModel, null, false);
                row.setOnClickListener(v -> { choice[0] = ""; choice[1] = ""; render[0].run(); });
            }
            JSONArray providers = providerData.optJSONArray("providers");
            for (int i = 0; providers != null && i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p == null) continue;
                final String id = p.optString("id");
                boolean ready = chatSupported(p), shown = ready && open.contains(id);
                JSONArray models = p.optJSONArray("models");
                int count = models == null ? 0 : models.length();
                LinearLayout group = Kit.group(rows);
                ((LinearLayout.LayoutParams) group.getLayoutParams()).topMargin = dp(10);
                View head = Kit.addRow(group);
                Kit.bindRow(head, Kit.Icon.CHAT, p.optString("label"),
                    ready ? count + (count == 1 ? " model" : " models") : p.optString("reason", "Not set up on this Pi"), null, false);
                if (!ready) { Kit.rowStatus(head, Kit.Status.IDLE, "Not set up"); continue; }
                Kit.rowAction(head, R.drawable.ic_chevron_down,
                    (shown ? "Hide " : "Show ") + p.optString("label") + " models", v -> head.performClick());
                head.findViewById(R.id.kit_action).setRotation(shown ? 180f : 0f);
                head.setOnClickListener(v -> { if (!open.remove(id)) open.add(id); render[0].run(); });
                for (int j = 0; shown && j < count; j++) {
                    final String name = models.optString(j);
                    boolean picked = name.equals(choice[0]) || (choice[0].isEmpty() && !forChat && name.equals(piModel));
                    View row = Kit.addRow(group);
                    Kit.bindRow(row, picked ? R.drawable.csi_check : R.drawable.csi_speed, name, null, null, false);
                    row.setOnClickListener(v -> {
                        choice[0] = name;
                        // Thinking levels differ by provider, so a level the new model lacks is dropped.
                        JSONArray levels = p.optJSONArray("efforts");
                        boolean kept = false;
                        for (int k = 0; levels != null && k < levels.length(); k++) if (levels.optString(k).equals(choice[1])) kept = true;
                        if (!kept) choice[1] = "";
                        render[0].run();
                    });
                }
            }

            String now = choice[0].isEmpty() ? piModel : choice[0];
            JSONObject owner = providerOf(now);
            JSONArray levels = owner == null ? null : owner.optJSONArray("efforts");
            if (levels != null && levels.length() > 0) {
                Kit.label(footer, "Thinking, for " + now);
                com.google.android.material.chip.ChipGroup chips = new com.google.android.material.chip.ChipGroup(this);
                chips.setSingleSelection(true);
                for (int k = forChat ? -1 : 0; k < levels.length(); k++) {
                    final String value = k < 0 ? "" : levels.optString(k);
                    com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
                    chip.setText(k < 0 ? "Default" : prettyName(value));
                    chip.setCheckable(true);
                    chip.setChecked(value.equals(choice[1]));
                    chip.setChipMinHeight(dp(40));
                    // The chosen level reads in the accent, like the selected item of every other control.
                    boolean on = value.equals(choice[1]);
                    chip.setCheckedIconVisible(false);
                    chip.setChipStrokeWidth(0);
                    chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(on
                        ? androidx.core.graphics.ColorUtils.blendARGB(col(R.color.surface2), accent(), 0.22f) : col(R.color.surface2)));
                    chip.setTextColor(on ? accent() : col(R.color.text));
                    chip.setOnClickListener(v -> { choice[1] = value; render[0].run(); });
                    chips.addView(chip);
                }
                footer.addView(chips);
            }
            LinearLayout buttons = new LinearLayout(this);
            buttons.setPadding(0, dp(10), 0, 0);
            boolean draft = forChat && chatInput.getText().toString().trim().length() > 0;
            com.google.android.material.button.MaterialButton save = draft
                ? new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
                : new com.google.android.material.button.MaterialButton(this);
            save.setText("Save");
            save.setIconResource(R.drawable.csi_check);
            save.setMinHeight(dp(48));
            buttons.addView(save);
            Runnable store = () -> {
                dialog.dismiss();
                if (forChat) {
                    ChatStore.patch(this, chatSession, "model", choice[0]);
                    ChatStore.patch(this, chatSession, "effort", choice[1]);
                    updateConfigSubtitle();
                } else {
                    saveDefaultModel(choice[0], choice[1]);
                }
            };
            save.setOnClickListener(v -> store.run());
            if (draft) {
                com.google.android.material.button.MaterialButton send = new com.google.android.material.button.MaterialButton(this);
                send.setText("Save and send");
                send.setIconResource(R.drawable.csi_send);
                send.setMinHeight(dp(48));
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
                gap.leftMargin = dp(8);
                buttons.addView(send, gap);
                send.setOnClickListener(v -> { store.run(); sendChat(); });
            }
            footer.addView(buttons);
        };
        render[0].run();

        // The list scrolls; Thinking and Save stay put under it, so a long model list never hides the way out.
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        androidx.core.widget.NestedScrollView scroll = new androidx.core.widget.NestedScrollView(this);
        scroll.addView(sheet);
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        frame.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        int tall = Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f);
        dialog.setContentView(frame, new android.view.ViewGroup.LayoutParams(-1, tall));
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.show();
    }

    /** Save the model new conversations use. It lives on the Pi, so every device sees the same default. */
    private void saveDefaultModel(String model, String effort) {
        JSONObject owner = providerOf(model);
        if (owner == null) { toast("Pick a model first"); return; }
        final String provider = owner.optString("id");
        final String assist = Prefs.assistIp(this), token = Prefs.token(this);
        new Thread(() -> {
            String failed = null;
            try { MeshClient.setConfig(assist, token, provider, model, effort); }
            catch (Throwable e) { failed = e.getMessage(); }
            final String problem = failed;
            ui.post(() -> {
                if (problem != null) Kit.sheet(this, "The model was not saved", problem);
                else { toast("Saved"); chatDefaultModel = model; chatDefaultEffort = effort; }
                refreshAssistant();
            });
        }, "settings-model").start();
    }

    // ---------------- chat page (assistant on the Pi) ----------------

    private EditText chatInput, chatSearch, chatTitleEdit;
    private TextView chatSubtitle, chatTitle, chatBack, chatToggleFav, chatToggleArchived;
    private android.widget.ImageView chatEdit, chatFavorite, chatArchive;
    private LinearLayout chatList, chatHistoryList, chatToolsList, chatConvo, chatFilterbar;
    private ScrollView chatScroll, chatHistory;
    private String chatSession;
    private String chatFilter = "All";
    private String chatDefaultModel = "", chatDefaultEffort = "";
    private boolean chatExpanded, chatConvoMode, showArchived, showFavOnly, composerDidDrag;
    private float composerDragY;
    private int composerDragLines;
    private io.noties.markwon.Markwon markwon;

    private void acceptChatDraft(Intent intent) {
        if (intent == null) return;
        // A launcher shortcut, a widget or a tile asked for a fresh conversation.
        if ("chat-new".equals(intent.getStringExtra("destination"))) {
            intent.removeExtra("destination");
            newConversation();
            // Coming from outside the app, the point is to type straight away.
            chatInput.postDelayed(() -> ((android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT), 250);
            return;
        }
        // A reply notification asked for the conversation it belongs to.
        String wanted = intent.getStringExtra("open_conversation");
        if (wanted != null) {
            intent.removeExtra("open_conversation");
            org.json.JSONObject saved = convEntry(wanted);
            if (saved != null) openConversation(wanted, saved.optString("title"));
            return;
        }
        String draft = intent.getStringExtra("chat_prefill");
        if (draft == null || draft.isEmpty()) return;
        String id = intent.getStringExtra("chat_session");
        org.json.JSONObject entry = id == null ? null : convEntry(id);
        if (entry == null) newConversation();
        else openConversation(id, entry.optString("title"));
        chatInput.setText(draft);
        chatInput.setSelection(chatInput.length());
        intent.removeExtra("chat_prefill");
    }

    private void setupChatPage() {
        chatInput = pageChat.findViewById(R.id.chat_input);
        chatList = pageChat.findViewById(R.id.chat_list);
        chatScroll = pageChat.findViewById(R.id.chat_scroll);
        chatSubtitle = pageChat.findViewById(R.id.chat_subtitle);
        chatSubtitle.setVisibility(View.VISIBLE);
        chatTitle = pageChat.findViewById(R.id.chat_title);
        chatTitleEdit = pageChat.findViewById(R.id.chat_title_edit);
        chatEdit = pageChat.findViewById(R.id.chat_edit);
        chatFavorite = pageChat.findViewById(R.id.chat_favorite);
        chatArchive = pageChat.findViewById(R.id.chat_archive);
        chatBack = pageChat.findViewById(R.id.chat_back);
        chatHistory = pageChat.findViewById(R.id.chat_history);
        chatHistoryList = pageChat.findViewById(R.id.chat_history_list);
        chatToolsList = pageChat.findViewById(R.id.chat_tools_list);
        chatConvo = pageChat.findViewById(R.id.chat_convo);
        chatFilterbar = pageChat.findViewById(R.id.chat_filterbar);
        chatSearch = pageChat.findViewById(R.id.chat_search);
        chatToggleFav = pageChat.findViewById(R.id.chat_toggle_fav);
        chatToggleArchived = pageChat.findViewById(R.id.chat_toggle_archived);
        markwon = buildMarkwon();
        chatFind = new ChatFind(pageChat, chatList, chatScroll);
        chatSuggest = new ChatSuggest(pageChat, chatInput);

        final View expand = pageChat.findViewById(R.id.chat_expand);
        expand.setContentDescription("Drag to resize the message editor");
        pageChat.findViewById(R.id.chat_attach).setOnClickListener(v -> openAttachSheet());
        chatInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                updateSendButton();
                chatInput.post(() -> updateComposerHandle());
            }
        });
        expand.setOnClickListener(v -> setComposerLines(chatExpanded ? 1 : 6));
        expand.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                composerDragY = event.getRawY();
                composerDragLines = chatInput.getMinLines();
                composerDidDrag = false;
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                int delta = Math.round((composerDragY - event.getRawY()) / dp(30));
                if (delta != 0) {
                    composerDidDrag = true;
                    int lines = Math.max(1, Math.min(8, composerDragLines + delta));
                    if (lines >= 4) {
                        android.view.inputmethod.InputMethodManager keyboard =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        keyboard.hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
                    }
                    setComposerLines(lines);
                }
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                if (!composerDidDrag) v.performClick();
                return true;
            }
            return false;
        });
        chatEdit.setOnClickListener(v -> beginTitleEdit());
        chatFavorite.setOnClickListener(v -> {
            if (convEntry(chatSession) == null) return;
            boolean favorite = convEntry(chatSession).optBoolean("favorite");
            ChatStore.patch(this, chatSession, "favorite", !favorite);
            updateConversationActions();
        });
        chatArchive.setOnClickListener(v -> {
            if (convEntry(chatSession) == null) return;
            boolean archived = convEntry(chatSession).optBoolean("archived");
            ChatStore.patch(this, chatSession, "archived", !archived);
            updateConversationActions();
        });
        chatTitleEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveTitleEdit();
                return true;
            }
            return false;
        });
        pageChat.findViewById(R.id.chat_send).setOnClickListener(v -> sendChat());
        chatBack.setOnClickListener(v -> showChatList());
        pageChat.findViewById(R.id.chat_model_pill).setOnClickListener(v -> openConfigDialog());
        setupChatFollowing();
        chatSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { renderHistoryList(); }
        });
        pageChat.findViewById(R.id.chat_filter_all).setOnClickListener(v -> selectChatFilter("All"));
        chatToggleFav.setOnClickListener(v -> selectChatFilter("Favorites"));
        chatToggleArchived.setOnClickListener(v -> selectChatFilter("Archived"));
        pageChat.findViewById(R.id.chat_filter_tools).setOnClickListener(v -> selectChatFilter("Tools"));
        showChatList();
    }

    private void selectChatFilter(String filter) {
        chatFilter = filter;
        showArchived = "Archived".equals(filter);
        showFavOnly = "Favorites".equals(filter);
        int[] ids = {R.id.chat_filter_all, R.id.chat_toggle_fav,
            R.id.chat_toggle_archived, R.id.chat_filter_tools};
        String[] names = {"All", "Favorites", "Archived", "Tools"};
        for (int i = 0; i < ids.length; i++) {
            TextView tab = pageChat.findViewById(ids[i]);
            boolean selected = names[i].equals(filter);
            tab.setTextColor(selected ? accent() : col(R.color.dim));
            tab.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(
                selected ? accent() : col(R.color.dim)));
            tab.setBackground(selected ? Kit.underline(this, accent()) : null);
            tab.setSelected(selected);
        }
        boolean tools = "Tools".equals(filter);
        pageChat.findViewById(R.id.chat_search_wrap).setVisibility(tools ? View.GONE : View.VISIBLE);
        chatHistoryList.setVisibility(tools ? View.GONE : View.VISIBLE);
        chatToolsList.setVisibility(tools ? View.VISIBLE : View.GONE);
        TextView heading = pageChat.findViewById(R.id.chat_recent_heading);
        heading.setText(showArchived ? "Archived" : showFavOnly ?
            "Favorites" : tools ? "Assistant tools" : "Recent");
        if (tools) refreshChatTools();
        else renderHistoryList();
        chatHistory.post(() -> chatHistory.smoothScrollTo(0, 0));
    }

    private void refreshChatTools() {
        chatToolsList.removeAllViews();
        addChatToolRow("Assistant tools", "Checking declared Pi tools");
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) {
            chatToolsList.removeAllViews();
            addChatToolRow("Assistant unavailable", "Set the Pi and token in Settings");
            return;
        }
        new Thread(() -> {
            JSONArray tools = null;
            try { tools = MeshClient.capabilities(ip, token); } catch (Exception ignored) { }
            final JSONArray observed = tools;
            ui.post(() -> {
                if (!"Tools".equals(chatFilter) || chatConvoMode) return;
                chatToolsList.removeAllViews();
                if (observed == null) {
                    addChatToolRow("Pi tools unavailable", "Check the assistant connection");
                    return;
                }
                if (observed.length() == 0) {
                    addChatToolRow("No tools advertised", "The Pi assistant is reachable");
                    return;
                }
                for (int i = 0; i < observed.length(); i++) {
                    JSONObject tool = observed.optJSONObject(i);
                    if (tool != null) addChatToolRow(tool.optString("name"), chatToolSummary(tool.optString("name")));
                }
            });
        }, "chat-tools").start();
    }

    private void addChatToolRow(String name, String description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(11));
        card.setBackgroundResource(R.drawable.card_bg);
        TextView title = new TextView(this);
        title.setText(chatToolTitle(name));
        title.setTextColor(col(R.color.text));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextSize(13);
        card.addView(title);
        TextView sub = new TextView(this);
        sub.setText(description);
        sub.setTextColor(col(R.color.dim));
        sub.setTextSize(11);
        card.addView(sub);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = dp(7);
        chatToolsList.addView(card, layout);
    }

    private String chatToolSummary(String name) {
        switch (name) {
            case "home_health": return "Check the Pi's storage, memory, uptime, and services";
            case "list_peers": return "See your connected devices";
            case "send_to_peer": return "Send text to a named device";
            case "run_command": return "Run a Pi command when the owner allows it";
            case "camera": return "Check the camera, take a photo, or record a clip";
            case "send_file": return "Bring a Pi file into the conversation";
            case "list_skills": return "See saved assistant skills";
            case "load_skill": return "Read a saved assistant skill";
            case "top_processes": return "See which Pi processes use the most resources";
            case "pi_vitals": return "Check Pi temperature and power health";
            case "media_drives": return "See connected media drives";
            case "media_search": return "Find files on connected drives";
            case "media_status": return "Check current playback";
            case "media_play": return "Play a found item on the Pi";
            case "media_cast_youtube": return "Play a YouTube video on the Pi";
            case "media_pause": return "Pause playback";
            case "media_seek": return "Move to another point in playback";
            case "media_resume": return "Resume playback";
            case "media_stop": return "Stop playback";
            case "media_volume": return "Change playback volume";
            case "media_speed": return "Change playback speed";
            case "media_diagnose": return "Check media service, drives, power, and display";
            case "notes": return "Read or manage Pi notes";
            default: return "Available through the Pi assistant";
        }
    }

    private String chatToolTitle(String name) {
        switch (name) {
            case "home_health": return "Pi health";
            case "list_peers": return "Your devices";
            case "send_to_peer": return "Send to a device";
            case "run_command": return "Pi commands";
            case "list_skills": return "Saved skills";
            case "load_skill": return "Read a skill";
            case "top_processes": return "Busy processes";
            case "pi_vitals": return "Pi temperature and power";
            case "media_drives": return "Media drives";
            case "media_search": return "Find media";
            case "media_status": return "Playback status";
            case "media_play": return "Play media";
            case "media_cast_youtube": return "Play YouTube on Pi";
            case "media_pause": return "Pause playback";
            case "media_seek": return "Seek playback";
            case "media_resume": return "Resume playback";
            case "media_stop": return "Stop playback";
            case "media_volume": return "Playback volume";
            case "media_speed": return "Playback speed";
            case "media_diagnose": return "Media diagnostics";
            case "send_file": return "Share a Pi file";
            case "notes": return "Pi notes";
            default: return prettyName(name);
        }
    }

    private void setComposerLines(int lines) {
        chatExpanded = lines > 1;
        chatInput.setMinLines(lines);
        // The box grows with the message up to six lines on its own; the handle opens it taller.
        chatInput.setMaxLines(Math.max(lines, 6));
        updateComposerHandle();
    }

    /** The drag handle appears only when the message no longer fits in two lines, or the editor is already open. */
    private void updateComposerHandle() {
        boolean overflows = chatInput.getLayout() != null && chatInput.getLayout().getLineCount() > 2;
        pageChat.findViewById(R.id.chat_expand).setVisibility(chatExpanded || overflows ? View.VISIBLE : View.GONE);
    }

    /** Chats list: Home / Chats. A conversation: Home / Chat, where back returns to the list. */
    private void renderChatTop() {
        View top = pageChat.findViewById(R.id.chat_top);
        if (chatConvoMode) {
            Kit.pageTop(top, "conversation", this::openPlace);
            Kit.topAction(top, R.drawable.csi_search, "Find in this conversation", v -> chatFind.open());
            Kit.topAction(top, R.drawable.csi_download, "Save this conversation", v -> exportConversation());
            return;
        }
        chatFind.close();
        Kit.pageTop(top, "chat", this::openPlace);
        Kit.topAction(top, R.drawable.csi_sliders, "Model for new conversations", v -> openModelSheet("default"));
        Kit.topAction(top, R.drawable.csi_plus, "New chat", v -> newConversation());
    }

    /**
     * Save the open conversation as a Markdown file and hand it to Android's share menu, so it can
     * go to Files, a note app or another person. Your messages and the answers are written in full;
     * each tool the assistant used is named under the answer it led to.
     */
    private void exportConversation() {
        if (chatSession == null) return;
        if (ChatStore.transcript(this, chatSession).length() == 0) { toast("There is nothing to save yet"); return; }
        Kit.sheet(this, "Save this conversation", chatTitle.getText(),
            new Kit.Action(R.drawable.csi_markdown, "Markdown",
                "Text you can edit. What the assistant used is listed under each reply.", this::exportMarkdown),
            new Kit.Action(R.drawable.csi_image, "Image",
                "One tall picture of the whole conversation, as it looks here.", this::exportImage));
    }

    /** Draw every message of the open conversation into one picture and hand it to Android's share menu. */
    private void exportImage() {
        if (chatList.getWidth() == 0 || chatList.getHeight() == 0) return;
        // A very long conversation is drawn smaller, so the picture stays within what a phone can hold in memory.
        final float scale = Math.min(1f, 16000f / chatList.getHeight());
        final android.graphics.Bitmap picture;
        try {
            picture = android.graphics.Bitmap.createBitmap(Math.round(chatList.getWidth() * scale) + dp(28),
                Math.round(chatList.getHeight() * scale) + dp(28), android.graphics.Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError tooLarge) {
            Kit.sheet(this, "The picture could not be made", "This conversation is too long for one picture. Markdown still works.");
            return;
        }
        android.graphics.Canvas canvas = new android.graphics.Canvas(picture);
        canvas.drawColor(col(R.color.bg));
        canvas.translate(dp(14), dp(14));
        canvas.scale(scale, scale);
        chatList.draw(canvas);
        final String title = chatTitle.getText().toString();
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "share");
                dir.mkdirs();
                String name = title.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
                java.io.File file = new java.io.File(dir, (name.isEmpty() ? "conversation" : name) + ".png");
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                }
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".share", file);
                Intent send = new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ui.post(() -> startActivity(Intent.createChooser(send, "Save this conversation")));
            } catch (Exception error) {
                ui.post(() -> Kit.sheet(this, "The conversation could not be saved", "The picture could not be written on this phone."));
            }
        }, "chat-export").start();
    }

    private void exportMarkdown() {
        JSONArray transcript = ChatStore.transcript(this, chatSession);
        String title = chatTitle.getText().toString();
        StringBuilder out = new StringBuilder("# ").append(title).append("\n");
        java.util.List<String> used = new java.util.ArrayList<>();
        for (int i = 0; i < transcript.length(); i++) {
            JSONObject entry = transcript.optJSONObject(i);
            if (entry == null) continue;
            if ("user".equals(entry.optString("role"))) {
                out.append("\n## You\n\n").append(entry.optString("text")).append("\n");
            } else if ("tool_call".equals(entry.optString("type"))) {
                used.add(chatToolTitle(entry.optString("name")));
            } else if ("text".equals(entry.optString("type"))) {
                out.append("\n## Pi assistant\n\n").append(entry.optString("text")).append("\n");
                if (!used.isEmpty()) out.append("\n_Used: ").append(android.text.TextUtils.join(", ", used)).append("_\n");
                used.clear();
            }
        }
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "share");
            dir.mkdirs();
            String name = title.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
            java.io.File file = new java.io.File(dir, (name.isEmpty() ? "conversation" : name) + ".md");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                stream.write(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".share", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/markdown")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Save this conversation"));
        } catch (Exception error) {
            Kit.sheet(this, "The conversation could not be saved", "The file could not be written on this phone.");
        }
    }

    /** A short conversation title from the first message: one line, cut at a word, no ellipsis. */
    private static String titleFrom(String text) {
        String line = text.split("\n", 2)[0].trim();
        if (line.length() <= 40) return line;
        int cut = line.lastIndexOf(' ', 40);
        return line.substring(0, cut > 20 ? cut : 40);
    }

    /** The composer's + drawer. */
    private void openAttachSheet() {
        Kit.sheet(this, "Add to this message", null,
            new Kit.Action(Kit.Icon.PHOTO, "Send image", "Pick a photo for the assistant to see",
                () -> chatAttachPicker.launch(new String[]{"image/*"})),
            new Kit.Action(Kit.Icon.FILE, "Upload file", "A document or any file, saved on the Pi",
                () -> chatAttachPicker.launch(new String[]{"*/*"})));
    }

    // Files waiting to go with the next message: {name, mime, path to a private cached copy}.
    private final java.util.List<JSONObject> chatAttachments = new java.util.ArrayList<>();
    private final androidx.activity.result.ActivityResultLauncher<String[]> chatAttachPicker =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            String name = displayName(uri);
            String mime = getContentResolver().getType(uri);
            java.io.File dir = new java.io.File(getCacheDir(), "chat-attach");
            dir.mkdirs();
            java.io.File copy = new java.io.File(dir, System.currentTimeMillis() + "-" + name.replace('/', '_'));
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(copy)) {
                if (in == null) throw new java.io.IOException("cannot open");
                byte[] block = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(block)) != -1) {
                    total += n;
                    if (total > 8L * 1024 * 1024) throw new java.io.IOException("Choose a file under 8 MB");
                    out.write(block, 0, n);
                }
                chatAttachments.add(new JSONObject().put("name", name)
                    .put("mime", mime == null ? "application/octet-stream" : mime).put("path", copy.getPath()));
                renderChatAttachments();
            } catch (Exception error) {
                copy.delete();
                toast(error.getMessage() == null ? "Could not attach this file" : error.getMessage());
            }
        });

    private void renderChatAttachments() {
        com.google.android.material.chip.ChipGroup group = pageChat.findViewById(R.id.chat_attachments);
        group.removeAllViews();
        for (JSONObject item : new java.util.ArrayList<>(chatAttachments)) {
            com.google.android.material.chip.Chip chip = new com.google.android.material.chip.Chip(this);
            chip.setText(item.optString("name"));
            chip.setChipIconResource(item.optString("mime").startsWith("image/") ? Kit.Icon.PHOTO : Kit.Icon.FILE);
            chip.setCloseIconVisible(true);
            chip.setOnCloseIconClickListener(v -> {
                chatAttachments.remove(item);
                new java.io.File(item.optString("path")).delete();
                renderChatAttachments();
            });
            group.addView(chip);
        }
        group.setVisibility(chatAttachments.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** "model · effort" for this conversation, falling back to the Pi's defaults. */
    private String configSummary() {
        JSONObject entry = convEntry(chatSession);
        String model = entry == null ? "" : entry.optString("model");
        String effort = entry == null ? "" : entry.optString("effort");
        if (model.isEmpty()) model = chatDefaultModel;
        if (effort.isEmpty()) effort = chatDefaultEffort;
        if (model.isEmpty() && effort.isEmpty()) return "Model and effort";
        return model + (!model.isEmpty() && !effort.isEmpty() ? " · " : "") + effort;
    }

    private void beginTitleEdit() {
        if (!chatConvoMode || convEntry(chatSession) == null) return;
        chatTitleEdit.setText(chatTitle.getText());
        chatTitle.setVisibility(View.GONE);
        chatTitleEdit.setVisibility(View.VISIBLE);
        chatTitleEdit.requestFocus();
        chatTitleEdit.setSelection(chatTitleEdit.length());
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.showSoftInput(chatTitleEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    private void saveTitleEdit() {
        String name = chatTitleEdit.getText().toString().trim();
        if (!name.isEmpty() && chatSession != null) {
            ChatStore.patch(this, chatSession, "title", name);
            chatTitle.setText(name);
        }
        chatTitleEdit.setVisibility(View.GONE);
        chatTitle.setVisibility(View.VISIBLE);
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(chatTitleEdit.getWindowToken(), 0);
        chatTitle.clearFocus();
    }

    private org.json.JSONObject convEntry(String id) {
        org.json.JSONArray idx = ChatStore.index(this);
        for (int i = 0; i < idx.length(); i++) {
            org.json.JSONObject o = idx.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    /** The conversation's model and thinking are chosen in the same sheet Settings uses. */
    private void openConfigDialog() { openModelSheet("chat"); }

    private void updateConfigSubtitle() {
        chatSubtitle.setTextAppearance(R.style.Kit_Text_PageSub);
        // The model lives in the message box, next to Send, where it is chosen. The line under the title counts messages.
        String summary = configSummary();
        ((TextView) pageChat.findViewById(R.id.chat_model_pill)).setText(
            "Model and effort".equals(summary) ? "Model" : summary.replace(" · ", " "));
        int said = 0;
        JSONArray transcript = chatSession == null ? new JSONArray() : ChatStore.transcript(this, chatSession);
        for (int i = 0; i < transcript.length(); i++) {
            JSONObject turn = transcript.optJSONObject(i);
            if (turn != null && ("user".equals(turn.optString("role")) || "text".equals(turn.optString("type")))) said++;
        }
        chatSubtitle.setText(said == 0 ? "" : said == 1 ? "1 message" : said + " messages");
        chatSubtitle.setVisibility(said == 0 ? View.GONE : View.VISIBLE);
        chatSubtitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
    }

    private void refreshChatConfigSubtitle() {
        String session = chatSession;
        String assist = Prefs.assistIp(this), token = Prefs.token(this);
        if (session == null || assist.isEmpty() || token.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject active = MeshClient.providers(assist, token).optJSONObject("active");
                if (active == null) return;
                String model = active.optString("model");
                String effort = active.optString("effort");
                ui.post(() -> {
                    if (!chatConvoMode || !session.equals(chatSession)) return;
                    chatDefaultModel = model;
                    chatDefaultEffort = effort;
                    updateConfigSubtitle();
                });
            } catch (Exception ignored) { }
        }, "chat-active-model").start();
    }

    private void updateConversationActions() {
        org.json.JSONObject entry = convEntry(chatSession);
        boolean present = chatConvoMode && entry != null;
        chatFavorite.setVisibility(present ? View.VISIBLE : View.GONE);
        chatArchive.setVisibility(present ? View.VISIBLE : View.GONE);
        if (!present) return;
        boolean favorite = entry.optBoolean("favorite");
        boolean archived = entry.optBoolean("archived");
        chatFavorite.setColorFilter(favorite ? accent() : col(R.color.dim));
        chatFavorite.setContentDescription(favorite ? "Unfavorite conversation" : "Favorite conversation");
        chatArchive.setColorFilter(archived ? accent() : col(R.color.dim));
        chatArchive.setContentDescription(archived ? "Unarchive conversation" : "Archive conversation");
    }

    // ---- chat: history list vs one open conversation ----

    private void showChatList() {
        searchResultChat = false;
        chatConvoMode = false;
        chatHistory.setVisibility(View.VISIBLE);
        chatConvo.setVisibility(View.GONE);
        chatBack.setVisibility(View.GONE);
        renderChatTop();
        chatFilterbar.setVisibility(View.VISIBLE);
        chatTitleEdit.setVisibility(View.GONE);
        chatTitle.setVisibility(View.VISIBLE);
        chatEdit.setVisibility(View.GONE);
        updateConversationActions();
        // The top bar already says Chat, so the list leads with how the assistant is instead of a second title.
        chatTitle.setVisibility(View.GONE);
        chatSubtitle.setTextAppearance(R.style.Kit_Text_Section);
        chatSubtitle.setTextSize(18);
        chatSubtitle.setTextColor(col(R.color.text));
        pageChat.findViewById(R.id.chat_presence_dot).setVisibility(View.VISIBLE);
        chatSubtitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        selectChatFilter(chatFilter);
        refreshAgentStatus();
        // The Pi holds the conversations, so ones started on another device show up here too.
        new Thread(() -> {
            if (ChatStore.pull(this)) ui.post(() -> { if (!chatConvoMode) renderHistoryList(); });
        }, "chat-pull").start();
    }

    private LinearLayout historyGroup;

    private void renderHistoryList() {
        chatHistoryList.removeAllViews();
        historyGroup = null;
        String q = chatSearch == null ? "" : chatSearch.getText().toString().trim().toLowerCase();
        org.json.JSONArray idx = ChatStore.index(this);
        int shown = 0;
        for (int i = 0; i < idx.length(); i++) {
            org.json.JSONObject o = idx.optJSONObject(i);
            if (o == null) continue;
            final boolean archived = o.optBoolean("archived");
            final boolean fav = o.optBoolean("favorite");
            if (archived != showArchived) continue;
            if (showFavOnly && !fav) continue;
            final String id = o.optString("id");
            final String title = o.optString("title").isEmpty() ? "(untitled)" : o.optString("title");
            if (!q.isEmpty() && !title.toLowerCase().contains(q)) continue;
            long updated = o.optLong("updated");
            int messages = 0;
            JSONArray transcript = ChatStore.transcript(this, id);
            for (int j = 0; j < transcript.length(); j++) {
                JSONObject turn = transcript.optJSONObject(j);
                if (turn != null && ("user".equals(turn.optString("role")) ||
                    "text".equals(turn.optString("type")))) messages++;
            }
            if (historyGroup == null) historyGroup = Kit.group(chatHistoryList);
            View row = Kit.addRow(historyGroup);
            Kit.bindRow(row, fav ? R.drawable.csi_favorite : Kit.Icon.CHAT, title,
                relTime(updated) + " · " + messages + (messages == 1 ? " message" : " messages"), null, true);
            row.setOnClickListener(v -> openConversation(id, o.optString("title")));
            row.setOnLongClickListener(v -> { chatOptions(id, title, fav, archived); return true; });
            shown++;
        }
        if (shown == 0) {
            TextView e = new TextView(this);
            e.setText(showArchived ? "No archived chats." : (idx.length() == 0 ? "No chats yet. Tap New." : "No matches."));
            e.setTextColor(col(R.color.dim)); e.setTextSize(13); e.setPadding(dp(6), dp(12), 0, 0);
            chatHistoryList.addView(e);
        }
    }

    private void chatOptions(String id, String title, boolean fav, boolean archived) {
        // Rename opens the conversation with its title in inline edit, never a dialog.
        Kit.sheet(this, title, null,
            new Kit.Action(R.drawable.csi_edit, "Rename", null, () -> { openConversation(id, title); beginTitleEdit(); }),
            new Kit.Action(R.drawable.csi_favorite, fav ? "Unfavorite" : "Favorite", null,
                () -> { ChatStore.patch(this, id, "favorite", !fav); renderHistoryList(); }),
            new Kit.Action(R.drawable.csi_archive, archived ? "Unarchive" : "Archive", null,
                () -> { ChatStore.patch(this, id, "archived", !archived); renderHistoryList(); }),
            // Deleting cannot be undone, so it asks first in its own sheet, with a named button.
            new Kit.Action(R.drawable.csi_trash, "Delete", null, () -> Kit.sheet(this, "Delete this conversation?", title,
                new Kit.Action(R.drawable.csi_back, "Keep it", null, () -> { }),
                new Kit.Action(R.drawable.csi_trash, "Delete", "It is removed from this phone",
                    () -> { ChatStore.delete(this, id); renderHistoryList(); toast("Deleted"); }))));
    }

    private void openConversation(String id, String title) {
        chatFind.close();
        chatSession = id;
        chatConvoMode = true;
        chatHistory.setVisibility(View.GONE);
        chatConvo.setVisibility(View.VISIBLE);
        chatBack.setVisibility(View.GONE);
        renderChatTop();
        chatFilterbar.setVisibility(View.GONE);
        chatTitleEdit.setVisibility(View.GONE);
        chatTitle.setVisibility(View.VISIBLE);
        chatTitle.setText(title == null || title.isEmpty() ? "Chat" : title);
        pageChat.findViewById(R.id.chat_presence_dot).setVisibility(View.GONE);
        chatEdit.setVisibility(View.VISIBLE);
        updateConversationActions();
        updateConfigSubtitle();
        refreshChatConfigSubtitle();
        renderTranscript(ChatStore.transcript(this, id));
        scrollDown();
    }

    private void newConversation() {
        chatFind.close();
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();
        chatConvoMode = true;
        chatHistory.setVisibility(View.GONE);
        chatConvo.setVisibility(View.VISIBLE);
        chatBack.setVisibility(View.GONE);
        renderChatTop();
        chatFilterbar.setVisibility(View.GONE);
        chatTitleEdit.setVisibility(View.GONE);
        chatTitle.setVisibility(View.VISIBLE);
        chatTitle.setText("New chat");
        pageChat.findViewById(R.id.chat_presence_dot).setVisibility(View.GONE);
        chatEdit.setVisibility(View.GONE);
        updateConversationActions();
        updateConfigSubtitle();
        refreshChatConfigSubtitle();
        chatList.removeAllViews(); chatPending = null;
        writingBox = null; writingText = null; queuedViews.clear(); workBody = null;
        selectedMessageActions = null; selectedMessageBubble = null;
        updateSendButton();
        chatInput.requestFocus();
    }

    private void renderTranscript(org.json.JSONArray tr) {
        chatList.removeAllViews(); chatPending = null;
        writingBox = null; writingText = null; queuedViews.clear(); workBody = null;
        selectedMessageActions = null; selectedMessageBubble = null;
        for (int i = 0; i < tr.length(); i++) {
            org.json.JSONObject o = tr.optJSONObject(i);
            if (o == null) continue;
            if ("user".equals(o.optString("role"))) addUserBubble(o.optString("text"), i);
            else renderSingleTurn(o, i);
        }
        // Opened while its reply is still coming: show how far the answer has got, and offer Stop.
        if (chatSession != null && ChatService.running.contains(chatSession)) {
            if (ChatService.writing.containsKey(chatSession)) showWriting();
            else chatPending = addPending();
        }
        renderQueued();
        updateSendButton();
        chatFind.refresh();
    }

    private void refreshAgentStatus() {
        final String ip = Prefs.assistIp(this);
        View dot = pageChat.findViewById(R.id.chat_presence_dot);
        if (ip.isEmpty()) { chatSubtitle.setText("Set the assistant in Settings"); setDot(dot, false); return; }
        chatSubtitle.setText("Checking the Pi assistant");
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(col(R.color.offline)));
        new Thread(() -> {
            boolean up = MeshClient.reachable(ip, MeshClient.ASSIST_PORT);
            ui.post(() -> {
                if (chatConvoMode) return;
                chatSubtitle.setText(up ? "The Pi assistant is online" : "The Pi assistant is offline");
                setDot(dot, up);
            });
        }).start();
    }

    private String relTime(long t) {
        if (t <= 0) return "";
        long d = System.currentTimeMillis() - t;
        if (d < 60000) return "just now";
        if (d < 3600000) return (d / 60000) + "m ago";
        if (d < 86400000) return (d / 3600000) + "h ago";
        return (d / 86400000) + "d ago";
    }

    private TextView chatPending;
    private LinearLayout selectedMessageActions;
    private View selectedMessageBubble;

    // Hand the request to ChatService (a foreground service with a wakelock) so a
    // reply that lands after the app is backgrounded still completes and can
    // notify. The reply comes back via chatReceiver, or is drained from the
    // service's stash on the next resume.
    private void sendChat() {
        final String typed = chatInput.getText().toString().trim();
        if (typed.isEmpty() && chatAttachments.isEmpty()) {
            // With nothing typed while a reply is being written, the button is Stop.
            if (chatSession != null && ChatService.running.contains(chatSession)) stopReply();
            return;
        }
        StringBuilder names = new StringBuilder();
        for (JSONObject item : chatAttachments) names.append(names.length() == 0 ? "" : ", ").append(item.optString("name"));
        final String msg = names.length() == 0 ? typed : (typed.isEmpty() ? "" : typed + "\n\n") + "Attached: " + names;
        if (Prefs.assistIp(this).isEmpty() || Prefs.token(this).isEmpty()) { toast("Set the assistant and token in Settings"); return; }
        if (chatSession == null || !chatConvoMode) newConversation();
        JSONObject ask = new JSONObject();
        try {
            ask.put("typed", typed).put("shown", msg).put("title", titleFrom(typed.isEmpty() ? names.toString() : typed));
            if (!chatAttachments.isEmpty()) ask.put("attachments", new org.json.JSONArray(chatAttachments).toString());
        } catch (org.json.JSONException impossible) { return; }
        chatAttachments.clear();
        renderChatAttachments();
        chatInput.setText("");
        // A message sent while a reply is still being written waits its turn.
        if (ChatService.running.contains(chatSession)) {
            queued(chatSession).add(ask);
            renderQueued();
            scrollDown();
            return;
        }
        startReply(chatSession, ask);
    }

    /** Show your message and ask the Pi for the reply. Works for a conversation that is not the one on screen. */
    private void startReply(String session, JSONObject ask) {
        boolean open = chatConvoMode && session.equals(chatSession);
        String shown = ask.optString("shown"), title = ask.optString("title");
        if (open) addUserBubble(shown, ChatStore.transcript(this, session).length());
        try { ChatStore.append(this, session, title, new JSONObject().put("role", "user").put("text", shown)); }
        catch (Throwable ignore) {}
        org.json.JSONObject ce = convEntry(session);
        Intent svc = new Intent(this, ChatService.class);
        svc.putExtra("assist", Prefs.assistIp(this)); svc.putExtra("token", Prefs.token(this));
        svc.putExtra("session", session); svc.putExtra("message", ask.optString("typed"));
        if (ask.has("attachments")) svc.putExtra("attachments", ask.optString("attachments"));
        if (ce != null) { svc.putExtra("model", ce.optString("model")); svc.putExtra("effort", ce.optString("effort")); }
        ChatService.running.add(session);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        if (!open) return;
        updateConversationActions();
        if ("New chat".contentEquals(chatTitle.getText())) chatTitle.setText(title);
        chatEdit.setVisibility(View.VISIBLE);
        if (chatPending != null) chatList.removeView(chatPending);
        chatPending = addPending();
        renderQueued();
        updateSendButton();
    }

    // ---- stop, and messages waiting for the reply to end ----

    private final java.util.Map<String, java.util.List<JSONObject>> chatQueue = new java.util.HashMap<>();
    private final java.util.List<View> queuedViews = new java.util.ArrayList<>();

    private java.util.List<JSONObject> queued(String session) {
        java.util.List<JSONObject> waiting = chatQueue.get(session);
        if (waiting == null) { waiting = new java.util.ArrayList<>(); chatQueue.put(session, waiting); }
        return waiting;
    }

    /** Ask the Pi to stop writing. What has arrived is kept, and messages waiting to be sent are dropped. */
    private void stopReply() {
        final String session = chatSession, ip = Prefs.assistIp(this), token = Prefs.token(this);
        queued(session).clear();
        renderQueued();
        new Thread(() -> {
            try { MeshClient.conversations(ip, token, "POST", "/chat/stop", new JSONObject().put("session", session)); }
            catch (Throwable failed) { ui.post(() -> toast("The Pi did not stop the reply")); }
        }, "chat-stop").start();
    }

    /** Draw the waiting messages under everything else, so they always sit where they will be sent from. */
    private void renderQueued() {
        for (View view : queuedViews) chatList.removeView(view);
        queuedViews.clear();
        if (chatSession == null || !chatConvoMode) return;
        for (JSONObject ask : queued(chatSession)) {
            LinearLayout waiting = new LinearLayout(this);
            waiting.setOrientation(LinearLayout.VERTICAL);
            waiting.setGravity(android.view.Gravity.END);
            TextView text = new TextView(this);
            text.setText(ask.optString("shown"));
            text.setTextColor(col(R.color.dim));
            text.setTextSize(14);
            text.setPadding(dp(13), dp(10), dp(13), dp(10));
            text.setBackgroundResource(R.drawable.card_bg);
            text.setMaxWidth(Math.min(dp(260), getResources().getDisplayMetrics().widthPixels - dp(80)));
            waiting.addView(text, new LinearLayout.LayoutParams(-2, -2));
            TextView label = new TextView(this);
            label.setText("Waiting for this reply to end");
            label.setTextColor(col(R.color.dim));
            label.setTextSize(11);
            label.setPadding(0, dp(3), dp(4), 0);
            waiting.addView(label, new LinearLayout.LayoutParams(-2, -2));
            addTo(waiting, android.view.Gravity.END, 12);
            queuedViews.add(waiting);
        }
    }

    /** The reply ended: send the message that has waited longest. */
    private void nextQueued(String session) {
        if (ChatService.running.contains(session) || queued(session).isEmpty()) return;
        startReply(session, queued(session).remove(0));
    }

    /** The reply failed, so sending more would fail too: the waiting messages go back into the box. */
    private void returnQueued(String session) {
        java.util.List<JSONObject> waiting = queued(session);
        if (waiting.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        for (JSONObject ask : waiting) text.append(text.length() == 0 ? "" : "\n\n").append(ask.optString("typed"));
        waiting.clear();
        if (!chatConvoMode || !session.equals(chatSession)) return;
        renderQueued();
        chatInput.setText(text);
        chatInput.setSelection(chatInput.length());
    }

    /** Send turns into Stop while a reply is being written and the box is empty. */
    private void updateSendButton() {
        android.widget.ImageView send = pageChat.findViewById(R.id.chat_send);
        boolean stop = chatConvoMode && chatSession != null && ChatService.running.contains(chatSession)
            && chatInput.getText().toString().trim().isEmpty() && chatAttachments.isEmpty();
        send.setImageResource(stop ? R.drawable.csi_stop : R.drawable.csi_send);
        send.setContentDescription(stop ? "Stop this reply" : "Send");
        send.setImageTintList(android.content.res.ColorStateList.valueOf(col(stop ? R.color.text : R.color.onAccent)));
        if (stop) send.setBackground(bg(col(R.color.surface2), 22));
        else send.setBackgroundResource(R.drawable.chat_send_bg);
    }

    // ---- the answer while it is still being written ----

    private View writingBox;
    private TextView writingText;

    private void showWriting() {
        StringBuilder soFar = ChatService.writing.get(chatSession);
        if (soFar == null) return;
        String text;
        synchronized (soFar) { text = soFar.toString(); }
        if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
        if (writingText == null) {
            closeWork();
            LinearLayout box = new LinearLayout(this);
            box.setBackground(bg(col(R.color.surface2), 16));
            box.setPadding(dp(13), dp(11), dp(13), dp(11));
            writingText = new TextView(this);
            writingText.setTextColor(col(R.color.text));
            writingText.setTextSize(14);
            writingText.setLineSpacing(0, 1.2f);
            box.addView(writingText);
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-1, -2);
            at.topMargin = dp(12);
            chatList.addView(box, at);
            writingBox = box;
        }
        writingText.setText(text);
        scrollDown();
    }

    private void clearWriting() {
        if (writingBox != null) chatList.removeView(writingBox);
        writingBox = null;
        writingText = null;
    }

    // Render a reply from ChatService, live or stashed, clearing the placeholder.
    private void deliverReply(String turnsStr, String err) {
        if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
        if (turnsStr != null) {
            try { renderTurns(new org.json.JSONArray(turnsStr)); }
            catch (Throwable e) { addError("bad reply: " + e.getMessage()); }
        } else if (err != null) {
            addError(err);
        }
        scrollDown();
    }

    private final android.content.BroadcastReceiver chatReceiver = new android.content.BroadcastReceiver() {
        public void onReceive(android.content.Context c, Intent i) {
            String s = i.getStringExtra("session");
            if (s == null) return;
            String turn = i.getStringExtra("turn");
            String err = i.getStringExtra("error");
            boolean done = i.getBooleanExtra("done", false);
            // Every chat persists to its own store; only the open one renders live.
            if (chatConvoMode && s.equals(chatSession)) {
                if (turn != null) {
                    clearWriting();
                    try { renderSingleTurn(new org.json.JSONObject(turn),
                        ChatStore.transcript(MainActivity.this, chatSession).length() - 1); } catch (Throwable e) {}
                } else if (i.getBooleanExtra("writing", false)) {
                    showWriting();
                } else if (err != null || done) {
                    if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
                    clearWriting();
                    if (err != null) { addError(err); scrollDown(); }
                }
                renderQueued();
                updateSendButton();
            }
            if (err != null) returnQueued(s);
            else if (done) nextQueued(s);
        }
    };

    private void renderTurns(org.json.JSONArray turns) {
        int first = ChatStore.transcript(this, chatSession).length() - turns.length();
        for (int i = 0; i < turns.length(); i++) renderSingleTurn(turns.optJSONObject(i), first + i);
    }

    // Render one turn as it streams in: thinking and tool calls collapse, text
    // shows as selectable markdown with message actions, and a tool result carrying a media_url
    // renders the captured image inline.
    private void renderSingleTurn(org.json.JSONObject t, int transcriptIndex) {
        if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
        if (t == null) return;
        String type = t.optString("type");
        if ("thinking".equals(type)) {
            String txt = t.optString("text");
            workStep(false);
            addCollapsible(workBody, "Thinking", firstLine(txt), txt);
        } else if ("tool_call".equals(type)) {
            org.json.JSONObject res = t.optJSONObject("result");
            String toolName = t.optString("name");
            workStep(true);
            addCollapsible(workBody, chatToolTitle(toolName), toolPreview(t), toolBody(t));
            if (res != null && !res.optString("media_url").isEmpty())
                addImage(res.optString("media_url"), res.optString("media_type"));
            else addResultCard(toolName, res);
        } else if ("text".equals(type)) {
            closeWork();
            addMarkdown(t.optString("text"), transcriptIndex);
            if (t.optBoolean("stopped")) {
                TextView note = new TextView(this);
                note.setText("Stopped before it finished");
                note.setTextColor(col(R.color.dim));
                note.setTextSize(12);
                note.setPadding(dp(4), dp(4), 0, 0);
                chatList.addView(note);
            }
        }
        scrollDown();
    }

    // ---- the work strip: everything the assistant did before it answered, folded into one line ----

    private LinearLayout workBody;
    private TextView workHead;
    private int workTools, workSteps;

    /** Count one more step under the current reply's strip, starting the strip if this is the first. */
    private void workStep(boolean tool) {
        if (workBody == null) {
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(this);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setMinimumHeight(dp(48));
            final android.widget.ImageView caret = new android.widget.ImageView(this);
            caret.setImageResource(R.drawable.csi_forward);
            caret.setColorFilter(col(R.color.dim));
            caret.setPadding(dp(8), dp(16), dp(8), dp(16));
            head.addView(caret, new LinearLayout.LayoutParams(dp(32), dp(48)));
            workHead = new TextView(this);
            workHead.setTextColor(col(R.color.dim));
            workHead.setTextSize(13);
            head.addView(workHead);
            final LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(View.GONE);
            head.setOnClickListener(v -> {
                boolean open = body.getVisibility() != View.VISIBLE;
                body.setVisibility(open ? View.VISIBLE : View.GONE);
                caret.setRotation(open ? 90f : 0f);
            });
            strip.addView(head);
            strip.addView(body);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(6);
            chatList.addView(strip, lp);
            workBody = body;
            workTools = 0;
            workSteps = 0;
        }
        workSteps++;
        if (tool) workTools++;
        workHead.setText("Working, " + workSummary());
    }

    private String workSummary() {
        return workTools == 0 ? "thinking" : workTools == 1 ? "1 tool" : workTools + " tools";
    }

    /** The answer has arrived: the strip stops saying Working and the next reply starts a new one. */
    private void closeWork() {
        if (workBody != null) workHead.setText(workTools == 0 ? "Thought it through" : "Used " + workSummary());
        workBody = null;
    }

    /**
     * What a tool gave back, drawn so it can be read: devices and drives as a list with a status,
     * anything else as a few labelled facts. A result with nothing readable adds no card; the raw
     * reply stays available inside the work strip.
     */
    private void addResultCard(String tool, org.json.JSONObject result) {
        if (result == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(bg(col(R.color.surface2), 14));
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextView top = new TextView(this);
        top.setText(chatToolTitle(tool));
        top.setTextColor(col(R.color.dim));
        top.setTextSize(12);
        card.addView(top);
        int rows = 0;
        java.util.Iterator<String> keys = result.keys();
        while (keys.hasNext() && rows < 8) {
            String key = keys.next();
            Object value = result.opt(key);
            if (value instanceof org.json.JSONArray) {
                org.json.JSONArray list = (org.json.JSONArray) value;
                for (int i = 0; i < list.length() && rows < 8; i++) {
                    org.json.JSONObject item = list.optJSONObject(i);
                    if (item == null) continue;
                    String name = item.optString("label", item.optString("name"));
                    if (name.isEmpty() || !item.has("online")) continue;
                    boolean up = item.optBoolean("online");
                    // A drive is connected or not; a device is online or not. Same dot, the right word.
                    boolean drive = "media_drives".equals(tool);
                    card.addView(resultLine(name, up ? (drive ? "Connected" : "Online") : (drive ? "Disconnected" : "Offline"),
                        up ? Kit.Status.GOOD : Kit.Status.IDLE));
                    rows++;
                }
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                String words = String.valueOf(value);
                if (words.isEmpty() || words.length() > 60 || "ok".equals(key)) continue;
                card.addView(resultLine(prettyName(key), value instanceof Boolean ? ((Boolean) value ? "Yes" : "No") : words, null));
                rows++;
            }
        }
        if (rows == 0) return;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        chatList.addView(card, lp);
    }

    private View resultLine(String name, String value, Kit.Status status) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, 0);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextColor(col(R.color.text));
        label.setTextSize(14);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        if (status != null) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable round = new android.graphics.drawable.GradientDrawable();
            round.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            round.setColor(Kit.statusColor(this, status));
            dot.setBackground(round);
            LinearLayout.LayoutParams dotAt = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotAt.rightMargin = dp(6);
            line.addView(dot, dotAt);
        }
        TextView words = new TextView(this);
        words.setText(value);
        words.setTextColor(col(R.color.dim));
        words.setTextSize(13);
        line.addView(words);
        return line;
    }

    private void resetChat() {
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();
        chatList.removeAllViews();
        if (!ip.isEmpty() && !token.isEmpty()) {
            final String session = chatSession;
            new Thread(() -> { try { MeshClient.chatReset(ip, token, session); } catch (Throwable ignore) {} }).start();
        }
        toast("New conversation");
    }

    // Markwon with code syntax highlighting, themed light or dark to match the app.
    private io.noties.markwon.Markwon buildMarkwon() {
        io.noties.prism4j.Prism4j prism = new io.noties.prism4j.Prism4j(new GrammarLocatorDef());
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        io.noties.markwon.syntax.Prism4jTheme theme = night
                ? io.noties.markwon.syntax.Prism4jThemeDarkula.create()
                : io.noties.markwon.syntax.Prism4jThemeDefault.create();
        return io.noties.markwon.Markwon.builder(this)
                .usePlugin(io.noties.markwon.syntax.SyntaxHighlightPlugin.create(prism, theme))
                .build();
    }

    // ---- chat view builders ----
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int col(int res) { return androidx.core.content.ContextCompat.getColor(this, res); }
    private int accent() { android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true); return tv.data; }
    private android.graphics.drawable.GradientDrawable bg(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private void addTo(View v, int gravity, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity; lp.topMargin = dp(topMargin); v.setLayoutParams(lp); chatList.addView(v); }
    // Scroll to the bottom WITHOUT fullScroll, which would move focus to the last
    // view and drop the keyboard off the input while a reply streams in.
    private void scrollDown() {
        if (!chatFollowing) { if (chatJump != null) chatJump.setVisibility(View.VISIBLE); return; }
        chatScroll.post(() -> chatScroll.smoothScrollTo(0, chatList.getBottom()));
    }

    // Reading back through a long reply: the view stops following once you scroll up, and a row offers the way back.
    private boolean chatFollowing = true;
    private TextView chatJump;
    private ChatFind chatFind;
    private ChatSuggest chatSuggest;

    private void setupChatFollowing() {
        chatJump = new TextView(this);
        chatJump.setText("Jump to the latest");
        chatJump.setTextColor(accent());
        chatJump.setTextSize(13);
        chatJump.setGravity(android.view.Gravity.CENTER);
        chatJump.setMinHeight(dp(48));
        chatJump.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_chevron_down, 0, 0, 0);
        chatJump.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(accent()));
        chatJump.setCompoundDrawablePadding(dp(6));
        chatJump.setVisibility(View.GONE);
        chatJump.setOnClickListener(v -> { chatFollowing = true; chatJump.setVisibility(View.GONE); scrollDown(); });
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
        at.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        chatConvo.addView(chatJump, chatConvo.indexOfChild(pageChat.findViewById(R.id.chat_suggest)), at);
        chatScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            boolean atEnd = chatList.getBottom() - (y + chatScroll.getHeight()) < dp(96);
            if (atEnd) { chatFollowing = true; chatJump.setVisibility(View.GONE); }
            else if (y < oldY) chatFollowing = false;
        });
    }

    private void addUserBubble(String text, int transcriptIndex) {
        workBody = null;
        // Sending, or opening a conversation, always lands on the newest message.
        chatFollowing = true;
        if (chatJump != null) chatJump.setVisibility(View.GONE);
        TextView tv = new TextView(this); markwon.setMarkdown(tv, text);
        ChatFind.mark(tv);
        tv.setTextColor(col(R.color.onAccent)); tv.setTextIsSelectable(true);
        tv.setLinkTextColor(col(R.color.onAccent));
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tv.setTextSize(14); tv.setPadding(dp(13), dp(10), dp(13), dp(10)); tv.setBackground(bg(accent(), 16));
        tv.setMaxWidth(Math.min(dp(260), getResources().getDisplayMetrics().widthPixels - dp(80)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);
        row.addView(tv);
        onMessageTap(tv, () -> showMessageActions(row, text, transcriptIndex, true));
        addTo(row, android.view.Gravity.END, 12); scrollDown();
    }
    private TextView addPending() {
        TextView tv = new TextView(this); tv.setText("Thinking"); tv.setTextColor(col(R.color.dim));
        tv.setTextSize(13); tv.setPadding(dp(12), dp(9), dp(12), dp(9)); tv.setBackground(bg(col(R.color.surface2), 14));
        addTo(tv, android.view.Gravity.START, 12); scrollDown(); return tv;
    }
    private void addMarkdown(String md, int transcriptIndex) {
        final String text = md == null ? "" : md;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(col(R.color.surface2), 16)); box.setPadding(dp(13), dp(11), dp(13), dp(11));
        // Prose and code are drawn apart: code keeps its line breaks, scrolls sideways and has its own Copy.
        String[] pieces = text.split("```", -1);
        for (int i = 0; i < pieces.length; i++) {
            boolean code = i % 2 == 1 && i < pieces.length - 1;
            String piece = i % 2 == 1 && !code ? "```" + pieces[i] : pieces[i];
            if (piece.trim().isEmpty()) continue;
            if (code) { box.addView(codeBlock(piece)); continue; }
            TextView tv = new TextView(this); markwon.setMarkdown(tv, piece.trim());
            ChatFind.mark(tv);
            tv.setTextColor(col(R.color.text)); tv.setTextSize(14); tv.setLineSpacing(0, 1.2f);
            tv.setTextIsSelectable(true);
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            box.addView(tv);
            onMessageTap(tv, () -> showMessageActions(box, text, transcriptIndex, false));
        }
        box.setOnClickListener(v -> showMessageActions(box, text, transcriptIndex, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(12); box.setLayoutParams(lp); chatList.addView(box);
    }

    /**
     * One fenced block from a reply: the language and a Copy button on top, the code under it,
     * coloured and scrolling sideways so a long line is never wrapped mid-word.
     */
    private View codeBlock(String fenced) {
        int firstBreak = fenced.indexOf('\n');
        String language = firstBreak < 0 ? "" : fenced.substring(0, firstBreak).trim();
        final String code = (firstBreak < 0 ? fenced : fenced.substring(firstBreak + 1)).replaceAll("\\s+$", "");
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackground(bg(col(R.color.bg), 10));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(language.isEmpty() ? "Code" : language);
        name.setTextColor(col(R.color.dim));
        name.setTextSize(12);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.ImageView copy = new android.widget.ImageView(this);
        copy.setImageResource(R.drawable.csi_copy);
        copy.setColorFilter(col(R.color.dim));
        copy.setPadding(dp(14), dp(14), dp(14), dp(14));
        copy.setContentDescription("Copy this code");
        copy.setBackgroundResource(android.R.drawable.list_selector_background);
        copy.setOnClickListener(v -> { copyText(code); toast("Copied"); });
        head.addView(copy, new LinearLayout.LayoutParams(dp(48), dp(48)));
        block.addView(head);
        android.widget.HorizontalScrollView across = new android.widget.HorizontalScrollView(this);
        across.setHorizontalScrollBarEnabled(false);
        TextView body = new TextView(this);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(12.5f);
        body.setTextColor(col(R.color.text));
        body.setTextIsSelectable(true);
        body.setPadding(dp(12), 0, dp(12), dp(12));
        // Set as plain text: the block is its own card, and the markdown renderer would draw a second box inside it.
        body.setText(code);
        ChatFind.mark(body);
        across.addView(body);
        block.addView(across);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        block.setLayoutParams(lp);
        return block;
    }

    /**
     * Selectable text swallows ordinary clicks, so a single tap is read from the raw
     * touches instead. Long-press still selects text, and a tap on a link still opens it.
     */
    private void onMessageTap(TextView message, Runnable tapped) {
        android.view.GestureDetector taps = new android.view.GestureDetector(this,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                    if (!onLink(message, e)) tapped.run();
                    return false;
                }
            });
        message.setOnTouchListener((v, event) -> { taps.onTouchEvent(event); return false; });
    }

    private static boolean onLink(TextView view, android.view.MotionEvent e) {
        if (!(view.getText() instanceof android.text.Spanned) || view.getLayout() == null) return false;
        int x = (int) e.getX() - view.getTotalPaddingLeft() + view.getScrollX();
        int y = (int) e.getY() - view.getTotalPaddingTop() + view.getScrollY();
        int line = view.getLayout().getLineForVertical(y);
        int offset = view.getLayout().getOffsetForHorizontal(line, x);
        return ((android.text.Spanned) view.getText()).getSpans(offset, offset,
            android.text.style.ClickableSpan.class).length > 0;
    }

    private void showMessageActions(View bubble, String source, int transcriptIndex, boolean mine) {
        boolean closing = selectedMessageBubble == bubble;
        if (selectedMessageActions != null) chatList.removeView(selectedMessageActions);
        selectedMessageActions = null;
        selectedMessageBubble = null;
        if (closing) return;
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.ImageView copy = new android.widget.ImageView(this);
        copy.setImageResource(R.drawable.csi_copy); copy.setContentDescription("Copy message");
        copy.setColorFilter(col(R.color.dim)); copy.setPadding(dp(13), dp(13), dp(13), dp(13));
        copy.setBackgroundResource(android.R.drawable.list_selector_background);
        actions.addView(copy, new LinearLayout.LayoutParams(dp(48), dp(48)));
        copy.setOnClickListener(v -> { copyText(source); toast("Copied"); });
        android.widget.ImageView fork = new android.widget.ImageView(this);
        fork.setImageResource(R.drawable.csi_fork); fork.setContentDescription("Preview fork through this message");
        fork.setColorFilter(col(R.color.dim)); fork.setPadding(dp(13), dp(13), dp(13), dp(13));
        fork.setBackgroundResource(android.R.drawable.list_selector_background);
        actions.addView(fork, new LinearLayout.LayoutParams(dp(48), dp(48)));
        fork.setOnClickListener(v -> previewFork(transcriptIndex));
        // Your own message can be edited and sent again; an answer can be asked for again.
        android.widget.ImageView again = new android.widget.ImageView(this);
        again.setImageResource(mine ? R.drawable.csi_edit : R.drawable.csi_refresh);
        again.setContentDescription(mine ? "Edit and send again" : "Regenerate this answer");
        again.setColorFilter(col(R.color.dim)); again.setPadding(dp(13), dp(13), dp(13), dp(13));
        again.setBackgroundResource(android.R.drawable.list_selector_background);
        actions.addView(again, new LinearLayout.LayoutParams(dp(48), dp(48)));
        again.setOnClickListener(v -> rewindTo(transcriptIndex, !mine));
        // What an answer cost sits quietly beside its actions, for the times you want to know.
        JSONObject entry = mine || chatSession == null ? null
            : ChatStore.transcript(this, chatSession).optJSONObject(transcriptIndex);
        String cost = entry == null ? "" : usageLine(entry.optJSONObject("usage"));
        if (!cost.isEmpty()) {
            TextView usage = new TextView(this);
            usage.setText(cost);
            usage.setTextColor(col(R.color.dim));
            usage.setTextSize(12);
            usage.setGravity(android.view.Gravity.CENTER_VERTICAL);
            usage.setPadding(dp(6), 0, dp(6), 0);
            actions.addView(usage, new LinearLayout.LayoutParams(-2, dp(48)));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        actions.setLayoutParams(params);
        chatList.addView(actions, chatList.indexOfChild(bubble) + 1);
        selectedMessageActions = actions;
        selectedMessageBubble = bubble;
    }

    /** "1,204 in, 388 out · 6.2 s · gpt-6.1-sol", leaving out whatever the provider did not report. */
    private static String usageLine(JSONObject usage) {
        if (usage == null) return "";
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (usage.has("input_tokens") || usage.has("output_tokens"))
            parts.add(String.format(java.util.Locale.US, "%,d in, %,d out",
                usage.optLong("input_tokens"), usage.optLong("output_tokens")));
        if (usage.optLong("ms") > 0)
            parts.add(String.format(java.util.Locale.US, "%.1f s", usage.optLong("ms") / 1000.0));
        if (!usage.optString("model").isEmpty()) parts.add(usage.optString("model"));
        return android.text.TextUtils.join(" · ", parts);
    }

    /**
     * Cut the conversation back to the message at or before this entry that you wrote, on the Pi
     * and on this phone, and put that message in the box. With resend it goes straight out again,
     * which is Regenerate; without, it waits to be edited.
     */
    private void rewindTo(int transcriptIndex, boolean resend) {
        if (chatSession == null) return;
        final String session = chatSession;
        JSONArray transcript = ChatStore.transcript(this, session);
        int at = Math.min(transcriptIndex, transcript.length() - 1);
        while (at >= 0 && !"user".equals(transcript.optJSONObject(at).optString("role"))) at--;
        if (at < 0) return;
        final int keep = at;
        final String text = transcript.optJSONObject(at).optString("text");
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        new Thread(() -> {
            String failed = null;
            try { MeshClient.conversations(ip, token, "POST", "/conversations/" + session + "/rewind", new JSONObject().put("keep", keep)); }
            catch (Throwable e) { failed = e.getMessage(); }
            final String problem = failed;
            ui.post(() -> {
                if (problem != null) { Kit.sheet(this, "The conversation could not be rewound", "The Pi assistant did not answer. Nothing was changed."); return; }
                if (!session.equals(chatSession)) return;
                ChatStore.truncate(this, session, keep);
                renderTranscript(ChatStore.transcript(this, session));
                chatInput.setText(text);
                chatInput.setSelection(chatInput.length());
                if (resend) sendChat();
                else chatInput.requestFocus();
            });
        }, "chat-rewind").start();
    }

    private void previewFork(int transcriptIndex) {
        if (chatSession == null) return;
        org.json.JSONArray transcript = ChatStore.transcript(this, chatSession);
        ScrollView scroll = new ScrollView(this);
        LinearLayout context = new LinearLayout(this);
        context.setOrientation(LinearLayout.VERTICAL);
        context.setPadding(dp(16), dp(10), dp(16), dp(10));
        scroll.addView(context);
        for (int i = 0; i < transcript.length() && i <= transcriptIndex; i++) {
            org.json.JSONObject entry = transcript.optJSONObject(i);
            if (entry == null) continue;
            boolean mine = "user".equals(entry.optString("role"));
            if (!mine && !"text".equals(entry.optString("type"))) continue;
            TextView bubble = new TextView(this);
            markwon.setMarkdown(bubble, entry.optString("text"));
            bubble.setTextColor(col(mine ? R.color.onAccent : R.color.text));
            if (mine) bubble.setLinkTextColor(col(R.color.onAccent));
            bubble.setTextIsSelectable(true);
            bubble.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            bubble.setTextSize(14);
            bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
            bubble.setBackground(bg(mine ? accent() : col(R.color.surface2), 16));
            bubble.setMaxWidth(dp(260));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
            params.topMargin = dp(8);
            context.addView(bubble, params);
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Fork preview")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show();
    }

    private void copyText(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("csync", s == null ? "" : s));
    }

    // Render a captured image inline (fetched authed from the assistant's /media),
    // or a tap-to-note for a video clip.
    private void addImage(String mediaUrl, String mediaType) {
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        final String name = mediaName(mediaUrl);
        if (!"image".equals(mediaType)) {
            TextView tv = new TextView(this);
            tv.setText(("video".equals(mediaType) ? "▷  " : "▤  ") + name);
            tv.setTextColor(col(R.color.text)); tv.setTextSize(13);
            tv.setPadding(dp(12), dp(9), dp(12), dp(9)); tv.setBackground(bg(col(R.color.surface2), 12));
            tv.setOnClickListener(v -> openMediaModal(mediaUrl, mediaType, name));
            addTo(tv, android.view.Gravity.START, 12);
            return;
        }
        final android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setAdjustViewBounds(true); iv.setMaxWidth(dp(280));
        iv.setOnClickListener(v -> openMediaModal(mediaUrl, "image", name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(12); iv.setLayoutParams(lp); chatList.addView(iv);
        new Thread(() -> {
            try {
                byte[] b = MeshClient.fetchMedia(ip, token, mediaUrl);
                final android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
                runOnUiThread(() -> { if (bm != null) { iv.setImageBitmap(bm); scrollDown(); } });
            } catch (Throwable ignore) {}
        }).start();
    }

    private String mediaName(String url) {
        if (url == null) return "file";
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(i + 1) : url;
    }

    private String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase() : "";
    }

    // A full-screen viewer for a media file: the path on top with download, pin and
    // close, and a body that adapts to the type (image, selectable syntax-highlighted
    // text or markdown, or a note plus download for anything else).
    private void openMediaModal(final String mediaUrl, final String mediaType, final String name) {
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        final android.app.Dialog d = new android.app.Dialog(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(col(R.color.bg));

        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(6), dp(12)); header.setBackgroundColor(col(R.color.surface));
        TextView path = new TextView(this); path.setText(name); path.setTextColor(col(R.color.text));
        path.setTextSize(13);
        path.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(path);
        final String[] textHolder = {null};
        header.addView(iconBtn(R.drawable.csi_copy, "Copy file text", v -> { if (textHolder[0] != null) { copyText(textHolder[0]); toast("Copied"); } else toast("nothing to copy"); }));
        header.addView(iconBtn(R.drawable.csi_download, "Download file", v -> downloadMedia(ip, token, mediaUrl, name)));
        header.addView(iconBtn(R.drawable.csi_favorite, "Pin file", v -> { addPin(name, mediaUrl, mediaType); toast("Pinned"); }));
        header.addView(iconBtn(R.drawable.csi_close, "Close viewer", v -> d.dismiss()));
        root.addView(header);

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final TextView loading = new TextView(this); loading.setText("Loading"); loading.setTextColor(col(R.color.dim));
        loading.setPadding(dp(16), dp(16), dp(16), dp(16)); body.addView(loading);
        root.addView(body);

        d.setContentView(root);
        if (d.getWindow() != null)
            d.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        d.show();

        new Thread(() -> {
            try {
                final byte[] b = MeshClient.fetchMedia(ip, token, mediaUrl);
                ui.post(() -> {
                    body.removeAllViews();
                    body.addView(buildMediaView(mediaType, name, b, textHolder));
                });
            } catch (Throwable e) {
                ui.post(() -> loading.setText("could not load: " + e.getMessage()));
            }
        }).start();
    }

    private View buildMediaView(String type, String name, byte[] b, String[] textHolder) {
        String e = ext(name);
        boolean image = "image".equals(type) || e.matches("png|jpe?g|gif|webp|bmp");
        if (image) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length));
            iv.setAdjustViewBounds(true);
            ScrollView sv = new ScrollView(this); sv.addView(iv); return sv;
        }
        boolean textual = "markdown".equals(type) || "text".equals(type) || "code".equals(type)
                || e.matches("md|markdown|txt|log|json|ya?ml|toml|xml|csv|sh|bash|py|js|ts|java|kt|go|c|h|cpp|rs|html|css|conf|ini|env");
        if (textual) {
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            ScrollView sv = new ScrollView(this); sv.setPadding(dp(14), dp(12), dp(14), dp(16));
            TextView tv = new TextView(this); tv.setTextColor(col(R.color.text)); tv.setTextSize(13);
            tv.setTextIsSelectable(true);
            if (e.equals("md") || e.equals("markdown") || "markdown".equals(type)) {
                markwon.setMarkdown(tv, s);
                textHolder[0] = s;
            } else {
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                markwon.setMarkdown(tv, "```" + e + "\n" + s.replace("```", "``​`") + "\n```");
                textHolder[0] = s;
            }
            sv.addView(tv); return sv;
        }
        TextView tv = new TextView(this);
        tv.setText(name + "\n\nUse Download to save and open this file.");
        tv.setTextColor(col(R.color.dim)); tv.setTextSize(13); tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        return tv;
    }

    private android.widget.ImageView iconBtn(int drawable, String description, View.OnClickListener onClick) {
        android.widget.ImageView t = new android.widget.ImageView(this);
        t.setImageResource(drawable); t.setColorFilter(col(R.color.text));
        t.setContentDescription(description); t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(bg(col(R.color.surface2), 10)); t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = dp(4); t.setLayoutParams(lp); return t;
    }

    private void downloadMedia(String ip, String token, String mediaUrl, String name) {
        final String fn = name == null || name.isEmpty() ? mediaName(mediaUrl) : name;
        new Thread(() -> {
            try {
                byte[] b = MeshClient.fetchMedia(ip, token, mediaUrl);
                if (Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fn);
                    android.net.Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(b); os.close();
                } else {
                    java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(dir, fn));
                    os.write(b); os.close();
                }
                ui.post(() -> toast("Saved to Downloads: " + fn));
            } catch (Throwable e) { ui.post(() -> toast("Download failed: " + e.getMessage())); }
        }).start();
    }

    private void addPin(String name, String mediaUrl, String mediaType) {
        try {
            android.content.SharedPreferences p = getSharedPreferences("csync_pins", MODE_PRIVATE);
            org.json.JSONArray a = new org.json.JSONArray(p.getString("pins", "[]"));
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("name", name); o.put("media_url", mediaUrl); o.put("media_type", mediaType);
            o.put("ts", System.currentTimeMillis());
            a.put(o); p.edit().putString("pins", a.toString()).apply();
        } catch (Throwable ignore) {}
    }
    // Errors (connection lost, agent down) are shown quietly as a dim centered
    // note rather than a loud red block.
    private void addError(String msg) {
        TextView tv = new TextView(this); tv.setText("· " + (msg == null ? "failed" : msg) + " ·");
        tv.setTextColor(col(R.color.dim)); tv.setTextSize(12); tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10); tv.setLayoutParams(lp); chatList.addView(tv);
    }
    // A collapsible block: a dim header that toggles a body. Used for thinking and tool calls.
    // A collapsed step shows two lines: the label, and a dim preview of the first
    // line of the command or thought. Tapping expands the full body.
    private void addCollapsible(LinearLayout parent, String label, String preview, String body) {
        boolean thinking = "Thinking".equals(label);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(col(R.color.surface2), 12));
        box.setPadding(dp(11), thinking ? dp(3) : dp(7), dp(11), thinking ? dp(3) : dp(7));
        LinearLayout headRow = new LinearLayout(this);
        headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headRow.setMinimumHeight(dp(thinking ? 38 : 44));
        final android.widget.ImageView chevron = new android.widget.ImageView(this);
        chevron.setImageResource(R.drawable.csi_forward);
        chevron.setColorFilter(col(R.color.dim));
        chevron.setPadding(dp(8), dp(14), dp(8), dp(14));
        headRow.addView(chevron, new LinearLayout.LayoutParams(dp(32), dp(thinking ? 38 : 44)));
        final TextView head = new TextView(this); head.setText(label); head.setTextColor(col(R.color.dim)); head.setTextSize(12);
        headRow.addView(head);
        final TextView prev = new TextView(this);
        prev.setTextColor(col(R.color.dim)); prev.setTextSize(12); prev.setAlpha(0.75f);
        prev.setMaxLines(2);
        prev.setPadding(dp(15), dp(2), 0, 0);
        markwon.setMarkdown(prev, preview == null ? "" : preview);
        final TextView bodyV = new TextView(this); bodyV.setTextColor(col(R.color.dim));
        bodyV.setTextSize(12); bodyV.setPadding(dp(2), dp(6), 0, 0); bodyV.setVisibility(View.GONE);
        bodyV.setTextIsSelectable(true);
        markwon.setMarkdown(bodyV, body == null ? "" : body);
        headRow.setOnClickListener(v -> { boolean vis = bodyV.getVisibility() == View.VISIBLE;
            bodyV.setVisibility(vis ? View.GONE : View.VISIBLE);
            prev.setVisibility(vis ? View.VISIBLE : View.GONE);
            chevron.setRotation(vis ? 0f : 90f); });
        box.addView(headRow);
        if (!thinking && preview != null && !preview.isEmpty()) box.addView(prev);
        box.addView(bodyV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(7); box.setLayoutParams(lp); parent.addView(box);
    }

    private String firstLine(String s) {
        if (s == null) return "";
        s = s.trim();
        int nl = s.indexOf('\n');
        return nl >= 0 ? s.substring(0, nl) : s;
    }

    // Wrap text as a fenced code block so command and code output renders monospaced
    // and syntax-coloured rather than as loose prose.
    private String codeFence(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        return "```\n" + s.replace("```", "``​`") + "\n```";
    }

    // The one-line preview for a collapsed tool step: the command it ran, else its
    // arguments, else the first line of the result.
    private String toolPreview(org.json.JSONObject t) {
        if ("list_skills".equals(t.optString("name"))) {
            org.json.JSONObject result = t.optJSONObject("result");
            org.json.JSONArray skills = result == null ? null : result.optJSONArray("skills");
            if (skills != null) return skills.length() + " available skills";
        }
        org.json.JSONObject args = t.optJSONObject("args");
        if (args != null) {
            String cmd = args.optString("command");
            if (!cmd.isEmpty()) return firstLine(cmd);
            if (args.length() > 0) return firstLine(args.toString());
        }
        return firstLine(pretty(t.optJSONObject("result")));
    }
    private String toolBody(org.json.JSONObject t) {
        if ("list_skills".equals(t.optString("name"))) {
            org.json.JSONObject result = t.optJSONObject("result");
            org.json.JSONArray skills = result == null ? null : result.optJSONArray("skills");
            if (skills != null) {
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < skills.length(); i++) {
                    org.json.JSONObject skill = skills.optJSONObject(i);
                    String name = skill == null ? skills.optString(i) : skill.optString("name");
                    if (!name.isEmpty()) summary.append("- ").append(name).append('\n');
                }
                return summary.toString();
            }
        }
        return codeFence(pretty(t.optJSONObject("result")));
    }
    private String pretty(org.json.JSONObject o) {
        if (o == null) return "";
        String r = o.optString("report", o.optString("output", o.optString("error", "")));
        return r.isEmpty() ? o.toString() : r;
    }

    private String clipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            return t == null ? "" : t.toString();
        }
        return "";
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
