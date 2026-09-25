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
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "csync update failed: " +
                result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
        }
    }
}
