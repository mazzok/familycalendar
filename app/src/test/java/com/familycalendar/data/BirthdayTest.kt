package com.familycalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BirthdayTest {
    private val lisa = Birthday(id = "b1", name = "Lisa", month = 3, day = 14, year = 1986)
    private val leap = Birthday(id = "b2", name = "Leo", month = 2, day = 29, year = 2004)
    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `date and age in a year`() {
        assertEquals(LocalDate.of(2026, 3, 14), lisa.dateIn(2026))
        assertEquals(40, lisa.ageIn(2026))
        assertEquals("🎂 Lisa (40)", lisa.label(2026))
    }

    @Test
    fun `no age without year`() {
        val oma = lisa.copy(name = "Oma", year = null)
        assertNull(oma.ageIn(2026))
        assertEquals("🎂 Oma", oma.label(2026))
        assertEquals(LocalDate.of(1900, 3, 14), oma.dateIn(1900))
    }

    @Test
    fun `no birthday before birth`() {
        assertNull(lisa.dateIn(1985))
        assertEquals(0, lisa.ageIn(1986))
    }

    @Test
    fun `feb 29 falls on feb 28 in non-leap years`() {
        assertEquals(LocalDate.of(2026, 2, 28), leap.dateIn(2026))
        assertEquals(LocalDate.of(2028, 2, 29), leap.dateIn(2028))
    }

    @Test
    fun `invalid dates are rejected`() {
        assertFalse(validBirthDate(31, 2, null, today))
        assertFalse(validBirthDate(29, 2, 2023, today))
        assertTrue(validBirthDate(29, 2, null, today))
        assertTrue(validBirthDate(29, 2, 2024, today))
        assertFalse(validBirthDate(1, 13, null, today))
        assertFalse(validBirthDate(0, 1, null, today))
        assertFalse(validBirthDate(1, 1, 1899, today))
        assertFalse(validBirthDate(23, 10, 2026, today))
        assertTrue(validBirthDate(22, 10, 2026, today))
    }

    @Test
    fun `self is the account birthday`() {
        assertTrue(Birthday(id = SELF_BIRTHDAY_ID).isAccount)
        assertFalse(lisa.isAccount)
    }
}
