package com.familycalendar.ui.birthday

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familycalendar.data.Birthday
import com.familycalendar.data.dateIn
import com.familycalendar.data.validBirthDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class BirthDate(val day: Int, val month: Int, val year: Int?)

/** Day, month and optional year as typed; `null` unless they form a valid birth date. */
fun parseBirthDate(day: String, month: String, year: String, today: LocalDate): BirthDate? {
    val d = day.trim().toIntOrNull() ?: return null
    val m = month.trim().toIntOrNull() ?: return null
    val y = year.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: return null }
    return BirthDate(d, m, y).takeIf { validBirthDate(d, m, y, today) }
}

/** The next time this birthday comes round, today included. */
fun Birthday.nextDate(today: LocalDate): LocalDate {
    // Without a birth year dateIn never returns null; with one, today's year is at or after it.
    val thisYear = copy(year = null).dateIn(today.year)!!
    return if (thisYear < today) copy(year = null).dateIn(today.year + 1)!! else thisYear
}

private val DateFormat = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
private val DateWithYearFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

/** "Mar 14, 1986", or "Mar 14" without a year. */
fun Birthday.dateText(): String {
    val date = copy(year = null).dateIn(year ?: 2000)!! // 2000 is a leap year, so Feb 29 stays Feb 29
    return if (year != null) date.format(DateWithYearFormat) else date.format(DateFormat)
}

/** Day / month / year (optional) number fields side by side. */
@Composable
fun BirthDateFields(
    day: String,
    month: String,
    year: String,
    onChange: (day: String, month: String, year: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = day, onValueChange = { onChange(it.take(2), month, year) },
            label = { Text("Day") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = month, onValueChange = { onChange(day, it.take(2), year) },
            label = { Text("Month") }, singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = year, onValueChange = { onChange(day, month, it.take(4)) },
            label = { Text("Year") }, placeholder = { Text("optional") }, singleLine = true,
            keyboardOptions = numbers, modifier = Modifier.weight(1.3f),
        )
    }
}
