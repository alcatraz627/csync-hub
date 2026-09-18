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

/**
 * The csync hub: one app to reach the owner's own machines over Tailscale. Five
 * surfaces sit behind a bottom nav (Home, Share, Chat, Tools, Settings): Home is
 * a status-and-capabilities map, Share sends to mesh peers, Chat talks to the Pi
 * assistant, Tools holds the xkcd widget help and the Shizuku system monitor, and
 * Settings carries appearance (with more to come). Every page is inflated once at
 * startup and shown or hidden by {@link #show(int)}.
 */
public class MainActivity extends androidx.appcompat.app.AppCompatActivity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Five surfaces swapped by the bottom nav: Home, Share, Chat, Tools, Settings.
    // Share reuses the old devices page for now; Tools folds the xkcd and system
    // pages into one. current indexes them 0..4 in that order.
    private View pageHome, pageShare, pageChat, pageTools, pageSettings;
    private int current = 0; // 0 home, 1 share, 2 chat, 3 tools, 4 settings
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
        content.addView(pageHome);
        content.addView(pageShare);
        content.addView(pageChat);
        content.addView(pageTools);
        content.addView(pageSettings);

        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) show(0);
            else if (id == R.id.nav_share) show(1);
            else if (id == R.id.nav_chat) show(2);
            else if (id == R.id.nav_tools) show(3);
            else if (id == R.id.nav_settings) show(4);
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
        int[] navIds = {R.id.nav_home, R.id.nav_share, R.id.nav_chat, R.id.nav_tools, R.id.nav_settings};
        int start = b != null ? b.getInt("tab", 0) : 0;
        if (start < 0 || start >= navIds.length) start = 0;
        nav.setSelectedItemId(navIds[start]);
    }

    // Keep the open surface across a theme or accent change, which recreates the
    // Activity; without this the app would snap back to Home on every toggle.
    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("tab", current);
    }

    private void show(int page) {
        current = page;
        pageHome.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageShare.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageChat.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(page == 4 ? View.VISIBLE : View.GONE);
        if (page == 0) refreshHome();
        if (page == 1) refreshShare();
        if (page == 2) warmChat();
        if (page == 3) ensureShizuku();
        if (page == 4) { refreshConnection(); refreshAssistant(); }
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
            String t = ChatService.stashedTurns, e = ChatService.stashedError;
            ChatService.stashedTurns = null; ChatService.stashedError = null;
            deliverReply(t, e);
        }
        ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(7002);
        if (current == 3) ensureShizuku();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
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
        homeCaps = pageHome.findViewById(R.id.home_caps);
        com.google.android.material.bottomnavigation.BottomNavigationView nav = findViewById(R.id.nav);
        pageHome.findViewById(R.id.home_share).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_share));
        pageHome.findViewById(R.id.home_chat).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_chat));
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

    private EditText chatInput;
    private TextView chatSubtitle;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private String chatSession;
    private io.noties.markwon.Markwon markwon;

    private void setupChatPage() {
        chatInput = pageChat.findViewById(R.id.chat_input);
        chatList = pageChat.findViewById(R.id.chat_list);
        chatScroll = pageChat.findViewById(R.id.chat_scroll);
        chatSubtitle = pageChat.findViewById(R.id.chat_subtitle);
        markwon = buildMarkwon();
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();
        chatSubtitle.setText("assistant on " + Prefs.assistIp(this));

        pageChat.findViewById(R.id.chat_send).setOnClickListener(v -> sendChat());
        pageChat.findViewById(R.id.chat_reset).setOnClickListener(v -> resetChat());
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
        addUserBubble(msg);
        chatInput.setText("");
        if (chatPending != null) chatList.removeView(chatPending);
        chatPending = addPending();
        Intent svc = new Intent(this, ChatService.class);
        svc.putExtra("assist", ip); svc.putExtra("token", token);
        svc.putExtra("session", chatSession); svc.putExtra("message", msg);
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
            deliverReply(i.getStringExtra("turns"), i.getStringExtra("error"));
        }
    };

    private void renderTurns(org.json.JSONArray turns) {
        for (int i = 0; i < turns.length(); i++) {
            org.json.JSONObject t = turns.optJSONObject(i);
            if (t == null) continue;
            String type = t.optString("type");
            if ("thinking".equals(type)) addCollapsible("Thinking", t.optString("text"), true);
            else if ("tool_call".equals(type)) addCollapsible(t.optString("name"), pretty(t.optJSONObject("result")), false);
            else if ("text".equals(type)) addMarkdown(t.optString("text"));
        }
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
    private void scrollDown() { chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN)); }

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
        TextView tv = new TextView(this); markwon.setMarkdown(tv, md == null ? "" : md);
        tv.setTextColor(col(R.color.text)); tv.setTextSize(14); tv.setLineSpacing(0, 1.2f);
        tv.setPadding(dp(13), dp(11), dp(13), dp(11)); tv.setBackground(bg(col(R.color.surface2), 16));
        tv.setTextIsSelectable(true); addTo(tv, android.view.Gravity.START, 12);
    }
    private void addError(String msg) {
        TextView tv = new TextView(this); tv.setText(msg == null ? "failed" : msg); tv.setTextColor(col(R.color.onAccent));
        tv.setTextSize(13); tv.setPadding(dp(12), dp(9), dp(12), dp(9)); tv.setBackground(bg(col(R.color.danger), 12));
        addTo(tv, android.view.Gravity.START, 12);
    }
    // A collapsible block: a dim header that toggles a body. Used for thinking and tool calls.
    private void addCollapsible(String label, String body, boolean italic) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(bg(col(R.color.surface2), 12)); box.setPadding(dp(11), dp(8), dp(11), dp(8));
        final TextView head = new TextView(this); head.setText("▸  " + label); head.setTextColor(col(R.color.dim)); head.setTextSize(12);
        final TextView bodyV = new TextView(this); bodyV.setTextColor(col(R.color.dim));
        bodyV.setTextSize(12); bodyV.setPadding(0, dp(6), 0, 0); bodyV.setVisibility(View.GONE);
        bodyV.setTextIsSelectable(true);
        markwon.setMarkdown(bodyV, body == null ? "" : body);
        head.setOnClickListener(v -> { boolean vis = bodyV.getVisibility() == View.VISIBLE;
            bodyV.setVisibility(vis ? View.GONE : View.VISIBLE); head.setText((vis ? "▸  " : "▾  ") + label); });
        box.addView(head); box.addView(bodyV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.START; lp.topMargin = dp(12); box.setLayoutParams(lp); chatList.addView(box);
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
