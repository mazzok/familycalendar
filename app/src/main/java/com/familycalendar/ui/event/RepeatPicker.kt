package com.familycalendar.ui.event

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.familycalendar.data.Frequency
import com.familycalendar.data.Recurrence
import com.familycalendar.data.weekdayOrdinal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// The app's texts are English, so names inside sentences are English too (no "Every week on Dienstag").
private val English = Locale.ENGLISH
private val MonthDay = DateTimeFormatter.ofPattern("MMMM d", English)
private val UntilDate = DateTimeFormatter.ofPattern("MMM d, yyyy", English)
private val WorkWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

private fun DayOfWeek.fullName() = getDisplayName(TextStyle.FULL, English)

private fun ordinalName(ordinal: Int) = when (ordinal) {
    1 -> "first"
    2 -> "second"
    3 -> "third"
    4 -> "fourth"
    else -> "last"
}

/** The quick choices Google Calendar offers, phrased for the event's start date. */
fun repeatPresets(start: LocalDate): List<Recurrence> = listOf(
    Recurrence(),
    Recurrence(Frequency.DAILY),
    Recurrence(Frequency.WEEKLY, weekdays = setOf(start.dayOfWeek)),
    Recurrence(Frequency.WEEKLY, weekdays = WorkWeek),
    Recurrence(Frequency.MONTHLY),
    Recurrence(Frequency.MONTHLY, monthlyOrdinal = weekdayOrdinal(start)),
    Recurrence(Frequency.YEARLY),
)

/** Human-readable rule, e.g. "Every 2 weeks on Monday, Wednesday, until Dec 1, 2026". */
fun repeatLabel(rule: Recurrence, start: LocalDate): String {
    if (!rule.repeats) return "Does not repeat"
    val n = rule.interval.coerceAtLeast(1)
    val base = when (rule.frequency) {
        Frequency.DAILY -> if (n == 1) "Daily" else "Every $n days"
        Frequency.WEEKLY -> {
            val days = rule.weekdays.ifEmpty { setOf(start.dayOfWeek) }
            if (n == 1 && days == WorkWeek) {
                "Every weekday (Monday to Friday)"
            } else {
                val names = days.sorted().joinToString(", ") { it.fullName() }
                if (n == 1) "Weekly on $names" else "Every $n weeks on $names"
            }
        }
        Frequency.MONTHLY -> {
            val on = if (rule.monthlyOrdinal == 0) "on day ${start.dayOfMonth}"
            else "on the ${ordinalName(rule.monthlyOrdinal)} ${start.dayOfWeek.fullName()}"
            if (n == 1) "Monthly $on" else "Every $n months $on"
        }
        Frequency.YEARLY -> if (n == 1) "Annually on ${start.format(MonthDay)}" else "Every $n years on ${start.format(MonthDay)}"
        Frequency.NONE -> "Does not repeat"
    }
    return if (rule.until != null) "$base, until ${rule.until.format(UntilDate)}" else base
}

/** Keeps a rule meaningful when the event's start date moves (e.g. "weekly on Tuesday" follows to Thursday). */
fun Recurrence.followStart(oldStart: LocalDate, newStart: LocalDate): Recurrence = copy(
    weekdays = if (weekdays == setOf(oldStart.dayOfWeek)) setOf(newStart.dayOfWeek) else weekdays,
    monthlyOrdinal = if (monthlyOrdinal != 0) weekdayOrdinal(newStart) else 0,
    until = until?.takeIf { !it.isBefore(newStart) },
)

private fun Recurrence.sameRuleAs(other: Recurrence, start: LocalDate) =
    frequency == other.frequency && interval == other.interval && monthlyOrdinal == other.monthlyOrdinal &&
        (frequency != Frequency.WEEKLY || weekdays.ifEmpty { setOf(start.dayOfWeek) } == other.weekdays.ifEmpty { setOf(start.dayOfWeek) })

/** "Repeat" button with Google's presets plus "Custom…". Exceptions (deleted occurrences) are preserved. */
@Composable
fun RepeatPicker(rule: Recurrence, start: LocalDate, onChange: (Recurrence) -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val presets = repeatPresets(start)
    Box {
        OutlinedButton(onClick = { menu = true }) { Text(repeatLabel(rule, start)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(repeatLabel(preset, start)) },
                    leadingIcon = { RadioButton(selected = rule.sameRuleAs(preset, start) && rule.until == null, onClick = null) },
                    onClick = {
                        onChange(preset.copy(exceptions = rule.exceptions))
                        menu = false
                    },
                )
            }
            HorizontalDivider()
            val isCustom = rule.repeats && (rule.until != null || presets.none { rule.sameRuleAs(it, start) })
            DropdownMenuItem(
                text = { Text("Custom…") },
                leadingIcon = { RadioButton(selected = isCustom, onClick = null) },
                onClick = { menu = false; custom = true },
            )
        }
    }
    if (custom) {
        CustomRepeatDialog(
            initial = if (rule.repeats) rule else Recurrence(Frequency.WEEKLY, weekdays = setOf(start.dayOfWeek)),
            start = start,
            onDone = { onChange(it.copy(exceptions = rule.exceptions)); custom = false },
            onDismiss = { custom = false },
        )
    }
}

@Composable
private fun CustomRepeatDialog(initial: Recurrence, start: LocalDate, onDone: (Recurrence) -> Unit, onDismiss: () -> Unit) {
    var intervalText by rememberSaveable { mutableStateOf(initial.interval.coerceAtLeast(1).toString()) }
    var frequency by rememberSaveable { mutableStateOf(initial.frequency) }
    var weekdays by rememberSaveable { mutableStateOf(initial.weekdays.ifEmpty { setOf(start.dayOfWeek) }) }
    var byWeekday by rememberSaveable { mutableStateOf(initial.monthlyOrdinal != 0) }
    var until by rememberSaveable { mutableStateOf(initial.until) }
    var unitMenu by rememberSaveable { mutableStateOf(false) }
    val interval = intervalText.toIntOrNull()?.takeIf { it in 1..99 }
    val context = LocalContext.current

    fun unitName(f: Frequency, plural: Boolean) = when (f) {
        Frequency.DAILY -> if (plural) "days" else "day"
        Frequency.WEEKLY -> if (plural) "weeks" else "week"
        Frequency.MONTHLY -> if (plural) "months" else "month"
        else -> if (plural) "years" else "year"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom recurrence") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Repeats every")
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                        isError = interval == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(64.dp),
                    )
                    Box {
                        OutlinedButton(onClick = { unitMenu = true }) { Text(unitName(frequency, (interval ?: 1) > 1)) }
                        DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            listOf(Frequency.DAILY, Frequency.WEEKLY, Frequency.MONTHLY, Frequency.YEARLY).forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(unitName(f, (interval ?: 1) > 1)) },
                                    onClick = { frequency = f; unitMenu = false },
                                )
                            }
                        }
                    }
                }

                if (frequency == Frequency.WEEKLY) {
                    Text("Repeats on", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val selected = day in weekdays
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .then(
                                        if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                    .clickable {
                                        // At least one day must stay selected.
                                        weekdays = if (selected && weekdays.size > 1) weekdays - day else weekdays + day
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    day.getDisplayName(TextStyle.NARROW, English),
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                if (frequency == Frequency.MONTHLY) {
                    Column {
                        RadioRow("Monthly on day ${start.dayOfMonth}", selected = !byWeekday) { byWeekday = false }
                        RadioRow(
                            "Monthly on the ${ordinalName(weekdayOrdinal(start))} ${start.dayOfWeek.fullName()}",
                            selected = byWeekday,
                        ) { byWeekday = true }
                    }
                }

                Column {
                    Text("Ends", style = MaterialTheme.typography.labelLarge)
                    RadioRow("Never", selected = until == null) { until = null }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = until != null, onClick = { until = until ?: start.plusMonths(1) })
                        Text("On")
                        TextButton(
                            onClick = {
                                val shown = until ?: start.plusMonths(1)
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d -> until = LocalDate.of(y, m + 1, d) },
                                    shown.year, shown.monthValue - 1, shown.dayOfMonth,
                                ).apply {
                                    datePicker.minDate = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                }.show()
                            },
                        ) { Text((until ?: start.plusMonths(1)).format(UntilDate)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = interval != null,
                onClick = {
                    onDone(
                        Recurrence(
                            frequency = frequency,
                            interval = interval ?: 1,
                            weekdays = if (frequency == Frequency.WEEKLY) weekdays else emptySet(),
                            monthlyOrdinal = if (frequency == Frequency.MONTHLY && byWeekday) weekdayOrdinal(start) else 0,
                            until = until,
                        )
                    )
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
