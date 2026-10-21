package com.familycalendar.notifications

import android.content.Context

/** Per-device notification settings: which shared calendars the user muted, and which alarms are armed. */
class NotificationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)

    fun mutedOwners(): Set<String> = prefs.getStringSet(KEY_MUTED, emptySet()).orEmpty().toSet()

    fun setMuted(ownerId: String, muted: Boolean) {
        val updated = if (muted) mutedOwners() + ownerId else mutedOwners() - ownerId
        prefs.edit().putStringSet(KEY_MUTED, updated).apply()
    }

    fun scheduledKeys(): Set<String> = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().toSet()

    fun setScheduledKeys(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_SCHEDULED, keys).apply()
    }

    private companion object {
        const val KEY_MUTED = "muted_owners"
        const val KEY_SCHEDULED = "scheduled_keys"
    }
}
