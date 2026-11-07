package com.edujalont.tvhome;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Result of the update session that {@link Updater#install} commits. Not exported: only the
 * PendingIntent the launcher hands to its own PackageInstaller session reaches it.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's own confirmation screen for this session.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(context, context.getString(R.string.update_failed,
                        context.getString(R.string.update_err_install)), Toast.LENGTH_LONG).show();
            }
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, R.string.update_cancelled, Toast.LENGTH_SHORT).show();
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, context.getString(R.string.update_failed,
                    msg != null && !msg.isEmpty() ? msg : context.getString(R.string.update_err_install)),
                    Toast.LENGTH_LONG).show();
        }
    }
}
