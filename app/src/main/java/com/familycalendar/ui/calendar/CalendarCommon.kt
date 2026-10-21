package com.familycalendar.ui.calendar

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextDecoration
import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.Occurrence
import com.familycalendar.data.RequestStatus
import com.familycalendar.ui.theme.CalendarColor
import com.familycalendar.ui.theme.CalendarPalette
import com.familycalendar.ui.theme.paletteColor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** All-day events and anything lasting a day or more go into the all-day lane, like Google Calendar. */
val Occurrence.isAllDayLike: Boolean
    get() = event.allDay || Duration.between(start, end) >= Duration.ofHours(24)

/** Local days this occurrence touches; the end is exclusive, so an event ending at midnight doesn't spill over. */
fun Occurrence.days(zone: ZoneId): List<LocalDate> {
    val first = LocalDateTime.ofInstant(start, zone).toLocalDate()
    val endLocal = LocalDateTime.ofInstant(end, zone)
    val last = if (end > start && endLocal.toLocalTime() == java.time.LocalTime.MIDNIGHT) {
        endLocal.toLocalDate().minusDays(1)
    } else {
        endLocal.toLocalDate()
    }
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}

/** Groups occurrences by every day they touch, limited to [range]; all-day first, then by start. */
fun occurrencesByDay(occurrences: List<Occurrence>, range: ClosedRange<LocalDate>, zone: ZoneId): Map<LocalDate, List<Occurrence>> {
    val result = mutableMapOf<LocalDate, MutableList<Occurrence>>()
    occurrences.forEach { occ ->
        occ.days(zone).filter { it in range }.forEach { day -> result.getOrPut(day) { mutableListOf() } += occ }
    }
    return result.mapValues { (_, list) -> list.sortedWith(compareBy({ !it.isAllDayLike }, { it.start })) }
}

fun Map<String, Int>.colorFor(ownerId: String) = paletteColor(this[ownerId])

/**
 * Color of a checkmark: the color of the ticking person's calendar on this phone, or, if their calendar
 * isn't visible here, a stable color derived from their uid.
 */
fun Map<String, Int>.checkColor(uid: String): CalendarColor =
    paletteColor(this[uid] ?: uid.hashCode().mod(CalendarPalette.size))

/** Title as shown in the calendar: a standalone checklist shows its progress, an attached one a mark. */
fun displayTitle(event: CalendarEvent): String = when {
    event.isChecklist -> "☑ ${event.title} ${event.checklistDone}/${event.checklist.size}"
    event.hasChecklist -> "${event.title} ☑"
    else -> event.title
}

private val DarkText = Color(0xFF202124)

/** How an event is painted, depending on its date request state. */
data class EventLook(val background: Modifier, val text: Color, val decoration: TextDecoration?)

/**
 * Normal events are filled with their calendar color. A date request still waiting for an answer gets a
 * light checkerboard in that color; a declined one is faded and struck through.
 */
fun eventLook(status: RequestStatus?, color: CalendarColor): EventLook = when (status) {
    RequestStatus.PENDING -> EventLook(
        background = Modifier
            .checkerboard(lerp(color.color, Color.White, 0.35f), lerp(color.color, Color.White, 0.7f))
            .border(1.dp, color.color, RoundedCornerShape(4.dp)),
        text = DarkText,
        decoration = null,
    )
    RequestStatus.DECLINED -> EventLook(
        background = Modifier.background(lerp(color.color, Color.White, 0.8f)),
        text = DarkText.copy(alpha = 0.6f),
        decoration = TextDecoration.LineThrough,
    )
    else -> EventLook(Modifier.background(color.color), color.onColor, null)
}

/** Look of one occurrence: a single declined date of a repeating request is struck through on its own. */
fun eventLook(occurrence: Occurrence, color: CalendarColor, zone: ZoneId): EventLook =
    eventLook(occurrence.requestStatus(zone), color)

/**
 * Short state of a date request from the viewer's perspective, or `null` for normal events.
 * [status] is the answer for the shown date (see [CalendarEvent.requestStatusOn]).
 */
fun requestSummary(event: CalendarEvent, myUid: String?, status: RequestStatus? = event.requestStatus): String? {
    if (!event.isRequest) return null
    val toMe = event.requestedUid == myUid
    val name = event.requestedName.ifBlank { "them" }
    // A repeating request declined only on this date says so, to tell it from "all dates declined".
    val onlyThisDate = status == RequestStatus.DECLINED && event.requestStatus != RequestStatus.DECLINED
    return when (status) {
        RequestStatus.ACCEPTED -> if (toMe) "You accepted" else "Confirmed by $name"
        RequestStatus.DECLINED -> when {
            onlyThisDate && toMe -> "You declined this date"
            onlyThisDate -> "Declined by $name for this date"
            toMe -> "You declined"
            else -> "Declined by $name"
        }
        else -> if (toMe) "Needs your answer" else "Waiting for $name"
    }
}

fun Modifier.checkerboard(first: Color, second: Color, cell: Dp = 6.dp): Modifier = drawBehind {
    val c = cell.toPx()
    drawRect(second)
    val rows = (size.height / c).toInt() + 1
    val cols = (size.width / c).toInt() + 1
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            if ((row + col) % 2 == 0) drawRect(first, topLeft = Offset(col * c, row * c), size = Size(c, c))
        }
    }
}

/**
 * Compact colored bar with the event title, used in month cells and the all-day lane.
 * [continuesBefore]/[continuesAfter]: the event runs on beyond this bar (previous/next week), so that
 * side gets square corners and no gap, as in Google Calendar.
 */
@Composable
fun EventChip(
    occurrence: Occurrence,
    colors: Map<String, Int>,
    zone: ZoneId,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    continuesBefore: Boolean = false,
    continuesAfter: Boolean = false,
    /** Narrow cells show only the title; the time would push it out of view. */
    roomForTime: Boolean = true,
) {
    val look = eventLook(occurrence, colors.colorFor(occurrence.event.ownerId), zone)
    val showTime = roomForTime && !occurrence.event.allDay && !continuesBefore
    val label = if (showTime) "${LocalDateTime.ofInstant(occurrence.start, zone).format(TimeFormatter)} ${displayTitle(occurrence.event)}"
    else displayTitle(occurrence.event)
    val start = if (continuesBefore) 0.dp else 4.dp
    val end = if (continuesAfter) 0.dp else 4.dp
    Text(
        label,
        color = look.text,
        textDecoration = look.decoration,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (continuesBefore) 0.dp else 1.dp, end = if (continuesAfter) 0.dp else 1.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
            .then(look.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    )
}

@Composable
fun ColorDot(color: Color, size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun ColorPickerDialog(title: String, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CalendarPalette.withIndex().chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (index, entry) ->
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(entry.color)
                                    .then(
                                        if (index == selected.mod(CalendarPalette.size)) {
                                            Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        } else Modifier
                                    )
                                    .clickable { onPick(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (index == selected.mod(CalendarPalette.size)) {
                                    Icon(Icons.Default.Check, entry.name, tint = entry.onColor)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
