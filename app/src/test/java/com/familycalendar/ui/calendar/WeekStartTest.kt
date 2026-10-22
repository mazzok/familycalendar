package com.familycalendar.ui.calendar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class WeekStartTest {
    private val original = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun `weeks start on monday even where the locale starts them on sunday`() {
        Locale.setDefault(Locale.US)
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeek())
        assertEquals(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25).startOfWeek())
        assertEquals(LocalDate.of(2026, 9, 28), monthGridRange(YearMonth.of(2026, 10)).start)
    }
}
