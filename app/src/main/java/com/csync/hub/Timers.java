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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Timers kept on this phone: up to four at once, each with a label and a colour, that ring as a
 * notification even when the app is closed or the phone has restarted.
 *
 * Every change goes through here, whether it comes from the Timers screen, the dock, a widget, a
 * notification button or a shortcut, so they all agree. Listeners hear about each change on the
 * main thread. Saved tiles (quick timers started with one tap) live here too.
 */
final class Timers {
    private Timers() {}

    static final int MAX = 4;
    static final long MINUTE = 60_000L;
    static final String[] COLOURS = {"coral", "teal", "violet", "rust", "blue", "leaf", "rose"};

    static final class Timer {
        long id;
        String label = "", colour = "coral";
        // The length it runs for, which adding or taking time changes.
        long total;
        // When it rings while running; how much is left while paused.
        long end, remain;
        boolean paused, done;

        long left(long now) { return done ? 0 : paused ? remain : Math.max(0, end - now); }
        float fraction(long now) { return done ? 1f : total <= 0 ? 0f : Math.max(0f, Math.min(1f, left(now) / (float) total)); }
        String name() { return label.isEmpty() ? "Timer" : label; }
    }

    static final class Tile {
        long id;
        String label, colour;
        int minutes;
        Tile(long id, String label, int minutes, String colour) { this.id = id; this.label = label; this.minutes = minutes; this.colour = colour; }
    }

    // ---- listening ----

    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void listen(Runnable r) { listeners.add(r); }
    static void unlisten(Runnable r) { listeners.remove(r); }

    private static void changed(Context c) {
        Context app = c.getApplicationContext();
        main.post(() -> { for (Runnable r : listeners) r.run(); });
        TimerService.sync(app);
        HubWidgets.refreshTimers(app);
    }

    // ---- the timers ----

    static List<Timer> all(Context c) {
        List<Timer> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString("timers", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Timer t = new Timer();
                t.id = o.getLong("id");
                t.label = o.optString("label");
                t.colour = o.optString("colour", "coral");
                t.total = o.getLong("total");
                t.end = o.optLong("end");
                t.remain = o.optLong("remain");
                t.paused = o.optBoolean("paused");
                t.done = o.optBoolean("done");
                out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Timer find(Context c, long id) {
        for (Timer t : all(c)) if (t.id == id) return t;
        return null;
    }

    static int running(Context c) {
        int n = 0;
        for (Timer t : all(c)) if (!t.done) n++;
        return n;
    }

    /** Start a timer. Returns null when four already run. */
    static Timer start(Context c, int minutes, String label, String colour) {
        return startMs(c, Math.max(1, minutes) * MINUTE, label, colour);
    }

    static Timer startMs(Context c, long length, String label, String colour) {
        List<Timer> list = all(c);
        int live = 0;
        for (Timer t : list) if (!t.done) live++;
        if (live >= MAX) return null;
        Timer t = new Timer();
        t.id = System.currentTimeMillis();
        for (Timer other : list) if (other.id >= t.id) t.id = other.id + 1;
        t.label = label == null ? "" : label.trim();
        t.colour = colour == null ? "coral" : colour;
        t.total = length;
        t.end = System.currentTimeMillis() + length;
        t.remain = length;
        list.add(t);
        save(c, list);
        arm(c, t);
        changed(c);
        return t;
    }

    static void pauseToggle(Context c, long id) {
        update(c, id, t -> {
            if (t.done) return;
            long now = System.currentTimeMillis();
            if (t.paused) { t.end = now + t.remain; t.paused = false; }
            else { t.remain = Math.max(0, t.end - now); t.paused = true; }
        });
    }

    /**
     * Add time (positive) or take it off (negative). A timer that has rung starts again for the
     * added time, so "add a minute" on a finished timer runs it one more minute.
     */
    static void adjust(Context c, long id, int minutes) {
        adjustMs(c, id, minutes * MINUTE);
    }

    static void adjustMs(Context c, long id, long delta) {
        update(c, id, t -> {
            long now = System.currentTimeMillis();
            if (t.done) {
                if (delta <= 0) return;
                t.done = false; t.paused = false; t.total = delta; t.end = now + delta; t.remain = delta;
                cancelDone(c, t.id);
                return;
            }
            long floor = 10_000L;
            if (t.paused) t.remain = Math.max(floor, t.remain + delta);
            else t.end = Math.max(now + floor, t.end + delta);
            if (delta > 0) t.total += delta;
            else t.total = Math.max(t.left(now), t.total + delta);
        });
    }

    /** Set how much is left, as the dial does when it is dragged on a running timer. */
    static void setLeft(Context c, long id, long left) {
        update(c, id, t -> {
            if (t.done) return;
            long now = System.currentTimeMillis();
            long value = Math.max(10_000L, left);
            if (t.paused) t.remain = value; else t.end = now + value;
            t.total = Math.max(t.total, value);
        });
    }

    static void restart(Context c, long id) {
        update(c, id, t -> {
            t.done = false; t.paused = false; t.end = System.currentTimeMillis() + t.total; t.remain = t.total;
            cancelDone(c, t.id);
        });
    }

    static void rename(Context c, long id, String label) { update(c, id, t -> t.label = label == null ? "" : label.trim()); }

    static void recolour(Context c, long id, String colour) { update(c, id, t -> t.colour = colour); }

    static void stop(Context c, long id) {
        List<Timer> list = all(c);
        list.removeIf(t -> t.id == id);
        save(c, list);
        alarms(c).cancel(ringIntent(c, id));
        notifications(c).cancel(liveId(id));
        cancelDone(c, id);
        changed(c);
    }

    private interface Change { void apply(Timer t); }

    private static void update(Context c, long id, Change change) {
        List<Timer> list = all(c);
        Timer hit = null;
        for (Timer t : list) if (t.id == id) hit = t;
        if (hit == null) return;
        change.apply(hit);
        save(c, list);
        if (hit.done || hit.paused) alarms(c).cancel(ringIntent(c, id));
        else arm(c, hit);
        changed(c);
    }

    /** Arm every timer still running, as after a restart or an update, and ring any that ran out meanwhile. */
    static void rearm(Context c) {
        long now = System.currentTimeMillis();
        for (Timer t : all(c)) {
            if (t.done || t.paused) continue;
            if (t.end <= now) ring(c, t.id); else arm(c, t);
        }
        changed(c);
    }

    // ---- ringing ----

    private static void arm(Context c, Timer t) {
        long at = Math.max(t.end, System.currentTimeMillis() + 500);
        if (Reminders.exact(c)) alarms(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(c, t.id));
        else alarms(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(c, t.id));
    }

    private static void ring(Context c, long id) {
        List<Timer> list = all(c);
        Timer hit = null;
        for (Timer t : list) if (t.id == id) hit = t;
        if (hit == null || hit.done || hit.paused) return;
        hit.done = true;
        hit.end = System.currentTimeMillis();
        save(c, list);
        notifications(c).cancel(liveId(id));
        channels(c);
        Timer t = hit;
        Notification.Builder n = new Notification.Builder(c, CHANNEL_DONE)
            .setSmallIcon(R.drawable.csi_timer)
            .setLargeIcon(ring(c, t, 1f, true))
            .setContentTitle(t.name() + " is done")
            .setContentText("Rang at " + android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(t.end)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setColor(colour(c, t.colour))
            .setAutoCancel(true)
            .setContentIntent(openTimers(c, t.id))
            .setDeleteIntent(action(c, ACT_DISMISS, t.id))
            .addAction(new Notification.Action.Builder(null, "Add a minute", action(c, ACT_ADD, t.id)).build())
            .addAction(new Notification.Action.Builder(null, "Restart", action(c, ACT_RESTART, t.id)).build())
            .addAction(new Notification.Action.Builder(null, "Dismiss", action(c, ACT_DISMISS, t.id)).build());
        notifications(c).notify(doneId(id), n.build());
        changed(c);
    }

    // ---- notifications ----

    static final String CHANNEL_LIVE = "csync_timers_live", CHANNEL_DONE = "csync_timers_done";
    static final String ACT_PAUSE = "com.csync.hub.TIMER_PAUSE", ACT_ADD = "com.csync.hub.TIMER_ADD",
        ACT_STOP = "com.csync.hub.TIMER_STOP", ACT_RESTART = "com.csync.hub.TIMER_RESTART",
        ACT_DISMISS = "com.csync.hub.TIMER_DISMISS", ACT_RING = "com.csync.hub.TIMER_RING",
        ACT_TILE = "com.csync.hub.TIMER_TILE";

    static void channels(Context c) {
        NotificationManager nm = notifications(c);
        NotificationChannel live = new NotificationChannel(CHANNEL_LIVE, "Running timers", NotificationManager.IMPORTANCE_LOW);
        live.setShowBadge(false);
        nm.createNotificationChannel(live);
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "Timers that ring", NotificationManager.IMPORTANCE_HIGH);
        done.enableVibration(true);
        done.setVibrationPattern(new long[]{0, 400, 250, 400, 250, 400});
        nm.createNotificationChannel(done);
    }

    /** The notification for one running or paused timer: its ring, the time left, and its buttons. */
    static Notification live(Context c, Timer t) {
        channels(c);
        long now = System.currentTimeMillis();
        String at = android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(t.end));
        Notification.Builder n = new Notification.Builder(c, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.csi_timer)
            .setLargeIcon(ring(c, t, t.fraction(now), false))
            .setContentTitle(t.name())
            .setColor(colour(c, t.colour))
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setSortKey("t" + t.id)
            .setGroup("csync_timers")
            .setContentIntent(openTimers(c, t.id))
            .addAction(new Notification.Action.Builder(null, t.paused ? "Resume" : "Pause", action(c, ACT_PAUSE, t.id)).build())
            .addAction(new Notification.Action.Builder(null, "Add a minute", action(c, ACT_ADD, t.id)).build())
            .addAction(new Notification.Action.Builder(null, "Stop", action(c, ACT_STOP, t.id)).build());
        if (t.paused) {
            n.setContentText(clock(t.remain) + " left, paused").setUsesChronometer(false).setWhen(now);
        } else {
            n.setContentText("Rings at " + at).setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.end);
        }
        return n.build();
    }

    /**
     * The ring drawn for a notification: the timer's colour around a track, filled for the part
     * still to run, with the share left written inside. A finished timer shows a full green ring.
     */
    static Bitmap ring(Context c, Timer t, float fraction, boolean finished) {
        int size = Kit.dp(c, 64);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float stroke = size * 0.11f;
        RectF box = new RectF(stroke / 2 + 1, stroke / 2 + 1, size - stroke / 2 - 1, size - stroke / 2 - 1);
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x33808080);
        canvas.drawOval(box, track);
        int ink = finished ? Kit.statusColor(c, Kit.Status.GOOD) : colour(c, t.colour);
        if (fraction > 0.004f) {
            Paint arc = new Paint(track);
            arc.setColor(ink);
            arc.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(box, -90, 360f * fraction, false, arc);
        }
        Paint words = new Paint(Paint.ANTI_ALIAS_FLAG);
        words.setColor(ink);
        words.setTextAlign(Paint.Align.CENTER);
        words.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        words.setTextSize(size * 0.26f);
        String said = finished ? "Done" : Math.round(fraction * 100) + "%";
        if (finished) words.setTextSize(size * 0.22f);
        canvas.drawText(said, size / 2f, size / 2f - (words.descent() + words.ascent()) / 2, words);
        return b;
    }

    /** A ring with no words inside, for the widget, which lays its own countdown over it. */
    static Bitmap ringBare(Context c, int colour, float fraction, int sizeDp) {
        int size = Kit.dp(c, sizeDp);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        float stroke = size * 0.085f;
        RectF box = new RectF(stroke / 2 + 1, stroke / 2 + 1, size - stroke / 2 - 1, size - stroke / 2 - 1);
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        track.setColor(0x33808080);
        canvas.drawOval(box, track);
        if (fraction > 0.004f) {
            Paint arc = new Paint(track);
            arc.setColor(colour);
            arc.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(box, -90, 360f * fraction, false, arc);
        }
        return b;
    }

    static int colour(Context c, String name) {
        switch (name == null ? "" : name) {
            case "teal": return ContextCompat.getColor(c, R.color.teal);
            case "violet": return ContextCompat.getColor(c, R.color.violet);
            case "rust": return ContextCompat.getColor(c, R.color.rust);
            case "blue": return ContextCompat.getColor(c, R.color.blue);
            case "leaf": return ContextCompat.getColor(c, R.color.leaf);
            case "rose": return ContextCompat.getColor(c, R.color.rose);
            default: return ContextCompat.getColor(c, R.color.coral);
        }
    }

    static String colourName(String name) {
        return name == null || name.isEmpty() ? "Coral" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** 04:12 under an hour, 1:04:12 above it. */
    static String clock(long ms) {
        long s = Math.max(0, (ms + 999) / 1000), h = s / 3600, m = s % 3600 / 60, x = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, x)
            : String.format(java.util.Locale.ROOT, "%02d:%02d", m, x);
    }

    /** "1 minute", "25 minutes", "1 hour 10 minutes". */
    static String lengthWords(int minutes) {
        if (minutes < 60) return minutes == 1 ? "1 minute" : minutes + " minutes";
        int h = minutes / 60, m = minutes % 60;
        String hours = h == 1 ? "1 hour" : h + " hours";
        return m == 0 ? hours : hours + " " + (m == 1 ? "1 minute" : m + " minutes");
    }

    /** The short form for tiles and chips: "25 min", "1 h 10 min". */
    static String lengthShort(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    static PendingIntent openTimers(Context c, long id) {
        Intent open = new Intent(c, MainActivity.class).putExtra("destination", "more").putExtra("detail", "timers")
            .putExtra("timer", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, (int) (id % 100000) + 300000, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent action(Context c, String what, long id) {
        Intent i = new Intent(c, Receiver.class).setAction(what).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (what.hashCode() * 31 + (int) (id % 100000)), i,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent ringIntent(Context c, long id) { return action(c, ACT_RING, id); }

    static int liveId(long id) { return 4_000_000 + (int) (id % 1_000_000); }
    static int doneId(long id) { return 5_000_000 + (int) (id % 1_000_000); }

    private static void cancelDone(Context c, long id) { notifications(c).cancel(doneId(id)); }

    private static NotificationManager notifications(Context c) { return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE); }
    private static AlarmManager alarms(Context c) { return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("timers", Context.MODE_PRIVATE); }

    private static void save(Context c, List<Timer> list) {
        JSONArray out = new JSONArray();
        try {
            for (Timer t : list) out.put(new JSONObject().put("id", t.id).put("label", t.label).put("colour", t.colour)
                .put("total", t.total).put("end", t.end).put("remain", t.remain).put("paused", t.paused).put("done", t.done));
        } catch (Exception ignored) { }
        // Committed at once: a notification button may be the only thing running, and the process can end right after.
        prefs(c).edit().putString("timers", out.toString()).commit();
    }

    // ---- saved tiles ----

    static List<Tile> tiles(Context c) {
        List<Tile> out = new ArrayList<>();
        String raw = prefs(c).getString("tiles", null);
        if (raw == null) {
            out.add(new Tile(1, "Quick", 1, "coral"));
            out.add(new Tile(2, "Quick", 5, "coral"));
            out.add(new Tile(3, "Quick", 10, "teal"));
            out.add(new Tile(4, "Focus", 25, "violet"));
            return out;
        }
        try {
            JSONArray list = new JSONArray(raw);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                out.add(new Tile(o.getLong("id"), o.optString("label", "Timer"), o.optInt("minutes", 5), o.optString("colour", "coral")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static Tile tile(Context c, long id) {
        for (Tile q : tiles(c)) if (q.id == id) return q;
        return null;
    }

    static void saveTiles(Context c, List<Tile> list) {
        JSONArray out = new JSONArray();
        try {
            for (Tile q : list) out.put(new JSONObject().put("id", q.id).put("label", q.label).put("minutes", q.minutes).put("colour", q.colour));
        } catch (Exception ignored) { }
        prefs(c).edit().putString("tiles", out.toString()).commit();
        TimerShortcuts.tiles(c.getApplicationContext());
        changed(c);
    }

    static Tile addTile(Context c, String label, int minutes, String colour) {
        List<Tile> list = tiles(c);
        long id = System.currentTimeMillis();
        Tile q = new Tile(id, label == null || label.trim().isEmpty() ? "Timer" : label.trim(), Math.max(1, Math.min(180, minutes)), colour);
        list.add(q);
        saveTiles(c, list);
        return q;
    }

    static void editTile(Context c, long id, String label, int minutes, String colour) {
        List<Tile> list = tiles(c);
        for (Tile q : list) if (q.id == id) {
            q.label = label == null || label.trim().isEmpty() ? "Timer" : label.trim();
            q.minutes = Math.max(1, Math.min(180, minutes));
            q.colour = colour;
        }
        saveTiles(c, list);
    }

    static void deleteTile(Context c, long id) {
        List<Tile> list = tiles(c);
        list.removeIf(q -> q.id == id);
        saveTiles(c, list);
        TimerShortcuts.forgetTile(c, id);
    }

    static Timer startTile(Context c, long id) {
        Tile q = tile(c, id);
        return q == null ? null : start(c, q.minutes, q.label.equals("Quick") ? "" : q.label, q.colour);
    }

    /** Move any timer the old shared store still holds into this one, once. */
    static void migrate(Context c) {
        if (prefs(c).getBoolean("migrated", false)) return;
        for (Reminders.Item old : Reminders.takeOldTimers(c))
            if (old.at > System.currentTimeMillis()) startMs(c, old.at - System.currentTimeMillis(), old.label, "coral");
        prefs(c).edit().putBoolean("migrated", true).apply();
    }

    /** Hears notification buttons, widget buttons, alarms and restarts. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            String what = intent.getAction();
            long id = intent.getLongExtra("id", -1);
            if (what == null) return;
            switch (what) {
                case ACT_RING: ring(c, id); break;
                case ACT_PAUSE: pauseToggle(c, id); break;
                case ACT_ADD: adjust(c, id, 1); break;
                case ACT_STOP: case ACT_DISMISS: stop(c, id); break;
                case ACT_RESTART: restart(c, id); break;
                case ACT_TILE:
                    if (startTile(c, id) == null)
                        android.widget.Toast.makeText(c, "Four timers is the most at once", android.widget.Toast.LENGTH_SHORT).show();
                    break;
                default: migrate(c); rearm(c);
            }
        }
    }
}
