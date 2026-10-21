package com.familycalendar.ui.calendar

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familycalendar.MainViewModel
import com.familycalendar.data.Occurrence
import com.familycalendar.data.RecurrenceExpander
import com.familycalendar.ui.sharing.InvitationCard
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private const val SCHEDULE_DAYS = 180L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: MainViewModel,
    onOpenOccurrence: (Occurrence) -> Unit,
    onNewEvent: (start: LocalDateTime, allDay: Boolean) -> Unit,
    onOpenSharing: () -> Unit,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val shared by viewModel.sharedWithMe.collectAsStateWithLifecycle()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    val requests by viewModel.openRequests.collectAsStateWithLifecycle()
    val hidden by viewModel.hiddenOwners.collectAsStateWithLifecycle()
    val colors by viewModel.calendarColors.collectAsStateWithLifecycle()
    val mode by viewModel.viewMode.collectAsStateWithLifecycle()
    val date by viewModel.selectedDate.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val myUid = user?.uid
    val zone = ZoneId.systemDefault()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // The span of days the current view needs, expanded into concrete occurrences.
    val visibleRange: ClosedRange<LocalDate> = when (mode) {
        CalendarViewMode.MONTH -> monthGridRange(YearMonth.from(date))
        CalendarViewMode.SCHEDULE -> date..date.plusDays(SCHEDULE_DAYS - 1)
        else -> mode.visibleDays(date).let { it.first()..it.last() }
    }
    val occurrences = remember(events, hidden, visibleRange) {
        val series = events?.filter { it.ownerId !in hidden } ?: return@remember null
        RecurrenceExpander.expand(
            series,
            visibleRange.start.atStartOfDay(zone).toInstant(),
            visibleRange.endInclusive.plusDays(1).atStartOfDay(zone).toInstant(),
            zone,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title(mode, date), maxLines = 1) },
                actions = {
                    TodayButton(onClick = viewModel::goToToday)
                    ViewModeMenu(mode, onSelect = viewModel::setViewMode)
                    OverflowMenu(onOpenSharing = onOpenSharing, onSignOut = viewModel::signOut)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val start = if (date == LocalDate.now()) {
                    LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
                } else {
                    date.atTime(9, 0)
                }
                onNewEvent(start, false)
            }) { Icon(Icons.Default.Add, "New event") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            requests.forEach { request ->
                val now = java.time.Instant.now()
                val next = remember(request) {
                    RecurrenceExpander.expand(request, now, now.plus(java.time.Duration.ofDays(400)), zone).firstOrNull()
                        ?: Occurrence(request, request.start, request.end)
                }
                RequestCard(
                    event = request,
                    start = next.start,
                    color = colors.colorFor(request.ownerId),
                    onOpen = { onOpenOccurrence(next) },
                    onAnswer = { accept -> viewModel.answerRequest(request, accept) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            invitations.forEach { invitation ->
                InvitationCard(
                    invitation = invitation,
                    onAccept = { viewModel.accept(invitation) },
                    onDecline = { viewModel.decline(invitation) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            CalendarFilterChips(
                calendars = listOfNotNull(myUid?.let { it to "Me" }) + shared.map { it.ownerId to it.ownerName },
                hidden = hidden,
                colors = colors,
                onToggle = viewModel::toggleHidden,
            )
            Box(
                Modifier
                    .weight(1f)
                    .then(if (mode != CalendarViewMode.SCHEDULE) Modifier.swipeToStep(viewModel::step) else Modifier),
            ) {
                when {
                    occurrences == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    mode == CalendarViewMode.MONTH -> MonthView(
                        month = YearMonth.from(date),
                        occurrences = occurrences,
                        colors = colors,
                        zone = zone,
                        onDayClick = viewModel::openDay,
                        onOccurrenceClick = onOpenOccurrence,
                    )
                    mode == CalendarViewMode.SCHEDULE -> ScheduleView(
                        from = date,
                        days = SCHEDULE_DAYS,
                        occurrences = occurrences,
                        colors = colors,
                        myUid = myUid,
                        zone = zone,
                        onOccurrenceClick = onOpenOccurrence,
                    )
                    else -> TimeGridView(
                        days = mode.visibleDays(date),
                        occurrences = occurrences,
                        colors = colors,
                        zone = zone,
                        onDayClick = viewModel::openDay,
                        onSlotClick = { onNewEvent(it, false) },
                        onOccurrenceClick = onOpenOccurrence,
                    )
                }
            }
        }
    }
}

private fun title(mode: CalendarViewMode, date: LocalDate): String {
    val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy")
    val days = mode.visibleDays(date)
    return if (days.size > 1 && YearMonth.from(days.first()) != YearMonth.from(days.last())) {
        val short = DateTimeFormatter.ofPattern("LLL")
        "${days.first().format(short)} – ${days.last().format(short)} ${days.last().year}"
    } else {
        (days.firstOrNull() ?: date).format(monthYear).replaceFirstChar { it.titlecase() }
    }
}

/** Horizontal swipe moves to the previous/next period. */
private fun Modifier.swipeToStep(onStep: (Int) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (abs(total) > 80.dp.toPx()) onStep(if (total < 0) 1 else -1) },
            onHorizontalDrag = { _, dx -> total += dx },
        )
    }
)

@Composable
private fun TodayButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(24.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(LocalDate.now().dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ViewModeMenu(mode: CalendarViewMode, onSelect: (CalendarViewMode) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(mode.label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CalendarViewMode.entries.reversed().forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); open = false })
            }
        }
    }
}

@Composable
private fun OverflowMenu(onOpenSharing: () -> Unit, onSignOut: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Sharing & colors") }, onClick = { open = false; onOpenSharing() })
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; onSignOut() })
        }
    }
}

@Composable
private fun CalendarFilterChips(
    calendars: List<Pair<String, String>>,
    hidden: Set<String>,
    colors: Map<String, Int>,
    onToggle: (String) -> Unit,
) {
    if (calendars.size < 2) return // nothing to filter with a single calendar
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(calendars, key = { it.first }) { (ownerId, label) ->
            FilterChip(
                selected = ownerId !in hidden,
                onClick = { onToggle(ownerId) },
                label = { Text(label) },
                leadingIcon = { ColorDot(colors.colorFor(ownerId).color, size = 10.dp) },
            )
        }
    }
}
