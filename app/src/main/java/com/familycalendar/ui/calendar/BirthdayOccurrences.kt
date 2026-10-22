package com.familycalendar.ui.calendar

import com.familycalendar.data.Birthday
import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.EventKind
import com.familycalendar.data.NO_REMINDER
import com.familycalendar.data.Occurrence
import com.familycalendar.data.dateIn
import com.familycalendar.data.label
import java.time.LocalDate
import java.time.ZoneId

/**
 * Birthdays falling in [range] as all-day occurrences of synthetic events, one per year, so the calendar views
 * can draw them like any other event. They never reach reminders or change notifications.
 */
fun birthdayOccurrences(birthdays: List<Birthday>, range: ClosedRange<LocalDate>, zone: ZoneId): List<Occurrence> =
    birthdays.flatMap { b ->
        (range.start.year..range.endInclusive.year).mapNotNull { year ->
            val date = b.dateIn(year)?.takeIf { it in range } ?: return@mapNotNull null
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            val event = CalendarEvent(
                id = b.id,
                ownerId = b.ownerId,
                ownerName = b.ownerName,
                title = b.label(year),
                start = start,
                end = end,
                allDay = true,
                reminderMinutes = NO_REMINDER,
                kind = EventKind.BIRTHDAY,
            )
            Occurrence(event, start, end)
        }
    }
