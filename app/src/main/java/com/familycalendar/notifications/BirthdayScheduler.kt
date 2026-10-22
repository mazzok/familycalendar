package com.familycalendar.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familycalendar.MainActivity
import com.familycalendar.R
import com.familycalendar.data.Birthday
import java.time.Instant

/** Keeps one AlarmManager alarm per upcoming day with birthdays, at 08:00 (see [BirthdayPlanner]). */
object BirthdayScheduler {
    const val CHANNEL_ID = "birthdays"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Birthdays", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "On the day someone has a birthday" }
        )
    }

    /** [birthdays] must be every birthday the user can see; days no longer planned get their alarm cancelled. */
    fun sync(context: Context, birthdays: List<Birthday>, myUid: String?) {
        val prefs = NotificationPrefs(context)
        val plan = BirthdayPlanner.plan(birthdays, Instant.now(), myUid, prefs.mutedOwners(), prefs.birthdaysEnabled)
        val plannedKeys = plan.map { it.key }.toSet()
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        (prefs.scheduledBirthdayKeys() - plannedKeys).forEach { key ->
            PendingIntent.getBroadcast(
                context, 0, birthdayIntent(context, key),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }

        plan.forEach { notice ->
            val pending = PendingIntent.getBroadcast(
                context, 0,
                birthdayIntent(context, notice.key).putExtra(BirthdayReceiver.EXTRA_TEXT, notice.text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = notice.at.toEpochMilli()
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }

        prefs.setScheduledBirthdayKeys(plannedKeys)
    }

    // The data URI makes each day's PendingIntent distinct (Intent.filterEquals compares data).
    private fun birthdayIntent(context: Context, key: String) =
        Intent(context, BirthdayReceiver::class.java)
            .setAction(BirthdayReceiver.ACTION_BIRTHDAY)
            .setData(Uri.parse("familycalendar://$key"))
}

class BirthdayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BIRTHDAY) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, BirthdayScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(intent.dataString.hashCode(), notification)
    }

    companion object {
        const val ACTION_BIRTHDAY = "com.familycalendar.action.BIRTHDAY"
        const val EXTRA_TEXT = "text"
    }
}
