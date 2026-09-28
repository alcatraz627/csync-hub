package com.csync.hub;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * A home-screen widget that shows a random xkcd comic. It refreshes on its own
 * roughly every hour, has a refresh button, is resizable, and opens the comic's
 * page in a browser when tapped.
 *
 * The actual network fetch runs in {@link XkcdJobService}. A broadcast receiver's
 * process is frozen on modern Android and denied background network, so the work
 * is handed to the job scheduler, which the system permits to use the network.
 */
public class XkcdWidgetProvider extends AppWidgetProvider {

    static final String ACTION_REFRESH = "com.csync.hub.ACTION_REFRESH";
    private static final int JOB_ID = 4242;

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) showLoading(context, mgr, id);
        schedule(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, XkcdWidgetProvider.class));
            for (int id : ids) showLoading(context, mgr, id);
            schedule(context);
        }
    }

    private void showLoading(Context context, AppWidgetManager mgr, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.xkcd_widget);
        rv.setTextViewText(R.id.widget_title, "Loading");
        rv.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context));
        mgr.updateAppWidget(id, rv);
    }

    /** Queue the fetch on the job scheduler so it runs with network access. */
    static void schedule(Context context) {
        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        ComponentName service = new ComponentName(context, XkcdJobService.class);
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                JobInfo expedited = new JobInfo.Builder(JOB_ID, service)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setExpedited(true)
                        .build();
                if (js.schedule(expedited) == JobScheduler.RESULT_SUCCESS) return;
            } catch (RuntimeException ignore) {
                // expedited quota exhausted; fall back to a normal job
            }
        }
        js.schedule(new JobInfo.Builder(JOB_ID, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(1000)
                .build());
    }

    static PendingIntent refreshIntent(Context context) {
        Intent i = new Intent(context, XkcdWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
