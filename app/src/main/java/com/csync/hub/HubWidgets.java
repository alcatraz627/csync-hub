package com.csync.hub;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * The home-screen widgets that talk to the Raspberry Pi: its status, a remote
 * for what the Pi screen is playing, the latest camera photo, and a one-tap
 * way to start a conversation.
 *
 * Each widget is a small receiver. It shows what it last knew at once, then
 * hands the network read to {@link HubWidgetJob}, because a receiver is frozen
 * before a request to the Pi could finish.
 */
public final class HubWidgets {
    private HubWidgets() {}

    static final String STATUS = "status", MEDIA = "media", CAMERA = "camera";
    static final String ACTION = "com.csync.hub.WIDGET_ACTION";

    /** Open a place in the app from a widget. Each place gets its own request code so the intents stay distinct. */
    static PendingIntent open(Context c, String destination, int code) {
        Intent i = new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", destination);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openMedia(Context c) {
        Intent i = new Intent(c, MediaActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 9104, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** A tap on a widget button: it comes back to the widget's own receiver, which queues the work. */
    static PendingIntent act(Context c, Class<?> widget, String action, int code) {
        Intent i = new Intent(c, widget).setAction(ACTION).putExtra("do", action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int[] ids(Context c, Class<?> widget) {
        return AppWidgetManager.getInstance(c).getAppWidgetIds(new ComponentName(c, widget));
    }

    /** Refresh every Pi widget that is on the home screen, for example after the app changes what is playing. */
    static void refreshAll(Context c) {
        if (ids(c, PiStatus.class).length > 0) HubWidgetJob.run(c, STATUS, null);
        if (ids(c, MediaRemote.class).length > 0) HubWidgetJob.run(c, MEDIA, null);
        if (ids(c, CameraGlance.class).length > 0) HubWidgetJob.run(c, CAMERA, null);
    }

    public static class PiStatus extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { HubWidgetJob.run(c, STATUS, null); }
        @Override public void onReceive(Context c, Intent i) {
            super.onReceive(c, i);
            if (ACTION.equals(i.getAction())) HubWidgetJob.run(c, STATUS, null);
        }
    }

    public static class MediaRemote extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { HubWidgetJob.run(c, MEDIA, null); }
        @Override public void onReceive(Context c, Intent i) {
            super.onReceive(c, i);
            if (ACTION.equals(i.getAction())) HubWidgetJob.run(c, MEDIA, i.getStringExtra("do"));
        }
    }

    public static class CameraGlance extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { HubWidgetJob.run(c, CAMERA, null); }
        @Override public void onReceive(Context c, Intent i) {
            super.onReceive(c, i);
            if (ACTION.equals(i.getAction())) HubWidgetJob.run(c, CAMERA, i.getStringExtra("do"));
        }
    }

    // ---- timers and reminders: drawn from this phone's own stores, so no network job is needed ----

    static void refreshTimers(Context c) {
        int[] ids = ids(c, TimerWidget.class);
        if (ids.length > 0) drawTimers(c, AppWidgetManager.getInstance(c), ids);
    }

    static void refreshReminders(Context c) {
        int[] ids = ids(c, ReminderWidget.class);
        if (ids.length > 0) drawReminders(c, AppWidgetManager.getInstance(c), ids);
    }

    private static PendingIntent openDetail(Context c, String detail, int code) {
        Intent i = new Intent(c, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("destination", "more").putExtra("detail", detail);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Running timers as rows with their buttons; under them, saved tiles while there is room for another timer. */
    static void drawTimers(Context c, AppWidgetManager m, int[] ids) {
        long now = System.currentTimeMillis();
        java.util.List<Timers.Timer> live = new java.util.ArrayList<>();
        for (Timers.Timer t : Timers.all(c)) if (!t.done) live.add(t);
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_timers);
            rv.setOnClickPendingIntent(R.id.wg_open, openDetail(c, "timers", 9201));
            rv.setTextViewText(R.id.wg_state, live.isEmpty() ? "None running" : live.size() + " of " + Timers.MAX);
            rv.removeAllViews(R.id.wg_rows);
            for (Timers.Timer t : live) {
                RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_timer_row);
                row.setInt(R.id.wr_dot, "setColorFilter", Timers.colour(c, t.colour));
                row.setTextViewText(R.id.wr_name, t.name());
                if (t.paused) {
                    row.setViewVisibility(R.id.wr_clock, android.view.View.GONE);
                    row.setViewVisibility(R.id.wr_still, android.view.View.VISIBLE);
                    row.setTextViewText(R.id.wr_still, Timers.clock(t.remain));
                } else {
                    row.setChronometer(R.id.wr_clock, android.os.SystemClock.elapsedRealtime() + (t.end - now), null, true);
                    row.setChronometerCountDown(R.id.wr_clock, true);
                }
                row.setImageViewResource(R.id.wr_toggle, t.paused ? R.drawable.wg_play : R.drawable.wg_pause);
                row.setContentDescription(R.id.wr_toggle, t.paused ? "Resume " + t.name() : "Pause " + t.name());
                row.setOnClickPendingIntent(R.id.wr_toggle, Timers.action(c, Timers.ACT_PAUSE, t.id));
                row.setOnClickPendingIntent(R.id.wr_add, Timers.action(c, Timers.ACT_ADD, t.id));
                row.setOnClickPendingIntent(R.id.wr_stop, Timers.action(c, Timers.ACT_STOP, t.id));
                row.setOnClickPendingIntent(R.id.wr_open, Timers.openTimers(c, t.id));
                rv.addView(R.id.wg_rows, row);
            }
            rv.removeAllViews(R.id.wg_tiles);
            boolean room = live.size() < Timers.MAX;
            rv.setViewVisibility(R.id.wg_tiles, room ? android.view.View.VISIBLE : android.view.View.GONE);
            if (room) {
                java.util.List<Timers.Tile> tiles = Timers.tiles(c);
                for (int k = 0; k < Math.min(4, tiles.size()); k++) {
                    Timers.Tile q = tiles.get(k);
                    RemoteViews tile = new RemoteViews(c.getPackageName(), R.layout.widget_timer_tile);
                    tile.setInt(R.id.wq_dot, "setColorFilter", Timers.colour(c, q.colour));
                    tile.setTextViewText(R.id.wq_words, Timers.lengthShort(q.minutes));
                    tile.setContentDescription(R.id.wq_root, "Start " + (q.label.equals("Quick") ? "" : q.label + ", ") + Timers.lengthWords(q.minutes));
                    tile.setOnClickPendingIntent(R.id.wq_root, Timers.action(c, Timers.ACT_TILE, q.id));
                    rv.addView(R.id.wg_tiles, tile);
                }
            }
            m.updateAppWidget(id, rv);
        }
    }

    /** The next three reminders with Done and a ten-minute snooze, and Add. */
    static void drawReminders(Context c, AppWidgetManager m, int[] ids) {
        java.util.List<Reminders.Item> coming = new java.util.ArrayList<>();
        for (Reminders.Item r : Reminders.all(c)) if (!r.rang && coming.size() < 3) coming.add(r);
        java.text.DateFormat time = android.text.format.DateFormat.getTimeFormat(c);
        long today = Reminders.startOfDay(System.currentTimeMillis());
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_reminders);
            rv.setOnClickPendingIntent(R.id.wg_open, openDetail(c, "reminders", 9202));
            rv.setOnClickPendingIntent(R.id.wg_add, openDetail(c, "reminder-add", 9203));
            rv.removeAllViews(R.id.wg_rows);
            rv.setViewVisibility(R.id.wg_state, coming.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            for (Reminders.Item r : coming) {
                RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_reminder_row);
                long day = Reminders.startOfDay(r.at);
                String when = day == today ? time.format(new java.util.Date(r.at))
                    : new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new java.util.Date(r.at));
                row.setTextViewText(R.id.wm_when, when);
                row.setTextViewText(R.id.wm_label, r.label);
                row.setOnClickPendingIntent(R.id.wm_open, openDetail(c, "reminders", 9204));
                row.setOnClickPendingIntent(R.id.wm_snooze, Reminders.action(c, Reminders.ACT_SNOOZE, r.id));
                row.setOnClickPendingIntent(R.id.wm_done, Reminders.action(c, Reminders.ACT_DONE, r.id));
                rv.addView(R.id.wg_rows, row);
            }
            m.updateAppWidget(id, rv);
        }
    }

    public static class TimerWidget extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { drawTimers(c, m, ids); }
    }

    public static class ReminderWidget extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { drawReminders(c, m, ids); }
    }

    /** Needs no network: the whole widget is one button that opens a new conversation. */
    public static class Ask extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
            for (int id : ids) {
                RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_ask);
                rv.setOnClickPendingIntent(R.id.wg_root, open(c, "chat-new", 9101));
                m.updateAppWidget(id, rv);
            }
        }
    }
}
