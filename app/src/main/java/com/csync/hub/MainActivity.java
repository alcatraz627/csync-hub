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
 * The csync hub: one app holding the xkcd widget's companion screen, the Shizuku
 * system monitor, and the devices page that sends to and receives from other
 * mesh peers over the tailnet. The three pages are inflated once and swapped by
 * the bottom nav bar.
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private View pageXkcd, pageSystem, pageDevices, pageChat;
    private int current = 0; // 0 xkcd, 1 system, 2 devices, 3 chat
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
                public void onBinderReceived() { if (current == 1) ensureShizuku(); }
            };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        FrameLayout content = findViewById(R.id.content);
        LayoutInflater inf = LayoutInflater.from(this);
        pageXkcd = inf.inflate(R.layout.page_xkcd, content, false);
        pageSystem = inf.inflate(R.layout.page_system, content, false);
        pageDevices = inf.inflate(R.layout.page_devices, content, false);
        pageChat = inf.inflate(R.layout.page_chat, content, false);
        content.addView(pageXkcd);
        content.addView(pageSystem);
        content.addView(pageDevices);
        content.addView(pageChat);

        ((Button) findViewById(R.id.nav_xkcd)).setOnClickListener(v -> show(0));
        ((Button) findViewById(R.id.nav_system)).setOnClickListener(v -> show(1));
        ((Button) findViewById(R.id.nav_devices)).setOnClickListener(v -> show(2));
        ((Button) findViewById(R.id.nav_chat)).setOnClickListener(v -> show(3));

        setupSystemPage();
        setupDevicesPage();
        setupChatPage();

        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2002);
        }
        show(0);
    }

    private void show(int page) {
        current = page;
        pageXkcd.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        pageSystem.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        pageDevices.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        pageChat.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        if (page == 1) ensureShizuku();
        if (page == 2) refreshDevicesHeader();
        if (page == 3) warmChat();
    }

    // Wake the tailnet path to the assistant so the first message is not the cold
    // start that would otherwise time out.
    private void warmChat() {
        final String ip = Prefs.assistIp(this);
        if (ip.isEmpty()) return;
        new Thread(() -> MeshClient.warmUp(ip, MeshClient.ASSIST_PORT)).start();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; if (current == 1) ensureShizuku(); }
    @Override protected void onPause() { super.onPause(); resumed = false; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permListener);
        Shizuku.removeBinderReceivedListener(binderListener);
    }

    // ---------------- system page (Shizuku top) ----------------

    private void setupSystemPage() {
        sysStatus = pageSystem.findViewById(R.id.sys_status);
        sysOutput = pageSystem.findViewById(R.id.sys_output);
        ((Button) pageSystem.findViewById(R.id.sys_refresh)).setOnClickListener(v -> sysTickOnce());
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
        if (!resumed || current != 1) { sysLooping = false; return; }
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
        devSelf = pageDevices.findViewById(R.id.dev_self);
        devIp = pageDevices.findViewById(R.id.dev_ip);
        devToken = pageDevices.findViewById(R.id.dev_token);
        devText = pageDevices.findViewById(R.id.dev_text);
        devStatus = pageDevices.findViewById(R.id.dev_status);
        devReceiver = pageDevices.findViewById(R.id.dev_receiver);

        devIp.setText(Prefs.homeIp(this));
        devToken.setText(Prefs.token(this));

        pageDevices.findViewById(R.id.dev_save).setOnClickListener(v -> {
            Prefs.save(this, devIp.getText().toString(), devToken.getText().toString());
            toast("Saved");
        });
        pageDevices.findViewById(R.id.dev_send_text).setOnClickListener(v ->
                sendText(devText.getText().toString()));
        pageDevices.findViewById(R.id.dev_send_clip).setOnClickListener(v -> sendText(clipboardText()));
        pageDevices.findViewById(R.id.dev_scan).setOnClickListener(v -> scan());
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
    private TextView chatLog;
    private ScrollView chatScroll;
    private String chatSession;

    private void setupChatPage() {
        chatIp = pageChat.findViewById(R.id.chat_assist_ip);
        chatInput = pageChat.findViewById(R.id.chat_input);
        chatLog = pageChat.findViewById(R.id.chat_log);
        chatScroll = pageChat.findViewById(R.id.chat_scroll);
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
        appendChat("You", msg);
        chatInput.setText("");
        appendChat("csync", "…");
        new Thread(() -> {
            String reply;
            try {
                reply = MeshClient.chat(ip, token, chatSession, msg);
            } catch (Throwable e) {
                reply = "[error] " + e.getMessage();
            }
            final String r = reply;
            ui.post(() -> replaceLastReply(r));
        }).start();
    }

    private void resetChat() {
        final String ip = Prefs.assistIp(this), token = Prefs.token(this);
        chatSession = Prefs.deviceName(this) + "-" + System.currentTimeMillis();
        chatLog.setText("");
        if (!ip.isEmpty() && !token.isEmpty()) {
            final String session = chatSession;
            new Thread(() -> {
                try { MeshClient.chatReset(ip, token, session); } catch (Throwable ignore) {}
            }).start();
        }
        toast("New conversation");
    }

    private void appendChat(String who, String text) {
        chatLog.append(who + ":  " + text + "\n\n");
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    // Swap the "…" placeholder for the real reply once it arrives.
    private void replaceLastReply(String reply) {
        String s = chatLog.getText().toString();
        int i = s.lastIndexOf("csync:  …\n\n");
        if (i >= 0) {
            chatLog.setText(s.substring(0, i));
            chatLog.append("csync:  " + reply + "\n\n");
        } else {
            chatLog.append("csync:  " + reply + "\n\n");
        }
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
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
