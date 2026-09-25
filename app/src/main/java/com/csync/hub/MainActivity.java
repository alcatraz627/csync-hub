package com.csync.hub;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/** The csync hub: Home, Share, Chat, and More pages plus the Media activity. */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View pageHome, pageShare, pageChat, pageTools, pageSettings, pageCamera, pageMore;
    private CameraController cameraController;
    private MediaMiniPlayer miniPlayer;
    private int current = 0;
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
                public void onBinderReceived() { if (current == 3) ensureShizuku(); }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        applyAppearance();
        setContentView(R.layout.activity_main);
        FrameLayout content = findViewById(R.id.content);
        LayoutInflater inf = LayoutInflater.from(this);
        pageHome = inf.inflate(R.layout.page_home, content, false);
        pageShare = inf.inflate(R.layout.page_share, content, false);
        pageChat = inf.inflate(R.layout.page_chat, content, false);
        pageTools = inf.inflate(R.layout.page_tools, content, false);
        pageSettings = inf.inflate(R.layout.page_settings, content, false);
        pageCamera = inf.inflate(R.layout.page_camera, content, false);
        pageMore = inf.inflate(R.layout.page_more, content, false);
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
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int tab = tabFromIntent(intent);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        nav.setSelectedItemId(tab == 1 ? R.id.nav_share : tab == 2 ? R.id.nav_chat :
            tab == 6 ? R.id.nav_more : R.id.nav_home);
        show(tab);
    }

    private int tabFromIntent(Intent intent) {
        if (intent == null) return 0;
        String destination = intent.getStringExtra("destination");
        return "share".equals(destination) ? 1 : "chat".equals(destination) ? 2 :
            "more".equals(destination) ? 6 : 0;
    }

    // Keep the open surface across a theme or accent change, which recreates the
    // Activity; without this the app would snap back to Home on every toggle.
    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("tab", current);
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
        if (page == 0) refreshHome();
        if (page == 1) refreshShare();
        if (page == 2) { warmChat(); if (!chatConvoMode) { renderHistoryList(); refreshAgentStatus(); } }
        if (page == 3) ensureShizuku();
        if (page == 4) { refreshConnection(); refreshAssistant(); }
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
        ChatService.uiForeground = true;
        android.content.IntentFilter f = new android.content.IntentFilter(ChatService.ACTION_REPLY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(chatReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(chatReceiver, f);
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
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(7002);
        if (current == 3) ensureShizuku();
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

    // ---------------- appearance (theme + accent) ----------------

    // Apply the saved theme and accent before the content view inflates. Light is
    // the default; the accent maps to one of the four Theme.Csync colour variants.
    private void applyAppearance() {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                Prefs.darkTheme(this)
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        setTheme(accentThemeRes(Prefs.accent(this)));
    }

    private int accentThemeRes(String accent) {
        switch (accent) {
            case "teal": return R.style.Theme_Csync_Teal;
            case "violet": return R.style.Theme_Csync_Violet;
            case "rust": return R.style.Theme_Csync_Rust;
            default: return R.style.Theme_Csync_Coral;
        }
    }

    // ---------------- home page (status + capabilities) ----------------

    private View homeDotTail, homeDotMac, homeDotPi;
    private LinearLayout homeCaps;

    private void setupHomePage() {
        homeDotTail = pageHome.findViewById(R.id.home_dot_tail);
        homeDotMac = pageHome.findViewById(R.id.home_dot_mac);
        homeDotPi = pageHome.findViewById(R.id.home_dot_pi);
        homeCaps = pageMore.findViewById(R.id.home_caps);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        pageHome.findViewById(R.id.home_share).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_share));
        pageHome.findViewById(R.id.home_chat).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_chat));
        pageHome.findViewById(R.id.home_media).setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
        pageHome.findViewById(R.id.home_camera).setOnClickListener(v -> { nav.setSelectedItemId(R.id.nav_more); show(5); });
        pageMore.findViewById(R.id.more_camera).setOnClickListener(v -> show(5));
        pageMore.findViewById(R.id.more_tools).setOnClickListener(v -> show(3));
        pageMore.findViewById(R.id.more_settings).setOnClickListener(v -> show(4));
        pageMore.findViewById(R.id.more_assistant_tools).setOnClickListener(v ->
            homeCaps.setVisibility(homeCaps.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        pageTools.findViewById(R.id.tools_media).setOnClickListener(v -> startActivity(new Intent(this, MediaActivity.class)));
        pageTools.findViewById(R.id.tools_update).setOnClickListener(v ->
            AppUpdater.start(this, pageTools.findViewById(R.id.tools_update_status)));
    }

    // Probe reachability and pull the assistant's tool list, off the UI thread.
    // A slow "no" reads the same as a fast one, so the dots use the short probe.
    private void refreshHome() {
        setDot(homeDotTail, MeshClient.tailnetIP() != null);
        final String home = Prefs.homeIp(this), assist = Prefs.assistIp(this), token = Prefs.token(this);
        new Thread(() -> {
            final boolean mac = !home.isEmpty() && MeshClient.reachable(home, MeshClient.PORT);
            final boolean pi = !assist.isEmpty() && MeshClient.reachable(assist, MeshClient.ASSIST_PORT);
            ui.post(() -> { setDot(homeDotMac, mac); setDot(homeDotPi, pi); });
        }).start();
        if (!assist.isEmpty() && !token.isEmpty()) {
            new Thread(() -> {
                JSONArray caps = null;
                try { caps = MeshClient.capabilities(assist, token); } catch (Throwable ignore) {}
                final JSONArray c = caps;
                ui.post(() -> renderCaps(c));
            }).start();
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
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView b = new TextView(this);
        b.setText(title); b.setTextColor(col(R.color.text)); b.setTextSize(13);
        row.addView(b);
        if (desc != null && !desc.isEmpty()) {
            TextView s = new TextView(this);
            s.setText("  " + desc); s.setTextColor(col(R.color.dim)); s.setTextSize(12);
            row.addView(s);
        }
        homeCaps.addView(row);
    }

    // home_health -> "Home health"; list_devices -> "List devices".
    private String prettyName(String raw) {
        if (raw == null || raw.isEmpty()) return "(tool)";
        String s = raw.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------------- settings page (appearance controls) ----------------

    private void setupSettingsPage() {
        com.google.android.material.button.MaterialButtonToggleGroup group =
                pageSettings.findViewById(R.id.set_theme_group);
        group.check(Prefs.darkTheme(this) ? R.id.set_theme_dark : R.id.set_theme_light);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean dark = checkedId == R.id.set_theme_dark;
            if (dark != Prefs.darkTheme(this)) {
                Prefs.saveDarkTheme(this, dark);
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(dark
                        ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
            }
        });
        wireAccent(R.id.set_accent_coral, "coral", R.color.coral);
        wireAccent(R.id.set_accent_teal, "teal", R.color.teal);
        wireAccent(R.id.set_accent_violet, "violet", R.color.violet);
        wireAccent(R.id.set_accent_rust, "rust", R.color.rust);
    }

    private void wireAccent(int viewId, String name, int colorRes) {
        View sw = pageSettings.findViewById(viewId);
        sw.setBackgroundTintList(android.content.res.ColorStateList.valueOf(col(colorRes)));
        sw.setOnClickListener(v -> {
            if (!name.equals(Prefs.accent(this))) { Prefs.saveAccent(this, name); recreate(); }
        });
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
        else { sysStatus.setText("Requesting Shizuku permission..."); Shizuku.requestPermission(SHIZUKU_REQ); }
    }

    private void startSysLoop() {
        sysStatus.setText("Live: top CPU and memory users, refreshing every 3s");
        if (!sysLooping) { sysLooping = true; sysLoop(); }
    }

    private void sysLoop() {
        if (!resumed || current != 3) { sysLooping = false; return; }
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
    private LinearLayout sharePeers, shareInbox;
    private TextView shareStatus;

    private void setupSharePage() {
        sharePeers = pageShare.findViewById(R.id.share_peers);
        shareText = pageShare.findViewById(R.id.share_text);
        shareInbox = pageShare.findViewById(R.id.share_inbox);
        shareStatus = pageShare.findViewById(R.id.share_status);
        pageShare.findViewById(R.id.share_send).setOnClickListener(v -> sendToSelected(shareText.getText().toString()));
        pageShare.findViewById(R.id.share_paste).setOnClickListener(v -> sendToSelected(clipboardText()));
        pageShare.findViewById(R.id.share_scan).setOnClickListener(v -> scanPeers());
    }

    // Show the stored roster and inbox immediately, then refresh online state in
    // the background so the picker is never blank while a scan runs.
    private void refreshShare() {
        renderPeers(PeerStore.load(this));
        renderInbox();
        if (!Prefs.token(this).isEmpty()) scanPeers();
    }

    private void scanPeers() {
        final String home = Prefs.homeIp(this), token = Prefs.token(this);
        if (home.isEmpty() || token.isEmpty()) { toast("Set the home peer and token in Settings"); return; }
        shareStatus.setText("scanning…");
        new Thread(() -> {
            try {
                JSONArray scanned = MeshClient.peers(home, token);
                final JSONArray merged = PeerStore.mergeScan(this, scanned);
                ui.post(() -> { renderPeers(merged); shareStatus.setText(""); });
            } catch (Throwable e) {
                final String msg = e.getMessage();
                ui.post(() -> shareStatus.setText("scan failed: " + msg));
            }
        }).start();
    }

    private void renderPeers(JSONArray roster) {
        sharePeers.removeAllViews();
        if (roster == null || roster.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No devices yet. Tap Scan.");
            empty.setTextColor(col(R.color.dim)); empty.setTextSize(13);
            sharePeers.addView(empty);
            return;
        }
        String selected = PeerStore.selected(this);
        for (int i = 0; i < roster.length(); i++) {
            JSONObject o = roster.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("name");
            boolean online = o.optBoolean("online");
            String meta = o.optString("platform");
            if (meta.isEmpty()) meta = online ? "online" : "offline";
            else meta += online ? " · online" : " · offline";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(11), dp(11), dp(12), dp(11));
            if (name.equals(selected)) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor((accent() & 0x00FFFFFF) | 0x22000000); // accent at ~13% for the fill
                g.setCornerRadius(dp(12));
                g.setStroke(dp(1), accent());
                row.setBackground(g);
            } else {
                row.setBackground(bg(col(R.color.surface), 12));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(i == 0 ? 0 : 8); row.setLayoutParams(lp);

            View dot = new View(this);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(10), dp(10));
            dp2.rightMargin = dp(11); dot.setLayoutParams(dp2);
            dot.setBackground(getDrawable(R.drawable.dot_circle));
            setDot(dot, online);
            row.addView(dot);

            TextView tvName = new TextView(this);
            tvName.setText(name); tvName.setTextColor(col(R.color.text)); tvName.setTextSize(14);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tvName.setLayoutParams(np);
            row.addView(tvName);

            TextView tvMeta = new TextView(this);
            tvMeta.setText(meta); tvMeta.setTextColor(col(R.color.dim)); tvMeta.setTextSize(12);
            row.addView(tvMeta);

            row.setOnClickListener(v -> { PeerStore.select(this, name); renderPeers(PeerStore.load(this)); });
            sharePeers.addView(row);
        }
    }

    private void sendToSelected(final String text) {
        if (text == null || text.isEmpty()) { toast("Nothing to send"); return; }
        final String target = PeerStore.selected(this), token = Prefs.token(this);
        if (target.isEmpty()) { toast("Pick a device first"); return; }
        if (token.isEmpty()) { toast("Set the token in Settings"); return; }
        final String from = Prefs.deviceName(this);
        shareStatus.setText("sending to " + target + "…");
        new Thread(() -> {
            String result;
            try {
                MeshClient.send(target, token, from, "text", "shared.txt", text.getBytes("UTF-8"));
                result = "sent to " + target;
            } catch (Throwable e) {
                result = "failed: " + e.getMessage();
            }
            final String r = result;
            ui.post(() -> { shareStatus.setText(r); shareText.setText(""); });
        }).start();
    }

    // List what has arrived in the app's inbox: files under inbox/<from>/<name>.
    // Tapping a row copies the file's text into the clipboard.
    private void renderInbox() {
        shareInbox.removeAllViews();
        java.io.File inbox = getExternalFilesDir("inbox");
        java.io.File[] senders = (inbox != null && inbox.exists()) ? inbox.listFiles() : null;
        boolean any = false;
        if (senders != null) {
            for (java.io.File sender : senders) {
                java.io.File[] files = sender.listFiles();
                if (files == null) continue;
                for (java.io.File f : files) {
                    any = true;
                    TextView row = new TextView(this);
                    row.setText(f.getName() + "   " + sender.getName());
                    row.setTextColor(col(R.color.text)); row.setTextSize(13);
                    row.setPadding(0, dp(6), 0, dp(6));
                    row.setOnClickListener(v -> copyFile(f));
                    shareInbox.addView(row);
                }
            }
        }
        if (!any) {
            TextView empty = new TextView(this);
            empty.setText("Nothing received yet.");
            empty.setTextColor(col(R.color.dim)); empty.setTextSize(13);
            shareInbox.addView(empty);
        }
    }

    private void copyFile(java.io.File f) {
        try {
            byte[] data = new byte[(int) Math.min(f.length(), 1 << 20)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(data); in.close();
            String text = new String(data, 0, Math.max(n, 0), "UTF-8");
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("csync", text));
            toast("Copied " + f.getName());
        } catch (Throwable e) {
            toast("Can't copy: " + e.getMessage());
        }
    }

    // ---------------- settings: connection card ----------------

    private EditText connHome, connToken, connAssist;
    private com.google.android.material.button.MaterialButton connReceiver;
    private TextView connStatus;

    private void setupConnectionCard() {
        connHome = pageSettings.findViewById(R.id.set_home);
        connAssist = pageSettings.findViewById(R.id.set_assist);
        connToken = pageSettings.findViewById(R.id.set_token);
        connReceiver = pageSettings.findViewById(R.id.set_receiver);
        connStatus = pageSettings.findViewById(R.id.set_conn_status);
        connHome.setText(Prefs.homeIp(this));
        connAssist.setText(Prefs.assistIp(this));
        connToken.setText(Prefs.token(this));
        pageSettings.findViewById(R.id.set_save).setOnClickListener(v -> {
            Prefs.save(this, connHome.getText().toString(), connToken.getText().toString());
            Prefs.saveAssistIp(this, connAssist.getText().toString());
            if (chatSubtitle != null) chatSubtitle.setText("assistant on " + Prefs.assistIp(this));
            toast("Saved");
        });
        connReceiver.setOnClickListener(v -> toggleReceiver());
        refreshConnection();
    }

    private void refreshConnection() {
        if (connReceiver == null) return;
        String self = Prefs.deviceName(this);
        String tail = MeshClient.tailnetIP();
        if (MeshService.running) {
            connReceiver.setText("Stop receiving");
            connStatus.setText(self + " · receiving on " + MeshService.boundInfo);
        } else {
            connReceiver.setText("Start receiving");
            connStatus.setText(self + " · tailnet " + (tail == null ? "not up" : tail));
        }
    }

    private void toggleReceiver() {
        Intent svc = new Intent(this, MeshService.class);
        if (MeshService.running) {
            stopService(svc);
        } else {
            if (Prefs.token(this).isEmpty()) { toast("Save the token first"); return; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        }
        ui.postDelayed(this::refreshConnection, 400);
    }

    // ---------------- settings: assistant provider/model/effort ----------------

    private JSONObject providerData;
    private boolean populatingProviders;

    private void setupAssistantCard() {
        pageSettings.findViewById(R.id.set_apply).setOnClickListener(v -> applyAssistantConfig());
        com.google.android.material.button.MaterialButtonToggleGroup pg = pageSettings.findViewById(R.id.set_provider);
        pg.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked || populatingProviders) return;
            String pid = tagOf(g, id);
            if (pid != null) populateModelsAndEfforts(pid, null, null);
        });
    }

    private void refreshAssistant() {
        final String assist = Prefs.assistIp(this), token = Prefs.token(this);
        if (assist.isEmpty() || token.isEmpty()) return;
        new Thread(() -> {
            JSONObject data = null; JSONArray caps = null; String err = null;
            try { data = MeshClient.providers(assist, token); } catch (Throwable e) { err = e.getMessage(); }
            try { caps = MeshClient.capabilities(assist, token); } catch (Throwable ignore) {}
            final JSONObject d = data; final JSONArray c = caps; final String e = err;
            ui.post(() -> {
                if (d != null) renderProviders(d);
                else ((TextView) pageSettings.findViewById(R.id.set_assist_status)).setText("assistant: " + e);
                renderRunCmd(c);
            });
        }).start();
    }

    private void renderProviders(JSONObject data) {
        providerData = data;
        JSONArray providers = data.optJSONArray("providers");
        JSONObject active = data.optJSONObject("active");
        String ap = active == null ? "" : active.optString("provider");
        String am = active == null ? "" : active.optString("model");
        String ae = active == null ? "" : active.optString("effort");
        com.google.android.material.button.MaterialButtonToggleGroup pg = pageSettings.findViewById(R.id.set_provider);
        populatingProviders = true;
        pg.removeAllViews();
        int checkId = -1;
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            com.google.android.material.button.MaterialButton b = toggleButton(p.optString("label"), p.optString("id"));
            pg.addView(b);
            if (p.optString("id").equals(ap)) checkId = b.getId();
        }
        if (checkId != -1) pg.check(checkId);
        populatingProviders = false;
        populateModelsAndEfforts(ap, am, ae);
    }

    private void populateModelsAndEfforts(String providerId, String selModel, String selEffort) {
        JSONObject prov = findProvider(providerId);
        if (prov == null) return;
        JSONArray models = prov.optJSONArray("models");
        JSONArray efforts = prov.optJSONArray("efforts");

        java.util.List<String> ms = new java.util.ArrayList<>();
        for (int i = 0; models != null && i < models.length(); i++) ms.add(models.optString(i));
        android.widget.Spinner sp = pageSettings.findViewById(R.id.set_model);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, ms);
        sp.setAdapter(ad);
        if (selModel != null) { int idx = ms.indexOf(selModel); if (idx >= 0) sp.setSelection(idx); }

        com.google.android.material.button.MaterialButtonToggleGroup eg = pageSettings.findViewById(R.id.set_effort);
        eg.removeAllViews();
        int checkId = -1;
        for (int i = 0; efforts != null && i < efforts.length(); i++) {
            String e = efforts.optString(i);
            com.google.android.material.button.MaterialButton b = toggleButton(prettyName(e), e);
            eg.addView(b);
            if (e.equals(selEffort)) checkId = b.getId();
        }
        if (checkId != -1) eg.check(checkId);
    }

    private void applyAssistantConfig() {
        com.google.android.material.button.MaterialButtonToggleGroup pg = pageSettings.findViewById(R.id.set_provider);
        com.google.android.material.button.MaterialButtonToggleGroup eg = pageSettings.findViewById(R.id.set_effort);
        android.widget.Spinner sp = pageSettings.findViewById(R.id.set_model);
        final String provider = tagOf(pg, pg.getCheckedButtonId());
        final String effort = tagOf(eg, eg.getCheckedButtonId());
        final String model = sp.getSelectedItem() == null ? "" : sp.getSelectedItem().toString();
        final TextView status = pageSettings.findViewById(R.id.set_assist_status);
        if (provider == null) { toast("Pick a provider"); return; }
        final String assist = Prefs.assistIp(this), token = Prefs.token(this);
        status.setText("applying…");
        new Thread(() -> {
            String r;
            try { MeshClient.setConfig(assist, token, provider, model, effort); r = "active: " + provider + " · " + model + " · " + effort; }
            catch (Throwable e) { r = "failed: " + e.getMessage(); }
            final String rr = r;
            ui.post(() -> status.setText(rr));
        }).start();
    }

    private void renderRunCmd(JSONArray caps) {
        TextView t = pageSettings.findViewById(R.id.set_runcmd);
        if (caps == null) { t.setText("unknown"); t.setTextColor(col(R.color.dim)); return; }
        boolean on = false;
        for (int i = 0; i < caps.length(); i++) {
            JSONObject c = caps.optJSONObject(i);
            String n = c == null ? "" : c.optString("name").toLowerCase();
            if (n.contains("run") || n.contains("command") || n.contains("exec") || n.contains("shell")) on = true;
        }
        t.setText(on ? "on" : "off");
        t.setTextColor(col(on ? R.color.online : R.color.dim));
    }

    private JSONObject findProvider(String id) {
        JSONArray providers = providerData == null ? null : providerData.optJSONArray("providers");
        for (int i = 0; providers != null && i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p;
        }
        return null;
    }

    // A MaterialButton styled as an outlined toggle, carrying its value as the tag.
    // The outlined style comes from the defStyleAttr, not a theme wrapper, so the
    // unchecked buttons read as outlines rather than solid fills.
    private com.google.android.material.button.MaterialButton toggleButton(String label, String value) {
        com.google.android.material.button.MaterialButton b =
                new com.google.android.material.button.MaterialButton(
                        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setTag(value);
        return b;
    }

    private String tagOf(android.view.ViewGroup group, int viewId) {
        if (viewId == View.NO_ID) return null;
        View v = group.findViewById(viewId);
        return v == null || v.getTag() == null ? null : v.getTag().toString();
    }

    // ---------------- chat page (assistant on the Pi) ----------------

    private EditText chatInput, chatSearch;
    private TextView chatSubtitle, chatTitle, chatBack, chatToggleFav, chatToggleArchived;
    private LinearLayout chatList, chatHistoryList, chatConvo, chatFilterbar;
    private ScrollView chatScroll, chatHistory;
    private String chatSession;
    private boolean chatExpanded, chatConvoMode, showArchived, showFavOnly;
    private io.noties.markwon.Markwon markwon;

    private static final String[] MODELS = {"(default)", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-flash-latest", "gemini-2.5-flash"};
    private static final String[] EFFORTS = {"(default)", "off", "low", "medium", "high"};

    private void setupChatPage() {
        chatInput = pageChat.findViewById(R.id.chat_input);
        chatList = pageChat.findViewById(R.id.chat_list);
        chatScroll = pageChat.findViewById(R.id.chat_scroll);
        chatSubtitle = pageChat.findViewById(R.id.chat_subtitle);
        chatTitle = pageChat.findViewById(R.id.chat_title);
        chatBack = pageChat.findViewById(R.id.chat_back);
        chatHistory = pageChat.findViewById(R.id.chat_history);
        chatHistoryList = pageChat.findViewById(R.id.chat_history_list);
        chatConvo = pageChat.findViewById(R.id.chat_convo);
        chatFilterbar = pageChat.findViewById(R.id.chat_filterbar);
        chatSearch = pageChat.findViewById(R.id.chat_search);
        chatToggleFav = pageChat.findViewById(R.id.chat_toggle_fav);
        chatToggleArchived = pageChat.findViewById(R.id.chat_toggle_archived);
        markwon = buildMarkwon();

        final TextView expand = pageChat.findViewById(R.id.chat_expand);
        expand.setOnClickListener(v -> {
            chatExpanded = !chatExpanded;
            int n = chatExpanded ? 6 : 2;
            chatInput.setMinLines(n); chatInput.setMaxLines(n);
            expand.setText(chatExpanded ? "⌃" : "⌄");
        });
        pageChat.findViewById(R.id.chat_send).setOnClickListener(v -> sendChat());
        pageChat.findViewById(R.id.chat_new).setOnClickListener(v -> newConversation());
        chatBack.setOnClickListener(v -> showChatList());
        chatSubtitle.setOnClickListener(v -> { if (chatConvoMode) openConfigDialog(); });
        // Long-press the Chats title to open the feature roadmap the agent maintains.
        chatTitle.setOnLongClickListener(v -> {
            if (!chatConvoMode) openMediaModal("/media/tasks.md", "markdown", "tasks.md");
            return true;
        });
        chatSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) { renderHistoryList(); }
        });
        chatToggleFav.setOnClickListener(v -> {
            showFavOnly = !showFavOnly;
            chatToggleFav.setTextColor(showFavOnly ? accent() : col(R.color.dim));
            renderHistoryList();
        });
        chatToggleArchived.setOnClickListener(v -> {
            showArchived = !showArchived;
            chatToggleArchived.setTextColor(showArchived ? accent() : col(R.color.dim));
            renderHistoryList();
        });
        showChatList();
    }

    private org.json.JSONObject convEntry(String id) {
        org.json.JSONArray idx = ChatStore.index(this);
        for (int i = 0; i < idx.length(); i++) {
            org.json.JSONObject o = idx.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private void openConfigDialog() {
        if (chatSession == null) return;
        new android.app.AlertDialog.Builder(this).setTitle("Model")
            .setItems(MODELS, (dlg, mi) -> {
                ChatStore.patch(this, chatSession, "model", mi == 0 ? "" : MODELS[mi]);
                new android.app.AlertDialog.Builder(this).setTitle("Effort")
                    .setItems(EFFORTS, (d2, ei) -> {
                        ChatStore.patch(this, chatSession, "effort", ei == 0 ? "" : EFFORTS[ei]);
                        updateConfigSubtitle();
                    }).show();
            }).show();
    }

    private void updateConfigSubtitle() {
        org.json.JSONObject e = convEntry(chatSession);
        String m = e != null ? e.optString("model") : "";
        String ef = e != null ? e.optString("effort") : "";
        if (m.startsWith("gemini-")) m = m.substring(7);
        chatSubtitle.setText((m.isEmpty() ? "default model" : m) + " · " + (ef.isEmpty() ? "default" : ef) + "  ⚙");
    }

    // ---- chat: history list vs one open conversation ----

    private void showChatList() {
        chatConvoMode = false;
        chatHistory.setVisibility(View.VISIBLE);
        chatConvo.setVisibility(View.GONE);
        chatBack.setVisibility(View.GONE);
        chatFilterbar.setVisibility(View.VISIBLE);
        chatTitle.setText("Chats");
        renderHistoryList();
        refreshAgentStatus();
    }

    private void renderHistoryList() {
        chatHistoryList.removeAllViews();
        String q = chatSearch == null ? "" : chatSearch.getText().toString().trim().toLowerCase();
        org.json.JSONArray idx = ChatStore.index(this);
        int shown = 0;
        for (int i = 0; i < idx.length(); i++) {
            org.json.JSONObject o = idx.optJSONObject(i);
            if (o == null) continue;
            final boolean archived = o.optBoolean("archived");
            final boolean fav = o.optBoolean("favorite");
            if (archived != showArchived) continue;      // Archived is a filter toggle
            if (showFavOnly && !fav) continue;
            final String id = o.optString("id");
            final String title = o.optString("title").isEmpty() ? "(untitled)" : o.optString("title");
            if (!q.isEmpty() && !title.toLowerCase().contains(q)) continue;
            long updated = o.optLong("updated");
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(bg(col(R.color.surface2), 12)); row.setPadding(dp(13), dp(11), dp(13), dp(11));
            row.setClickable(true); row.setFocusable(true);
            TextView tt = new TextView(this); tt.setText((fav ? "★ " : "") + title); tt.setTextColor(col(R.color.text));
            tt.setTextSize(15); tt.setSingleLine(true); tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = new TextView(this); sub.setText(relTime(updated)); sub.setTextColor(col(R.color.dim)); sub.setTextSize(12);
            row.addView(tt); row.addView(sub);
            row.setOnClickListener(v -> openConversation(id, o.optString("title")));
            row.setOnLongClickListener(v -> { chatOptions(id, title, fav, archived); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8); row.setLayoutParams(lp); chatHistoryList.addView(row);
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
        String[] opts = { "Rename", fav ? "Unfavorite" : "Favorite", archived ? "Unarchive" : "Archive", "Delete" };
        new android.app.AlertDialog.Builder(this).setItems(opts, (d, w) -> {
            switch (w) {
                case 0: renameChat(id, title); break;
                case 1: ChatStore.patch(this, id, "favorite", !fav); renderHistoryList(); break;
                case 2: ChatStore.patch(this, id, "archived", !archived); renderHistoryList(); break;
                case 3: ChatStore.delete(this, id); renderHistoryList(); toast("Deleted"); break;
            }
        }).show();
    }

    private void renameChat(String id, String title) {
        final EditText in = new EditText(this); in.setText(title); in.setSingleLine(true);
        new android.app.AlertDialog.Builder(this).setTitle("Rename chat").setView(in)
            .setPositiveButton("Save", (d, w) -> { ChatStore.patch(this, id, "title", in.getText().toString().trim()); renderHistoryList(); })
            .setNegativeButton("Cancel", null).show();
    }

    private void openConversation(String id, String title) {
        chatSession = id;
        chatConvoMode = true;
        chatHistory.setVisibility(View.GONE);
        chatConvo.setVisibility(View.VISIBLE);
        chatBack.setVisibility(View.VISIBLE);
        chatFilterbar.setVisibility(View.GONE);
        chatTitle.setText(title == null || title.isEmpty() ? "Chat" : title);
        updateConfigSubtitle();
        renderTranscript(ChatStore.transcript(this, id));
        scrollDown();
    }

    private void newConversation() {
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();
        chatConvoMode = true;
        chatHistory.setVisibility(View.GONE);
        chatConvo.setVisibility(View.VISIBLE);
        chatBack.setVisibility(View.VISIBLE);
        chatFilterbar.setVisibility(View.GONE);
        chatTitle.setText("New chat");
        updateConfigSubtitle();
        chatList.removeAllViews(); chatPending = null;
        chatInput.requestFocus();
    }

    private void renderTranscript(org.json.JSONArray tr) {
        chatList.removeAllViews(); chatPending = null;
        for (int i = 0; i < tr.length(); i++) {
            org.json.JSONObject o = tr.optJSONObject(i);
            if (o == null) continue;
            if ("user".equals(o.optString("role"))) addUserBubble(o.optString("text"));
            else renderSingleTurn(o);
        }
    }

    private void refreshAgentStatus() {
        final String ip = Prefs.assistIp(this);
        if (ip.isEmpty()) { chatSubtitle.setText("set the assistant in Settings"); return; }
        chatSubtitle.setText("assistant on " + ip + " · checking…");
        new Thread(() -> {
            boolean up = MeshClient.reachable(ip, MeshClient.ASSIST_PORT);
            ui.post(() -> chatSubtitle.setText("assistant on " + ip + (up ? " · online" : " · offline")));
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

    // Hand the request to ChatService (a foreground service with a wakelock) so a
    // reply that lands after the app is backgrounded still completes and can
    // notify. The reply comes back via chatReceiver, or is drained from the
    // service's stash on the next resume.
    private void sendChat() {
        final String msg = chatInput.getText().toString().trim();
        if (msg.isEmpty()) return;
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) { toast("Set the assistant and token in Settings"); return; }
        if (chatSession == null || !chatConvoMode) newConversation();
        addUserBubble(msg);
        try { org.json.JSONObject u = new org.json.JSONObject(); u.put("role", "user"); u.put("text", msg);
              ChatStore.append(this, chatSession, msg, u); } catch (Throwable ignore) {}
        if ("New chat".contentEquals(chatTitle.getText()))
            chatTitle.setText(msg.length() > 40 ? msg.substring(0, 40) : msg);
        chatInput.setText("");
        if (chatPending != null) chatList.removeView(chatPending);
        chatPending = addPending();
        org.json.JSONObject ce = convEntry(chatSession);
        Intent svc = new Intent(this, ChatService.class);
        svc.putExtra("assist", ip); svc.putExtra("token", token);
        svc.putExtra("session", chatSession); svc.putExtra("message", msg);
        if (ce != null) { svc.putExtra("model", ce.optString("model")); svc.putExtra("effort", ce.optString("effort")); }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
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
            // Every chat persists to its own store; only the open one renders live.
            if (!chatConvoMode || s == null || !s.equals(chatSession)) return;
            String turn = i.getStringExtra("turn");
            String err = i.getStringExtra("error");
            if (turn != null) {
                try { renderSingleTurn(new org.json.JSONObject(turn)); } catch (Throwable e) {}
            } else if (err != null) {
                if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
                addError(err); scrollDown();
            } else if (i.getBooleanExtra("done", false)) {
                if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
            }
        }
    };

    private void renderTurns(org.json.JSONArray turns) {
        for (int i = 0; i < turns.length(); i++) renderSingleTurn(turns.optJSONObject(i));
    }

    // Render one turn as it streams in: thinking and tool calls collapse, text
    // shows as markdown with a copy button, and a tool result carrying a media_url
    // renders the captured image inline.
    private void renderSingleTurn(org.json.JSONObject t) {
        if (chatPending != null) { chatList.removeView(chatPending); chatPending = null; }
        if (t == null) return;
        String type = t.optString("type");
        if ("thinking".equals(type)) {
            String txt = t.optString("text");
            addCollapsible("Thinking", firstLine(txt), txt);
        } else if ("tool_call".equals(type)) {
            org.json.JSONObject res = t.optJSONObject("result");
            addCollapsible(t.optString("name"), toolPreview(t), codeFence(pretty(res)));
            if (res != null && !res.optString("media_url").isEmpty())
                addImage(res.optString("media_url"), res.optString("media_type"));
        } else if ("text".equals(type)) addMarkdown(t.optString("text"));
        scrollDown();
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
    private void scrollDown() { chatScroll.post(() -> chatScroll.smoothScrollTo(0, chatList.getBottom())); }

    private void addUserBubble(String text) {
        TextView tv = new TextView(this); tv.setText(text); tv.setTextColor(col(R.color.onAccent));
        tv.setTextSize(14); tv.setPadding(dp(13), dp(10), dp(13), dp(10)); tv.setBackground(bg(accent(), 16));
        tv.setMaxWidth(dp(260)); addTo(tv, android.view.Gravity.END, 12); scrollDown();
    }
    private TextView addPending() {
        TextView tv = new TextView(this); tv.setText("thinking…"); tv.setTextColor(col(R.color.dim));
        tv.setTextSize(13); tv.setPadding(dp(12), dp(9), dp(12), dp(9)); tv.setBackground(bg(col(R.color.surface2), 14));
        addTo(tv, android.view.Gravity.START, 12); scrollDown(); return tv;
    }
    private void addMarkdown(String md) {
        final String text = md == null ? "" : md;
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(col(R.color.surface2), 16)); box.setPadding(dp(13), dp(11), dp(13), dp(7));
        TextView tv = new TextView(this); markwon.setMarkdown(tv, text);
        tv.setTextColor(col(R.color.text)); tv.setTextSize(14); tv.setLineSpacing(0, 1.2f);
        // LinkMovementMethod (not selectable text) so markdown links open on tap.
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        box.addView(tv);
        TextView copy = new TextView(this); copy.setText("⧉ copy"); copy.setTextColor(col(R.color.dim));
        copy.setTextSize(12); copy.setPadding(dp(6), dp(6), dp(2), dp(1));
        copy.setOnClickListener(v -> { copyText(text); toast("Copied"); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.gravity = android.view.Gravity.END; copy.setLayoutParams(cp); box.addView(copy);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(12); box.setLayoutParams(lp); chatList.addView(box);
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
        path.setTextSize(13); path.setSingleLine(true); path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(path);
        final String[] textHolder = {null};
        header.addView(iconBtn("⧉", v -> { if (textHolder[0] != null) { copyText(textHolder[0]); toast("Copied"); } else toast("nothing to copy"); }));
        header.addView(iconBtn("⇩", v -> downloadMedia(ip, token, mediaUrl, name)));
        header.addView(iconBtn("★", v -> { addPin(name, mediaUrl, mediaType); toast("Pinned"); }));
        header.addView(iconBtn("✕", v -> d.dismiss()));
        root.addView(header);

        final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
        body.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        final TextView loading = new TextView(this); loading.setText("loading…"); loading.setTextColor(col(R.color.dim));
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
        tv.setText(name + "\n\nUse ⇩ to download and open this file.");
        tv.setTextColor(col(R.color.dim)); tv.setTextSize(13); tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        return tv;
    }

    private TextView iconBtn(String label, View.OnClickListener onClick) {
        TextView t = new TextView(this); t.setText(label); t.setTextColor(col(R.color.text)); t.setTextSize(18);
        t.setGravity(android.view.Gravity.CENTER); t.setMinWidth(dp(44)); t.setPadding(dp(6), dp(8), dp(6), dp(8));
        t.setBackground(bg(col(R.color.surface2), 10)); t.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
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
    private void addCollapsible(String label, String preview, String body) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(col(R.color.surface2), 12)); box.setPadding(dp(11), dp(8), dp(11), dp(8));
        final TextView head = new TextView(this); head.setText("▸  " + label); head.setTextColor(col(R.color.dim)); head.setTextSize(12);
        final TextView prev = new TextView(this);
        prev.setTextColor(col(R.color.dim)); prev.setTextSize(12); prev.setAlpha(0.75f);
        prev.setSingleLine(true); prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
        prev.setPadding(dp(15), dp(2), 0, 0);
        markwon.setMarkdown(prev, preview == null ? "" : preview);
        final TextView bodyV = new TextView(this); bodyV.setTextColor(col(R.color.dim));
        bodyV.setTextSize(12); bodyV.setPadding(dp(2), dp(6), 0, 0); bodyV.setVisibility(View.GONE);
        bodyV.setTextIsSelectable(true);
        markwon.setMarkdown(bodyV, body == null ? "" : body);
        head.setOnClickListener(v -> { boolean vis = bodyV.getVisibility() == View.VISIBLE;
            bodyV.setVisibility(vis ? View.GONE : View.VISIBLE);
            prev.setVisibility(vis ? View.VISIBLE : View.GONE);
            head.setText((vis ? "▸  " : "▾  ") + label); });
        box.addView(head);
        if (preview != null && !preview.isEmpty()) box.addView(prev);
        box.addView(bodyV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(12); box.setLayoutParams(lp); chatList.addView(box);
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
        org.json.JSONObject args = t.optJSONObject("args");
        if (args != null) {
            String cmd = args.optString("command");
            if (!cmd.isEmpty()) return firstLine(cmd);
            if (args.length() > 0) return firstLine(args.toString());
        }
        return firstLine(pretty(t.optJSONObject("result")));
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
