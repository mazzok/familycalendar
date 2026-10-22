package com.familycalendar.notifications

import com.familycalendar.data.Birthday
import com.familycalendar.data.SELF_BIRTHDAY_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class BirthdayPlannerTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-20T12:00:00Z")

    private fun b(id: String, name: String, day: Int, owner: String = "me", year: Int? = null) =
        Birthday(id = id, ownerId = owner, name = name, month = 10, day = day, year = year)

    private fun plan(list: List<Birthday>, muted: Set<String> = emptySet(), enabled: Boolean = true) =
        BirthdayPlanner.plan(list, now, "me", muted, enabled, zone)

    @Test
    fun `notice at eight on the day`() {
        val n = plan(listOf(b("1", "Lisa", 22, year = 1986))).single()
        assertEquals(Instant.parse("2026-10-22T08:00:00Z"), n.at)
        assertEquals("🎂 Today is Lisa's birthday (40)", n.text)
        assertEquals("birthday/2026-10-22", n.key)
    }

    @Test
    fun `several birthdays share one notice`() {
        val n = plan(listOf(b("2", "Oma", 22), b("1", "Lisa", 22, year = 1986))).single()
        assertEquals("🎂 Birthdays today: Lisa (40), Oma", n.text)
    }

    @Test
    fun `own and muted birthdays are left out`() {
        val list = listOf(
            b(SELF_BIRTHDAY_ID, "Me", 22),
            b(SELF_BIRTHDAY_ID, "Ben", 22, owner = "ben"),
            b("x", "Max", 22, owner = "muted"),
        )
        assertEquals("🎂 Today is Ben's birthday", plan(list, muted = setOf("muted")).single().text)
    }

    @Test
    fun `today after eight and beyond the horizon are not planned`() {
        assertTrue(plan(listOf(b("1", "Lisa", 20))).isEmpty())
        assertTrue(plan(listOf(Birthday(id = "1", ownerId = "me", name = "X", month = 12, day = 24))).isEmpty())
    }

    @Test
    fun `switched off plans nothing`() {
        assertTrue(plan(listOf(b("1", "Lisa", 22)), enabled = false).isEmpty())
    }
}
