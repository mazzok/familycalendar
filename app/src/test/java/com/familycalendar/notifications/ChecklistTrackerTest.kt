package com.familycalendar.notifications

import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.ChecklistItem
import com.familycalendar.data.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ChecklistTrackerTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private val list = CalendarEvent(
        id = "c", ownerId = "anna", ownerName = "Anna", title = "Einkauf", kind = EventKind.CHECKLIST,
        allDay = true, start = Instant.parse("2026-10-22T22:00:00Z"),
    )
    private val owners = listOf("anna", "me")
    private val known = ChecklistSeen(true, setOf("anna", "me"), emptyMap())

    /** Items "i0", "i1"… ticked by [by] in this order. */
    private fun items(vararg by: String?) = by.mapIndexed { i, uid ->
        ChecklistItem(
            id = "i$i", text = "item $i", order = i.toLong(), checkedBy = uid, checkedByName = uid.orEmpty(),
            checkedAt = uid?.let { Instant.parse("2026-10-23T10:00:00Z").plusSeconds(i.toLong()) },
        )
    }

    @Test
    fun `first run only remembers`() {
        val d = ChecklistTracker.diff(ChecklistSeen(), owners, listOf(list), "me", emptySet())
        assertTrue(d.created.isEmpty())
        assertEquals(mapOf("anna/c" to false), d.seen.complete)
        assertTrue(d.seen.initialized)
    }

    @Test
    fun `a new list in a known calendar is announced`() {
        val d = ChecklistTracker.diff(known, owners, listOf(list), "me", emptySet())
        assertEquals(listOf(list), d.created)
        assertEquals("Anna created checklist \"Einkauf\" for Fri, Oct 23", ChecklistTracker.createdMessage(list, zone))
    }

    @Test
    fun `checklists of a newly visible calendar are only remembered`() {
        val carla = list.copy(id = "x", ownerId = "carla")
        val d = ChecklistTracker.diff(known, listOf("anna", "me", "carla"), listOf(carla), "me", emptySet())
        assertTrue(d.created.isEmpty())
        assertTrue("carla" in d.seen.owners)
    }

    @Test
    fun `my own new list and muted calendars stay quiet`() {
        val mine = list.copy(ownerId = "me")
        assertTrue(ChecklistTracker.diff(known, owners, listOf(mine), "me", emptySet()).created.isEmpty())
        assertTrue(ChecklistTracker.diff(known, owners, listOf(list), "me", setOf("anna")).created.isEmpty())
    }

    @Test
    fun `completion by someone else notifies once`() {
        val open = known.copy(complete = mapOf("anna/c" to false))
        val done = list.copy(checklist = items("me", "martin"))
        val d = ChecklistTracker.diff(open, owners, listOf(done), "me", emptySet())
        assertEquals(listOf(done), d.completed)
        assertEquals("\"Einkauf\" is done. The last item was ticked by martin", ChecklistTracker.completedMessage(done))
        assertTrue(ChecklistTracker.diff(d.seen, owners, listOf(done), "me", emptySet()).completed.isEmpty())
    }

    @Test
    fun `completing it myself stays quiet`() {
        val open = known.copy(complete = mapOf("anna/c" to false))
        val done = list.copy(checklist = items("martin", "me"))
        assertTrue(ChecklistTracker.diff(open, owners, listOf(done), "me", emptySet()).completed.isEmpty())
    }

    @Test
    fun `emptied then completed notifies`() {
        val emptied = ChecklistTracker.diff(known.copy(complete = mapOf("anna/c" to true)), owners, listOf(list), "me", emptySet()).seen
        val d = ChecklistTracker.diff(emptied, owners, listOf(list.copy(checklist = items("martin"))), "me", emptySet())
        assertEquals(1, d.completed.size)
    }

    @Test
    fun `removing the last open item does not count as completed`() {
        // Ben had ticked i0; i1 was open and got removed.
        val open = known.copy(complete = mapOf("anna/c" to false), lastTick = mapOf("anna/c" to "i0|ben"))
        val d = ChecklistTracker.diff(open, owners, listOf(list.copy(checklist = items("ben"))), "me", emptySet())
        assertTrue(d.completed.isEmpty())
    }

    @Test
    fun `a calendar missing from one run keeps its remembered lists`() {
        val remembered = known.copy(complete = mapOf("anna/c" to false))
        // App start: only my own calendar is loaded yet.
        val early = ChecklistTracker.diff(remembered, listOf("me"), emptyList(), "me", emptySet()).seen
        assertTrue("anna" in early.owners)
        val created = list.copy(id = "new")
        val d = ChecklistTracker.diff(early, owners, listOf(list, created), "me", emptySet())
        assertEquals(listOf(created), d.created)
    }

    @Test
    fun `a calendar that loads empty once keeps its remembered lists`() {
        val remembered = known.copy(complete = mapOf("anna/c" to false))
        val failed = ChecklistTracker.diff(remembered, owners, emptyList(), "me", emptySet()).seen
        assertTrue(ChecklistTracker.diff(failed, owners, listOf(list), "me", emptySet()).created.isEmpty())
    }

    @Test
    fun `attached checklists never notify`() {
        val event = list.copy(kind = EventKind.EVENT, checklist = items("martin"))
        val d = ChecklistTracker.diff(known.copy(complete = mapOf("anna/c" to false)), owners, listOf(event), "me", emptySet())
        assertTrue(d.created.isEmpty() && d.completed.isEmpty())
    }
}
