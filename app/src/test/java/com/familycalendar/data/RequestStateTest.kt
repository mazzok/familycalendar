package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RequestStateTest {
    private val start = Instant.parse("2026-10-23T17:00:00Z")
    private val accepted = CalendarEvent(
        id = "e", ownerId = "anna", title = "Dinner",
        start = start, end = start.plus(Duration.ofHours(2)),
        requestedUid = "ben", requestedName = "Ben", requestStatus = RequestStatus.ACCEPTED,
    )

    @Test
    fun `new request starts pending`() {
        val fresh = accepted.copy(id = "", requestStatus = null).withRequestStateFrom(previous = null)
        assertEquals(RequestStatus.PENDING, fresh.requestStatus)
    }

    @Test
    fun `editing only the title keeps the answer`() {
        assertEquals(RequestStatus.ACCEPTED, accepted.copy(title = "Dinner at 7").withRequestStateFrom(accepted).requestStatus)
    }

    @Test
    fun `moving the date or asking someone else reopens the request`() {
        val moved = accepted.copy(start = start.plus(Duration.ofDays(1)), end = start.plus(Duration.ofDays(1).plusHours(2)))
        assertEquals(RequestStatus.PENDING, moved.withRequestStateFrom(accepted).requestStatus)
        assertEquals(RequestStatus.PENDING, accepted.copy(requestedUid = "carla").withRequestStateFrom(accepted).requestStatus)
    }

    @Test
    fun `changing the repeat rule reopens the request`() {
        val weekly = accepted.copy(recurrence = Recurrence(Frequency.WEEKLY))
        assertEquals(RequestStatus.PENDING, weekly.withRequestStateFrom(accepted).requestStatus)
    }

    @Test
    fun `small changes keep the answer and leave a change notice`() {
        val edited = accepted.copy(location = "Pizzeria", description = "bring cake")
            .withRequestStateFrom(accepted)
            .withChangeNoticeFrom(accepted)
        assertEquals(RequestStatus.ACCEPTED, edited.requestStatus)
        assertEquals(1, edited.changeSeq)
        assertEquals(listOf("location", "notes"), edited.changedFields)
    }

    @Test
    fun `a reopened request gets no extra change notice`() {
        val moved = accepted.copy(start = start.plus(Duration.ofDays(1)), end = start.plus(Duration.ofDays(1).plusHours(2)))
            .withRequestStateFrom(accepted)
            .withChangeNoticeFrom(accepted)
        assertEquals(RequestStatus.PENDING, moved.requestStatus)
        assertEquals(0, moved.changeSeq)
    }

    @Test
    fun `single declined dates override the series and are cleared when the request reopens`() {
        val day = java.time.LocalDate.parse("2026-10-30")
        val series = accepted.copy(recurrence = Recurrence(Frequency.WEEKLY), declinedDates = setOf(day))
        assertEquals(RequestStatus.DECLINED, series.requestStatusOn(day))
        assertEquals(RequestStatus.ACCEPTED, series.requestStatusOn(day.plusWeeks(1)))
        assertEquals(setOf(day), series.copy(title = "Pizza").withRequestStateFrom(series).declinedDates)
        val moved = series.copy(start = start.plus(Duration.ofHours(1)), end = start.plus(Duration.ofHours(3)))
        assertEquals(emptySet<java.time.LocalDate>(), moved.withRequestStateFrom(series).declinedDates)
    }

    @Test
    fun `removing the person clears the request`() {
        val cleared = accepted.copy(requestedUid = null).withRequestStateFrom(accepted)
        assertNull(cleared.requestStatus)
        assertEquals("", cleared.requestedName)
    }
}
