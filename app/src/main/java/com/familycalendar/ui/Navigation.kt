package com.familycalendar.ui

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.familycalendar.MainViewModel
import com.familycalendar.data.EventKind
import com.familycalendar.ui.birthday.BirthdayEditScreen
import com.familycalendar.ui.birthday.BirthdayListScreen
import com.familycalendar.ui.calendar.CalendarScreen
import com.familycalendar.ui.event.EventScreen
import com.familycalendar.ui.sharing.SharingScreen
import java.time.Instant
import java.time.LocalDateTime

private object Routes {
    const val CALENDAR = "calendar"
    const val SHARING = "sharing"
    const val EVENT = "event?key={key}&occ={occ}&start={start}&allDay={allDay}&kind={kind}"
    const val BIRTHDAYS = "birthdays"
    const val BIRTHDAY = "birthday?owner={owner}&id={id}&view={view}"

    /**
     * An existing birthday of [ownerId]'s calendar, or a new one of my own when [id] is `null`.
     * [viewOnly]: just show it (tapped in the calendar); birthdays are edited from the birthday list.
     */
    fun birthday(ownerId: String?, id: String?, viewOnly: Boolean = false) =
        "birthday?owner=${Uri.encode(ownerId.orEmpty())}&id=${Uri.encode(id.orEmpty())}&view=$viewOnly"

    /** An existing event; [occurrenceStart] says which occurrence of a repeating series was tapped. */
    fun event(key: String, occurrenceStart: Instant) =
        "event?key=${Uri.encode(key)}&occ=${occurrenceStart.toEpochMilli()}"

    /** A new event (or checklist) starting at [start] (local time, as tapped in the grid). */
    fun newEvent(start: LocalDateTime, allDay: Boolean, kind: EventKind) =
        "event?start=${Uri.encode(start.toString())}&allDay=$allDay&kind=${kind.name}"
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val nav = rememberNavController()
    // Shown on whichever screen the invitation was accepted from.
    val shareBack by viewModel.shareBackOffer.collectAsStateWithLifecycle()
    shareBack?.let { other ->
        val name = other.ownerName.ifBlank { "them" }
        AlertDialog(
            onDismissRequest = viewModel::dismissShareBack,
            title = { Text("Share your calendar with $name too?") },
            text = { Text("$name gets an invitation and sees your events once they accept.") },
            confirmButton = { TextButton(onClick = viewModel::shareBack) { Text("Share back") } },
            dismissButton = { TextButton(onClick = viewModel::dismissShareBack) { Text("Not now") } },
        )
    }
    NavHost(navController = nav, startDestination = Routes.CALENDAR) {
        composable(Routes.CALENDAR) {
            CalendarScreen(
                viewModel = viewModel,
                onOpenOccurrence = {
                    if (it.event.isBirthday) nav.navigate(Routes.birthday(it.event.ownerId, it.event.id, viewOnly = true))
                    else nav.navigate(Routes.event(it.event.key, it.start))
                },
                onNewEvent = { start, allDay, kind -> nav.navigate(Routes.newEvent(start, allDay, kind)) },
                onOpenSharing = { nav.navigate(Routes.SHARING) },
                onOpenBirthdays = { nav.navigate(Routes.BIRTHDAYS) },
                onNewBirthday = { nav.navigate(Routes.birthday(null, null)) },
            )
        }
        composable(
            Routes.EVENT,
            arguments = listOf(
                navArgument("key") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("occ") { type = NavType.LongType; defaultValue = -1L },
                navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("allDay") { type = NavType.BoolType; defaultValue = false },
                navArgument("kind") { type = NavType.StringType; defaultValue = EventKind.EVENT.name },
            ),
        ) { entry ->
            val args = entry.arguments
            EventScreen(
                viewModel = viewModel,
                eventKey = args?.getString("key"),
                occurrenceStart = args?.getLong("occ")?.takeIf { it >= 0 }?.let(Instant::ofEpochMilli),
                newStart = args?.getString("start")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                newAllDay = args?.getBoolean("allDay") ?: false,
                newKind = args?.getString("kind")?.let { k -> EventKind.entries.firstOrNull { it.name == k } } ?: EventKind.EVENT,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SHARING) {
            SharingScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable(Routes.BIRTHDAYS) {
            BirthdayListScreen(
                viewModel = viewModel,
                onOpen = { nav.navigate(Routes.birthday(it.ownerId, it.id)) },
                onNew = { nav.navigate(Routes.birthday(null, null)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.BIRTHDAY,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("view") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            BirthdayEditScreen(
                viewModel = viewModel,
                ownerId = entry.arguments?.getString("owner")?.takeIf { it.isNotBlank() },
                birthdayId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                viewOnly = entry.arguments?.getBoolean("view") ?: false,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
