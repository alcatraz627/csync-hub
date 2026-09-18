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
        pageShare = inf.inflate(R.layout.page_devices, content, false);
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
        setupDevicesPage();
        setupChatPage();
        setupSettingsPage();

        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2002);
        }
        nav.setSelectedItemId(R.id.nav_home);
    }

    private void show(int page) {
        current = page;
        pageHome.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageShare.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageChat.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageTools.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(page == 4 ? View.VISIBLE : View.GONE);
        if (page == 0) refreshHome();
        if (page == 1) refreshDevicesHeader();
        if (page == 2) warmChat();
        if (page == 3) ensureShizuku();
    }

    // Wake the tailnet path to the assistant so the first message is not the cold
    // start that would otherwise time out.
    private void warmChat() {
        final String ip = Prefs.assistIp(this);
        if (ip.isEmpty()) return;
        new Thread(() -> MeshClient.warmUp(ip, MeshClient.ASSIST_PORT)).start();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; if (current == 3) ensureShizuku(); }
    @Override protected void onPause() { super.onPause(); resumed = false; }

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

    // ---------------- devices page (mesh) ----------------

    private EditText devIp, devToken, devText;
    private TextView devSelf, devStatus;
    private Button devReceiver;

    private void setupDevicesPage() {
        devSelf = pageShare.findViewById(R.id.dev_self);
        devIp = pageShare.findViewById(R.id.dev_ip);
        devToken = pageShare.findViewById(R.id.dev_token);
        devText = pageShare.findViewById(R.id.dev_text);
        devStatus = pageShare.findViewById(R.id.dev_status);
        devReceiver = pageShare.findViewById(R.id.dev_receiver);

        devIp.setText(Prefs.homeIp(this));
        devToken.setText(Prefs.token(this));

        pageShare.findViewById(R.id.dev_save).setOnClickListener(v -> {
            Prefs.save(this, devIp.getText().toString(), devToken.getText().toString());
            toast("Saved");
        });
        pageShare.findViewById(R.id.dev_send_text).setOnClickListener(v ->
                sendText(devText.getText().toString()));
        pageShare.findViewById(R.id.dev_send_clip).setOnClickListener(v -> sendText(clipboardText()));
        pageShare.findViewById(R.id.dev_scan).setOnClickListener(v -> scan());
        devReceiver.setOnClickListener(v -> toggleReceiver());

        updateReceiverButton();
    }

    private void refreshDevicesHeader() {
        String ip = MeshClient.tailnetIP();
        devSelf.setText("This device: " + Prefs.deviceName(this)
                + "\nTailnet IP: " + (ip == null ? "Tailscale not up" : ip));
        updateReceiverButton();
    }

    private void sendText(final String text) {
        if (text == null || text.isEmpty()) { toast("Nothing to send"); return; }
        final String ip = Prefs.homeIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) { toast("Save the home peer first"); return; }
        final String from = Prefs.deviceName(this);
        new Thread(() -> {
            String result;
            try {
                MeshClient.send(ip, token, from, "text", "shared.txt", text.getBytes("UTF-8"));
                result = "sent text to " + ip;
            } catch (Throwable e) {
                result = "failed: " + e.getMessage();
            }
            final String r = result;
            ui.post(() -> devStatus.setText(r));
        }).start();
    }

    private void scan() {
        final String ip = Prefs.homeIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) { toast("Save the home peer first"); return; }
        devStatus.setText("scanning via " + ip + " ...");
        new Thread(() -> {
            String out;
            try {
                JSONArray arr = MeshClient.peers(ip, token);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    String agent = p.optBoolean("reachable") ? "agent"
                            : (p.optBoolean("online") ? "online" : "offline");
                    sb.append(p.optString("name")).append("  ")
                      .append(p.optString("ip")).append("  ")
                      .append(agent).append("  ")
                      .append(p.optString("platform")).append('\n');
                }
                out = sb.length() == 0 ? "no peers" : sb.toString();
            } catch (Throwable e) {
                out = "scan failed: " + e.getMessage();
            }
            final String r = out;
            ui.post(() -> devStatus.setText(r));
        }).start();
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
        ui.postDelayed(this::updateReceiverButton, 400);
    }

    private void updateReceiverButton() {
        if (devReceiver == null) return;
        if (MeshService.running) {
            devReceiver.setText("Stop receiving");
            devStatus.setText("receiving on " + MeshService.boundInfo);
        } else {
            devReceiver.setText("Start receiving");
        }
    }

    // ---------------- chat page (assistant on the Pi) ----------------

    private EditText chatIp, chatInput;
    private LinearLayout chatList;
    private ScrollView chatScroll;
    private String chatSession;
    private io.noties.markwon.Markwon markwon;

    private void setupChatPage() {
        chatIp = pageChat.findViewById(R.id.chat_assist_ip);
        chatInput = pageChat.findViewById(R.id.chat_input);
        chatList = pageChat.findViewById(R.id.chat_list);
        chatScroll = pageChat.findViewById(R.id.chat_scroll);
        markwon = buildMarkwon();
        chatIp.setText(Prefs.assistIp(this));
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();

        pageChat.findViewById(R.id.chat_assist_save).setOnClickListener(v -> {
            Prefs.saveAssistIp(this, chatIp.getText().toString());
            toast("Assistant set to " + Prefs.assistIp(this));
        });
        pageChat.findViewById(R.id.chat_send).setOnClickListener(v -> sendChat());
        pageChat.findViewById(R.id.chat_reset).setOnClickListener(v -> resetChat());
    }

    private void sendChat() {
        final String msg = chatInput.getText().toString().trim();
        if (msg.isEmpty()) return;
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        if (ip.isEmpty() || token.isEmpty()) { toast("Set the assistant IP and save the token in Devices"); return; }
        addUserBubble(msg);
        chatInput.setText("");
        final TextView pending = addPending();
        new Thread(() -> {
            org.json.JSONArray turns = null; String err = null;
            try { turns = MeshClient.chatTurns(ip, token, chatSession, msg); }
            catch (Throwable e) { err = e.getMessage(); }
            final org.json.JSONArray t = turns; final String e = err;
            ui.post(() -> { chatList.removeView(pending); if (t != null) renderTurns(t); else addError(e); scrollDown(); });
        }).start();
    }

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
