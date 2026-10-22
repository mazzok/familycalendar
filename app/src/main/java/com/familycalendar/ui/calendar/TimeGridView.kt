package com.familycalendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familycalendar.data.Occurrence
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val HourHeight = 56.dp
private val GutterWidth = 44.dp
private const val MAX_ALL_DAY_ROWS = 3

/** Day, 3-day and week views: an all-day lane on top and a scrollable 24-hour grid below. */
@Composable
fun TimeGridView(
    days: List<LocalDate>,
    occurrences: List<Occurrence>,
    colors: Map<String, Int>,
    zone: ZoneId,
    onDayClick: (LocalDate) -> Unit,
    onSlotClick: (LocalDateTime) -> Unit,
    onOccurrenceClick: (Occurrence) -> Unit,
) {
    val range = days.first()..days.last()
    val byDay = remember(occurrences, range) { occurrencesByDay(occurrences, range, zone) }
    val today = LocalDate.now()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(60_000)
            value = LocalDateTime.now()
        }
    }
    val scroll = rememberScrollState()
    val hourPx = with(LocalDensity.current) { HourHeight.toPx() }
    LaunchedEffect(Unit) {
        // Start around the current hour (or 7:00), like Google Calendar.
        val hour = if (today in range) (now.hour - 1).coerceAtLeast(0) else 7
        scroll.scrollTo((hour * hourPx).toInt())
    }

    Column(Modifier.fillMaxSize()) {
        // Day headers
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(GutterWidth))
            days.forEach { day ->
                DayHeader(day, isToday = day == today, modifier = Modifier.weight(1f).clickable { onDayClick(day) })
            }
        }
        // All-day lane: events lasting a day or more, multi-day ones as one continuous bar.
        val allDay = remember(byDay) { days.flatMap { byDay[it].orEmpty() }.filter { it.isAllDayLike }.distinctBy { it.key } }
        if (allDay.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Box(Modifier.width(GutterWidth))
                EventLanes(
                    days = days,
                    occurrences = allDay,
                    colors = colors,
                    zone = zone,
                    maxLanes = MAX_ALL_DAY_ROWS,
                    onOccurrenceClick = onOccurrenceClick,
                    onMoreClick = onDayClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider()

        val lineColor = MaterialTheme.colorScheme.outlineVariant
        Row(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        ) {
            // Hour labels
            Column(Modifier.width(GutterWidth)) {
                repeat(24) { hour ->
                    Box(Modifier.height(HourHeight).fillMaxWidth()) {
                        if (hour > 0) {
                            Text(
                                LocalTime.of(hour, 0).format(TimeFormatter),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-7).dp),
                            )
                        }
                    }
                }
            }
            days.forEach { day ->
                DayColumn(
                    day = day,
                    occurrences = byDay[day].orEmpty().filterNot { it.isAllDayLike },
                    colors = colors,
                    zone = zone,
                    now = now.takeIf { day == today },
                    lineColor = lineColor,
                    onSlotClick = onSlotClick,
                    onOccurrenceClick = onOccurrenceClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, isToday: Boolean, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DayColumn(
    day: LocalDate,
    occurrences: List<Occurrence>,
    colors: Map<String, Int>,
    zone: ZoneId,
    now: LocalDateTime?,
    lineColor: Color,
    onSlotClick: (LocalDateTime) -> Unit,
    onOccurrenceClick: (Occurrence) -> Unit,
    modifier: Modifier,
) {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()

    // Clip each occurrence to this day and convert to minutes since midnight.
    val segments = occurrences.mapNotNull { occ ->
        val from = maxOf(occ.start, dayStart)
        val to = minOf(occ.end, dayEnd)
        val startMin = Duration.between(dayStart, from).toMinutes().toInt()
        val endMin = Duration.between(dayStart, to).toMinutes().toInt()
        if (endMin < startMin) null else Triple(occ, startMin, maxOf(endMin, startMin + 20)) // min. visual height
    }
    val slots = remember(segments) { DayLayout.layout(segments.map { (_, s, e) -> s until e }) }

    BoxWithConstraints(
        modifier
            .height(HourHeight * 24)
            .drawBehind {
                val hour = HourHeight.toPx()
                for (h in 1 until 24) {
                    drawLine(lineColor, Offset(0f, h * hour), Offset(size.width, h * hour), strokeWidth = 1f)
                }
                drawLine(lineColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
            }
            .pointerInput(day) {
                detectTapGestures { offset ->
                    // Snap to the half hour that was tapped.
                    val halfHours = (offset.y / (HourHeight.toPx() / 2)).toInt().coerceIn(0, 47)
                    onSlotClick(day.atTime(halfHours / 2, (halfHours % 2) * 30))
                }
            },
    ) {
        val columnWidth = maxWidth
        segments.forEachIndexed { i, (occ, startMin, endMin) ->
            val slot = slots[i]
            val look = eventLook(occ, colors.colorFor(occ.event), zone)
            val top = HourHeight * (startMin / 60f)
            val height = HourHeight * ((endMin - startMin) / 60f)
            val width = columnWidth / slot.columns
            Box(
                Modifier
                    .offset(x = width * slot.column, y = top)
                    .width(width)
                    .height(height)
                    .padding(start = 1.dp, end = 2.dp, bottom = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .then(look.background)
                    .clickable { onOccurrenceClick(occ) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Column {
                    Text(
                        occ.event.title,
                        color = look.text,
                        textDecoration = look.decoration,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (height > 40.dp) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (height > 36.dp) {
                        Text(
                            LocalDateTime.ofInstant(occ.start, zone).format(TimeFormatter) + " – " +
                                LocalDateTime.ofInstant(occ.end, zone).format(TimeFormatter),
                            color = look.text,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (now != null) {
            val nowLineColor = MaterialTheme.colorScheme.error
            val y = HourHeight * ((now.hour * 60 + now.minute) / 60f)
            Box(
                Modifier
                    .offset(y = y - 1.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(nowLineColor),
            )
            Box(
                Modifier
                    .offset(x = (-5).dp, y = y - 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(nowLineColor),
            )
        }
    }
}
