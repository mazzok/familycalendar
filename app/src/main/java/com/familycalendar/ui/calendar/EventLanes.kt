package com.familycalendar.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycalendar.data.Occurrence
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Height of one lane; EventChip is a 13sp line plus 4dp padding. */
val LaneHeight = 18.dp

private data class Bar(val occurrence: Occurrence, val columns: IntRange, val continuesBefore: Boolean, val continuesAfter: Boolean)

/** Bars of [occurrences] within [days], clipped to them, in lane priority order. */
private fun bars(occurrences: List<Occurrence>, days: List<LocalDate>, zone: ZoneId): List<Bar> {
    val first = days.first()
    val last = days.last()
    return occurrences.mapNotNull { occ ->
        val touched = occ.days(zone)
        if (touched.isEmpty() || touched.last() < first || touched.first() > last) return@mapNotNull null
        val from = maxOf(touched.first(), first)
        val to = minOf(touched.last(), last)
        Bar(
            occurrence = occ,
            columns = ChronoUnit.DAYS.between(first, from).toInt()..ChronoUnit.DAYS.between(first, to).toInt(),
            continuesBefore = touched.first() < first,
            continuesAfter = touched.last() > last,
        )
    }.distinctBy { it.occurrence.key }
        // Ties after start column and length (WeekLayout): all-day first, then by start time.
        .sortedWith(compareBy({ !it.occurrence.isAllDayLike }, { it.occurrence.start }))
}

/** How many lanes [occurrences] need within [days] (to size the all-day row). */
fun lanesNeeded(occurrences: List<Occurrence>, days: List<LocalDate>, zone: ZoneId): Int =
    (WeekLayout.lanes(bars(occurrences, days, zone).map { it.columns }).maxOrNull() ?: -1) + 1

/**
 * Events of a week (or of the days of a week/day view) as bars: an event lasting several days is one
 * continuous bar across its days, single-day events fill free lanes. With more than [maxLanes] lanes on a
 * day, the last lane shows "+N" for that day instead.
 */
@Composable
fun EventLanes(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    colors: Map<String, Int>,
    zone: ZoneId,
    maxLanes: Int,
    onOccurrenceClick: (Occurrence) -> Unit,
    onMoreClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bars = remember(occurrences, days) { bars(occurrences, days, zone) }
    val lanes = remember(bars) { WeekLayout.lanes(bars.map { it.columns }) }
    val lanesPerDay = days.indices.map { col -> bars.indices.filter { col in bars[it].columns }.maxOfOrNull { lanes[it] + 1 } ?: 0 }
    val overflow = lanesPerDay.any { it > maxLanes }
    val shownLanes = if (overflow) (maxLanes - 1).coerceAtLeast(0) else maxLanes

    BoxWithConstraints(modifier.fillMaxWidth().height(LaneHeight * minOf(maxLanes, lanesPerDay.maxOrNull() ?: 0))) {
        val columnWidth = maxWidth / days.size
        bars.forEachIndexed { i, bar ->
            if (lanes[i] >= shownLanes) return@forEachIndexed
            EventChip(
                occurrence = bar.occurrence,
                colors = colors,
                zone = zone,
                onClick = { onOccurrenceClick(bar.occurrence) },
                continuesBefore = bar.continuesBefore,
                continuesAfter = bar.continuesAfter,
                roomForTime = columnWidth * bar.columns.count() >= 120.dp,
                modifier = Modifier
                    .offset(x = columnWidth * bar.columns.first, y = LaneHeight * lanes[i])
                    .width(columnWidth * bar.columns.count())
                    .height(LaneHeight),
            )
        }
        if (overflow) {
            days.forEachIndexed { col, day ->
                val hidden = bars.indices.count { col in bars[it].columns && lanes[it] >= shownLanes }
                if (hidden > 0) {
                    MoreLabel(hidden, Modifier.offset(x = columnWidth * col, y = LaneHeight * shownLanes).width(columnWidth)) {
                        onMoreClick(day)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreLabel(count: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(LaneHeight).clickable(onClick = onClick)) {
        Text(
            "+$count",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

fun Dp.lanesThatFit(): Int = (this / LaneHeight).toInt().coerceAtLeast(0)
