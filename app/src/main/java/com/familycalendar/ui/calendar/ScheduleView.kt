package com.familycalendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.familycalendar.data.Occurrence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Google-style "Schedule": a scrolling list of upcoming days that have events. */
@Composable
fun ScheduleView(
    from: LocalDate,
    days: Long,
    occurrences: List<Occurrence>,
    colors: Map<String, Int>,
    myUid: String?,
    zone: ZoneId,
    onOccurrenceClick: (Occurrence) -> Unit,
) {
    val range = from..from.plusDays(days - 1)
    val byDay = remember(occurrences, range) { occurrencesByDay(occurrences, range, zone).toSortedMap() }
    if (byDay.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
            Text(
                "Nothing planned. Tap + to add an event, or share calendars from the menu.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val today = LocalDate.now()
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 88.dp)) {
        byDay.forEach { (day, list) ->
            item(key = day.toString()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    DateBadge(day, isToday = day == today)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        list.forEach { occ -> ScheduleRow(occ, day, colors, myUid, zone) { onOccurrenceClick(occ) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DateBadge(day: LocalDate, isToday: Boolean) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .then(if (isToday) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (day.dayOfMonth == 1 || isToday) {
            Text(
                day.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleRow(
    occ: Occurrence,
    day: LocalDate,
    colors: Map<String, Int>,
    myUid: String?,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val look = eventLook(occ, colors.colorFor(occ.event.ownerId), zone)
    val start = LocalDateTime.ofInstant(occ.start, zone)
    val end = LocalDateTime.ofInstant(occ.end, zone)
    val time = when {
        occ.isAllDayLike -> "All day"
        start.toLocalDate() != day -> "Until ${end.format(TimeFormatter)}"
        end.toLocalDate() != day -> "From ${start.format(TimeFormatter)}"
        else -> "${start.format(TimeFormatter)} – ${end.format(TimeFormatter)}"
    }
    val details = listOfNotNull(
        time,
        occ.event.location.takeIf { it.isNotBlank() },
        occ.event.ownerName.takeIf { occ.event.ownerId != myUid },
        requestSummary(occ.event, myUid, occ.requestStatus(zone)),
    ).joinToString(" · ")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(look.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            displayTitle(occ.event),
            color = look.text,
            textDecoration = look.decoration,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(details, color = look.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
