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

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String assist = intent.getStringExtra("assist");
        final String token = intent.getStringExtra("token");
        final String session = intent.getStringExtra("session");
        final String message = intent.getStringExtra("message");

        active.incrementAndGet();
        startForeground(FG_ID, sendingNote());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "csync:chat");
        wl.acquire(300000);
        final android.content.Context ctx = getApplicationContext();

        new Thread(() -> {
            final JSONArray acc = new JSONArray();
            final String[] err = {null};
            MeshClient.chatStream(assist, token, session, message, new MeshClient.TurnSink() {
                public void onTurn(JSONObject turn) {
                    if (turn != null) { acc.put(turn); ChatStore.append(ctx, session, null, turn); }
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    if (turn != null) b.putExtra("turn", turn.toString());
                    sendBroadcast(b);
                }
                public void onError(String message) {
                    err[0] = message;
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    b.putExtra("error", message);
                    sendBroadcast(b);
                }
                public void onDone(String reply) {
                    Intent b = new Intent(ACTION_REPLY).setPackage(getPackageName());
                    b.putExtra("session", session);
                    b.putExtra("done", true);
                    sendBroadcast(b);
                }
            });

            if (!uiForeground) {
                if (acc.length() > 0) { stashedTurns = acc.toString(); stashedSession = session; notifyReply(lastText(acc.toString())); }
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
        return b.setContentTitle("Asking the assistant…")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true).build();
    }

    private void notifyReply(String text) {
        if (text == null || text.isEmpty()) return;
        ensureChannel();
        String preview = text.length() > 160 ? text.substring(0, 160) + "…" : text;
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification note = b.setContentTitle("Assistant replied")
                .setContentText(preview)
                .setStyle(new Notification.BigTextStyle().bigText(preview))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true).build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(REPLY_ID, note);
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
}
