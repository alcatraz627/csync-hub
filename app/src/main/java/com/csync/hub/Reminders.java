package com.csync.hub;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Timers and reminders kept on this phone. A timer rings after a length of time, a reminder at a
 * time of day; both ring as a notification even when the app is closed or the phone restarted.
 * This is the general capability the Pi agent and the Mac will later drive.
 */
final class Reminders {

    static final class Item {
        final long id, at;
        final String kind, label;
        boolean fired;
        Item(long id, String kind, String label, long at, boolean fired) {
            this.id = id; this.kind = kind; this.label = label; this.at = at; this.fired = fired;
        }
        boolean timer() { return "timer".equals(kind); }
    }

    private static final String CHANNEL = "csync_reminders";
    private static final String STORE = "reminders";

    private Reminders() {}

    static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                out.add(new Item(o.getLong("id"), o.optString("kind", "reminder"), o.optString("label"),
                    o.getLong("at"), o.optBoolean("fired")));
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    /** Add a timer or reminder and arm its alarm. */
    static Item add(Context c, String kind, String label, long at) {
        Item item = new Item(System.currentTimeMillis(), kind, label, at, false);
        List<Item> items = all(c);
        items.add(item);
        save(c, items);
        arm(c, item);
        return item;
    }

    static void cancel(Context c, long id) {
        List<Item> items = all(c);
        items.removeIf(i -> i.id == id);
        save(c, items);
        alarms(c).cancel(pending(c, id));
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int) (id % Integer.MAX_VALUE));
    }

    /** Forget rung items older than a day, keeping the list short. */
    static void tidy(Context c) {
        long cutoff = System.currentTimeMillis() - 86400000L;
        List<Item> items = all(c);
        if (items.removeIf(i -> i.fired && i.at < cutoff)) save(c, items);
    }

    /** Arm every alarm still to come, as after a restart or an update. */
    static void rearm(Context c) {
        for (Item i : all(c)) if (!i.fired) arm(c, i);
    }

    /** Whether Android lets this app ring on the exact minute; otherwise it may ring a little late. */
    static boolean exact(Context c) {
        return android.os.Build.VERSION.SDK_INT < 31 || alarms(c).canScheduleExactAlarms();
    }

    private static void arm(Context c, Item item) {
        long at = Math.max(item.at, System.currentTimeMillis() + 1000);
        if (exact(c)) alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, item.id));
        else alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, item.id));
    }

    private static void ring(Context c, long id) {
        List<Item> items = all(c);
        Item hit = null;
        for (Item i : items) if (i.id == id) hit = i;
        if (hit == null || hit.fired) return;
        hit.fired = true;
        save(c, items);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Timers and reminders", NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
        Intent open = new Intent(c, MainActivity.class).putExtra("destination", "more").putExtra("detail", "reminders")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification n = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.csi_history)
            .setContentTitle(hit.label.isEmpty() ? (hit.timer() ? "Timer done" : "Reminder") : hit.label)
            .setContentText(hit.timer() ? "Your timer has finished" : "Reminder from csync")
            .setCategory(hit.timer() ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, (int) (id % 100000), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .build();
        nm.notify((int) (id % Integer.MAX_VALUE), n);
    }

    private static void save(Context c, List<Item> items) {
        JSONArray list = new JSONArray();
        try {
            for (Item i : items) list.put(new JSONObject().put("id", i.id).put("kind", i.kind).put("label", i.label)
                .put("at", i.at).put("fired", i.fired));
        } catch (Exception ignored) { }
        prefs(c).edit().putString("items", list.toString()).apply();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(STORE, Context.MODE_PRIVATE); }

    private static AlarmManager alarms(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }

    private static PendingIntent pending(Context c, long id) {
        Intent ring = new Intent(c, Receiver.class).setAction("com.csync.hub.RING").putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int) (id % 100000), ring, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Rings an item when its alarm comes, and arms them all again after the phone restarts. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if ("com.csync.hub.RING".equals(intent.getAction())) ring(c, intent.getLongExtra("id", -1));
            else rearm(c);
        }
    }
}
