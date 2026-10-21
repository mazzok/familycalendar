package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceExpanderTest {
    private val zone = ZoneId.of("Europe/Vienna")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun event(start: String, end: String, recurrence: Recurrence = Recurrence()) =
        CalendarEvent(id = "e", ownerId = "me", start = at(start), end = at(end), recurrence = recurrence)

    private fun starts(event: CalendarEvent, from: String, until: String) =
        RecurrenceExpander.expand(event, at(from), at(until), zone)
            .map { LocalDateTime.ofInstant(it.start, zone).toString() }

    @Test
    fun `single event is returned only when it overlaps the window`() {
        val e = event("2026-10-20T10:00", "2026-10-20T11:00")
        assertEquals(listOf("2026-10-20T10:00"), starts(e, "2026-10-20T00:00", "2026-10-21T00:00"))
        assertEquals(listOf("2026-10-20T10:00"), starts(e, "2026-10-20T10:30", "2026-10-20T12:00"))
        assertEquals(emptyList<String>(), starts(e, "2026-10-20T11:00", "2026-10-21T00:00"))
    }

    @Test
    fun `weekly event keeps wall-clock time across DST and honours exceptions and until`() {
        val e = event(
            "2026-10-19T09:00", "2026-10-19T10:00",
            Recurrence(
                frequency = Frequency.WEEKLY,
                until = LocalDate.parse("2026-11-16"),
                exceptions = setOf(LocalDate.parse("2026-11-02")),
            ),
        )
        assertEquals(
            listOf("2026-10-19T09:00", "2026-10-26T09:00", "2026-11-09T09:00", "2026-11-16T09:00"),
            starts(e, "2026-10-01T00:00", "2027-01-01T00:00"),
        )
    }

    @Test
    fun `monthly event on the 31st clamps per month without drifting`() {
        val e = event("2026-01-31T08:00", "2026-01-31T09:00", Recurrence(frequency = Frequency.MONTHLY))
        assertEquals(
            listOf("2026-01-31T08:00", "2026-02-28T08:00", "2026-03-31T08:00"),
            starts(e, "2026-01-01T00:00", "2026-04-01T00:00"),
        )
    }

    @Test
    fun `daily event with interval jumps straight to a far window`() {
        val e = event("2020-01-01T07:00", "2020-01-01T07:30", Recurrence(frequency = Frequency.DAILY, interval = 2))
        // 2020-01-01 → 2026-10-19 is 2483 days (odd), so every other day lands on the 20th, 22nd, …
        assertEquals(
            listOf("2026-10-20T07:00", "2026-10-22T07:00"),
            starts(e, "2026-10-19T00:00", "2026-10-23T00:00"),
        )
    }

    @Test
    fun `weekly on several weekdays skips weekdays before the series start`() {
        // Wednesday 2026-10-21, repeating Mon/Wed/Fri: the Monday of the first week is not included.
        val e = event(
            "2026-10-21T18:00", "2026-10-21T19:00",
            Recurrence(
                frequency = Frequency.WEEKLY,
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            ),
        )
        assertEquals(
            listOf("2026-10-21T18:00", "2026-10-23T18:00", "2026-10-26T18:00", "2026-10-28T18:00"),
            starts(e, "2026-10-19T00:00", "2026-10-29T00:00"),
        )
    }

    @Test
    fun `every second week stays aligned to the start week`() {
        val e = event("2026-10-20T08:00", "2026-10-20T09:00", Recurrence(frequency = Frequency.WEEKLY, interval = 2))
        assertEquals(
            listOf("2026-11-03T08:00", "2026-11-17T08:00"),
            starts(e, "2026-10-28T00:00", "2026-11-20T00:00"),
        )
    }

    @Test
    fun `monthly on the third Tuesday and on the last Friday`() {
        val third = event(
            "2026-10-20T19:00", "2026-10-20T20:00", // third Tuesday of October
            Recurrence(frequency = Frequency.MONTHLY, monthlyOrdinal = weekdayOrdinal(LocalDate.parse("2026-10-20"))),
        )
        assertEquals(
            listOf("2026-10-20T19:00", "2026-11-17T19:00", "2026-12-15T19:00"),
            starts(third, "2026-10-01T00:00", "2027-01-01T00:00"),
        )
        val last = event(
            "2026-10-30T12:00", "2026-10-30T13:00", // last Friday of October
            Recurrence(frequency = Frequency.MONTHLY, monthlyOrdinal = weekdayOrdinal(LocalDate.parse("2026-10-30"))),
        )
        assertEquals(
            listOf("2026-10-30T12:00", "2026-11-27T12:00", "2026-12-25T12:00"),
            starts(last, "2026-10-01T00:00", "2027-01-01T00:00"),
        )
    }

    @Test
    fun `occurrence that started before the window but is still running is included`() {
        val e = event("2026-10-19T22:00", "2026-10-20T02:00", Recurrence(frequency = Frequency.DAILY))
        assertEquals(
            listOf("2026-10-19T22:00", "2026-10-20T22:00"),
            starts(e, "2026-10-20T00:00", "2026-10-21T00:00"),
        )
    }
}
