package com.familycalendar.data

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/** Document id of a user's own (account) birthday; every accepted viewer may read it. */
const val SELF_BIRTHDAY_ID = "self"

/**
 * A birthday stored in `calendars/{ownerId}/birthdays/{id}`. Either the owner's account birthday ([isAccount])
 * or one the owner entered, visible to the viewers listed in [sharedWith].
 */
data class Birthday(
    val id: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val name: String = "",
    val month: Int = 1,
    val day: Int = 1,
    /** Birth year, if known; only then is an age shown. */
    val year: Int? = null,
    /** Viewer uids who may see this birthday; unused for the account birthday. */
    val sharedWith: Set<String> = emptySet(),
) {
    val isAccount: Boolean get() = id == SELF_BIRTHDAY_ID
}

/** The day this birthday falls on in [year], or `null` before the birth year. Feb 29 moves to Feb 28 in non-leap years. */
fun Birthday.dateIn(year: Int): LocalDate? {
    if (this.year != null && year < this.year) return null
    val month = YearMonth.of(year, month)
    return month.atDay(day.coerceAtMost(month.lengthOfMonth()))
}

/** The age turned in [year]; `null` without a birth year. */
fun Birthday.ageIn(year: Int): Int? = this.year?.let { year - it }

/** Calendar title, e.g. "🎂 Lisa (40)". */
fun Birthday.label(year: Int): String = "🎂 $name" + (ageIn(year)?.let { " ($it)" } ?: "")

/** A real date (Feb 29 needs a leap year only when the year is given), not in the future, year from 1900 on. */
fun validBirthDate(day: Int, month: Int, year: Int?, today: LocalDate): Boolean {
    if (month !in 1..12 || day < 1) return false
    if (year == null) return day <= Month.of(month).maxLength()
    if (year !in 1900..today.year) return false
    if (day > YearMonth.of(year, month).lengthOfMonth()) return false
    return !LocalDate.of(year, month, day).isAfter(today)
}
