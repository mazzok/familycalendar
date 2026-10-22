package com.familycalendar.ui.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class CalendarViewMode(val label: String) {
    SCHEDULE("Schedule"),
    DAY("Day"),
    THREE_DAY("3 days"),
    WEEK("Week"),
    MONTH("Month");

    /** The date shown after swiping forward ([direction] = 1) or back (-1). */
    fun step(date: LocalDate, direction: Int): LocalDate = when (this) {
        SCHEDULE, WEEK -> date.plusWeeks(direction.toLong())
        DAY -> date.plusDays(direction.toLong())
        THREE_DAY -> date.plusDays(3L * direction)
        MONTH -> date.plusMonths(direction.toLong()).withDayOfMonth(1)
    }

    /** The days rendered as columns in a time grid. */
    fun visibleDays(date: LocalDate): List<LocalDate> = when (this) {
        DAY -> listOf(date)
        THREE_DAY -> List(3) { date.plusDays(it.toLong()) }
        WEEK -> date.startOfWeek().let { start -> List(7) { start.plusDays(it.toLong()) } }
        SCHEDULE, MONTH -> emptyList()
    }
}

/** Weeks always start on Monday, whatever the phone's language would suggest. */
fun firstDayOfWeek(): DayOfWeek = DayOfWeek.MONDAY

fun LocalDate.startOfWeek(): LocalDate = with(TemporalAdjusters.previousOrSame(firstDayOfWeek()))
