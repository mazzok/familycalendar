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

object ChangeTracker {
    /**
     * Decides which of the [relevant] events had their counter ([seq]) grow since last seen. [known] maps
     * event key to the last seen counter. Events seen for the first time are only remembered, so a fresh
     * install doesn't replay old changes.
     */
    fun diff(
        known: Map<String, Int>,
        events: List<CalendarEvent>,
        relevant: (CalendarEvent) -> Boolean,
        seq: (CalendarEvent) -> Int,
    ): Pair<List<CalendarEvent>, Map<String, Int>> {
        val mine = events.filter(relevant)
        val toNotify = mine.filter { e -> known[e.key]?.let { seq(e) > it } == true }
        return toNotify to mine.associate { it.key to seq(it) }
    }

    /** Changes by the owner, for the requested person. */
    fun diffChanges(known: Map<String, Int>, events: List<CalendarEvent>, myUid: String) =
        diff(known, events, { it.requestedUid == myUid }, { it.changeSeq })

    /** Answers by the requested person, for the owner. */
    fun diffAnswers(known: Map<String, Int>, events: List<CalendarEvent>, myUid: String) =
        diff(known, events, { it.ownerId == myUid && it.isRequest }, { it.answerSeq })

    fun changeMessage(event: CalendarEvent): String {
        val who = event.ownerName.ifBlank { "Someone" }
        if (event.cancelled) return "$who cancelled \"${event.title}\""
        val what = event.changedFields.joinToString(", ").ifBlank { "details" }
        return "$who changed \"${event.title}\": $what"
    }

    fun answerMessage(event: CalendarEvent): String =
        "${event.requestedName.ifBlank { "Someone" }} ${event.lastAnswer.ifBlank { "answered \"${event.title}\"" }}"
}

/**
 * Notifies the requested person when the owner changes or cancels a date request, and the owner when
 * the requested person answers.
 */
object ChangeNotifier {
    private const val CHANGES_CHANNEL = "request_changes"
    private const val ANSWERS_CHANNEL = "request_answers"
    private const val PREFS = "change_prefs"
    private const val KEY_CHANGES_SEEN = "seen" // entries "eventKey|changeSeq"
    private const val KEY_ANSWERS_SEEN = "answers_seen" // entries "eventKey|answerSeq"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANGES_CHANNEL, "Changed or cancelled dates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When someone changes or cancels a date they asked you to confirm" }
        )
        manager.createNotificationChannel(
            NotificationChannel(ANSWERS_CHANNEL, "Answers to your requests", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When someone accepts or declines a date you asked them to confirm" }
        )
    }

    /** [events] must include tombstones of cancelled requests. */
    fun notifyChanges(context: Context, myUid: String, events: List<CalendarEvent>) =
        notifyGrown(context, KEY_CHANGES_SEEN, CHANGES_CHANNEL, "change/", ChangeTracker.diffChanges(load(context, KEY_CHANGES_SEEN), events, myUid)) { e ->
            ChangeTracker.changeMessage(e) to if (e.cancelled) "It was removed from the calendar." else "Open Family Calendar to see the details."
        }

    fun notifyAnswers(context: Context, myUid: String, events: List<CalendarEvent>) =
        notifyGrown(context, KEY_ANSWERS_SEEN, ANSWERS_CHANNEL, "answer/", ChangeTracker.diffAnswers(load(context, KEY_ANSWERS_SEEN), events, myUid)) { e ->
            ChangeTracker.answerMessage(e) to "Open Family Calendar to see the details."
        }

    private fun load(context: Context, key: String): Map<String, Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
            val split = entry.lastIndexOf('|')
            entry.substring(split + 1).toIntOrNull()?.let { entry.substring(0, split) to it }
        }.toMap()

    private fun notifyGrown(
        context: Context,
        prefsKey: String,
        channel: String,
        idPrefix: String,
        result: Pair<List<CalendarEvent>, Map<String, Int>>,
        text: (CalendarEvent) -> Pair<String, String>,
    ) {
        val (grown, seen) = result
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed && grown.isNotEmpty()) {
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            grown.forEach { event ->
                val (title, body) = text(event)
                val notification = NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context).notify((idPrefix + event.key).hashCode(), notification)
            }
        }
        // Without permission the changes are still marked seen, so they don't pile up for later.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(prefsKey, seen.map { (key, seq) -> "$key|$seq" }.toSet()).apply()
    }
}
