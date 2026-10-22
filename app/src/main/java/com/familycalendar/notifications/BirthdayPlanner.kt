package com.familycalendar.notifications

import com.familycalendar.data.Birthday
import com.familycalendar.data.ageIn
import com.familycalendar.data.dateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One notification on [date] at [at], naming everyone who has a birthday that day. */
data class BirthdayNotice(val date: LocalDate, val at: Instant, val text: String) {
    val key: String get() = "birthday/$date"
}

object BirthdayPlanner {
    private const val HOUR = 8

    /**
     * Notices to arm right now: one per day with birthdays, at 08:00 on the day. My own account birthday and
     * birthdays from muted calendars are left out; only future instants within the reminder horizon count.
     */
    fun plan(
        birthdays: List<Birthday>,
        now: Instant,
        myUid: String?,
        mutedOwners: Set<String>,
        enabled: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<BirthdayNotice> {
        if (!enabled) return emptyList()
        val relevant = birthdays.filterNot { (it.isAccount && it.ownerId == myUid) || it.ownerId in mutedOwners }
        val until = now.plus(ReminderPlanner.HORIZON)
        val today = now.atZone(zone).toLocalDate()
        return (0L..ReminderPlanner.HORIZON.toDays() + 1).mapNotNull { offset ->
            val date = today.plusDays(offset)
            val at = date.atTime(HOUR, 0).atZone(zone).toInstant()
            if (!at.isAfter(now) || !at.isBefore(until)) return@mapNotNull null
            val due = relevant.filter { it.dateIn(date.year) == date }
            if (due.isEmpty()) null else BirthdayNotice(date, at, text(due, date.year))
        }
    }

    /** "🎂 Today is Lisa's birthday (40)", or for several "🎂 Birthdays today: Lisa (40), Oma". */
    fun text(birthdays: List<Birthday>, year: Int): String {
        val names = birthdays.sortedBy { it.name }.map { b -> b.name + (b.ageIn(year)?.let { " ($it)" } ?: "") }
        if (names.size > 1) return "🎂 Birthdays today: ${names.joinToString(", ")}"
        val b = birthdays.single()
        return "🎂 Today is ${b.name}'s birthday" + (b.ageIn(year)?.let { " ($it)" } ?: "")
    }
}
