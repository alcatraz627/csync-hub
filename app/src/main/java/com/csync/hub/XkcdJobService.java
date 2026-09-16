package com.csync.hub;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.app.PendingIntent;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/** Runs the comic fetch with network access, then updates every widget instance. */
public class XkcdJobService extends JobService {

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                Context ctx = getApplicationContext();
                AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
                int[] ids = mgr.getAppWidgetIds(
                        new ComponentName(ctx, XkcdWidgetProvider.class));
                for (int id : ids) {
                    try {
                        fetchAndShow(ctx, mgr, id);
                    } catch (Throwable e) {
                        Log.e("csynchub", "fetch failed", e);
                        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.xkcd_widget);
                        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                        rv.setTextViewText(R.id.widget_title,
                                msg.length() > 70 ? msg.substring(0, 70) : msg);
                        rv.setOnClickPendingIntent(R.id.widget_refresh,
                                XkcdWidgetProvider.refreshIntent(ctx));
                        mgr.updateAppWidget(id, rv);
                    }
                }
                jobFinished(params, false);
            }
        }).start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // do not reschedule on stop
    }

    private void fetchAndShow(Context context, AppWidgetManager mgr, int id) throws Exception {
        JSONObject latest = getJson("https://xkcd.com/info.0.json");
        int max = latest.getInt("num");
        int n = 1 + new Random().nextInt(max);
        if (n == 404) n = 403; // xkcd #404 intentionally does not exist
        JSONObject comic = getJson("https://xkcd.com/" + n + "/info.0.json");

        String imgUrl = comic.getString("img");
        String title = comic.optString("safe_title", comic.optString("title", "xkcd #" + n));
        String page = "https://xkcd.com/" + n + "/";

        Bitmap bmp = getBitmap(imgUrl, 600);

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.xkcd_widget);
        if (bmp != null) {
            rv.setImageViewBitmap(R.id.widget_image, bmp);
        }
        rv.setTextViewText(R.id.widget_title, "#" + n + "  " + title);
        rv.setOnClickPendingIntent(R.id.widget_refresh, XkcdWidgetProvider.refreshIntent(context));
        rv.setOnClickPendingIntent(R.id.widget_image, openIntent(context, page, id));
        mgr.updateAppWidget(id, rv);
    }

    private PendingIntent openIntent(Context context, String url, int id) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private JSONObject getJson(String url) throws Exception {
        return new JSONObject(new String(download(url, 10000), "UTF-8"));
    }

    private Bitmap getBitmap(String url, int maxSide) throws Exception {
        byte[] data = download(url, 15000);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        int sample = 1;
        int big = Math.max(bounds.outWidth, bounds.outHeight);
        while (big / sample > maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private byte[] download(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("User-Agent", "csync-hub/1.0");
        c.setInstanceFollowRedirects(true);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        c.disconnect();
        return bo.toByteArray();
    }
}
