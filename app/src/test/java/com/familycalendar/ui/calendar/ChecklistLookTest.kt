package com.familycalendar.ui.calendar

import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.ChecklistItem
import com.familycalendar.data.EventKind
import com.familycalendar.ui.theme.paletteColor
import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistLookTest {
    private val items = listOf(ChecklistItem("a", "Milch", checkedBy = "ben"), ChecklistItem("b", "Brot"), ChecklistItem("c", "Eier"))

    @Test
    fun `standalone list shows progress`() =
        assertEquals("☑ Einkauf 1/3", displayTitle(CalendarEvent(title = "Einkauf", kind = EventKind.CHECKLIST, checklist = items)))

    @Test
    fun `attached list gets a mark`() =
        assertEquals("Grillabend ☑", displayTitle(CalendarEvent(title = "Grillabend", checklist = items)))

    @Test
    fun `plain events are unchanged`() = assertEquals("Dinner", displayTitle(CalendarEvent(title = "Dinner")))

    @Test
    fun `chip keeps a standalone list's progress apart, so it isn't clipped with the title`() =
        assertEquals("☑ Einkauf" to "1/3", chipLabel(CalendarEvent(title = "Einkauf", kind = EventKind.CHECKLIST, checklist = items)))

    @Test
    fun `chip of other events is the display title alone`() {
        assertEquals("Grillabend ☑" to null, chipLabel(CalendarEvent(title = "Grillabend", checklist = items)))
        assertEquals("Dinner" to null, chipLabel(CalendarEvent(title = "Dinner")))
    }

    @Test
    fun `check color follows the calendar color, else is stable per uid`() {
        assertEquals(paletteColor(4), mapOf("ben" to 4).checkColor("ben"))
        assertEquals(emptyMap<String, Int>().checkColor("zoe"), emptyMap<String, Int>().checkColor("zoe"))
    }
}
