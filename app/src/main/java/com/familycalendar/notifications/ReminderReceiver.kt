package com.familycalendar.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familycalendar.MainActivity
import com.familycalendar.R
import com.google.firebase.auth.FirebaseAuth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Event" }
        val start = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_START, 0L))
        val ownerId = intent.getStringExtra(EXTRA_OWNER_ID)
        val ownerName = intent.getStringExtra(EXTRA_OWNER_NAME).orEmpty()
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val isMine = ownerId == FirebaseAuth.getInstance().currentUser?.uid

        val zoned = start.atZone(ZoneId.systemDefault())
        val text = buildList {
            if (intent.getBooleanExtra(EXTRA_ALL_DAY, false)) {
                add("All day · " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(zoned))
            } else {
                add("Starts at " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(zoned))
            }
            if (location.isNotBlank()) add(location)
            if (!isMine && ownerName.isNotBlank()) add("$ownerName's calendar")
        }.joinToString(" · ")

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(start.toEpochMilli())
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(intent.dataString.hashCode(), notification)
    }

    companion object {
        const val ACTION_REMIND = "com.familycalendar.action.REMIND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_OWNER_ID = "ownerId"
        const val EXTRA_OWNER_NAME = "ownerName"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START = "start"
        const val EXTRA_ALL_DAY = "allDay"
    }
}
