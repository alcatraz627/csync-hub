package com.csync.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Work that takes a while, such as saving a post, sending a file or installing an update.
 *
 * Each piece of work shows as an Android notification with a progress bar and, when it can
 * stop, a Stop button. The work keeps going when the page that started it closes. Pages that
 * want to show the same progress listen with {@link #listen}.
 */
public final class WorkService extends Service {
    private static final String CHANNEL = "csync-work";
    private static final String ACTION_KEEP = "com.csync.hub.WORK_KEEP";
    private static final String ACTION_STOP = "com.csync.hub.WORK_STOP";
    private static final int BASE_ID = 8100;

    /** What a piece of work does. It reports progress through {@code work} and returns the words to finish with. */
    interface Task { String run(Work work) throws Exception; }

    /** One piece of work while it runs and the words it ended with. */
    static final class Work {
        final int id;
        final String title;
        final int icon;
        final boolean canStop;
        volatile int percent = -1;
        volatile String detail = "";
        volatile boolean stopped;
        volatile boolean done;
        volatile boolean failed;
        volatile String result = "";
        private final Context app;

        Work(Context app, int id, String title, int icon, boolean canStop) {
            this.app = app; this.id = id; this.title = title; this.icon = icon; this.canStop = canStop;
        }

        /** Say how far along it is: a percent from 0 to 100, or -1 when that cannot be known. */
        void progress(int percent, String detail) {
            this.percent = percent;
            this.detail = detail == null ? "" : detail;
            changed(app, this);
        }

        /** True once Stop was pressed; long loops check it and end early. */
        boolean stopped() { return stopped; }
    }

    private static final Map<Integer, Work> LIVE = new LinkedHashMap<>();
    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int next = 1;
    // Notifications are redrawn at most this often, so a fast download does not flood the shade.
    private static final long REDRAW_MS = 400;
    private static final Map<Integer, Long> DRAWN = new LinkedHashMap<>();

    /** Start a piece of work in the background, with its own notification. */
    static Work start(Context c, String title, int icon, boolean canStop, Task task) {
        Context app = c.getApplicationContext();
        Work work;
        synchronized (LIVE) {
            work = new Work(app, next++, title, icon, canStop);
            LIVE.put(work.id, work);
        }
        androidx.core.content.ContextCompat.startForegroundService(app,
            new Intent(app, WorkService.class).setAction(ACTION_KEEP).putExtra("id", work.id));
        new Thread(() -> {
            try {
                work.result = task.run(work);
                work.failed = false;
            } catch (Exception error) {
                work.failed = !work.stopped;
                work.result = work.stopped ? "Stopped" :
                    error.getMessage() == null ? "It did not finish" : error.getMessage();
            }
            work.done = true;
            synchronized (LIVE) { LIVE.remove(work.id); }
            DRAWN.remove(work.id);
            notifyDone(app, work);
            MAIN.post(WorkService::tell);
            // The service hands its foreground notice to the next running piece, or ends.
            app.startService(new Intent(app, WorkService.class).setAction(ACTION_KEEP));
        }, "work-" + work.id).start();
        MAIN.post(WorkService::tell);
        return work;
    }

    /** Every piece of work still running, oldest first. */
    static List<Work> live() {
        synchronized (LIVE) { return new ArrayList<>(LIVE.values()); }
    }

    /** Hear about every change, on the main thread. */
    static void listen(Runnable listener) { if (!LISTENERS.contains(listener)) LISTENERS.add(listener); }

    static void unlisten(Runnable listener) { LISTENERS.remove(listener); }

    static void stop(int id) {
        Work work;
        synchronized (LIVE) { work = LIVE.get(id); }
        if (work != null && work.canStop) { work.stopped = true; work.progress(work.percent, "Stopping"); }
    }

    private static void tell() { for (Runnable listener : new ArrayList<>(LISTENERS)) listener.run(); }

    private static void changed(Context app, Work work) {
        long now = System.currentTimeMillis();
        Long last = DRAWN.get(work.id);
        if (last == null || now - last >= REDRAW_MS || work.stopped) {
            DRAWN.put(work.id, now);
            manager(app).notify(BASE_ID + work.id, running(app, work));
            MAIN.post(WorkService::tell);
        }
    }

    private static NotificationManager manager(Context app) {
        NotificationManager nm = (NotificationManager) app.getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Work in progress", NotificationManager.IMPORTANCE_LOW));
        return nm;
    }

    private static Notification running(Context app, Work work) {
        Notification.Builder b = new Notification.Builder(app, CHANNEL)
            .setSmallIcon(work.icon)
            .setContentTitle(work.title)
            .setContentText(work.detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, Math.max(0, work.percent), work.percent < 0);
        if (work.canStop && !work.stopped)
            b.addAction(new Notification.Action.Builder(null, "Stop",
                PendingIntent.getService(app, work.id, new Intent(app, WorkService.class)
                        .setAction(ACTION_STOP).putExtra("id", work.id),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        return b.build();
    }

    private static void notifyDone(Context app, Work work) {
        Intent open = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        manager(app).notify(BASE_ID + work.id, new Notification.Builder(app, CHANNEL)
            .setSmallIcon(work.icon)
            .setContentTitle(work.result)
            .setContentText(work.title)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, work.id, open, PendingIntent.FLAG_IMMUTABLE))
            .build());
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stop(intent.getIntExtra("id", -1)); return START_NOT_STICKY; }
        List<Work> running = live();
        if (running.isEmpty()) {
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
            return START_NOT_STICKY;
        }
        Work first = running.get(0);
        Notification notice = running(this, first);
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(BASE_ID + first.id, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(BASE_ID + first.id, notice);
        return START_NOT_STICKY;
    }
}
