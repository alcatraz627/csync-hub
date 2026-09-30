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
