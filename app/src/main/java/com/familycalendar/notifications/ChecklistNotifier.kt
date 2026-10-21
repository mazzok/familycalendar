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
import java.util.Locale

/**
 * What this phone has seen of standalone checklists: whether it ran before, which calendars it knows,
 * and per checklist (event key) whether all items were ticked.
 */
data class ChecklistSeen(
    val initialized: Boolean = false,
    val owners: Set<String> = emptySet(),
    val complete: Map<String, Boolean> = emptyMap(),
    /** Per checklist, the newest tick as "itemId|uid" ("" if none); completion needs a new one. */
    val lastTick: Map<String, String> = emptyMap(),
)

data class ChecklistDiff(val created: List<CalendarEvent>, val completed: List<CalendarEvent>, val seen: ChecklistSeen)

object ChecklistTracker {
    private val DayFormat = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)

    /**
     * Decides which standalone checklists to announce as created or completed. Completion is worked out
     * from the items (no stored counter), so simultaneous or offline ticks can't lose or double it.
     * The first run, and the first sighting of a calendar, only remember what they see.
     */
    fun diff(
        seen: ChecklistSeen,
        owners: Collection<String>,
        events: List<CalendarEvent>,
        myUid: String,
        muted: Set<String>,
    ): ChecklistDiff {
        val lists = events.filter { it.isChecklist && !it.cancelled && it.ownerId in owners }
        // A calendar without checklists in this run may just not be loaded yet (app start) or have a failed
        // listener, so what we knew about it stays; otherwise its lists would later look new or be missed.
        val withLists = lists.map { it.ownerId }.toSet()
        fun kept(key: String) = key.substringBefore('/') !in withLists
        val next = ChecklistSeen(
            initialized = true,
            owners = seen.owners + owners,
            complete = seen.complete.filterKeys(::kept) + lists.associate { it.key to it.checklistComplete },
            lastTick = seen.lastTick.filterKeys(::kept) + lists.associate { it.key to tickMarker(it) },
        )
        if (!seen.initialized) return ChecklistDiff(emptyList(), emptyList(), next)
        val audible = lists.filter { it.ownerId !in muted }
        val created = audible.filter { it.key !in seen.complete && it.ownerId in seen.owners && it.ownerId != myUid }
        // Completed means a new tick finished it, not that the last open item was removed.
        // Whoever ticked the last item already knows.
        val completed = audible.filter {
            seen.complete[it.key] == false && it.checklistComplete &&
                tickMarker(it) != seen.lastTick[it.key] && it.lastTicked()?.checkedBy != myUid
        }
        return ChecklistDiff(created, completed, next)
    }

    /** Which tick is the newest, as "itemId|uid"; "" if nothing is ticked. */
    private fun tickMarker(event: CalendarEvent): String =
        event.lastTicked()?.let { "${it.id}|${it.checkedBy}" }.orEmpty()

    fun createdMessage(event: CalendarEvent, zone: ZoneId): String =
        "${event.ownerName.ifBlank { "Someone" }} created checklist \"${event.title}\" for " +
            event.start.atZone(zone).format(DayFormat)

    fun completedMessage(event: CalendarEvent): String =
        "\"${event.title}\" is done. The last item was ticked by ${event.lastTicked()?.checkedByName?.ifBlank { null } ?: "someone"}"
}

/** Tells everyone who can see a calendar when a standalone checklist is created there or completed. */
object ChecklistNotifier {
    private const val CHANNEL = "shared_checklists"
    private const val PREFS = "checklist_prefs"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_OWNERS = "owners"
    private const val KEY_COMPLETE = "complete" // entries "eventKey|1" or "eventKey|0"
    private const val KEY_LAST_TICK = "last_tick" // entries "eventKey|itemId|uid" or "eventKey|"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Shared checklists", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When someone creates or finishes a shared checklist" }
        )
    }

    /** [owners]: every calendar [events] was loaded from, including ones without events. */
    fun notify(context: Context, myUid: String, owners: Collection<String>, events: List<CalendarEvent>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = ChecklistSeen(
            initialized = prefs.getBoolean(KEY_INITIALIZED, false),
            owners = prefs.getStringSet(KEY_OWNERS, emptySet()).orEmpty().toSet(),
            complete = prefs.getStringSet(KEY_COMPLETE, emptySet()).orEmpty().mapNotNull { entry ->
                val split = entry.lastIndexOf('|')
                if (split < 0) null else entry.substring(0, split) to (entry.substring(split + 1) == "1")
            }.toMap(),
            // The marker itself contains '|', the event key never does.
            lastTick = prefs.getStringSet(KEY_LAST_TICK, emptySet()).orEmpty().mapNotNull { entry ->
                val split = entry.indexOf('|')
                if (split < 0) null else entry.substring(0, split) to entry.substring(split + 1)
            }.toMap(),
        )
        val diff = ChecklistTracker.diff(seen, owners, events, myUid, NotificationPrefs(context).mutedOwners())
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            val zone = ZoneId.systemDefault()
            diff.created.forEach { post(context, "checklist-created/" + it.key, ChecklistTracker.createdMessage(it, zone)) }
            diff.completed.forEach { post(context, "checklist-done/" + it.key, ChecklistTracker.completedMessage(it)) }
        }
        // Without permission the state is still saved, so old news doesn't pile up for later.
        prefs.edit()
            .putBoolean(KEY_INITIALIZED, diff.seen.initialized)
            .putStringSet(KEY_OWNERS, diff.seen.owners)
            .putStringSet(KEY_COMPLETE, diff.seen.complete.map { (key, done) -> "$key|${if (done) 1 else 0}" }.toSet())
            .putStringSet(KEY_LAST_TICK, diff.seen.lastTick.map { (key, marker) -> "$key|$marker" }.toSet())
            .apply()
    }

    private fun post(context: Context, id: String, message: String) {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message)
            .setContentText("Open Family Calendar to see the list.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
    }
}
