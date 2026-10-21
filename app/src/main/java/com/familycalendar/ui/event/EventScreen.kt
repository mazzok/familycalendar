package com.familycalendar.ui.event

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familycalendar.MainViewModel
import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.ChecklistItem
import com.familycalendar.data.EventKind
import com.familycalendar.data.Frequency
import com.familycalendar.data.NO_REMINDER
import com.familycalendar.data.Recurrence
import com.familycalendar.data.RequestStatus
import com.familycalendar.data.Viewer
import com.familycalendar.data.plusItem
import com.familycalendar.ui.calendar.ColorDot
import com.familycalendar.ui.calendar.requestSummary
import com.familycalendar.ui.calendar.colorFor
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val ReminderOptions = listOf(
    NO_REMINDER to "No reminder",
    0 to "At start time",
    5 to "5 minutes before",
    10 to "10 minutes before",
    15 to "15 minutes before",
    30 to "30 minutes before",
    60 to "1 hour before",
    120 to "2 hours before",
    1440 to "1 day before",
    10080 to "1 week before",
)

private fun reminderLabel(minutes: Int) =
    ReminderOptions.firstOrNull { it.first == minutes }?.second ?: "$minutes minutes before"

private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TimeFmt: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/**
 * Shows a new/own event as an editable form, and someone else's event read-only.
 * [occurrenceStart] identifies the tapped occurrence of a repeating event; [newStart] pre-fills a new event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventScreen(
    viewModel: MainViewModel,
    eventKey: String?,
    occurrenceStart: Instant?,
    newStart: LocalDateTime?,
    newAllDay: Boolean,
    newKind: EventKind,
    onBack: () -> Unit,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val colors by viewModel.calendarColors.collectAsStateWithLifecycle()
    val viewers by viewModel.myViewers.collectAsStateWithLifecycle()
    val myName by viewModel.myName.collectAsStateWithLifecycle()
    val existing = eventKey?.let { key -> events?.firstOrNull { it.key == key } }
    // An existing checklist is live (others' ticks show up while editing); a new event's is a draft until Save.
    val (draftItems, draftActions) = rememberDraftChecklist(user?.uid.orEmpty(), myName)
    val checklistItems = existing?.checklist ?: draftItems
    val checklistActions = existing?.let(viewModel::liveChecklistActions) ?: draftActions
    val editable = eventKey == null || existing?.ownerId == user?.uid
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val isChecklist = (existing?.kind ?: newKind) == EventKind.CHECKLIST
    val noun = if (isChecklist) "checklist" else "event"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            eventKey == null -> "New $noun"
                            editable -> "Edit $noun"
                            else -> "${existing?.ownerName.orEmpty()}'s $noun"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (existing != null && editable) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                eventKey != null && existing == null ->
                    Text("This $noun no longer exists.", Modifier.align(Alignment.Center))
                editable && isChecklist -> ChecklistEditor(
                    initial = existing ?: newChecklistTemplate(),
                    checklistItems = checklistItems,
                    checklistActions = checklistActions,
                    colors = colors,
                    onSave = { viewModel.saveEvent(it, onDone = onBack) },
                )
                editable -> EventEditor(
                    initial = existing ?: newEventTemplate(newStart, newAllDay),
                    // Only people who can see this calendar can be asked; they need to read the event.
                    requestCandidates = viewers.filter { it.accepted },
                    checklistItems = checklistItems,
                    checklistActions = checklistActions,
                    colors = colors,
                    onSave = { viewModel.saveEvent(it, onDone = onBack) },
                )
                else -> EventDetails(
                    event = existing!!,
                    start = occurrenceStart ?: existing.start,
                    colors = colors,
                    checklistActions = checklistActions,
                    myUid = user?.uid,
                    onAnswerAll = { accept -> viewModel.answerRequest(existing, accept) },
                    onAnswerDate = { date, decline -> viewModel.answerDate(existing, date, decline) },
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        val occurrenceDate = (occurrenceStart ?: existing.start).atZone(zone).toLocalDate()
        DeleteDialog(
            event = existing,
            onDeleteOne = {
                confirmDelete = false
                viewModel.deleteOccurrence(existing, occurrenceDate, onDone = onBack)
            },
            onDeleteAll = {
                confirmDelete = false
                viewModel.deleteEvent(existing, onDone = onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DeleteDialog(event: CalendarEvent, onDeleteOne: () -> Unit, onDeleteAll: () -> Unit, onDismiss: () -> Unit) {
    if (!event.recurrence.repeats) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (event.isChecklist) "Delete checklist?" else "Delete event?") },
            text = { Text("\"${event.title}\" will be removed for everyone you share your calendar with.") },
            confirmButton = { TextButton(onClick = onDeleteAll) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete repeating event") },
        text = {
            Column {
                TextButton(onClick = onDeleteOne, modifier = Modifier.fillMaxWidth()) { Text("This event") }
                TextButton(onClick = onDeleteAll, modifier = Modifier.fillMaxWidth()) { Text("All events") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun newEventTemplate(start: LocalDateTime?, allDay: Boolean): CalendarEvent {
    val zone = ZoneId.systemDefault()
    val from = start ?: LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1)
    return if (allDay) {
        CalendarEvent(
            start = from.toLocalDate().atStartOfDay(zone).toInstant(),
            end = from.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant(),
            allDay = true,
            reminderMinutes = NO_REMINDER,
        )
    } else {
        CalendarEvent(
            start = from.atZone(zone).toInstant(),
            end = from.plusHours(1).atZone(zone).toInstant(),
            reminderMinutes = 15,
        )
    }
}

/**
 * Items to save, including text still sitting in the add field: a new event's draft takes it into the
 * create write, an existing event adds it live.
 */
private fun withPendingItem(
    initial: CalendarEvent,
    items: List<ChecklistItem>,
    actions: ChecklistActions,
    pending: String,
): List<ChecklistItem> {
    if (initial.id.isNotBlank()) {
        actions.onAdd(pending)
        return items
    }
    return items.plusItem(pending, UUID.randomUUID().toString())
}

/** A standalone checklist is stored as an all-day event on its due date, without reminder, repeat or request. */
private fun newChecklistTemplate(): CalendarEvent = checklistDueOn(LocalDate.now(), CalendarEvent(kind = EventKind.CHECKLIST))

private fun checklistDueOn(due: LocalDate, event: CalendarEvent): CalendarEvent {
    val zone = ZoneId.systemDefault()
    return event.copy(
        kind = EventKind.CHECKLIST,
        allDay = true,
        start = due.atStartOfDay(zone).toInstant(),
        end = due.plusDays(1).atStartOfDay(zone).toInstant(),
        reminderMinutes = NO_REMINDER,
        recurrence = Recurrence(),
        requestedUid = null,
        requestedName = "",
    )
}

@Composable
private fun ChecklistEditor(
    initial: CalendarEvent,
    checklistItems: List<ChecklistItem>,
    checklistActions: ChecklistActions,
    colors: Map<String, Int>,
    onSave: (CalendarEvent) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var due by rememberSaveable { mutableStateOf(LocalDateTime.ofInstant(initial.start, zone)) }
    var newItem by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Due", style = MaterialTheme.typography.labelLarge)
        DateTimeRow(due, showTime = false) { due = it }
        ChecklistSection(checklistItems, colors, checklistActions, newItem, { newItem = it })
        Button(
            onClick = {
                // Only a new checklist's create write takes the items; existing items are written live.
                val items = withPendingItem(initial, checklistItems, checklistActions, newItem)
                onSave(checklistDueOn(due.toLocalDate(), initial.copy(title = title.trim(), checklist = items)))
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

@Composable
private fun EventEditor(
    initial: CalendarEvent,
    requestCandidates: List<Viewer>,
    checklistItems: List<ChecklistItem>,
    checklistActions: ChecklistActions,
    colors: Map<String, Int>,
    onSave: (CalendarEvent) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var location by rememberSaveable { mutableStateOf(initial.location) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var allDay by rememberSaveable { mutableStateOf(initial.allDay) }
    var start by rememberSaveable { mutableStateOf(LocalDateTime.ofInstant(initial.start, zone)) }
    // For all-day events the stored end is exclusive (next midnight); the form shows the last day.
    var end by rememberSaveable {
        mutableStateOf(LocalDateTime.ofInstant(initial.end, zone).let { if (initial.allDay) it.minusDays(1) else it })
    }
    var reminder by rememberSaveable { mutableStateOf(initial.reminderMinutes) }
    var recurrence by rememberSaveable { mutableStateOf(initial.recurrence) }
    var reminderMenu by rememberSaveable { mutableStateOf(false) }
    var requestedUid by rememberSaveable { mutableStateOf(initial.requestedUid) }
    var requestedName by rememberSaveable { mutableStateOf(initial.requestedName) }
    var showChecklist by rememberSaveable { mutableStateOf(false) }
    var newItem by rememberSaveable { mutableStateOf("") }

    val valid = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else end.isAfter(start)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("All day", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = allDay, onCheckedChange = { allDay = it })
        }

        Text("Starts", style = MaterialTheme.typography.labelLarge)
        DateTimeRow(start, showTime = !allDay) { newStart ->
            // Keep the duration when the start moves.
            val duration = Duration.between(start, end)
            recurrence = recurrence.followStart(start.toLocalDate(), newStart.toLocalDate())
            start = newStart
            end = newStart.plus(duration)
        }
        Text("Ends", style = MaterialTheme.typography.labelLarge)
        DateTimeRow(end, showTime = !allDay) { end = it }
        if (!valid) {
            Text("The end must be after the start", color = MaterialTheme.colorScheme.error)
        }

        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        RequestPicker(
            selectedUid = requestedUid,
            selectedName = requestedName,
            candidates = requestCandidates,
            status = initial.requestStatus.takeIf { initial.requestedUid == requestedUid },
            onChange = { uid, name -> requestedUid = uid; requestedName = name },
        )

        RepeatPicker(rule = recurrence, start = start.toLocalDate(), onChange = { recurrence = it })
        if (recurrence.repeats && initial.recurrence.repeats && initial.id.isNotBlank()) {
            Text(
                "Changes apply to all events in this series.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = location, onValueChange = { location = it },
            label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description, onValueChange = { description = it },
            label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
        )

        if (showChecklist || checklistItems.isNotEmpty()) {
            ChecklistSection(checklistItems, colors, checklistActions, newItem, { newItem = it })
        } else {
            OutlinedButton(onClick = { showChecklist = true }) { Text("+ Add checklist") }
        }

        Text("Reminder", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { reminderMenu = true }) { Text(reminderLabel(reminder)) }
            DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                ReminderOptions.forEach { (minutes, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { reminder = minutes; reminderMenu = false })
                }
            }
        }
        Text(
            "Everyone you share your calendar with gets this reminder too (unless they muted your calendar).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                val (startInstant, endInstant) = if (allDay) {
                    start.toLocalDate().atStartOfDay(zone).toInstant() to
                        end.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                } else {
                    start.atZone(zone).toInstant() to end.atZone(zone).toInstant()
                }
                onSave(
                    initial.copy(
                        title = title.trim(),
                        location = location.trim(),
                        description = description.trim(),
                        start = startInstant,
                        end = endInstant,
                        allDay = allDay,
                        reminderMinutes = reminder,
                        recurrence = recurrence,
                        requestedUid = requestedUid,
                        requestedName = requestedName,
                        // Only a new event's create write takes these; existing items are written live.
                        checklist = withPendingItem(initial, checklistItems, checklistActions, newItem),
                    )
                )
            },
            enabled = title.isNotBlank() && valid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

@Composable
private fun DateTimeRow(value: LocalDateTime, showTime: Boolean, onChange: (LocalDateTime) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).atTime(value.toLocalTime())) },
                value.year, value.monthValue - 1, value.dayOfMonth,
            ).show()
        }) { Text(value.format(DateFmt)) }
        if (showTime) {
            OutlinedButton(onClick = {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> onChange(value.withHour(hour).withMinute(minute)) },
                    value.hour, value.minute, DateFormat.is24HourFormat(context),
                ).show()
            }) { Text(value.format(TimeFmt)) }
        }
    }
}

/**
 * Accept / Decline for the requested person; they can change their answer later. On a repeating request,
 * Decline asks whether it is meant for this date only or for all dates, like Google Calendar.
 */
@Composable
private fun AnswerButtons(
    event: CalendarEvent,
    date: LocalDate,
    statusHere: RequestStatus?,
    onAnswerAll: (accept: Boolean) -> Unit,
    onAnswerDate: (date: LocalDate, decline: Boolean) -> Unit,
) {
    var askScope by rememberSaveable { mutableStateOf(false) }
    val declinedOnlyHere = date in event.declinedDates && event.requestStatus != RequestStatus.DECLINED
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (statusHere != RequestStatus.DECLINED) {
            OutlinedButton(onClick = { if (event.recurrence.repeats) askScope = true else onAnswerAll(false) }) {
                Text("Decline")
            }
        }
        if (statusHere != RequestStatus.ACCEPTED) {
            Button(onClick = {
                // Re-accepting one declined date leaves the rest of the series as it is.
                if (declinedOnlyHere) onAnswerDate(date, false) else onAnswerAll(true)
            }) { Text(if (declinedOnlyHere) "Accept this date" else "Accept") }
        }
    }
    if (askScope) {
        AlertDialog(
            onDismissRequest = { askScope = false },
            title = { Text("Decline repeating date") },
            text = {
                Column {
                    TextButton(onClick = { askScope = false; onAnswerDate(date, true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Only this date (${date.format(DateFmt)})")
                    }
                    TextButton(onClick = { askScope = false; onAnswerAll(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("All dates")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askScope = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EventDetails(
    event: CalendarEvent,
    start: Instant,
    colors: Map<String, Int>,
    checklistActions: ChecklistActions,
    myUid: String?,
    onAnswerAll: (accept: Boolean) -> Unit,
    onAnswerDate: (date: LocalDate, decline: Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val end = start.plus(Duration.between(event.start, event.end))
    val startLocal = LocalDateTime.ofInstant(start, zone)
    val endLocal = LocalDateTime.ofInstant(end, zone)
    val whenText = if (event.isChecklist) {
        "Due ${startLocal.format(DateFmt)}"
    } else if (event.allDay) {
        val lastDay = endLocal.toLocalDate().minusDays(1)
        if (lastDay == startLocal.toLocalDate()) "${startLocal.format(DateFmt)} · All day"
        else "${startLocal.format(DateFmt)} – ${lastDay.format(DateFmt)}"
    } else if (startLocal.toLocalDate() == endLocal.toLocalDate()) {
        "${startLocal.format(DateFmt)} · ${startLocal.format(TimeFmt)} – ${endLocal.format(TimeFmt)}"
    } else {
        "${startLocal.format(DateFmt)} ${startLocal.format(TimeFmt)} – ${endLocal.format(DateFmt)} ${endLocal.format(TimeFmt)}"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorDot(colors.colorFor(event.ownerId).color, size = 16.dp)
            Text(event.title, style = MaterialTheme.typography.headlineSmall)
        }
        Text(whenText)
        if (event.recurrence.repeats) {
            Text(repeatLabel(event.recurrence, LocalDateTime.ofInstant(event.start, zone).toLocalDate()))
        }
        val date = startLocal.toLocalDate()
        val statusHere = event.requestStatusOn(date)
        requestSummary(event, myUid, statusHere)?.let { summary ->
            Text(summary, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (event.isRequest && event.requestedUid == myUid) {
            AnswerButtons(event, date, statusHere, onAnswerAll, onAnswerDate)
        }
        if (event.location.isNotBlank()) Text("📍 ${event.location}")
        if (event.description.isNotBlank()) Text(event.description)
        // Viewers can tick and edit the items, though not the event itself.
        if (event.hasChecklist || event.isChecklist) ChecklistSection(event.checklist, colors, checklistActions)
        if (!event.isChecklist) {
            Text("Reminder: ${reminderLabel(event.reminderMinutes)}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (event.isChecklist) "${event.ownerName}'s calendar · everyone can tick and edit the items; only ${event.ownerName} can change the title and date."
            else "${event.ownerName}'s calendar · only ${event.ownerName} can change this event.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
