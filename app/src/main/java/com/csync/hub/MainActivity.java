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
    private TextView sysStatus;
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
            syncChatHeading(typing);
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

    /** In a conversation the large title steps aside while the keyboard is up, unless it is the title being typed. */
    private void syncChatHeading(boolean typing) {
        if (pageChat == null || chatTitleEdit == null) return;
        boolean renaming = chatTitleEdit.getVisibility() == View.VISIBLE;
        pageChat.findViewById(R.id.chat_heading).setVisibility(
            typing && chatConvoMode && !renaming ? View.GONE : View.VISIBLE);
    }

    private boolean keyboardUp() {
        androidx.core.view.WindowInsetsCompat insets =
            androidx.core.view.ViewCompat.getRootWindowInsets(getWindow().getDecorView());
        return insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
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
            AppUpdater.refreshStatus(this, toolsUpdateStatus);
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
        if (current == 3) AppUpdater.refreshStatus(this, toolsUpdateStatus);
        if (current == 5) cameraController.show();
        miniPlayer.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        keepDraft();
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

    private void setupHomePage() {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        View top = pageHome.findViewById(R.id.home_top);
        Kit.pageTop(top, "home", this::openPlace);
        Kit.topAction(top, Kit.Icon.SEARCH, "Search", v -> openHomeSearch());
        Kit.bindSection(pageHome.findViewById(R.id.home_pickup_head), 0, "Pick up", null);
        // Home leads with doing: the four things done most, each one tap from here.
        Kit.bindAction(pageHome.findViewById(R.id.home_do_play), Kit.Icon.MEDIA, "Play", "Play something from Media",
            v -> startActivity(new Intent(this, MediaActivity.class)));
        Kit.bindAction(pageHome.findViewById(R.id.home_do_ask), Kit.Icon.CHAT, "Ask", "Ask the Pi assistant in a new conversation",
            v -> { nav.setSelectedItemId(R.id.nav_chat); newConversation(); });
        Kit.bindAction(pageHome.findViewById(R.id.home_do_send), Kit.Icon.SHARE, "Send", "Send something to a device",
            v -> nav.setSelectedItemId(R.id.nav_share));
        Kit.bindAction(pageHome.findViewById(R.id.home_do_camera), Kit.Icon.CAMERA, "Camera", "Open the Pi camera",
            v -> { nav.setSelectedItemId(R.id.nav_more); show(5); });
        pageHome.findViewById(R.id.home_status).setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(3); });
        bindHomeStatus(null, false, null);
        renderMore(null);
        showMoreDetail(0);
        buildTools();
        showToolsDetail(0);
        closeSettingsDetail();
    }

    // ---------------- tools ----------------

    private static final String[] TOOL_PARTS = {"Media", "Assistant", "Camera", "Power", "Drives"};
    private final View[] toolRows = new View[TOOL_PARTS.length];
    // What a tap on each part's row explains, in a sentence.
    private final String[] toolFacts = new String[TOOL_PARTS.length];
    private View toolsLeadDot;
    private TextView toolsLeadWords, toolsUpdateStatus;

    /** Tools: one line on how things are, then each part of the Pi, this phone's tools, and the app's own update. */
    private void buildTools() {
        LinearLayout page = pageTools.findViewById(R.id.tools_overview);
        page.removeAllViews();
        LinearLayout lead = new LinearLayout(this);
        lead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        lead.setPadding(dp(2), dp(8), 0, 0);
        toolsLeadDot = new View(this);
        toolsLeadDot.setBackgroundResource(R.drawable.kit_dot);
        lead.addView(toolsLeadDot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        toolsLeadWords = new TextView(this);
        toolsLeadWords.setTextAppearance(R.style.Kit_Text_RowTitle);
        toolsLeadWords.setPadding(dp(9), 0, 0, 0);
        lead.addView(toolsLeadWords);
        page.addView(lead);

        Kit.label(page, "Raspberry Pi");
        LinearLayout group = Kit.group(page);
        int[] icons = {Kit.Icon.MEDIA, Kit.Icon.CHAT, Kit.Icon.CAMERA, R.drawable.csi_alert, Kit.Icon.FILES};
        for (int i = 0; i < TOOL_PARTS.length; i++) {
            int part = i;
            toolRows[i] = Kit.addRow(group);
            Kit.bindRow(toolRows[i], icons[i], TOOL_PARTS[i], null, null, false);
            toolRows[i].setOnClickListener(v -> {
                if (toolFacts[part] != null) Kit.sheet(this, TOOL_PARTS[part], toolFacts[part]);
            });
        }

        Kit.label(page, "This phone");
        group = Kit.group(page);
        Kit.bindRow(Kit.addRow(group), Kit.Icon.DEVICE, "Process monitor", "Memory, processor, temperature", null, true)
            .setOnClickListener(v -> showToolsDetail(1));
        Kit.bindRow(Kit.addRow(group), R.drawable.csi_launcher, "Widgets", "Widgets, tiles, shortcuts and the share menu", null, true)
            .setOnClickListener(v -> showToolsDetail(2));

        Kit.label(page, "This app");
        View update = Kit.addRow(Kit.group(page));
        Kit.bindRow(update, R.drawable.csi_download, "Update csync from the Pi", "Installed: " + appVersion(), null, false);
        toolsUpdateStatus = update.findViewById(R.id.kit_sub);
        update.setOnClickListener(v -> AppUpdater.start(this, toolsUpdateStatus));
    }

    private void setToolsLead(Kit.Status status, String words) {
        Kit.setStatus(toolsLeadDot, status);
        toolsLeadWords.setText(words);
    }

    private void setToolPart(int part, Kit.Status status, String words, String fact) {
        Kit.rowStatus(toolRows[part], status, words);
        toolFacts[part] = fact;
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
        Kit.bindRow(Kit.addRow(group), Kit.Icon.DISPLAY, "Pi screen", "Plays a video, a song or a YouTube link at once", null, false);
        Kit.bindRow(Kit.addRow(group), Kit.Icon.DEVICE, "Last device", "Sends it to the device you sent to last", null, false);
    }

    private void showMoreDetail(int detail) {
        moreDetail = detail;
        pageMore.findViewById(R.id.more_overview).setVisibility(detail == 0 ? View.VISIBLE : View.GONE);
        pageMore.findViewById(R.id.more_guide).setVisibility(detail == 1 ? View.VISIBLE : View.GONE);
        pageMore.findViewById(R.id.more_help).setVisibility(detail == 2 ? View.VISIBLE : View.GONE);
        Kit.pageTop(pageMore.findViewById(R.id.more_crumb), detail == 0 ? "more" : detail == 1 ? "guide" : "help", this::openPlace);
        if (detail == 1) renderGuide();
        if (detail == 2) renderHelp();
    }

    /**
     * More: what lives on the Pi, what looks after things, and what to read.
     * {@code piUp} is null until the Pi has been checked, which leaves Tools without a status.
     */
    private void renderMore(Boolean piUp) {
        LinearLayout page = pageMore.findViewById(R.id.more_rows);
        page.removeAllViews();
        Kit.label(page, "On the Pi");
        LinearLayout group = Kit.group(page);
        Kit.bindRow(Kit.addRow(group), Kit.Icon.CAMERA, "Pi camera", "Live picture, photos and recordings", null, true)
            .setOnClickListener(v -> show(5));
        Kit.bindRow(Kit.addRow(group), Kit.Icon.NOTES, "Notes", "Notes and pins, kept on the Pi", null, true)
            .setOnClickListener(v -> startActivity(new Intent(this, NotesActivity.class)));

        Kit.label(page, "Looking after things");
        group = Kit.group(page);
        View tools = Kit.addRow(group);
        Kit.bindRow(tools, Kit.Icon.TOOLS, "Tools", "Pi health, this phone, updates", null, true);
        if (piUp != null) Kit.rowStatus(tools, piUp ? Kit.Status.GOOD : Kit.Status.IDLE, piUp ? "Ready" : "Offline");
        tools.setOnClickListener(v -> show(3));
        Kit.bindRow(Kit.addRow(group), Kit.Icon.SETTINGS, "Settings", "How the app connects, plays and looks", null, true)
            .setOnClickListener(v -> show(4));

        Kit.label(page, "Reference");
        group = Kit.group(page);
        Kit.bindRow(Kit.addRow(group), R.drawable.csi_help, "Assistant guide", "What you can ask the Pi assistant", null, true)
            .setOnClickListener(v -> showMoreDetail(1));
        Kit.bindRow(Kit.addRow(group), R.drawable.csi_info, "Help and about", "Version " + appVersion(), null, true)
            .setOnClickListener(v -> showMoreDetail(2));
    }

    /** Start a new conversation with an ask already written, so an example can be tried with one tap. */
    private void tryAsk(String ask) {
        ((com.google.android.material.bottomnavigation.BottomNavigationView) findViewById(R.id.nav))
            .setSelectedItemId(R.id.nav_chat);
        newConversation();
        chatInput.setText(ask);
        chatInput.setSelection(chatInput.length());
    }

    /** The guide: three things the assistant is for, each with asks that can be tried as they are. */
    private void renderGuide() {
        LinearLayout page = pageMore.findViewById(R.id.guide_rows);
        page.removeAllViews();
        String[][] parts = {
            {"Ask", "Find a film, ask what is playing, or check how the Pi is doing.",
                "What is on the Pi screen?", "Find the knot tutorials shorter than five minutes", "Is the Pi running hot?"},
            {"Act", "The assistant names the output or the device before it acts, the same way the app does.",
                "Play the newest video on the Pi screen", "Send my last note to my Mac", "Take a photo with the Pi camera"},
            {"Keep", "It can read and write your notes on the Pi."}};
        for (String[] part : parts) {
            Kit.label(page, part[0]);
            TextView words = new TextView(this);
            words.setTextAppearance(R.style.Kit_Text_RowSub);
            words.setText(part[1]);
            words.setPadding(dp(2), 0, 0, dp(8));
            page.addView(words);
            if (part.length == 2) continue;
            LinearLayout group = Kit.group(page);
            for (int i = 2; i < part.length; i++) {
                String ask = part[i];
                Kit.bindRow(Kit.addRow(group), Kit.Icon.CHAT, ask, null, null, true).setOnClickListener(v -> tryAsk(ask));
            }
        }
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-2, -2);
        below.topMargin = dp(18);
        page.addView(Kit.button(this, Kit.Icon.TOOLS, "See what it can use", R.color.text, () -> {
            ((com.google.android.material.bottomnavigation.BottomNavigationView) findViewById(R.id.nav))
                .setSelectedItemId(R.id.nav_chat);
            showChatList();
            selectChatFilter("Tools");
        }), below);
    }

    private void renderHelp() {
        LinearLayout page = pageMore.findViewById(R.id.help_rows);
        page.removeAllViews();
        String[][] parts = {
            {"Where things live", "Media browses the drives and plays on the Pi screen or this phone. Share sends to your devices. "
                + "Chat talks to the Pi assistant. More holds the camera, notes, tools and settings."},
            {"When something does not work", "Open More, then Tools. It shows how the Pi is doing right now. "
                + "If the Pi cannot be reached at all, open Settings, then Connection."}};
        for (String[] part : parts) {
            Kit.label(page, part[0]);
            TextView words = new TextView(this);
            words.setTextColor(col(R.color.text));
            words.setTextSize(14);
            words.setLineSpacing(0, 1.25f);
            words.setPadding(dp(2), 0, 0, 0);
            words.setText(part[1]);
            page.addView(words);
        }
        Kit.label(page, "About");
        LinearLayout group = Kit.group(page);
        String pi = Prefs.assistIp(this);
        Kit.bindRow(Kit.addRow(group), R.drawable.csi_info, "csync", null, appVersion(), false).setClickable(false);
        Kit.bindRow(Kit.addRow(group), Kit.Icon.TOOLS, "Raspberry Pi", null, pi.isEmpty() ? "Not set" : pi, false).setClickable(false);
    }

    private void showToolsDetail(int detail) {
        toolsDetail = detail;
        boolean overview = detail == 0;
        pageTools.findViewById(R.id.tools_overview).setVisibility(overview ? View.VISIBLE : View.GONE);
        pageTools.findViewById(R.id.tools_process_section).setVisibility(detail == 1 ? View.VISIBLE : View.GONE);
        pageTools.findViewById(R.id.tools_widget_section).setVisibility(detail == 2 ? View.VISIBLE : View.GONE);
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
        String host = Prefs.assistIp(this), token = Prefs.token(this);
        if (host.isEmpty() || token.isEmpty()) {
            setToolsLead(Kit.Status.IDLE, "The Pi is not connected");
            for (int i = 0; i < TOOL_PARTS.length; i++)
                setToolPart(i, Kit.Status.IDLE, "Not set", "Set the Pi's address and the token in Settings, under Connection.");
            return;
        }
        setToolsLead(Kit.Status.WARN, "Checking the Pi");
        for (int i = 0; i < TOOL_PARTS.length; i++) setToolPart(i, Kit.Status.IDLE, "Checking", null);
        new Thread(() -> {
            MediaClient client = new MediaClient(host, token);
            boolean media = false, camera = false;
            JSONObject power = null;
            JSONArray drives = null;
            try { media = client.get("/v1/health").optBoolean("ok"); } catch (Exception ignored) { }
            try { power = client.get("/v1/diagnostics").optJSONObject("power"); } catch (Exception ignored) { }
            try { client.get("/v1/camera/status"); camera = true; } catch (Exception ignored) { }
            try { drives = client.get("/v1/drives").optJSONArray("drives"); } catch (Exception ignored) { }
            boolean assistant = MeshClient.reachable(host, MeshClient.ASSIST_PORT);
            final boolean mediaUp = media, cameraUp = camera, assistantUp = assistant;
            final JSONObject currentPower = power;
            final JSONArray listed = drives;
            ui.post(() -> {
                setToolPart(0, mediaUp ? Kit.Status.GOOD : Kit.Status.BAD, mediaUp ? "Ready" : "Not answering",
                    mediaUp ? "The Pi is serving its drives and its screen." : "The part of the Pi that serves drives and the screen did not answer.");
                setToolPart(1, assistantUp ? Kit.Status.GOOD : Kit.Status.BAD, assistantUp ? "Ready" : "Not answering",
                    assistantUp ? "The Pi assistant is running." : "The Pi assistant did not answer, so Chat cannot reply.");
                setToolPart(2, cameraUp ? Kit.Status.GOOD : Kit.Status.IDLE, cameraUp ? "Ready" : "Not found",
                    cameraUp ? "The Pi camera can be opened." : "The Pi did not report a camera.");
                boolean lowNow = currentPower != null && currentPower.optBoolean("underVoltageNow");
                boolean lowEarlier = currentPower != null && currentPower.optBoolean("underVoltageSinceBoot");
                String fix = currentPower == null || currentPower.isNull("fix") ? "" : currentPower.optString("fix", "").trim();
                if (currentPower == null) setToolPart(3, Kit.Status.IDLE, "Not known", "The Pi did not report on its power.");
                else setToolPart(3, lowNow ? Kit.Status.BAD : lowEarlier ? Kit.Status.WARN : Kit.Status.GOOD,
                    lowNow ? "Low power" : lowEarlier ? "Was low" : "Ready",
                    (lowNow ? "The Pi is short of power right now. The picture may stop."
                        : lowEarlier ? "The Pi ran short of power at least once since it started."
                        : "The Pi has had enough power since it started.") + (fix.isEmpty() ? "" : " " + fix));
                int connected = 0, known = listed == null ? 0 : listed.length();
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < known; i++) {
                    JSONObject drive = listed.optJSONObject(i);
                    if (drive == null) continue;
                    if (drive.optBoolean("online")) connected++;
                    names.append(names.length() == 0 ? "" : "\n").append(drive.optString("label", "Drive")).append(": ")
                        .append(drive.optBoolean("online") ? "connected" : "not connected");
                }
                setToolPart(4, listed == null ? Kit.Status.IDLE : connected == known && known > 0 ? Kit.Status.GOOD : Kit.Status.WARN,
                    listed == null ? "Not known" : connected + (known == connected ? " connected" : " of " + known + " connected"),
                    listed == null ? "The Pi did not list its drives." : names.toString());
                boolean reached = mediaUp || assistantUp;
                boolean allGood = mediaUp && assistantUp && !lowNow && listed != null && connected == known;
                setToolsLead(!reached ? Kit.Status.BAD : lowNow ? Kit.Status.BAD : allGood ? Kit.Status.GOOD : Kit.Status.WARN,
                    !reached ? "The Pi cannot be reached" : lowNow ? "Power is low" : allGood ? "Everything is ready" : "One thing needs a look");
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
        renderPickUp(null);
        new Thread(() -> {
            final boolean mac = !home.isEmpty() && MeshClient.reachable(home, MeshClient.PORT);
            final boolean pi = !assist.isEmpty() && MeshClient.reachable(assist, MeshClient.ASSIST_PORT);
            boolean mediaReady = false;
            JSONObject played = null;
            if (!assist.isEmpty() && !token.isEmpty()) {
                MediaClient media = new MediaClient(assist, token);
                try { mediaReady = media.get("/v1/health").optBoolean("ok"); }
                catch (Exception ignored) { }
                try {
                    JSONArray entries = media.get("/v1/history").optJSONArray("entries");
                    if (entries != null) played = entries.optJSONObject(0);
                } catch (Exception ignored) { }
            }
            final boolean media = mediaReady;
            final JSONObject lastPlayed = played;
            ui.post(() -> { bindHomeStatus(pi, media, mac); renderPickUp(lastPlayed); renderMore(pi || media); });
        }).start();
    }

    /**
     * Fill Home's lead line, capability cards and device rows. A null reading means it
     * is still being checked. The words are the status vocabulary of the app model.
     */
    private void bindHomeStatus(Boolean pi, boolean media, Boolean mac) {
        boolean checking = pi == null;
        boolean piUp = !checking && (pi || media);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        // One quiet line on how the Pi is. The detail, and the app's own version and updates, live in Tools.
        Kit.setStatus(pageHome.findViewById(R.id.home_status_dot),
            checking ? Kit.Status.WARN : piUp ? Kit.Status.GOOD : Kit.Status.BAD);
        ((TextView) pageHome.findViewById(R.id.home_status_words)).setText(
            checking ? "Checking the Pi" : piUp ? "The Pi is online" : "The Pi cannot be reached");
        ((TextView) pageHome.findViewById(R.id.home_status_version)).setText("csync " + appVersion());
        pageHome.findViewById(R.id.home_status).setContentDescription(
            (checking ? "Checking the Pi" : piUp ? "The Pi is online" : "The Pi cannot be reached") + ". Open Tools");

        // Your devices as one row with a count. Choosing which one happens in Share.
        JSONArray roster = PeerStore.load(this);
        String self = Prefs.deviceName(this);
        int known = 0, online = 0;
        for (int i = 0; roster != null && i < roster.length(); i++) {
            JSONObject peer = roster.optJSONObject(i);
            if (peer == null || self.equals(peer.optString("name"))) continue;
            known++;
            if (peer.optBoolean("online")) online++;
        }
        LinearLayout box = pageHome.findViewById(R.id.home_devices);
        box.removeAllViews();
        View devices = Kit.addRow(Kit.group(box));
        Kit.bindRow(devices, Kit.Icon.DEVICE, "Your devices",
            known == 0 ? "None found yet" : online + " of " + known + " online", null, true);
        devices.setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_share));
    }

    /** Pick up: the last conversation, and what was last played when the Pi remembers one. */
    private void renderPickUp(JSONObject played) {
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        LinearLayout box = pageHome.findViewById(R.id.home_pickup_content);
        box.removeAllViews();
        LinearLayout group = Kit.group(box);
        JSONObject recent = ChatStore.index(this).optJSONObject(0);
        View talk = Kit.addRow(group);
        if (recent == null) Kit.bindRow(talk, Kit.Icon.CHAT, "Start a conversation", "Ask the Pi assistant", null, true);
        else Kit.bindRow(talk, Kit.Icon.CHAT, recent.optString("title", "Chat"),
            "Conversation · " + relTime(recent.optLong("updated")), null, true);
        talk.setOnClickListener(v -> {
            nav.setSelectedItemId(R.id.nav_chat);
            if (recent != null) openConversation(recent.optString("id"), recent.optString("title"));
            else newConversation();
        });
        if (played == null || played.optString("name").isEmpty()) return;
        View watch = Kit.addRow(group);
        boolean onPhone = "phone".equals(played.optString("target"));
        Kit.bindRow(watch, Kit.Icon.VIDEO, MediaActivity.displayMediaName(played.optString("name")),
            (played.optBoolean("completed") ? "Finished" : "Last played") + (onPhone ? " · on this phone" : " · on the Pi screen"),
            null, true);
        watch.setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { return ""; }
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
            } catch (java.io.IOException error) {
                inboxRevealPath = null;
                toast("That received file is no longer on this phone");
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
                shareSay("");
            } else shareSay("That device is no longer in your list. Choose another.");
        }
    }

    private void setDot(View dot, boolean up) {
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                col(up ? R.color.online : R.color.offline)));
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
            label.setTextColor(picked ? Kit.accentText(this) : col(R.color.dim));
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
            states, new int[]{Kit.accentText(this), col(R.color.dim)});
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
    }

    /** With Shizuku absent the page says so once and offers to open it; there is nothing else to show. */
    private void ensureShizuku() {
        LinearLayout body = pageTools.findViewById(R.id.sys_body);
        if (!Shizuku.pingBinder()) {
            sysStatus.setText("");
            body.removeAllViews();
            Kit.empty(body, Kit.Icon.DEVICE, "Live readings need Shizuku to be running on this phone", null,
                Kit.button(this, R.drawable.csi_upload, "Open Shizuku", R.color.text, () -> {
                    Intent shizuku = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (shizuku == null) toast("Shizuku is not installed on this phone");
                    else startActivity(shizuku);
                }));
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) startSysLoop();
        else { sysStatus.setText("Asking Shizuku for permission"); Shizuku.requestPermission(SHIZUKU_REQ); }
    }

    private void startSysLoop() {
        sysStatus.setText("What is using the processor and memory, read every 3 seconds");
        if (!sysLooping) { sysLooping = true; sysLoop(); }
    }

    private void sysLoop() {
        if (!resumed || current != 3 || toolsDetail != 1) { sysLooping = false; return; }
        sysTickOnce();
        ui.postDelayed(this::sysLoop, 3000);
    }

    private void sysTickOnce() {
        new Thread(() -> {
            final String raw = rawTop();
            ui.post(() -> showReadings(raw));
        }).start();
    }

    /** Draw what top reported: memory, processor and task counts as rows, then the busiest apps. */
    private void showReadings(String raw) {
        LinearLayout body = pageTools.findViewById(R.id.sys_body);
        body.removeAllViews();
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
        if (headerIdx < 0) {
            Kit.empty(body, Kit.Icon.DEVICE, "The readings could not be taken", raw.trim().isEmpty() ? null : raw.trim(), null);
            return;
        }
        LinearLayout readings = Kit.group(body);
        long total = matchMB(memLine, "total"), used = matchMB(memLine, "used");
        if (total > 0) Kit.bindRow(Kit.addRow(readings), Kit.Icon.DEVICE, "Memory in use", gb(used) + " of " + gb(total),
            Math.round(used * 100.0 / total) + "%", false).setClickable(false);
        String cpu = cpuSummary(cpuLine);
        if (!cpu.endsWith("?")) Kit.bindRow(Kit.addRow(readings), Kit.Icon.SPEED, "Processor", "Busy right now",
            cpu.replace("CPU ", ""), false).setClickable(false);
        String tasks = matchOne(tasksLine, "Tasks:\\s+(\\d+)");
        if (!tasks.isEmpty()) Kit.bindRow(Kit.addRow(readings), Kit.Icon.TOOLS, "Running", "Processes on this phone",
            tasks, false).setClickable(false);
        Kit.label(body, "Busiest apps");
        LinearLayout busiest = Kit.group(body);
        int shown = 0;
        for (int i = headerIdx + 1; i < lines.length && shown < 10; i++) {
            String[] f = lines[i].trim().split("\\s+");
            if (f.length < 12) continue;
            String name = f[11];
            if (name.length() > 40) name = name.substring(name.length() - 40);
            Kit.bindRow(Kit.addRow(busiest), Kit.Icon.DEVICE, name, f[8] + "% processor · " + f[9] + "% memory",
                null, false).setClickable(false);
            shown++;
        }
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
        });
    private View shareSend;

    private void setupSharePage() {
        shareText = pageShare.findViewById(R.id.share_text);
        shareInbox = pageShare.findViewById(R.id.share_inbox);
        shareStatus = pageShare.findViewById(R.id.share_status);
        shareSend = Kit.primaryButton(this, R.drawable.csi_send, "Send", this::sendComposed);
        ((android.widget.FrameLayout) pageShare.findViewById(R.id.share_send_host)).addView(shareSend,
            new android.widget.FrameLayout.LayoutParams(-1, -2));
        shareText.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(android.text.Editable s) { renderSendReady(); }
        });
        bindShareFile();
        setShareMode(false);
    }

    /** Send looks ready only when there is something to send and someone to send it to. */
    private void renderSendReady() {
        if (shareSend == null) return;
        boolean ready = !PeerStore.selected(this).isEmpty() &&
            (shareFileUri != null || !shareText.getText().toString().trim().isEmpty());
        shareSend.setAlpha(ready ? 1f : 0.45f);
    }

    /** One Send for the whole page: the attached file goes, and the message with it when there is one. */
    private void sendComposed() {
        String words = shareText.getText().toString();
        if (shareFileUri == null && words.trim().isEmpty()) { toast("Write a message or attach a file first"); return; }
        if (shareFileUri != null) sendSelectedFile();
        if (!words.trim().isEmpty()) sendToSelected(words, true);
    }

    /** Say what is happening to a send under the button, or with nothing to say take the line away. */
    private void shareSay(String words) {
        shareStatus.setText(words);
        shareStatus.setVisibility(words == null || words.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Compose and Inbox are two modes of the Share tab; each has its own crumb, and back leaves Inbox for Compose. */
    private void setShareMode(boolean inbox) {
        shareInboxMode = inbox;
        pageShare.findViewById(R.id.share_compose).setVisibility(inbox ? View.GONE : View.VISIBLE);
        pageShare.findViewById(R.id.share_inbox_section).setVisibility(inbox ? View.VISIBLE : View.GONE);
        View top = pageShare.findViewById(R.id.share_top);
        if (inbox) {
            Kit.pageTop(top, "received", this::openPlace);
            // The breadcrumb already names this page, so it carries no heading of its own.
            refreshShareHeading();
            renderInbox();
            return;
        }
        Kit.pageTop(top, "share", this::openPlace);
        Kit.topAction(top, Kit.Icon.DEVICE, "Choose who receives", v -> openRecipientSheet());
        Kit.topAction(top, R.drawable.csi_download, "Received", v -> setShareMode(true));
        refreshShareHeading();
    }

    /** Draw what can go with the message: the attached file, or the way to attach one, and the clipboard. */
    private void bindShareFile() {
        LinearLayout host = pageShare.findViewById(R.id.share_adds);
        host.removeAllViews();
        LinearLayout group = Kit.group(host);
        View file = Kit.addRow(group);
        if (shareFileUri == null) {
            Kit.bindRow(file, Kit.Icon.FILE, "Attach a file", "A photo, a video or a document", null, true);
            file.setOnClickListener(v -> shareFilePicker.launch(new String[]{"*/*"}));
        } else {
            Kit.bindRow(file, Kit.Icon.FILE, shareFileName, "Attached", null, false);
            Kit.rowAction(file, R.drawable.csi_trash, "Remove the attachment", v -> {
                shareFileUri = null;
                shareFileName = "";
                bindShareFile();
            });
            file.setClickable(false);
        }
        // The clipboard is read only on the tap: Android shows a notice every time an app looks at it.
        View clip = Kit.addRow(group);
        Kit.bindRow(clip, R.drawable.csi_clipboard, "Use the clipboard", "Adds the text you copied to the message", null, false);
        clip.setOnClickListener(v -> {
            String held = clipboardText();
            if (held == null || held.trim().isEmpty()) { toast("There is no text on the clipboard"); return; }
            shareText.getText().insert(Math.max(0, shareText.getSelectionStart()), held);
        });
        renderSendReady();
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

    /** Draw the row that says who receives and whether they are online. */
    private void refreshShareHeading() {
        LinearLayout host = pageShare.findViewById(R.id.share_recipient);
        host.removeAllViews();
        String selected = PeerStore.selected(this);
        View row = Kit.addRow(Kit.group(host));
        Kit.bindRow(row, Kit.Icon.DEVICE, selected.isEmpty() ? "Choose who receives" : "Send to " + selected, null, null, true);
        JSONArray roster = PeerStore.load(this);
        for (int i = 0; roster != null && i < roster.length(); i++) {
            JSONObject peer = roster.optJSONObject(i);
            if (peer == null || selected.isEmpty() || !selected.equals(peer.optString("name"))) continue;
            boolean online = peer.optBoolean("online");
            Kit.rowStatus(row, online ? Kit.Status.GOOD : Kit.Status.IDLE, online ? "Online" : "Offline");
        }
        row.setOnClickListener(v -> openRecipientSheet());
        renderSendReady();
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
        shareSay("Sending " + name + " to " + target);
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
                result = "";
                delivered = true;
            } catch (Exception error) {
                result = name + " was not delivered to " + target + ". It is still attached."
                    + (error.getMessage() == null ? "" : " " + error.getMessage());
            }
            final String message = result;
            final boolean sent = delivered;
            final String sentKind = kind;
            ui.post(() -> {
                shareSay(message);
                recordSent(name, sentKind, target, sent);
                if (sent && uri.equals(shareFileUri)) {
                    shareFileUri = null;
                    shareFileName = "";
                    bindShareFile();
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
        if (entries.length() == 0) {
            Kit.empty(container, R.drawable.csi_send, "Nothing sent yet", "What you send from this phone is listed here.", null);
            return;
        }
        LinearLayout group = Kit.group(container);
        for (int i = 0; i < Math.min(entries.length(), 10); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("kind", "file");
            boolean delivered = item.optBoolean("delivered");
            View row = Kit.addRow(group);
            Kit.bindRow(row, "text".equals(kind) ? R.drawable.csi_text : "image".equals(kind) ? Kit.Icon.PHOTO : Kit.Icon.FILE,
                item.optString("name"), item.optString("target") + " · " + relTime(item.optLong("at")), null, false);
            Kit.rowStatus(row, delivered ? Kit.Status.GOOD : Kit.Status.BAD, delivered ? "Delivered" : "Failed");
            row.setClickable(false);
        }
    }

    private void scanPeers() {
        final String home = Prefs.homeIp(this), assist = Prefs.assistIp(this);
        final String token = Prefs.token(this);
        if (token.isEmpty() || (home.isEmpty() && assist.isEmpty())) {
            shareSay("Add the Pi address and the token in Settings to find your devices.");
            return;
        }
        new Thread(() -> {
            try {
                JSONArray scanned;
                try { scanned = MeshClient.peers(home.isEmpty() ? assist : home, token); }
                catch (Exception first) {
                    if (home.isEmpty() || assist.isEmpty() || home.equals(assist)) throw first;
                    scanned = MeshClient.peers(assist, token);
                }
                final JSONArray merged = PeerStore.mergeScan(this, scanned);
                ui.post(() -> renderPeers(merged));
            } catch (Throwable e) {
                ui.post(() -> shareSay("Your devices could not be checked, so who is online may be out of date."));
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
                platform.isEmpty() ? null : platform, () -> {
                    PeerStore.select(this, name);
                    refreshShareHeading();
                }).value(online ? "Online" : "Offline"));
        }
        actions.add(new Kit.Action(R.drawable.csi_refresh, "Check who is online", null, this::scanPeers));
        Kit.sheet(this, "Send to", null, actions.toArray(new Kit.Action[0]));
    }

    private void sendToSelected(final String text, boolean fromComposer) {
        if (text == null || text.isEmpty()) { toast("Nothing to send"); return; }
        final String target = PeerStore.selected(this), token = Prefs.token(this);
        if (target.isEmpty()) { toast("Pick a device first"); return; }
        if (token.isEmpty()) { toast("Set the token in Settings"); return; }
        final String from = Prefs.deviceName(this);
        shareSay("Sending to " + target);
        new Thread(() -> {
            String result;
            boolean sent = false;
            try {
                MeshClient.send(target, token, from, "text", "shared.txt", text.getBytes("UTF-8"));
                result = "";
                sent = true;
            } catch (Throwable e) {
                result = "The message was not delivered to " + target + ". It is still in the box."
                    + (e.getMessage() == null ? "" : " " + e.getMessage());
            }
            final String r = result;
            final boolean delivered = sent;
            ui.post(() -> {
                shareSay(r);
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
        // Newest first, whoever sent it: the list is about what arrived, not about who sent it.
        java.util.List<java.io.File> arrived = new java.util.ArrayList<>();
        if (senders != null) {
            for (java.io.File sender : senders) {
                java.io.File[] files = sender.listFiles();
                if (files != null) arrived.addAll(java.util.Arrays.asList(files));
            }
        }
        arrived.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (arrived.isEmpty()) {
            Kit.empty(shareInbox, R.drawable.csi_download, "Nothing received yet",
                "What other devices send to this phone is listed here.", null);
            return;
        }
        Kit.label(shareInbox, arrived.size() == 1 ? "1 item" : arrived.size() + " items");
        inboxGroup = Kit.group(shareInbox);
        for (java.io.File f : arrived) {
            View row = Kit.addRow(inboxGroup);
            String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
            boolean image = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
            java.io.File sender = f.getParentFile();
            Kit.bindRow(row, image ? Kit.Icon.PHOTO : Kit.Icon.FILE, f.getName(),
                (sender == null ? "" : sender.getName() + " · ") + relTime(f.lastModified()), null, true);
            row.setOnClickListener(v -> showInboxActions(f));
            try {
                if (f.getCanonicalPath().equals(inboxRevealPath))
                    row.post(() -> row.requestRectangleOnScreen(
                        new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true));
            } catch (java.io.IOException ignored) { }
        }
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
        boolean textFile = mime.startsWith("text/") || mime.equals("application/json");
        ItemActions.Item item = new ItemActions.Item(ItemActions.kindOf(mime), file.getName());
        java.io.File sender = file.getParentFile();
        item.sub = (sender == null ? "Received" : "From " + sender.getName()) + " · "
            + android.text.format.Formatter.formatShortFileSize(this, file.length());
        item.file = ItemActions.local(this, file, mime);
        if (textFile) item.more.add(new Kit.Action(R.drawable.csi_copy, "Copy the text", null, () -> copyFile(file)));
        ItemActions.sheet(this, item);
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
        renderPlaybackSettings();
        refreshConnection();
    }

    /** How playback begins and behaves: the starting volume and screen are kept on the Pi, the rest on this phone. */
    private void renderPlaybackSettings() {
        LinearLayout host = pageSettings.findViewById(R.id.set_playback_body);
        host.removeAllViews();
        LinearLayout playback = Kit.group(host);
        final android.content.SharedPreferences how = getSharedPreferences("player_controls", MODE_PRIVATE);
        final MediaClient pi = new MediaClient(Prefs.assistIp(this), Prefs.token(this));
        final View volume = Kit.addRow(playback);
        Kit.bindRow(volume, Kit.Icon.VOLUME, "Starting volume", "On the Pi screen", null, true);
        volume.setOnClickListener(v -> DisplaySheet.startVolume(this, this::renderPlaybackSettings));
        View resume = Kit.addRow(playback);
        Kit.bindRow(resume, Kit.Icon.HISTORY, "Resume where I stopped", null, null, false);
        Kit.rowToggle(resume, how.getBoolean("resume", true), on -> how.edit().putBoolean("resume", on).apply());
        View loop = Kit.addRow(playback);
        Kit.bindRow(loop, Kit.Icon.LOOP, "Loop", null, null, false);
        Kit.rowToggle(loop, how.getBoolean("loop", false), on -> how.edit().putBoolean("loop", on).apply());
        View skip = Kit.addRow(playback);
        Kit.bindRow(skip, R.drawable.csi_fastforward, "Skip length", null, how.getInt("skip_seconds", 10) + " seconds", true);
        skip.setOnClickListener(v -> {
            int now = how.getInt("skip_seconds", 10);
            java.util.List<Kit.Action> lengths = new java.util.ArrayList<>();
            for (int seconds : new int[]{5, 10, 15, 30}) lengths.add(new Kit.Action(
                seconds == now ? R.drawable.csi_check : R.drawable.csi_fastforward, seconds + " seconds", null, () -> {
                    how.edit().putInt("skip_seconds", seconds).apply();
                    renderPlaybackSettings();
                }));
            Kit.sheet(this, "Skip length", "For both Back and Forward", lengths.toArray(new Kit.Action[0]));
        });
        // The screen the Pi is plugged into and how loud it starts, written in once the Pi has answered.
        final View display = Kit.addRow(playback);
        Kit.bindRow(display, Kit.Icon.DISPLAY, "Display", "What the Pi is plugged into", null, true);
        display.setOnClickListener(v -> DisplaySheet.open(this));
        new Thread(() -> {
            final JSONObject screen = DisplaySheet.screenInUse(pi);
            if (screen == null) return;
            final JSONObject kept = screen.optJSONObject("settings");
            final int starts = kept == null ? 0 : kept.optInt("startVolume");
            ui.post(() -> {
                Kit.bindRow(display, Kit.Icon.DISPLAY, "Display", "What the Pi is plugged into", screen.optString("name"), true);
                Kit.bindRow(volume, Kit.Icon.VOLUME, "Starting volume", "On the Pi screen", starts == 0 ? "Muted" : starts + "%", true);
            });
        }, "display-name").start();
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
                    chip.setTextColor(on ? Kit.accentText(this) : col(R.color.text));
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
    private TextView chatSubtitle, chatTitle, chatBack;
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
        // Another app shared a file into a conversation: it waits beside a new message, ready to send.
        android.net.Uri shared = intent.getParcelableExtra("chat_attach_uri");
        String draft = intent.getStringExtra("chat_prefill");
        if (shared == null && (draft == null || draft.isEmpty())) return;
        // Into the conversation that was picked, or a new one when none was.
        String id = intent.getStringExtra("chat_session");
        org.json.JSONObject entry = id == null ? null : convEntry(id);
        if (entry == null) newConversation();
        else openConversation(id, entry.optString("title"));
        if (shared != null) {
            intent.removeExtra("chat_attach_uri");
            attachToChat(shared);
        }
        if (draft == null || draft.isEmpty()) return;
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
        showChatList();
    }

    // Whether the search box is open on the conversation list. The top bar's search button turns it.
    private boolean chatSearchOpen;

    private void toggleChatSearch() {
        chatSearchOpen = !chatSearchOpen;
        android.view.inputmethod.InputMethodManager keys =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (chatSearchOpen) { if (!"Tools".equals(chatFilter)) { chatSearch.requestFocus(); keys.showSoftInput(chatSearch, 0); } }
        else { chatSearch.setText(""); keys.hideSoftInputFromWindow(chatSearch.getWindowToken(), 0); }
        selectChatFilter(chatFilter);
    }

    private void selectChatFilter(String filter) {
        chatFilter = filter;
        showArchived = "Archived".equals(filter);
        showFavOnly = "Favorites".equals(filter);
        final String[] names = {"All", "Favorites", "Archived", "Tools"};
        Kit.tabs(pageChat.findViewById(R.id.chat_tabs),
            new int[]{R.drawable.csi_menu, R.drawable.csi_favorite, R.drawable.csi_archive, R.drawable.csi_clipboard},
            names, java.util.Arrays.asList(names).indexOf(filter), index -> selectChatFilter(names[index]));
        boolean tools = "Tools".equals(filter);
        pageChat.findViewById(R.id.chat_search_wrap).setVisibility(chatSearchOpen && !tools ? View.VISIBLE : View.GONE);
        chatHistoryList.setVisibility(tools ? View.GONE : View.VISIBLE);
        chatToolsList.setVisibility(tools ? View.VISIBLE : View.GONE);
        TextView heading = pageChat.findViewById(R.id.chat_recent_heading);
        heading.setText(showArchived ? "Archived" : showFavOnly ? "Favorites" : "Recent");
        // The tools come in named groups of their own, so the list heading steps aside.
        heading.setVisibility(tools ? View.GONE : View.VISIBLE);
        if (tools) refreshChatTools();
        else renderHistoryList();
        chatHistory.post(() -> chatHistory.smoothScrollTo(0, 0));
    }

    /** Say one thing in the tools view when there is no list to show. */
    private void sayChatTools(String title, String words) {
        chatToolsList.removeAllViews();
        Kit.bindRow(Kit.addRow(Kit.group(chatToolsList)), Kit.Icon.TOOLS, title, words, null, false).setClickable(false);
    }

    /**
     * Where a tool the assistant declares is listed: its group, the name it goes by and what it does.
     * Several tools that do one job, such as pause, seek and stop, share one line.
     */
    private static String[] chatToolLine(String name) {
        switch (name) {
            case "media_search": return new String[]{"Media", "Find media", "Search the drives by name"};
            case "media_play": case "media_cast_youtube":
                return new String[]{"Media", "Play media", "Play a file or a YouTube link on the Pi screen"};
            case "media_pause": case "media_seek": case "media_resume": case "media_stop": case "media_volume": case "media_speed":
                return new String[]{"Media", "Control playback", "Pause, resume, seek, stop, volume and speed"};
            case "media_status": return new String[]{"Media", "Playback status", "What the Pi screen is playing now"};
            case "media_drives": case "media_diagnose":
                return new String[]{"Media", "Check media", "Drives, power and the screen connection"};
            case "camera": return new String[]{"Camera", "Camera", "Check the camera, take a photo or record a clip"};
            case "home_health": case "pi_vitals": case "top_processes":
                return new String[]{"Devices and files", "Pi health", "Storage, memory, temperature, power and what is busy"};
            case "list_peers": return new String[]{"Devices and files", "Your devices", "Named devices and whether each is online"};
            case "send_to_peer": return new String[]{"Devices and files", "Send to a device", "Send text to a named device"};
            case "send_file": return new String[]{"Devices and files", "Share a Pi file", "Bring a file from the Pi into the conversation"};
            case "notes": return new String[]{"Notes", "Notes", "Read, write and delete notes on the Pi"};
            case "list_skills": case "load_skill":
                return new String[]{"Skills and commands", "Saved skills", "List and read saved skills"};
            case "run_command": return new String[]{"Skills and commands", "Pi commands", "Run a command on the Pi when the Pi allows it"};
            default: return null;
        }
    }

    private void refreshChatTools() {
        sayChatTools("Asking the Pi assistant", "What it can use is read from the Pi");
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) {
            sayChatTools("The Pi is not connected", "Set its address and token in Settings");
            return;
        }
        new Thread(() -> {
            JSONArray tools = null;
            try { tools = MeshClient.capabilities(ip, token); } catch (Exception ignored) { }
            final JSONArray observed = tools;
            ui.post(() -> {
                if (!"Tools".equals(chatFilter) || chatConvoMode) return;
                if (observed == null) { sayChatTools("The Pi assistant did not answer", "Check Tools under More"); return; }
                if (observed.length() == 0) { sayChatTools("The assistant has no tools", "It can still answer questions"); return; }
                chatToolsList.removeAllViews();
                String[] order = {"Media", "Camera", "Devices and files", "Notes", "Skills and commands", "Other"};
                int[] icons = {Kit.Icon.MEDIA, Kit.Icon.CAMERA, Kit.Icon.DEVICE, Kit.Icon.NOTES, Kit.Icon.TOOLS, Kit.Icon.TOOLS};
                for (int g = 0; g < order.length; g++) {
                    LinearLayout group = null;
                    java.util.Set<String> listed = new java.util.HashSet<>();
                    for (int i = 0; i < observed.length(); i++) {
                        JSONObject tool = observed.optJSONObject(i);
                        if (tool == null) continue;
                        String[] line = chatToolLine(tool.optString("name"));
                        if (line == null) line = new String[]{"Other", chatToolTitle(tool.optString("name")), "Available through the Pi assistant"};
                        if (!line[0].equals(order[g]) || !listed.add(line[1])) continue;
                        if (group == null) {
                            Kit.label(chatToolsList, order[g]);
                            group = Kit.group(chatToolsList);
                        }
                        Kit.bindRow(Kit.addRow(group), icons[g], line[1], line[2], null, false).setClickable(false);
                    }
                }
            });
        }, "chat-tools").start();
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
        Kit.topAction(top, R.drawable.csi_search, "Search conversations", v -> toggleChatSearch());
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
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(), this::attachToChat);

    /** Put a copy of a file beside the message being written, to go with it when it is sent. */
    private void attachToChat(android.net.Uri uri) {
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
    }

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

    /** Remember what is typed for the conversation on screen, so leaving it and coming back loses nothing. */
    private void keepDraft() {
        if (chatInput == null || chatSession == null || !chatConvoMode) return;
        android.content.SharedPreferences drafts = getSharedPreferences("csync_drafts", MODE_PRIVATE);
        String typed = chatInput.getText().toString();
        if (typed.trim().isEmpty()) drafts.edit().remove(chatSession).apply();
        else drafts.edit().putString(chatSession, typed).apply();
    }

    /** Put back what was being written in this conversation, or clear the box when there was nothing. */
    private void restoreDraft() {
        chatInput.setText(getSharedPreferences("csync_drafts", MODE_PRIVATE).getString(chatSession, ""));
        chatInput.setSelection(chatInput.length());
    }

    private void showChatList() {
        keepDraft();
        searchResultChat = false;
        chatConvoMode = false;
        syncChatHeading(false);
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
            JSONArray transcript = ChatStore.transcript(this, id);
            // A search looks at what was said as well as the title, and shows the words it found.
            final String found = q.isEmpty() || title.toLowerCase().contains(q) ? null : saidWith(transcript, q);
            if (!q.isEmpty() && !title.toLowerCase().contains(q) && found == null) continue;
            long updated = o.optLong("updated");
            int messages = 0;
            for (int j = 0; j < transcript.length(); j++) {
                JSONObject turn = transcript.optJSONObject(j);
                if (turn != null && ("user".equals(turn.optString("role")) ||
                    "text".equals(turn.optString("type")))) messages++;
            }
            if (historyGroup == null) historyGroup = Kit.group(chatHistoryList);
            View row = Kit.addRow(historyGroup);
            Kit.bindRow(row, fav ? R.drawable.csi_favorite : Kit.Icon.CHAT, title, found != null ? found :
                relTime(updated) + " · " + messages + (messages == 1 ? " message" : " messages"), null, true);
            row.setOnClickListener(v -> {
                openConversation(id, o.optString("title"));
                // Opened from words found inside it: land on those words.
                if (found != null) chatFind.open(q);
            });
            row.setOnLongClickListener(v -> { chatOptions(id, title, fav, archived); return true; });
            shown++;
        }
        // With nothing to list, the heading that would name the list leaves and the page says what belongs here.
        pageChat.findViewById(R.id.chat_recent_heading).setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
        if (shown > 0) return;
        View showAll = Kit.button(this, R.drawable.csi_menu, "Show all conversations", R.color.text, () -> selectChatFilter("All"));
        if (!q.isEmpty()) Kit.empty(chatHistoryList, R.drawable.csi_search,
            "No conversation matches \"" + chatSearch.getText().toString().trim() + "\"", null, null);
        else if (showFavOnly) Kit.empty(chatHistoryList, R.drawable.csi_favorite, "No favorites yet",
            "Open a conversation and tap the heart to keep it here.", showAll);
        else if (showArchived) Kit.empty(chatHistoryList, R.drawable.csi_archive, "Nothing archived",
            "Archived conversations leave the main list and wait here.", showAll);
        else Kit.empty(chatHistoryList, Kit.Icon.CHAT, "No conversations yet",
            "Ask the Pi assistant to find, play or check something.",
            Kit.tonalButton(this, R.drawable.csi_plus, "New chat", this::newConversation));
    }

    /** The words around the first place a conversation says this, cut at whole words, or null when it never does. */
    private static String saidWith(JSONArray transcript, String wanted) {
        for (int i = 0; i < transcript.length(); i++) {
            JSONObject entry = transcript.optJSONObject(i);
            if (entry == null) continue;
            if (!"user".equals(entry.optString("role")) && !"text".equals(entry.optString("type"))) continue;
            String said = entry.optString("text").replace('\n', ' ');
            int at = said.toLowerCase().indexOf(wanted);
            if (at < 0) continue;
            int from = Math.max(0, at - 30), to = Math.min(said.length(), at + wanted.length() + 50);
            if (from > 0) { int space = said.indexOf(' ', from); if (space >= 0 && space < at) from = space + 1; }
            if (to < said.length()) { int space = said.lastIndexOf(' ', to); if (space > at + wanted.length()) to = space; }
            return said.substring(from, to).trim();
        }
        return null;
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
            new Kit.Action(R.drawable.csi_trash, "Delete", null, () -> Kit.confirm(this, "Delete this conversation?",
                title + " is removed from this phone.", R.drawable.csi_trash, "Delete",
                () -> { ChatStore.delete(this, id); ChatShortcuts.gone(this, id); renderHistoryList(); toast("Deleted"); }),
                true));
    }

    private void openConversation(String id, String title) {
        keepDraft();
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
        restoreDraft();
        scrollDown();
        syncChatHeading(keyboardUp());
    }

    private void newConversation() {
        keepDraft();
        chatFind.close();
        // Something typed before a conversation was open starts the new one; a draft left in another does not follow.
        if (chatConvoMode) chatInput.setText("");
        chatDay = null;
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
        chatDay = null;
        drawingStored = true;
        for (int i = 0; i < tr.length(); i++) {
            org.json.JSONObject o = tr.optJSONObject(i);
            if (o == null) continue;
            if ("user".equals(o.optString("role"))) addUserBubble(o.optString("text"), i, o.optLong("at"));
            else renderSingleTurn(o, i);
        }
        drawingStored = false;
        // Opened while its reply is still coming: show how far the answer has got, and offer Stop.
        if (chatSession != null && ChatService.running.contains(chatSession)) {
            if (ChatService.writing.containsKey(chatSession)) showWriting();
            else chatPending = addPending();
        }
        renderQueued();
        updateSendButton();
        chatFind.refresh();
    }

    // The day the last drawn message belongs to, so each new day is named above its first message.
    private String chatDay;
    // True while a saved conversation is being drawn. A saved message with no time recorded shows none;
    // one arriving now is stamped with the present.
    private boolean drawingStored;

    /** Name the day above the first message of that day. A message with no time recorded gets no name. */
    private void markDay(long at) {
        if (at <= 0) return;
        String day = dayName(at);
        if (day.equals(chatDay)) return;
        chatDay = day;
        TextView label = new TextView(this);
        label.setText(day);
        label.setTextColor(col(R.color.dim));
        label.setTextSize(12);
        addTo(label, android.view.Gravity.CENTER_HORIZONTAL, 16);
    }

    private String dayName(long at) {
        java.util.Calendar then = java.util.Calendar.getInstance(), now = java.util.Calendar.getInstance();
        then.setTimeInMillis(at);
        long days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(startOfDay(now) - startOfDay(then));
        if (days == 0) return "Today";
        if (days == 1) return "Yesterday";
        boolean thisYear = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR);
        return new java.text.SimpleDateFormat(thisYear ? "EEEE d MMMM" : "d MMMM yyyy", java.util.Locale.getDefault())
            .format(then.getTime());
    }

    private static long startOfDay(java.util.Calendar day) {
        java.util.Calendar start = (java.util.Calendar) day.clone();
        start.set(java.util.Calendar.HOUR_OF_DAY, 0);
        start.set(java.util.Calendar.MINUTE, 0);
        start.set(java.util.Calendar.SECOND, 0);
        start.set(java.util.Calendar.MILLISECOND, 0);
        return start.getTimeInMillis();
    }

    /** The small time written inside a message, in the phone's own 12 or 24 hour style. */
    private TextView timeLine(long at, int color) {
        TextView time = new TextView(this);
        time.setText(android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(at)));
        time.setTextColor(color);
        time.setTextSize(11);
        time.setGravity(android.view.Gravity.END);
        time.setPadding(0, dp(4), 0, 0);
        return time;
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
        keepDraft();
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
        if (open) addUserBubble(shown, ChatStore.transcript(this, session).length(), System.currentTimeMillis());
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
        org.json.JSONObject named = convEntry(session);
        ChatShortcuts.used(this, session, named == null ? title : named.optString("title", title));
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
        // Send is filled only when there is something to send.
        boolean ready = stop || !chatInput.getText().toString().trim().isEmpty() || !chatAttachments.isEmpty();
        send.setAlpha(ready ? 1f : 0.45f);
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
            long at = t.optLong("at");
            if (at == 0 && !drawingStored) at = System.currentTimeMillis();
            // A reply stopped before its first word has nothing to show but the note that it was stopped.
            if (!t.optString("text").isEmpty()) addMarkdown(t.optString("text"), transcriptIndex, at);
            // The line under the title counts messages, and one has just arrived.
            if (!drawingStored) updateConfigSubtitle();
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
        if ("pi_vitals".equals(tool)) {
            // The Pi reports these as raw readings; the card says them the way a person would.
            keys = java.util.Collections.emptyIterator();
            String heat = result.optString("cpu_temp_c").replaceAll("[^0-9.]", "");
            if (!heat.isEmpty()) {
                card.addView(resultLine("Temperature", Math.round(Float.parseFloat(heat)) + " °C", null));
                rows++;
            }
            String power = result.optString("throttled");
            if (power.contains("=")) {
                boolean steady = power.substring(power.indexOf('=') + 1).trim().matches("0x0+");
                card.addView(resultLine("Power", steady ? "Steady" : "Low voltage was seen",
                    steady ? Kit.Status.GOOD : Kit.Status.WARN));
                rows++;
            }
            String speed = result.optString("arm_clock").replaceAll("[^0-9.]", "");
            if (!speed.isEmpty()) {
                float megahertz = Float.parseFloat(speed);
                card.addView(resultLine("Processor speed", megahertz >= 1000
                    ? String.format(java.util.Locale.US, "%.1f GHz", megahertz / 1000) : Math.round(megahertz) + " MHz", null));
                rows++;
            }
        }
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

    // Colours the words of a code block by its language. A language it does not know comes back plain.
    private io.noties.markwon.syntax.Prism4jSyntaxHighlight codeColours;

    // Markwon with code syntax highlighting, themed light or dark to match the app.
    private io.noties.markwon.Markwon buildMarkwon() {
        io.noties.prism4j.Prism4j prism = new io.noties.prism4j.Prism4j(new GrammarLocatorDef());
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        io.noties.markwon.syntax.Prism4jTheme theme = night
                ? io.noties.markwon.syntax.Prism4jThemeDarkula.create()
                : io.noties.markwon.syntax.Prism4jThemeDefault.create();
        codeColours = io.noties.markwon.syntax.Prism4jSyntaxHighlight.create(prism, theme);
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
        chatJump.setTextColor(Kit.accentText(this));
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

    private void addUserBubble(String text, int transcriptIndex, long at) {
        workBody = null;
        // Sending, or opening a conversation, always lands on the newest message.
        chatFollowing = true;
        if (chatJump != null) chatJump.setVisibility(View.GONE);
        markDay(at);
        TextView tv = new TextView(this); markwon.setMarkdown(tv, text);
        ChatFind.mark(tv);
        tv.setTextColor(col(R.color.onAccent)); tv.setTextIsSelectable(true);
        tv.setLinkTextColor(col(R.color.onAccent));
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tv.setTextSize(14);
        tv.setMaxWidth(Math.min(dp(260), getResources().getDisplayMetrics().widthPixels - dp(80)));
        // The bubble holds the words and, under them, when they were sent.
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(at > 0 ? 7 : 10));
        bubble.setBackground(bg(Kit.accentFill(this), 16));
        bubble.addView(tv);
        if (at > 0) bubble.addView(timeLine(at, androidx.core.graphics.ColorUtils.setAlphaComponent(col(R.color.onAccent), 200)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.BOTTOM);
        row.addView(bubble);
        onMessageTap(tv, () -> showMessageActions(row, text, transcriptIndex, true));
        addTo(row, android.view.Gravity.END, 12); scrollDown();
    }
    private TextView addPending() {
        TextView tv = new TextView(this); tv.setText("Thinking"); tv.setTextColor(col(R.color.dim));
        tv.setTextSize(13); tv.setPadding(dp(12), dp(9), dp(12), dp(9)); tv.setBackground(bg(col(R.color.surface2), 14));
        addTo(tv, android.view.Gravity.START, 12); scrollDown(); return tv;
    }
    private void addMarkdown(String md, int transcriptIndex, long at) {
        final String text = md == null ? "" : md;
        markDay(at);
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
            tv.setLinkTextColor(Kit.accentText(this));
            tv.setTextIsSelectable(true);
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            box.addView(tv);
            onMessageTap(tv, () -> showMessageActions(box, text, transcriptIndex, false));
        }
        if (at > 0) box.addView(timeLine(at, col(R.color.dim)));
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
        // Coloured here and not by the markdown renderer: the block is its own card, and the renderer would draw a second box inside it.
        body.setText(language.isEmpty() ? code : codeColours.highlight(language, code));
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
        // Under the newest message the row would open below the edge of the screen, so bring it into view.
        actions.post(() -> actions.requestRectangleOnScreen(
            new android.graphics.Rect(0, 0, actions.getWidth(), actions.getHeight()), false));
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
            bubble.setBackground(bg(mine ? Kit.accentFill(this) : col(R.color.surface2), 16));
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
