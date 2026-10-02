package com.csync.hub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Asks the Raspberry Pi what a home-screen widget needs and redraws it.
 *
 * It runs as a scheduled job because the system gives a job network access
 * and time to finish, which a widget's own receiver does not get. A button on
 * the media remote arrives here as an action to carry out before the redraw.
 */
public class HubWidgetJob extends JobService {

    /** Queue a refresh of one kind of widget, optionally carrying out a button press first. */
    static void run(Context c, String kind, String action) { schedule(c, kind, action, 0); }

    private static void schedule(Context c, String kind, String action, long delayMs) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        PersistableBundle extras = new PersistableBundle();
        extras.putString("kind", kind);
        if (action != null) extras.putString("do", action);
        // A delayed follow-up gets its own id so it cannot replace a button press that is waiting to run.
        int id = 5000 + (HubWidgets.STATUS.equals(kind) ? 1 : HubWidgets.MEDIA.equals(kind) ? 2 : 3) + (delayMs > 0 ? 10 : 0);
        ComponentName service = new ComponentName(c, HubWidgetJob.class);
        if (delayMs == 0 && Build.VERSION.SDK_INT >= 31) {
            try {
                if (js.schedule(new JobInfo.Builder(id, service).setExtras(extras)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExpedited(true).build())
                        == JobScheduler.RESULT_SUCCESS) return;
            } catch (RuntimeException ignored) {
                // The expedited allowance is used up; the ordinary job below still runs.
            }
        }
        js.schedule(new JobInfo.Builder(id, service).setExtras(extras)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs).setOverrideDeadline(delayMs + 1000).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        final String kind = params.getExtras().getString("kind", "");
        final String action = params.getExtras().getString("do");
        new Thread(() -> {
            Context c = getApplicationContext();
            try {
                if (HubWidgets.STATUS.equals(kind)) status(c);
                else if (HubWidgets.MEDIA.equals(kind)) media(c, action);
                else if (HubWidgets.CAMERA.equals(kind)) camera(c);
            } catch (Throwable ignored) {
                // Each widget draws its own "cannot be reached" state; nothing here should take the job down.
            }
            jobFinished(params, false);
        }, "hub-widget-" + kind).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }

    private static MediaClient client(Context c) {
        String host = Prefs.assistIp(c), token = Prefs.token(c);
        return host.isEmpty() || token.isEmpty() ? null : new MediaClient(host, token);
    }

    private static String now(Context c) {
        return android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date());
    }

    private static void dot(RemoteViews rv, Context c, Kit.Status s) {
        rv.setInt(R.id.wg_dot, "setColorFilter", Kit.statusColor(c, s));
    }

    private static void push(Context c, Class<?> widget, RemoteViews rv) {
        AppWidgetManager.getInstance(c).updateAppWidget(new ComponentName(c, widget), rv);
    }

    // ---- Pi status ----

    private void status(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_status);
        rv.setOnClickPendingIntent(R.id.wg_root, HubWidgets.open(c, "tools", 9102));
        MediaClient client = client(c);
        JSONObject seen = null;
        if (client != null) {
            try { seen = client.get("/v1/diagnostics"); } catch (Exception ignored) { }
        }
        if (seen == null) {
            dot(rv, c, Kit.Status.IDLE);
            rv.setTextViewText(R.id.wg_state, client == null ? "Not set up" : "Offline");
            rv.setTextViewText(R.id.wg_line1, client == null ? "Add the Pi in Settings" : "The Pi cannot be reached");
            rv.setViewVisibility(R.id.wg_line2, View.GONE);
        } else {
            JSONObject power = seen.optJSONObject("power");
            boolean low = power != null && power.optBoolean("underVoltageNow");
            JSONArray drives = seen.optJSONArray("drives");
            int total = drives == null ? 0 : drives.length(), up = 0;
            for (int i = 0; i < total; i++) if (drives.optJSONObject(i).optBoolean("online")) up++;
            boolean missing = up < total;
            // Only power turns the dot amber. A drive that is unplugged is a normal state, said in its own line.
            dot(rv, c, low ? Kit.Status.WARN : Kit.Status.GOOD);
            rv.setTextViewText(R.id.wg_state, low ? "Low power" : "Online");
            // A reading the Pi did not give is left out instead of shown as unknown.
            boolean measured = power != null && power.has("underVoltageNow");
            rv.setViewVisibility(R.id.wg_line1, measured ? View.VISIBLE : View.GONE);
            rv.setTextViewText(R.id.wg_line1, low ? "Power is low" : "Power is steady");
            rv.setViewVisibility(R.id.wg_line2, total > 0 ? View.VISIBLE : View.GONE);
            // Short enough to fit the narrowest size, so the launcher never cuts it.
            rv.setTextViewText(R.id.wg_line2, missing ? up + " of " + total + " drives"
                : total == 1 ? "1 drive" : total + " drives");
        }
        rv.setTextViewText(R.id.wg_when, "Checked " + now(c));
        push(c, HubWidgets.PiStatus.class, rv);
    }

    // ---- media remote ----

    private void media(Context c, String action) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_media);
        rv.setOnClickPendingIntent(R.id.wg_open, HubWidgets.openMedia(c));
        rv.setOnClickPendingIntent(R.id.wg_toggle, HubWidgets.act(c, HubWidgets.MediaRemote.class, "toggle", 9201));
        rv.setOnClickPendingIntent(R.id.wg_stop, HubWidgets.act(c, HubWidgets.MediaRemote.class, "stop", 9202));
        MediaClient client = client(c);
        JSONObject state = null;
        String failed = null;
        if (client != null) {
            try {
                state = client.get("/v1/player/pi");
                if (action != null) {
                    String was = state.optString("state");
                    if ("stop".equals(action)) client.post("/v1/player/pi/immediate", new JSONObject().put("action", "stop"));
                    else if ("paused".equals(was)) client.post("/v1/player/pi/commands",
                        new JSONObject().put("action", "resume").put("expectedRevision", state.getInt("revision")));
                    else client.post("/v1/player/pi/immediate", new JSONObject().put("action", "pause"));
                    state = client.get("/v1/player/pi");
                }
            } catch (Exception error) {
                // A press that failed leaves what is playing as it was, so say so and keep the last state.
                if (state != null && action != null) failed = "stop".equals(action) ? "Stop did not reach the Pi" : "That did not reach the Pi";
                else state = null;
            }
        }
        String status = state == null ? "" : state.optString("state");
        boolean active = status.equals("playing") || status.equals("paused") || status.equals("loading") || status.equals("buffering");
        rv.setViewVisibility(R.id.wg_toggle, active ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.wg_stop, active ? View.VISIBLE : View.GONE);
        if (state == null) {
            dot(rv, c, Kit.Status.IDLE);
            rv.setTextViewText(R.id.wg_state, client == null ? "Not set up" : "Offline");
            rv.setTextViewText(R.id.wg_title, client == null ? "Add the Pi in Settings" : "The Pi cannot be reached");
        } else if (!active) {
            dot(rv, c, Kit.Status.IDLE);
            rv.setTextViewText(R.id.wg_state, "Showing the cover");
            rv.setTextViewText(R.id.wg_title, "Nothing is playing on the Pi screen");
        } else {
            boolean paused = status.equals("paused"), playing = status.equals("playing");
            dot(rv, c, failed != null ? Kit.Status.BAD : playing ? Kit.Status.GOOD : paused ? Kit.Status.IDLE : Kit.Status.WARN);
            rv.setTextViewText(R.id.wg_state, failed != null ? failed
                : (playing ? "Playing" : paused ? "Paused" : "Loading") + " on Pi screen");
            rv.setTextViewText(R.id.wg_title, state.optString("name", "Pi media"));
            rv.setImageViewResource(R.id.wg_toggle, paused ? R.drawable.wg_play : R.drawable.wg_pause);
            rv.setContentDescription(R.id.wg_toggle, paused ? "Resume on Pi screen" : "Pause on Pi screen");
            rv.setContentDescription(R.id.wg_stop, "Stop on Pi screen");
        }
        push(c, HubWidgets.MediaRemote.class, rv);
        // While something plays, look again in a minute so the title and state keep up without the app open.
        if (active && HubWidgets.ids(c, HubWidgets.MediaRemote.class).length > 0) schedule(c, HubWidgets.MEDIA, null, 60000);
    }

    // ---- camera glance ----

    private void camera(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_camera);
        rv.setOnClickPendingIntent(R.id.wg_image, HubWidgets.open(c, "camera", 9103));
        rv.setOnClickPendingIntent(R.id.wg_refresh, HubWidgets.act(c, HubWidgets.CameraGlance.class, "refresh", 9203));
        MediaClient client = client(c);
        Bitmap photo = null;
        boolean reached = false;
        if (client != null) {
            try {
                JSONArray captures = client.get("/v1/camera/captures").optJSONArray("captures");
                reached = true;
                for (int i = 0; captures != null && i < captures.length(); i++) {
                    String name = captures.optJSONObject(i).optString("name");
                    // Only the camera's own photos: pictures sent to the Pi screen share the folder.
                    if (!name.startsWith("photo-") || !name.endsWith(".jpg")) continue;
                    photo = small(client.getBytes("/v1/camera/captures/" + name, 8 * 1024 * 1024));
                    break;
                }
            } catch (Exception ignored) { }
        }
        if (photo != null) rv.setImageViewBitmap(R.id.wg_image, photo);
        rv.setTextViewText(R.id.wg_state, client == null ? "Not set up" : !reached ? "Pi camera, offline"
            : photo == null ? "No photos yet" : "Latest photo");
        rv.setTextViewText(R.id.wg_when, "Checked " + now(c));
        push(c, HubWidgets.CameraGlance.class, rv);
    }

    /** Decode a photo small enough to hand to the launcher, which refuses large pictures. */
    private static Bitmap small(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }
}
