package com.csync.hub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class AppUpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent consent = Build.VERSION.SDK_INT >= 33 ?
                result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) :
                result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (consent != null) {
                consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(consent);
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            context.getSharedPreferences("csync_update", Context.MODE_PRIVATE).edit()
                .remove("failure").apply();
            Toast.makeText(context, "csync update installed", Toast.LENGTH_LONG).show();
        } else {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            context.getSharedPreferences("csync_update", Context.MODE_PRIVATE).edit()
                .remove("expected_version")
                .putString("failure", message == null ? "Installer rejected the update" : message).apply();
            Toast.makeText(context, "csync update failed: " + message, Toast.LENGTH_LONG).show();
        }
    }
}
