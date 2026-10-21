package com.familycalendar.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class DayLayoutTest {
    @Test
    fun `non-overlapping events take the full width`() {
        assertEquals(
            listOf(Slot(0, 1), Slot(0, 1)),
            DayLayout.layout(listOf(540 until 600, 600 until 660)),
        )
    }

    @Test
    fun `overlapping events split the width and a later gap starts a new cluster`() {
        val slots = DayLayout.layout(
            listOf(
                540 until 660, // 9-11
                570 until 630, // 9:30-10:30
                630 until 690, // 10:30-11:30, reuses column 1
                720 until 780, // 12-13, alone
            )
        )
        assertEquals(listOf(Slot(0, 2), Slot(1, 2), Slot(1, 2), Slot(0, 1)), slots)
    }

    @Test
    fun `three mutually overlapping events get three columns`() {
        val slots = DayLayout.layout(listOf(600 until 700, 610 until 650, 620 until 640))
        assertEquals(listOf(Slot(0, 3), Slot(1, 3), Slot(2, 3)), slots)
    }
}
