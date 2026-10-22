package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `birthday round-trips through the Firestore shape`() {
        val b = Birthday(
            id = "b1", ownerId = "anna", ownerName = "Anna", name = "Oma",
            month = 2, day = 29, year = null, sharedWith = setOf("cara", "ben"),
        )
        // Firestore hands numbers back as Long.
        val stored = b.toMap().mapValues { (_, v) -> if (v is Int) v.toLong() else v }
        assertEquals(b, parseBirthday("b1", "anna", stored))
        assertEquals(listOf("ben", "cara"), b.toMap()["sharedWith"])
        assertEquals(1986, parseBirthday("b1", "anna", stored + ("year" to 1986L))?.year)
    }

    @Test
    fun `birthday without day is skipped`() {
        assertNull(parseBirthday("b1", "anna", mapOf("name" to "Oma", "month" to 2L)))
    }
}
