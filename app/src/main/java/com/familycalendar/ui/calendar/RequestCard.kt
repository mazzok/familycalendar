package com.familycalendar.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.familycalendar.data.CalendarEvent
import com.familycalendar.ui.theme.CalendarColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Anna asks you to confirm: Dinner · Fri 19:00" with Decline / Accept; tapping opens the event. */
@Composable
fun RequestCard(
    event: CalendarEvent,
    /** Start of the (next) occurrence to show. */
    start: Instant,
    color: CalendarColor,
    onOpen: () -> Unit,
    onAnswer: (accept: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val whenText = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(start.atZone(ZoneId.systemDefault())) +
        if (event.recurrence.repeats) " (repeating)" else ""
    Card(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Calendar icon in the asking person's calendar color.
            Icon(
                Icons.Default.DateRange,
                contentDescription = null,
                tint = color.color,
                modifier = Modifier.padding(end = 12.dp).size(32.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${event.ownerName.ifBlank { "Someone" }} asks you to confirm",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(event.title, style = MaterialTheme.typography.titleSmall)
                Text(whenText, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Decline") }
            Button(onClick = { onAnswer(true) }) { Text("Accept") }
        }
    }
}
