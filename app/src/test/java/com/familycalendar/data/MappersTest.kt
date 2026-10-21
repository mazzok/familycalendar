package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class MappersTest {
    @Test
    fun `toMap does not contain checklist`() {
        val e = CalendarEvent(ownerId = "anna", title = "Grillabend", checklist = listOf(ChecklistItem("a", "Kohle")))
        assertFalse("checklist" in e.toMap())
        assertEquals("event", e.toMap()["kind"])
        assertEquals("checklist", e.copy(kind = EventKind.CHECKLIST).toMap()["kind"])
    }

    @Test
    fun `checklist round-trips through the Firestore shape`() {
        val at = Instant.parse("2026-10-23T10:00:00Z")
        val items = listOf(
            ChecklistItem("b", "Brot", order = 1, checkedBy = "ben", checkedByName = "Ben", checkedAt = at),
            ChecklistItem("a", "Milch", order = 0),
        )
        assertEquals(items.sortedBy { it.order }, parseChecklist(items.toFirestore()))
    }

    @Test
    fun `a tick on an item someone removed meanwhile leaves no ghost`() {
        // An offline tick recreates checklist.<id> with only the tick fields.
        val ghost = mapOf("checkedBy" to "ben", "checkedByName" to "Ben")
        assertEquals(emptyList<ChecklistItem>(), parseChecklist(mapOf("gone" to ghost)))
    }

    @Test
    fun `malformed checklist entries are skipped`() {
        assertEquals(emptyList<ChecklistItem>(), parseChecklist(null))
        assertEquals(listOf("ok"), parseChecklist(mapOf("x" to "junk", "ok" to mapOf("text" to "Eier"))).map { it.id })
    }
}
