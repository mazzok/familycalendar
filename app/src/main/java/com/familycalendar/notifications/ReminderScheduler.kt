package com.familycalendar.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.familycalendar.R
import com.familycalendar.data.CalendarEvent
import java.time.Instant

/** Keeps AlarmManager in sync with the reminders of all visible events (own + shared). */
object ReminderScheduler {
    const val CHANNEL_ID = "event_reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.reminder_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [events] must be the complete set of events the user can see; anything missing gets its alarm cancelled. */
    fun sync(context: Context, events: List<CalendarEvent>, myUid: String?) {
        val prefs = NotificationPrefs(context)
        val plan = ReminderPlanner.plan(events, Instant.now(), prefs.mutedOwners(), myUid = myUid)
        val plannedKeys = plan.map { it.key }.toSet()
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        (prefs.scheduledKeys() - plannedKeys).forEach { key ->
            PendingIntent.getBroadcast(
                context, 0, reminderIntent(context, key),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }

        plan.forEach { reminder ->
            val event = reminder.occurrence.event
            val at = reminder.at
            val intent = reminderIntent(context, reminder.key).apply {
                putExtra(ReminderReceiver.EXTRA_TITLE, event.title)
                putExtra(ReminderReceiver.EXTRA_OWNER_ID, event.ownerId)
                putExtra(ReminderReceiver.EXTRA_OWNER_NAME, event.ownerName)
                putExtra(ReminderReceiver.EXTRA_LOCATION, event.location)
                putExtra(ReminderReceiver.EXTRA_START, reminder.occurrence.start.toEpochMilli())
                putExtra(ReminderReceiver.EXTRA_ALL_DAY, event.allDay)
            }
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
            }
        }

        prefs.setScheduledKeys(plannedKeys)
    }

    // The data URI makes each event's PendingIntent distinct (Intent.filterEquals compares data).
    private fun reminderIntent(context: Context, key: String) =
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMIND)
            .setData(Uri.parse("familycalendar://reminder/$key"))
}
