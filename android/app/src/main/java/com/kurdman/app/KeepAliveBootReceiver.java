package com.kurdman.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the user's explicit "keep alive until STOP" choice after a
 * reboot or an in-place app update. It does nothing unless that persistent
 * manual mode was switched on in KurdMan first.
 */
public class KeepAliveBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (SavedJobCoordinator.hasPending(context)) {
            KeepAliveService.startForJob(context);
        } else if (KeepAliveService.isManualDesired(context)) {
            JobRuntime.setStatus(context.getString(R.string.ks_0105));
            KeepAliveService.startManual(context);
        }
    }
}
