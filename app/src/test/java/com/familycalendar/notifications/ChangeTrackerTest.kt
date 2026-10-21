package com.familycalendar.notifications

import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.RequestStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ChangeTrackerTest {
    private val request = CalendarEvent(
        id = "e", ownerId = "anna", ownerName = "Anna", title = "Dinner",
        requestedUid = "ben", requestedName = "Ben", requestStatus = RequestStatus.ACCEPTED, changeSeq = 3,
    )

    @Test
    fun `first sighting is remembered without notifying`() {
        val (notify, seen) = ChangeTracker.diffChanges(emptyMap(), listOf(request), "ben")
        assertEquals(emptyList<CalendarEvent>(), notify)
        assertEquals(mapOf("anna/e" to 3), seen)
    }

    @Test
    fun `a higher change counter notifies once`() {
        val changed = request.copy(changeSeq = 4, changedFields = listOf("location"))
        val (notify, seen) = ChangeTracker.diffChanges(mapOf("anna/e" to 3), listOf(changed), "ben")
        assertEquals(listOf(changed), notify)
        assertEquals("Anna changed \"Dinner\": location", ChangeTracker.changeMessage(changed))
        assertEquals(emptyList<CalendarEvent>(), ChangeTracker.diffChanges(seen, listOf(changed), "ben").first)
    }

    @Test
    fun `cancellation is reported, requests to others are ignored`() {
        val cancelled = request.copy(changeSeq = 4, cancelledAt = Instant.EPOCH)
        val other = request.copy(id = "x", requestedUid = "carla", changeSeq = 9)
        val (notify, _) = ChangeTracker.diffChanges(mapOf("anna/e" to 3, "anna/x" to 1), listOf(cancelled, other), "ben")
        assertEquals(listOf(cancelled), notify)
        assertEquals("Anna cancelled \"Dinner\"", ChangeTracker.changeMessage(cancelled))
    }

    @Test
    fun `the owner hears about answers, the requested person does not`() {
        val answered = request.copy(answerSeq = 2, lastAnswer = "declined \"Dinner\" on Fri, Oct 30")
        val (forOwner, _) = ChangeTracker.diffAnswers(mapOf("anna/e" to 1), listOf(answered), "anna")
        assertEquals(listOf(answered), forOwner)
        assertEquals("Ben declined \"Dinner\" on Fri, Oct 30", ChangeTracker.answerMessage(answered))
        assertEquals(emptyList<CalendarEvent>(), ChangeTracker.diffAnswers(mapOf("anna/e" to 1), listOf(answered), "ben").first)
    }
}
