package com.familycalendar.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class WeekLayoutTest {
    @Test
    fun `a multi-day bar takes one lane across its days and others fit around it`() {
        // Mon–Wed trip, a Tuesday appointment, a Thursday appointment.
        assertEquals(listOf(0, 1, 0), WeekLayout.lanes(listOf(0..2, 1..1, 3..3)))
    }

    @Test
    fun `longer segments starting the same day go first`() {
        assertEquals(listOf(1, 0), WeekLayout.lanes(listOf(2..2, 2..5)))
    }

    @Test
    fun `free gaps in upper lanes are reused`() {
        // Mon–Fri takes lane 0, Mon–Tue lane 1, and Wed fits into lane 1 right after it.
        assertEquals(listOf(1, 0, 1), WeekLayout.lanes(listOf(0..1, 0..4, 2..2)))
    }
}
