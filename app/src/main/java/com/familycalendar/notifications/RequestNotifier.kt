package com.familycalendar.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familycalendar.MainActivity
import com.familycalendar.R
import com.familycalendar.data.CalendarEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Tells the requested person about a new date request — once per request and date. */
object RequestNotifier {
    private const val CHANNEL_ID = "date_requests"
    private const val PREFS = "request_prefs"
    private const val KEY_NOTIFIED = "notified_requests"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Date requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "When someone asks you to confirm a date" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** [openRequests]: requests addressed to the current user that wait for an answer. */
    fun notifyNew(context: Context, openRequests: List<CalendarEvent>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        // A moved date reopens the request, so the start is part of the key and it notifies again.
        val keyed = openRequests.associateBy { "${it.key}@${it.start.epochSecond}" }
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        keyed.filterKeys { it !in notified }.values.forEach { event ->
            val zoned = event.start.atZone(ZoneId.systemDefault())
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${event.ownerName.ifBlank { "Someone" }} asks you to confirm a date")
                .setContentText("${event.title} · ${format.format(zoned)}")
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(("request/" + event.key).hashCode(), notification)
        }
        // Only remember open ones, so a request reopened later notifies again.
        prefs.edit().putStringSet(KEY_NOTIFIED, keyed.keys).apply()
    }
}
