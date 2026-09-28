package com.csync.hub;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppUpdater {
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final AtomicBoolean updating = new AtomicBoolean();

    static void refreshStatus(Activity activity, TextView status) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE);
        String failure = prefs.getString("failure", "");
        if (!failure.isEmpty()) {
            status.setText("Update failed: " + failure);
            return;
        }
        long expected = prefs.getLong("expected_version", 0);
        String lastInstalled = prefs.getString("last_installed", "");
        if (expected == 0 && lastInstalled.isEmpty()) return;
        try {
            PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            long installed = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
            if (expected > 0 && installed >= expected) {
                status.setText("Installed csync " + current.versionName + " from Pi");
                prefs.edit().remove("expected_version").putString("last_installed", current.versionName).apply();
            } else if (expected > 0) {
                status.setText("Installing csync update " + prefs.getString("expected_name", ""));
            } else if (lastInstalled.equals(current.versionName)) {
                status.setText("Installed csync " + current.versionName + " from Pi");
            }
        } catch (Exception error) {
            status.setText("Could not check installed csync version");
        }
    }

    static void start(Activity activity, TextView status) {
        String host = Prefs.assistIp(activity);
        String token = Prefs.token(activity);
        if (host.isEmpty() || token.isEmpty()) {
            status.setText("Connect csync to the Pi before checking for an update");
            return;
        }
        if (!updating.compareAndSet(false, true)) {
            status.setText("An app update is already in progress");
            return;
        }
        status.setText("Downloading the app update from Pi");
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File apk = new File(activity.getCacheDir(), "csync-update.apk");
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("http://" + host + ":8792/v1/app/apk").openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("X-Csync-Token", token);
                int response = connection.getResponseCode();
                if (response == 404) throw new Exception("No app update is staged on the Pi");
                if (response != 200) throw new Exception("Pi update service returned " + response);
                long length = connection.getContentLengthLong();
                if (length < 1024 || length > MAX_APK) throw new Exception("Invalid update size");
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[65536];
                    long read = 0;
                    while (read < length) {
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, length - read));
                        if (count < 0) throw new Exception("Update download ended early");
                        output.write(buffer, 0, count);
                        read += count;
                    }
                    output.flush();
                }
                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (archive == null || !activity.getPackageName().equals(archive.packageName))
                    throw new Exception("The Pi file is not a csync app update");
                PackageInfo current = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                long newVersion = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
                long oldVersion = Build.VERSION.SDK_INT >= 28 ? current.getLongVersionCode() : current.versionCode;
                if (newVersion <= oldVersion) {
                    ui.post(() -> status.setText("csync is already up to date"));
                    return;
                }
                if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                    ui.post(() -> {
                        status.setText("Allow csync to install updates, then tap Update again");
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                    });
                    return;
                }
                activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE).edit()
                    .putLong("expected_version", newVersion)
                    .putString("expected_name", archive.versionName)
                    .remove("failure").apply();
                ui.post(() -> status.setText("Installing csync update " + archive.versionName));
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
            } catch (Exception error) {
                activity.getSharedPreferences("csync_update", Activity.MODE_PRIVATE).edit()
                    .remove("expected_version").putString("failure", error.getMessage()).apply();
                ui.post(() -> status.setText("Update failed: " + error.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
                if (apk.exists()) apk.delete();
                updating.set(false);
            }
        }, "csync-app-update").start();
    }
}
