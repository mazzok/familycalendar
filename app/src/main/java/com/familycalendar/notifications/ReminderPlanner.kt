package com.familycalendar.notifications

import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.NO_REMINDER
import com.familycalendar.data.Occurrence
import com.familycalendar.data.RecurrenceExpander
import com.familycalendar.data.RequestStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class PlannedReminder(val occurrence: Occurrence, val at: Instant) {
    val key: String get() = occurrence.key
}

object ReminderPlanner {
    /** Reminders further out are picked up by a later periodic sync. */
    val HORIZON: Duration = Duration.ofDays(14)

    /**
     * Every reminder (one per occurrence) that should be armed right now. Dates [myUid] was asked to confirm
     * and declined don't remind them; the owner keeps their reminders.
     */
    fun plan(
        events: List<CalendarEvent>,
        now: Instant,
        mutedOwners: Set<String>,
        zone: ZoneId = ZoneId.systemDefault(),
        myUid: String? = null,
    ): List<PlannedReminder> {
        val relevant = events.filter { it.reminderMinutes != NO_REMINDER && it.ownerId !in mutedOwners }
        // Occurrences that start up to a day after the horizon can still have a reminder inside it.
        return RecurrenceExpander.expand(relevant, now, now.plus(HORIZON).plus(Duration.ofDays(1)), zone)
            .filterNot { it.event.requestedUid == myUid && myUid != null && it.requestStatus(zone) == RequestStatus.DECLINED }
            .map { PlannedReminder(it, it.start.minus(Duration.ofMinutes(it.event.reminderMinutes.toLong()))) }
            .filter { it.at.isAfter(now) && it.at.isBefore(now.plus(HORIZON)) }
    }
}
