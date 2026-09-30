package com.csync.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs one chat request to the assistant as a foreground service holding a
 * wakelock, so a reply that arrives after the app is backgrounded still
 * completes rather than dying to doze. The turns are broadcast back to a live
 * UI; if the UI is not in front when the reply lands, they are stashed for the
 * next resume and a notification is raised.
 */
public class ChatService extends Service {

    static final String ACTION_REPLY = "com.csync.hub.CHAT_REPLY";
    private static final String CHANNEL = "csync";
    private static final int FG_ID = 7003;
    private static final int REPLY_ID = 7002;

    // MainActivity keeps this true while it is resumed, so the service only
    // raises a notification when the reply would otherwise go unseen.
    static volatile boolean uiForeground = false;
    // A reply that landed while the UI was away, drained by MainActivity.onResume.
    static volatile String stashedTurns, stashedError, stashedSession;
    // Number of in-flight chats, so several can stream at once and the foreground
    // service only stops when the last finishes.
    static final java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger(0);
    // Conversations with a reply in flight, and the answer text each has received so far.
    // The screen reads these when it opens a conversation mid-reply, so it shows the whole
    // answer up to now and offers Stop.
    static final java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final java.util.Map<String, StringBuilder> writing = new java.util.concurrent.ConcurrentHashMap<>();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String session = intent.getStringExtra("session");
        final String attached = intent.getStringExtra("attachments");
        // An answer typed in the notification arrives with only the conversation it belongs to,
        // so the rest is looked up here and the message is added to the conversation first.
        android.os.Bundle typed = android.app.RemoteInput.getResultsFromIntent(intent);
        CharSequence answered = typed == null ? null : typed.getCharSequence(TYPED_REPLY);
        final boolean fromShade = answered != null && answered.toString().trim().length() > 0;
        JSONObject saved = fromShade ? conversation(session) : null;
        final String assist = fromShade ? Prefs.assistIp(this) : intent.getStringExtra("assist");
        final String token = fromShade ? Prefs.token(this) : intent.getStringExtra("token");
        final String message = fromShade ? answered.toString().trim() : intent.getStringExtra("message");
        final String model = fromShade ? (saved == null ? "" : saved.optString("model")) : intent.getStringExtra("model");
        final String effort = fromShade ? (saved == null ? "" : saved.optString("effort")) : intent.getStringExtra("effort");
        if (fromShade) {
            try { ChatStore.append(this, session, null, new JSONObject().put("role", "user").put("text", message)); }
            catch (Exception ignored) { }
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(REPLY_ID);
        }

        active.incrementAndGet();
        running.add(session);
        startForeground(FG_ID, sendingNote());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csync:chat");
        wl.acquire(300000);
        final android.content.Context ctx = getApplicationContext();

        new Thread(() -> {
            final JSONArray acc = new JSONArray();
            final String[] err = {null};
            final long[] nudged = {0};
            MeshClient.chatStream(assist, token, session, message, model, effort, encodeAttachments(attached),
                    new MeshClient.TurnSink() {
                public void onTurn(JSONObject turn) {
                    // The whole turn replaces whatever was being written a few characters at a time.
                    writing.remove(session);
                    if (turn != null) { acc.put(turn); ChatStore.append(ctx, session, null, turn); }
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    if (turn != null) b.putExtra("turn", turn.toString());
                    sendBroadcast(b);
                }
                public void onDelta(String text) {
                    StringBuilder soFar = writing.get(session);
                    if (soFar == null) { soFar = new StringBuilder(); writing.put(session, soFar); }
                    synchronized (soFar) { soFar.append(text); }
                    // The screen reads the text from `writing`, so a burst of small pieces needs only one nudge.
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - nudged[0] < 60) return;
                    nudged[0] = now;
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    b.putExtra("writing", true);
                    sendBroadcast(b);
                }
                public void onUsage(JSONObject usage) {
                    ChatStore.usageOnLast(ctx, session, usage);
                }
                public void onError(String message) {
                    running.remove(session);
                    writing.remove(session);
                    err[0] = message;
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    b.putExtra("error", message);
                    sendBroadcast(b);
                }
                public void onDone(String reply) {
                    running.remove(session);
                    writing.remove(session);
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    b.putExtra("done", true);
                    sendBroadcast(b);
                }
            });

            running.remove(session);
            writing.remove(session);
            if (!uiForeground) {
                if (acc.length() > 0) { stashedTurns = acc.toString(); stashedSession = session; notifyReply(session, lastText(acc.toString())); }
                if (err[0] != null) stashedError = err[0];
            }
            try { wl.release(); } catch (Throwable ignore) {}
            if (active.decrementAndGet() <= 0) { stopForeground(true); stopSelf(); }
        }).start();
        return START_NOT_STICKY;
    }

    private Notification sendingNote() {
        ensureChannel();
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Asking the assistant")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true).build();
    }

    private void notifyReply(String session, String text) {
        if (text == null || text.isEmpty()) return;
        ensureChannel();
        String preview = text.length() > 160 ? text.substring(0, 160) : text;
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        // Tapping the notification lands in the conversation that replied, not just in the app.
        PendingIntent pi = PendingIntent.getActivity(this, session.hashCode(),
                new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("destination", "chat").putExtra("open_conversation", session), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        // Reply from the shade: the typed answer comes straight back to this service.
        int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        Intent again = new Intent(this, ChatService.class).putExtra("session", session);
        PendingIntent send = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, session.hashCode(), again, replyFlags)
                : PendingIntent.getService(this, session.hashCode(), again, replyFlags);
        Notification.Action reply = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.csi_send), "Reply", send)
            .addRemoteInput(new android.app.RemoteInput.Builder(TYPED_REPLY).setLabel("Message the Pi").build())
            .build();
        Notification note = b.setContentTitle("Assistant replied")
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .addAction(reply)
                .setAutoCancel(true).build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(REPLY_ID, note);
    }

    private static final String TYPED_REPLY = "typed_reply";

    /** The saved entry for a conversation, which holds the model and effort it was set to. */
    private JSONObject conversation(String session) {
        JSONArray index = ChatStore.index(this);
        for (int i = 0; i < index.length(); i++) {
            JSONObject entry = index.optJSONObject(i);
            if (entry != null && session.equals(entry.optString("id"))) return entry;
        }
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "csync",
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    // The assistant's final text turn, for the notification preview.
    private static String lastText(String turnsJson) {
        try {
            JSONArray t = new JSONArray(turnsJson);
            String last = "";
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.optJSONObject(i);
                if (o != null && "text".equals(o.optString("type"))) last = o.optString("text");
            }
            return last;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Read the cached files the app picked and turn them into {name, mime, data} for the Pi, then delete them. */
    private static JSONArray encodeAttachments(String listed) {
        JSONArray out = new JSONArray();
        if (listed == null || listed.isEmpty()) return out;
        try {
            JSONArray files = new JSONArray(listed);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                java.io.File file = new java.io.File(f.getString("path"));
                byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
                out.put(new JSONObject().put("name", f.optString("name")).put("mime", f.optString("mime"))
                    .put("data", android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)));
                file.delete();
            }
        } catch (Exception ignored) { }
        return out;
    }
}
