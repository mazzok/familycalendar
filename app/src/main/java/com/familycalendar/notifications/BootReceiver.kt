package com.familycalendar.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarms are cleared on reboot and app update, so re-arm them. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Nothing is armed any more after a reboot.
                NotificationPrefs(context).setScheduledKeys(emptySet())
                ReminderSyncWorker.syncNow(context)
            }
        }
    }
}
