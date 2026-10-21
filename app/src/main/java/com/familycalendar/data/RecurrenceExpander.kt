package com.familycalendar.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object RecurrenceExpander {

    /** All occurrences of [events] that overlap [from, until), sorted by start. */
    fun expand(events: List<CalendarEvent>, from: Instant, until: Instant, zone: ZoneId): List<Occurrence> =
        events.flatMap { expand(it, from, until, zone) }.sortedWith(compareBy({ it.start }, { it.end }))

    fun expand(event: CalendarEvent, from: Instant, until: Instant, zone: ZoneId): List<Occurrence> {
        val duration = Duration.between(event.start, event.end).coerceAtLeast(Duration.ZERO)
        val rule = event.recurrence
        if (!rule.repeats) {
            return if (overlaps(event.start, event.end, from, until)) listOf(Occurrence(event, event.start, event.end)) else emptyList()
        }

        val unit = when (rule.frequency) {
            Frequency.DAILY -> ChronoUnit.DAYS
            Frequency.WEEKLY -> ChronoUnit.WEEKS
            Frequency.MONTHLY -> ChronoUnit.MONTHS
            Frequency.YEARLY -> ChronoUnit.YEARS
            Frequency.NONE -> error("unreachable")
        }
        val interval = rule.interval.coerceAtLeast(1).toLong()
        // Wall-clock arithmetic keeps "every Monday 9:00" at 9:00 across DST changes.
        val base = LocalDateTime.ofInstant(event.start, zone)
        val windowStart = LocalDateTime.ofInstant(from.minus(duration), zone)

        // Jump close to the window instead of walking from the first occurrence.
        var period = (unit.between(base, windowStart) / interval - 1).coerceAtLeast(0)
        val result = mutableListOf<Occurrence>()
        while (true) {
            val dates = datesInPeriod(rule, base.toLocalDate(), period * interval)
            val firstStart = dates.firstOrNull()?.atTime(base.toLocalTime())?.atZone(zone)?.toInstant()
            if (firstStart != null && !firstStart.isBefore(until)) break
            if (rule.until != null && dates.firstOrNull()?.isAfter(rule.until) == true) break
            for (date in dates) {
                // Weekly rules can list weekdays earlier in the first week than the series start.
                if (date.isBefore(base.toLocalDate())) continue
                if (rule.until != null && date.isAfter(rule.until)) break
                if (date in rule.exceptions) continue
                val start = date.atTime(base.toLocalTime()).atZone(zone).toInstant()
                val end = start.plus(duration)
                if (overlaps(start, end, from, until)) result += Occurrence(event, start, end)
            }
            period++
        }
        return result
    }

    /** Occurrence dates of the period that is [offset] units after the series start, ascending. */
    private fun datesInPeriod(rule: Recurrence, base: LocalDate, offset: Long): List<LocalDate> = when (rule.frequency) {
        Frequency.DAILY -> listOf(base.plusDays(offset))
        Frequency.WEEKLY -> {
            // Weeks are counted from the Monday of the start's week, so intervals stay aligned.
            val monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset)
            rule.weekdays.ifEmpty { setOf(base.dayOfWeek) }.sorted().map { monday.plusDays(it.value - 1L) }
        }
        Frequency.MONTHLY -> {
            val month = YearMonth.from(base).plusMonths(offset)
            if (rule.monthlyOrdinal == 0) {
                // Always offset from the base so monthly events on the 31st don't drift to the 28th.
                listOf(base.plusMonths(offset))
            } else {
                listOf(month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(rule.monthlyOrdinal, base.dayOfWeek)))
            }
        }
        Frequency.YEARLY -> listOf(base.plusYears(offset))
        Frequency.NONE -> emptyList()
    }

    private fun overlaps(start: Instant, end: Instant, from: Instant, until: Instant): Boolean =
        start.isBefore(until) && (end.isAfter(from) || (end == start && !start.isBefore(from)))
}

/** Which occurrence of its weekday [date] is in its month: 1–4, or [Recurrence.LAST] for the last one. */
fun weekdayOrdinal(date: LocalDate): Int {
    val ordinal = (date.dayOfMonth - 1) / 7 + 1
    return if (ordinal >= 5) Recurrence.LAST else ordinal
}
