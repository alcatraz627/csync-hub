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
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reminders kept on this phone: something to remember at a time of day, which rings as a
 * notification even when the app is closed or the phone has restarted, and can be snoozed or
 * marked done from the notification itself. Timers are separate; see {@link Timers}.
 */
final class Reminders {

    static final class Item {
        final long id;
        long at;
        String label;
        boolean rang;
        Item(long id, String label, long at, boolean rang) { this.id = id; this.label = label; this.at = at; this.rang = rang; }
    }

    private static final String CHANNEL = "csync_reminders";
    private static final String STORE = "reminders";
    static final String ACT_RING = "com.csync.hub.RING", ACT_SNOOZE = "com.csync.hub.REMINDER_SNOOZE",
        ACT_DONE = "com.csync.hub.REMINDER_DONE";

    private Reminders() {}

    // ---- listening ----

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    private static void changed(Context c) {
        main.post(() -> { for (Runnable r : listeners) r.run(); });
        HubWidgets.refreshReminders(c.getApplicationContext());
    }

    // ---- the reminders ----

    static List<Item> all(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if ("timer".equals(o.optString("kind"))) continue;
                out.add(new Item(o.getLong("id"), o.optString("label"), o.getLong("at"),
                    o.optBoolean("rang", o.optBoolean("fired"))));
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> Long.compare(a.at, b.at));
        return out;
    }

    static Item find(Context c, long id) {
        for (Item i : all(c)) if (i.id == id) return i;
        return null;
    }

    /** The next reminder still to ring, or null. */
    static Item next(Context c) {
        for (Item i : all(c)) if (!i.rang) return i;
        return null;
    }

    static Item add(Context c, String label, long at) {
        List<Item> items = all(c);
        long id = System.currentTimeMillis();
        for (Item other : items) if (other.id >= id) id = other.id + 1;
        Item item = new Item(id, label == null || label.trim().isEmpty() ? "Reminder" : label.trim(), at, false);
        items.add(item);
        save(c, items);
        arm(c, item);
        changed(c);
        return item;
    }

    /** Change what or when; a reminder moved into the future rings again. */
    static void edit(Context c, long id, String label, long at) {
        List<Item> items = all(c);
        for (Item i : items) if (i.id == id) {
            if (label != null && !label.trim().isEmpty()) i.label = label.trim();
            if (at > 0) {
                i.at = at;
                if (at > System.currentTimeMillis()) i.rang = false;
            }
            save(c, items);
            silence(c, id);
            if (!i.rang) arm(c, i);
            changed(c);
            return;
        }
    }

    static void snooze(Context c, long id, int minutes) {
        edit(c, id, null, System.currentTimeMillis() + minutes * 60_000L);
    }

    /** Done with it: the notification goes and the reminder stays in the list as rung. */
    static void done(Context c, long id) {
        List<Item> items = all(c);
        for (Item i : items) if (i.id == id) i.rang = true;
        save(c, items);
        alarms(c).cancel(pending(c, id));
        silence(c, id);
        changed(c);
    }

    static void cancel(Context c, long id) {
        List<Item> items = all(c);
        items.removeIf(i -> i.id == id);
        save(c, items);
        alarms(c).cancel(pending(c, id));
        silence(c, id);
        changed(c);
    }

    /** Forget rung reminders older than a week, keeping the list short. */
    static void tidy(Context c) {
        long cutoff = System.currentTimeMillis() - 7 * 86_400_000L;
        List<Item> items = all(c);
        if (items.removeIf(i -> i.rang && i.at < cutoff)) save(c, items);
    }

    /** Arm every reminder still to come, as after a restart or an update. */
    static void rearm(Context c) {
        long now = System.currentTimeMillis();
        for (Item i : all(c)) if (!i.rang) { if (i.at <= now) ring(c, i.id); else arm(c, i); }
    }

    /** The timers the store held before timers had their own; they are removed here once taken. */
    static List<Item> takeOldTimers(Context c) {
        List<Item> old = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString("items", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                if ("timer".equals(o.optString("kind"))) {
                    if (!o.optBoolean("fired")) old.add(new Item(o.getLong("id"), o.optString("label"), o.getLong("at"), false));
                    alarms(c).cancel(pending(c, o.getLong("id")));
                } else kept.put(o);
            }
            prefs(c).edit().putString("items", kept.toString()).commit();
        } catch (Exception ignored) { }
        return old;
    }

    /** Whether Android lets this app ring on the exact minute; otherwise it may ring a little late. */
    static boolean exact(Context c) {
        return android.os.Build.VERSION.SDK_INT < 31 || alarms(c).canScheduleExactAlarms();
    }

    // ---- typed reminders ----

    /** What a typed reminder says and when, or null for an empty line. */
    static final class Parsed {
        final String label; final long at;
        Parsed(String label, long at) { this.label = label; this.at = at; }
    }

    private static final String[] DAYS = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};

    /**
     * Read "call home at 18:30", "water the plants tomorrow at 9am", "in 20 min check the oven",
     * "pay rent on the 1st" or "gym on friday at 7". With no time, it is the next whole hour.
     * {@code day} is the day the person is looking at, so a bare time lands on it.
     */
    static Parsed parse(String text, long now, long day) {
        String words = text == null ? "" : text.trim();
        if (words.isEmpty()) return null;
        long at;
        Matcher m = Pattern.compile("\\bin (\\d+)\\s*(min|mins|minutes|minute|m|h|hr|hrs|hour|hours)\\b", Pattern.CASE_INSENSITIVE).matcher(words);
        if (m.find()) {
            boolean hours = m.group(2).toLowerCase(java.util.Locale.ROOT).startsWith("h");
            at = now + Long.parseLong(m.group(1)) * (hours ? 60 : 1) * 60_000L;
            words = words.substring(0, m.start()) + words.substring(m.end());
        } else {
            Calendar d = Calendar.getInstance();
            d.setTimeInMillis(startOfDay(day > 0 && startOfDay(day) != startOfDay(now) ? day : now));
            boolean dayGiven = false;
            Matcher tomorrow = Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE).matcher(words);
            if (tomorrow.find()) {
                d.setTimeInMillis(startOfDay(now));
                d.add(Calendar.DAY_OF_MONTH, 1);
                words = words.substring(0, tomorrow.start()) + words.substring(tomorrow.end());
                dayGiven = true;
            }
            Matcher weekday = Pattern.compile("\\bon (sun|mon|tue|wed|thu|fri|sat)\\w*\\b", Pattern.CASE_INSENSITIVE).matcher(words);
            if (weekday.find()) {
                int want = java.util.Arrays.asList(DAYS).indexOf(weekday.group(1).toLowerCase(java.util.Locale.ROOT)) + 1;
                d.setTimeInMillis(startOfDay(now));
                do d.add(Calendar.DAY_OF_MONTH, 1); while (d.get(Calendar.DAY_OF_WEEK) != want);
                words = words.substring(0, weekday.start()) + words.substring(weekday.end());
                dayGiven = true;
            }
            Matcher dateOf = Pattern.compile("\\bon the (\\d{1,2})(st|nd|rd|th)?\\b", Pattern.CASE_INSENSITIVE).matcher(words);
            if (dateOf.find()) {
                int date = Integer.parseInt(dateOf.group(1));
                d.setTimeInMillis(startOfDay(now));
                if (date < d.get(Calendar.DAY_OF_MONTH)) d.add(Calendar.MONTH, 1);
                d.set(Calendar.DAY_OF_MONTH, Math.min(date, d.getActualMaximum(Calendar.DAY_OF_MONTH)));
                words = words.substring(0, dateOf.start()) + words.substring(dateOf.end());
                dayGiven = true;
            }
            Matcher time = Pattern.compile("\\bat (\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\b", Pattern.CASE_INSENSITIVE).matcher(words);
            int hour, minute = 0;
            if (time.find()) {
                hour = Integer.parseInt(time.group(1));
                if (time.group(2) != null) minute = Integer.parseInt(time.group(2));
                String half = time.group(3);
                if (half != null) {
                    if (half.equalsIgnoreCase("pm") && hour < 12) hour += 12;
                    if (half.equalsIgnoreCase("am") && hour == 12) hour = 0;
                }
                words = words.substring(0, time.start()) + words.substring(time.end());
            } else {
                Calendar n = Calendar.getInstance();
                n.setTimeInMillis(now);
                hour = n.get(Calendar.HOUR_OF_DAY) + 1;
            }
            d.set(Calendar.HOUR_OF_DAY, Math.min(23, hour));
            d.set(Calendar.MINUTE, Math.min(59, minute));
            at = d.getTimeInMillis();
            if (at <= now && !dayGiven) at += 86_400_000L;
        }
        String label = words.replaceFirst("(?i)\\b(remind me to|remind me|to)\\b", "").replaceAll("\\s+", " ").trim();
        if (label.isEmpty()) label = "Reminder";
        else label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        return new Parsed(label, at);
    }

    static long startOfDay(long t) {
        Calendar d = Calendar.getInstance();
        d.setTimeInMillis(t);
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    // ---- ringing ----

    private static void arm(Context c, Item item) {
        long at = Math.max(item.at, System.currentTimeMillis() + 1000);
        if (exact(c)) alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, item.id));
        else alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c, item.id));
    }

    private static void ring(Context c, long id) {
        List<Item> items = all(c);
        Item hit = null;
        for (Item i : items) if (i.id == id) hit = i;
        if (hit == null || hit.rang) return;
        hit.rang = true;
        save(c, items);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
        Intent open = new Intent(c, MainActivity.class).putExtra("destination", "more").putExtra("detail", "reminders")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification n = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.csi_bell)
            .setContentTitle(hit.label)
            .setContentText(android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(hit.at)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, (int) (id % 100000), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(new Notification.Action.Builder(null, "In 10 minutes", action(c, ACT_SNOOZE, id)).build())
            .addAction(new Notification.Action.Builder(null, "Done", action(c, ACT_DONE, id)).build())
            .build();
        nm.notify(notificationId(id), n);
        changed(c);
    }

    static PendingIntent action(Context c, String what, long id) {
        Intent i = new Intent(c, Receiver.class).setAction(what).putExtra("id", id);
        return PendingIntent.getBroadcast(c, what.hashCode() * 31 + (int) (id % 100000), i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int notificationId(long id) { return (int) (id % Integer.MAX_VALUE); }

    private static void silence(Context c, long id) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notificationId(id));
    }

    private static void save(Context c, List<Item> items) {
        JSONArray list = new JSONArray();
        try {
            for (Item i : items) list.put(new JSONObject().put("id", i.id).put("kind", "reminder").put("label", i.label)
                .put("at", i.at).put("rang", i.rang));
        } catch (Exception ignored) { }
        prefs(c).edit().putString("items", list.toString()).commit();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(STORE, Context.MODE_PRIVATE); }

    private static AlarmManager alarms(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }

    private static PendingIntent pending(Context c, long id) {
        Intent ring = new Intent(c, Receiver.class).setAction(ACT_RING).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int) (id % 100000), ring, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Rings a reminder when its alarm comes, hears its buttons, and arms them all again after a restart. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            String what = intent.getAction();
            long id = intent.getLongExtra("id", -1);
            if (ACT_RING.equals(what)) ring(c, id);
            else if (ACT_SNOOZE.equals(what)) snooze(c, id, 10);
            else if (ACT_DONE.equals(what)) done(c, id);
            else {
                rearm(c);
                Timers.migrate(c);
                Timers.rearm(c);
            }
        }
    }
}
