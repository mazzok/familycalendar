package com.familycalendar.ui.calendar

import com.familycalendar.data.Birthday
import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.EventKind
import com.familycalendar.data.NO_REMINDER
import com.familycalendar.ui.theme.BirthdayColor
import com.familycalendar.ui.theme.paletteColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class BirthdayOccurrencesTest {
    private val zone = ZoneOffset.UTC
    private val lisa = Birthday(id = "b1", ownerId = "anna", name = "Lisa", month = 1, day = 2, year = 1986)

    @Test
    fun `range across new year yields each year's date`() {
        val max = lisa.copy(id = "b2", name = "Max", month = 12, day = 30, year = null)
        val occ = birthdayOccurrences(listOf(lisa, max), LocalDate.of(2026, 12, 28)..LocalDate.of(2027, 1, 5), zone)
        assertEquals(listOf("🎂 Max", "🎂 Lisa (41)"), occ.sortedBy { it.start }.map { it.event.title })
    }

    @Test
    fun `synthetic birthday events have no reminder`() {
        val e = birthdayOccurrences(listOf(lisa), LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 1, 31), zone).single().event
        assertEquals(NO_REMINDER, e.reminderMinutes)
        assertTrue(e.allDay)
        assertTrue(e.isBirthday)
        assertEquals("anna/b1", e.key)
    }

    @Test
    fun `outside the range nothing`() {
        assertTrue(birthdayOccurrences(listOf(lisa), LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 12, 31), zone).isEmpty())
    }

    @Test
    fun `birthday color wins over calendar color`() {
        val colors = mapOf("anna" to 2)
        val e = CalendarEvent(ownerId = "anna", kind = EventKind.BIRTHDAY)
        assertEquals(BirthdayColor, colors.colorFor(e))
        assertEquals(paletteColor(2), colors.colorFor(e.copy(kind = EventKind.EVENT)))
    }
}
