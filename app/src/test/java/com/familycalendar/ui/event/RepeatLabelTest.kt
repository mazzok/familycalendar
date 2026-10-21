package com.familycalendar.ui.event

import com.familycalendar.data.Frequency
import com.familycalendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RepeatLabelTest {
    private val tuesday = LocalDate.parse("2026-10-20") // third Tuesday of October

    @Test
    fun `presets read like Google Calendar`() {
        assertEquals(
            listOf(
                "Does not repeat",
                "Daily",
                "Weekly on Tuesday",
                "Every weekday (Monday to Friday)",
                "Monthly on day 20",
                "Monthly on the third Tuesday",
                "Annually on October 20",
            ),
            repeatPresets(tuesday).map { repeatLabel(it, tuesday) },
        )
    }

    @Test
    fun `custom rules include interval, days and end date`() {
        val rule = Recurrence(
            Frequency.WEEKLY,
            interval = 2,
            weekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY),
            until = LocalDate.parse("2026-12-01"),
        )
        assertEquals("Every 2 weeks on Monday, Wednesday, until Dec 1, 2026", repeatLabel(rule, tuesday))
    }

    @Test
    fun `weekly rule follows the start date, custom weekdays stay`() {
        val thursday = tuesday.plusDays(2)
        assertEquals(setOf(DayOfWeek.THURSDAY), Recurrence(Frequency.WEEKLY, weekdays = setOf(DayOfWeek.TUESDAY)).followStart(tuesday, thursday).weekdays)
        val custom = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
        assertEquals(custom, Recurrence(Frequency.WEEKLY, weekdays = custom).followStart(tuesday, thursday).weekdays)
    }
}
