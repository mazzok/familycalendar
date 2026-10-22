package com.familycalendar.ui.birthday

import com.familycalendar.data.Birthday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BirthDateInputTest {
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `day and month with optional year`() {
        assertEquals(BirthDate(14, 3, 1986), parseBirthDate(" 14", "3 ", "1986", today))
        assertEquals(BirthDate(29, 2, null), parseBirthDate("29", "2", "", today))
    }

    @Test
    fun `incomplete or impossible input is rejected`() {
        assertNull(parseBirthDate("", "3", "", today))
        assertNull(parseBirthDate("x", "3", "", today))
        assertNull(parseBirthDate("31", "4", "", today))
        assertNull(parseBirthDate("14", "3", "86x", today))
    }

    @Test
    fun `next date is this year's or next year's`() {
        val lisa = Birthday(name = "Lisa", month = 3, day = 14)
        val max = Birthday(name = "Max", month = 10, day = 22)
        assertEquals(LocalDate.of(2027, 3, 14), lisa.nextDate(today))
        assertEquals(today, max.nextDate(today))
    }

    @Test
    fun `date text shows the year only when known`() {
        assertEquals("Mar 14, 1986", Birthday(month = 3, day = 14, year = 1986).dateText())
        assertEquals("Feb 29", Birthday(month = 2, day = 29).dateText())
    }
}
