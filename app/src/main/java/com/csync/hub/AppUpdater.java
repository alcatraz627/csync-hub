package com.csync.hub;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updating csync from the build staged on the Pi.
 *
 * {@link #check} asks the Pi which build it holds without downloading it, so Home can say an
 * update is ready. {@link #start} downloads and installs it as background work, with a
 * progress notification and Stop until the install is handed to Android.
 */
final class AppUpdater {
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final AtomicBoolean updating = new AtomicBoolean();

    /**
     * What the Pi holds, when it is newer than this app: its version name, or null otherwise.
     * {@code reached} is false when the Pi could not be asked, so "no newer build" is unknown.
     */
    interface Found { void newer(String versionName, boolean reached); }

    /** Ask the Pi which build it holds; {@code found} hears back on the main thread. */
    static void check(Context c, Found found) {
        String host = Prefs.assistIp(c), token = Prefs.token(c);
        Handler ui = new Handler(Looper.getMainLooper());
        if (host.isEmpty() || token.isEmpty()) { found.newer(null, false); return; }
        new Thread(() -> {
            String newer = null;
            boolean reached = false;
            try {
                JSONObject staged = new MediaClient(host, token).get("/v1/app/version");
                reached = true;
                long code = staged.optLong("versionCode", 0);
                if (staged.optBoolean("staged") && code > installedCode(c)) newer = staged.optString("versionName", "a newer build");
            } catch (Exception unreachable) { }
            String said = newer;
            boolean asked = reached;
            ui.post(() -> found.newer(said, asked));
        }, "csync-update-check").start();
    }

    static boolean running() { return updating.get(); }

    private static long installedCode(Context c) throws Exception {
        PackageInfo current = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        return Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
    }

    static void refreshStatus(Activity activity, TextView status) {
        if (status == null) return;
        android.content.SharedPreferences prefs = activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE);
        String failure = prefs.getString("failure", "");
        if (!failure.isEmpty()) {
            status.setText("The update did not install. " + failure);
            return;
        }
        long expected = prefs.getLong("expected_version", 0);
        String lastInstalled = prefs.getString("last_installed", "");
        if (expected == 0 && lastInstalled.isEmpty()) return;
        try {
            PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            long installed = installedCode(activity);
            if (expected > 0 && installed >= expected) {
                status.setText("csync " + current.versionName + " is installed, from the Pi");
                prefs.edit().remove("expected_version").putString("last_installed", current.versionName).apply();
            } else if (expected > 0) {
                status.setText("Installing csync " + prefs.getString("expected_name", ""));
            } else if (lastInstalled.equals(current.versionName)) {
                status.setText("csync " + current.versionName + " is installed, from the Pi");
            }
        } catch (Exception error) {
            status.setText("Which csync is installed could not be read");
        }
    }

    /** Download and install the Pi's build as background work. {@code status} may be null. */
    static void start(Activity activity, TextView status) {
        Handler ui = new Handler(Looper.getMainLooper());
        String host = Prefs.assistIp(activity);
        String token = Prefs.token(activity);
        if (host.isEmpty() || token.isEmpty()) {
            if (status != null) status.setText("Connect to the Pi first, then check for an update");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            if (status != null) status.setText("Allow csync to install updates, then tap Update again");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            if (status != null) status.setText("An update is already on its way");
            return;
        }
        if (status != null) status.setText("Fetching the update from the Pi");
        WorkService.start(activity, "Updating csync", R.drawable.csi_download, true, work -> {
            File apk = new File(activity.getCacheDir(), "csync-update.apk");
            HttpURLConnection connection = null;
            try {
                work.progress(-1, "Fetching it from the Pi");
                connection = (HttpURLConnection) new URL("http://" + host + ":8792/v1/app/apk").openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("X-Csync-Token", token);
                int response = connection.getResponseCode();
                if (response == 404) throw new Exception("No app update is staged on the Pi");
                if (response != 200) throw new Exception("The Pi answered " + response);
                long length = connection.getContentLengthLong();
                if (length < 1024 || length > MAX_APK) throw new Exception("The update on the Pi has an odd size");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[65536];
                    long read = 0;
                    while (read < length) {
                        if (work.stopped()) throw new Exception("Stopped");
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, length - read));
                        if (count < 0) throw new Exception("The update ended early");
                        output.write(buffer, 0, count);
                        read += count;
                        work.progress((int) (read * 90 / length), (read * 100 / length) + "% of " + (length / (1024 * 1024)) + " MB");
                    }
                    output.flush();
                }
                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (archive == null || !activity.getPackageName().equals(archive.packageName))
                    throw new Exception("What the Pi holds is not a csync update");
                long newVersion = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
                if (newVersion <= installedCode(activity)) {
                    if (status != null) ui.post(() -> status.setText("csync is already up to date"));
                    return "csync is already up to date";
                }
                if (work.stopped()) throw new Exception("Stopped");
                activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE).edit()
                    .putLong("expected_version", newVersion)
                    .putString("expected_name", archive.versionName)
                    .remove("failure").apply();
                work.progress(95, "Handing it to Android to install");
                if (status != null) ui.post(() -> status.setText("Installing csync " + archive.versionName));
                PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(activity.getPackageName());
                params.setSize(length);
                if (Build.VERSION.SDK_INT >= 31)
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int sessionId = installer.createSession(params);
                boolean committed = false;
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    try (InputStream input = new java.io.FileInputStream(apk);
                         OutputStream output = session.openWrite("base.apk", 0, length)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        session.fsync(output);
                    }
                    Intent result = new Intent(activity, AppUpdateReceiver.class);
                    PendingIntent callback = PendingIntent.getBroadcast(activity, sessionId, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(callback.getIntentSender());
                    committed = true;
                } finally {
                    if (!committed) installer.abandonSession(sessionId);
                }
                return "Installing csync " + archive.versionName;
            } catch (Exception error) {
                if (!work.stopped()) {
                    activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE).edit()
                        .remove("expected_version").putString("failure", error.getMessage()).apply();
                    if (status != null) ui.post(() -> status.setText("The update did not install. " + error.getMessage()));
                } else if (status != null) ui.post(() -> status.setText("The update was stopped"));
                throw error;
            } finally {
                if (connection != null) connection.disconnect();
                if (apk.exists()) //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                updating.set(false);
            }
        });
    }
}
