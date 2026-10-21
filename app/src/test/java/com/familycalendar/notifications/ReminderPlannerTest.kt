package com.familycalendar.notifications

import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.Frequency
import com.familycalendar.data.NO_REMINDER
import com.familycalendar.data.Recurrence
import com.familycalendar.data.RequestStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ReminderPlannerTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    private fun event(
        id: String,
        startsIn: Duration,
        reminder: Int = 15,
        owner: String = "me",
        recurrence: Recurrence = Recurrence(),
    ) = CalendarEvent(
        id = id,
        ownerId = owner,
        start = now.plus(startsIn),
        end = now.plus(startsIn).plus(Duration.ofHours(1)),
        reminderMinutes = reminder,
        recurrence = recurrence,
    )

    private fun plan(events: List<CalendarEvent>, muted: Set<String> = emptySet()) =
        ReminderPlanner.plan(events, now, muted, ZoneOffset.UTC)

    @Test
    fun `reminder fires the configured minutes before start`() {
        val plan = plan(listOf(event("a", Duration.ofHours(2))))
        assertEquals(listOf(now.plus(Duration.ofMinutes(105))), plan.map { it.at })
    }

    @Test
    fun `skips events without reminder, past reminders and muted calendars`() {
        val events = listOf(
            event("none", Duration.ofHours(2), reminder = NO_REMINDER),
            event("past", Duration.ofMinutes(10), reminder = 15),
            event("muted", Duration.ofHours(2), owner = "anna"),
            event("ok", Duration.ofHours(2), owner = "ben"),
        )
        assertEquals(listOf("ben/ok"), plan(events, muted = setOf("anna")).map { it.occurrence.event.key })
    }

    @Test
    fun `skips reminders beyond the horizon`() {
        assertEquals(emptyList<PlannedReminder>(), plan(listOf(event("far", ReminderPlanner.HORIZON.plusDays(1)))))
    }

    @Test
    fun `declined dates don't remind the requested person but still remind the owner`() {
        val weekly = event("w", Duration.ofDays(-30), owner = "anna", recurrence = Recurrence(frequency = Frequency.WEEKLY))
        val inFiveDays = now.plus(Duration.ofDays(5)).atZone(ZoneOffset.UTC).toLocalDate()
        val request = weekly.copy(
            requestedUid = "ben",
            requestStatus = RequestStatus.ACCEPTED,
            declinedDates = setOf(inFiveDays),
        )
        val forBen = ReminderPlanner.plan(listOf(request), now, emptySet(), ZoneOffset.UTC, myUid = "ben")
        assertEquals(1, forBen.size) // only the occurrence in 12 days
        val forAnna = ReminderPlanner.plan(listOf(request), now, emptySet(), ZoneOffset.UTC, myUid = "anna")
        assertEquals(2, forAnna.size)
        val allDeclined = request.copy(requestStatus = RequestStatus.DECLINED)
        assertEquals(0, ReminderPlanner.plan(listOf(allDeclined), now, emptySet(), ZoneOffset.UTC, myUid = "ben").size)
    }

    @Test
    fun `repeating event gets one reminder per occurrence inside the horizon`() {
        val weekly = event("w", Duration.ofDays(-30), recurrence = Recurrence(frequency = Frequency.WEEKLY))
        val reminders = plan(listOf(weekly))
        assertEquals(2, reminders.size) // occurrences 5 and 12 days from now
        assertEquals(2, reminders.map { it.key }.toSet().size)
    }
}
