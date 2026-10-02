package com.csync.hub;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;

/**
 * Keeps each running timer's notification current while any timer counts down.
 *
 * The time left counts down by itself in the notification; this redraws the ring around it every
 * fifteen seconds, and stops as soon as no timer is counting down. Paused timers keep a still
 * notification that needs no redrawing.
 */
public final class TimerService extends Service {
    private static final long REDRAW = 15_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int foregroundId;

    /** Post the notifications for the timers as they are now, and run or stop the redraw to match. */
    static void sync(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean counting = false;
        for (Timers.Timer t : Timers.all(c)) {
            if (t.done) continue;
            nm.notify(Timers.liveId(t.id), Timers.live(c, t));
            if (!t.paused) counting = true;
        }
        Intent self = new Intent(c, TimerService.class);
        if (counting) {
            try { c.startForegroundService(self); }
            // Android refuses to start it from the background on some paths; the notifications still stand.
            catch (Exception refused) { }
        } else c.stopService(self);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        redraw();
        return START_STICKY;
    }

    private void redraw() {
        handler.removeCallbacksAndMessages(null);
        List<Timers.Timer> all = Timers.all(this);
        Timers.Timer lead = null;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Android will not cancel the notification a service stands on, so a stopped timer's card
        // is removed here once the service has moved to another timer or let go.
        boolean previousLives = false;
        for (Timers.Timer t : all) if (!t.done && Timers.liveId(t.id) == foregroundId) previousLives = true;
        for (Timers.Timer t : all) {
            if (t.done) continue;
            Notification n = Timers.live(this, t);
            if (lead == null && !t.paused) {
                lead = t;
                int id = Timers.liveId(t.id);
                if (id != foregroundId) {
                    if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    else startForeground(id, n);
                    if (foregroundId != 0 && !previousLives) nm.cancel(foregroundId);
                    foregroundId = id;
                }
            }
            nm.notify(Timers.liveId(t.id), n);
        }
        if (lead == null) {
            stopForeground(previousLives ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
            foregroundId = 0;
            stopSelf();
            return;
        }
        // The widget's ring is a picture too, so it moves on the same beat.
        HubWidgets.refreshTimers(this);
        handler.postDelayed(this::redraw, REDRAW);
    }

    // Stopping a service takes its notification with it, so the card is let go first; then paused
    // timers keep theirs and a stopped timer's card goes.
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_DETACH);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean leadLives = false;
        for (Timers.Timer t : Timers.all(this)) {
            if (t.done) continue;
            if (Timers.liveId(t.id) == foregroundId) leadLives = true;
            nm.notify(Timers.liveId(t.id), Timers.live(this, t));
        }
        if (foregroundId != 0 && !leadLives) nm.cancel(foregroundId);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
