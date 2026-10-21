package com.familycalendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycalendar.data.Occurrence
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private val DayNumberHeight = 24.dp

/** First and last day shown in the month grid (whole weeks). */
fun monthGridRange(month: YearMonth): ClosedRange<LocalDate> {
    val first = month.atDay(1).startOfWeek()
    val weeks = (ChronoUnit.DAYS.between(first, month.atEndOfMonth()) / 7 + 1).toInt()
    return first..first.plusDays(weeks * 7L - 1)
}

@Composable
fun MonthView(
    month: YearMonth,
    occurrences: List<Occurrence>,
    colors: Map<String, Int>,
    zone: ZoneId,
    onDayClick: (LocalDate) -> Unit,
    onOccurrenceClick: (Occurrence) -> Unit,
) {
    val range = monthGridRange(month)
    val byDay = remember(occurrences, range) { occurrencesByDay(occurrences, range, zone) }
    val weeks = generateSequence(range.start) { it.plusWeeks(1) }.takeWhile { it <= range.endInclusive }.toList()
    val today = LocalDate.now()
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            weeks.first().let { start ->
                repeat(7) {
                    Text(
                        start.plusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        weeks.forEach { weekStart ->
            val days = List(7) { weekStart.plusDays(it.toLong()) }
            val weekEvents = days.flatMap { byDay[it].orEmpty() }.distinctBy { it.key }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Grid cells with day numbers; tapping a cell opens that day.
                Row(Modifier.fillMaxSize()) {
                    days.forEach { day ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .border(0.25.dp, lineColor)
                                .clickable { onDayClick(day) },
                        ) {
                            DayNumber(day, isToday = day == today, inMonth = YearMonth.from(day) == month)
                        }
                    }
                }
                // Events on top, so multi-day events run as one bar across the cells.
                EventLanes(
                    days = days,
                    occurrences = weekEvents,
                    colors = colors,
                    zone = zone,
                    maxLanes = (maxHeight - DayNumberHeight).lanesThatFit(),
                    onOccurrenceClick = onOccurrenceClick,
                    onMoreClick = onDayClick,
                    modifier = Modifier.offset(y = DayNumberHeight),
                )
            }
        }
    }
}

@Composable
private fun DayNumber(day: LocalDate, isToday: Boolean, inMonth: Boolean) {
    Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                fontSize = 11.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimary
                    inMonth -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
    }
}
