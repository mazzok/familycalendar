package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChecklistTest {
    private val t = Instant.parse("2026-10-23T10:00:00Z")
    private fun item(id: String, by: String? = null, at: Instant? = null, order: Long = 0) =
        ChecklistItem(id = id, text = id, order = order, checkedBy = by, checkedByName = by.orEmpty(), checkedAt = at)
    private val list = CalendarEvent(id = "c", ownerId = "anna", title = "Einkauf", kind = EventKind.CHECKLIST)

    @Test
    fun `an empty list is not complete`() {
        assertFalse(list.checklistComplete)
        assertEquals(0, list.checklistDone)
        assertFalse(list.hasChecklist)
    }

    @Test
    fun `complete when every item is checked`() {
        val e = list.copy(checklist = listOf(item("a", "anna", t), item("b", "ben", t.plusSeconds(5))))
        assertTrue(e.checklistComplete)
        assertEquals(2, e.checklistDone)
        assertEquals("ben", e.lastTicked()?.checkedBy)
    }

    @Test
    fun `pending checkedAt still counts as checked`() {
        val e = list.copy(checklist = listOf(item("a", "anna", t), item("b", "ben", null)))
        assertTrue(e.checklistComplete)
        assertEquals("ben", e.lastTicked()?.checkedBy)
    }

    @Test
    fun `next item goes to the bottom`() {
        assertEquals(0L, list.nextItemOrder())
        assertEquals(8L, list.copy(checklist = listOf(item("a", order = 7), item("b", order = 2))).nextItemOrder())
    }

    @Test
    fun `text still in the add field is appended at the bottom`() {
        val items = listOf(item("a", order = 3))
        assertEquals(items, items.plusItem("  ", "x"))
        assertEquals(ChecklistItem("x", "Milch", order = 4), items.plusItem(" Milch ", "x").last())
    }

    @Test
    fun `blank item text is rejected`() {
        assertNull(itemText("   "))
        assertEquals("Milch", itemText("  Milch "))
    }
}
