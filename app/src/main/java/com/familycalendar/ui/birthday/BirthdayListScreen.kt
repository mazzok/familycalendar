package com.familycalendar.ui.birthday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familycalendar.MainViewModel
import com.familycalendar.data.Birthday
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayListScreen(
    viewModel: MainViewModel,
    onOpen: (Birthday) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val birthdays by viewModel.birthdays.collectAsStateWithLifecycle()
    val myBirthday by viewModel.myBirthday.collectAsStateWithLifecycle()
    val viewers by viewModel.myViewers.collectAsStateWithLifecycle()
    val notify by viewModel.birthdayNotifications.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editingMine by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val today = LocalDate.now()
    val myUid = user?.uid
    val mine = birthdays.filter { it.ownerId == myUid && !it.isAccount }.sortedBy { it.nextDate(today) }
    val fromOthers = birthdays.filter { it.ownerId != myUid }.sortedBy { it.nextDate(today) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (editingMine) {
        MyBirthdayDialog(
            current = myBirthday,
            onSave = { viewModel.saveMyBirthday(it.day, it.month, it.year); editingMine = false },
            onRemove = { viewModel.deleteMyBirthday(); editingMine = false },
            onDismiss = { editingMine = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Birthdays") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Default.Add, "Add birthday") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("My birthday") },
                    supportingContent = {
                        Text(myBirthday?.let { "${it.dateText()} · everyone who sees your calendar sees it" } ?: "Not set")
                    },
                    modifier = Modifier.clickable { editingMine = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Birthday notifications") },
                    supportingContent = { Text("At 8:00 on the day, on this phone") },
                    trailingContent = { Switch(checked = notify, onCheckedChange = viewModel::setBirthdayNotifications) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("My birthdays") }
            if (mine.isEmpty()) item { EmptyLine("You haven't added any birthdays yet.") }
            items(mine, key = { "mine-${it.id}" }) { b ->
                // Someone who left may still be listed in sharedWith; only current viewers count.
                val sharedNames = viewers.filter { it.accepted && it.uid in b.sharedWith }.map { it.name.ifBlank { it.email } }
                ListItem(
                    headlineContent = { Text(b.name) },
                    supportingContent = {
                        Text(b.dateText() + if (sharedNames.isEmpty()) " · only you" else " · shared with ${sharedNames.joinToString(", ")}")
                    },
                    modifier = Modifier.clickable { onOpen(b) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Shared with me") }
            if (fromOthers.isEmpty()) item { EmptyLine("Nobody has shared a birthday with you yet.") }
            items(fromOthers, key = { "other-${it.ownerId}-${it.id}" }) { b ->
                ListItem(
                    headlineContent = { Text(b.name) },
                    supportingContent = { Text("${b.dateText()} · from ${b.ownerName.ifBlank { "someone" }}") },
                    modifier = Modifier.clickable { onOpen(b) },
                )
            }
        }
    }
}

@Composable
private fun MyBirthdayDialog(current: Birthday?, onSave: (BirthDate) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var day by rememberSaveable { mutableStateOf(current?.day?.toString().orEmpty()) }
    var month by rememberSaveable { mutableStateOf(current?.month?.toString().orEmpty()) }
    var year by rememberSaveable { mutableStateOf(current?.year?.toString().orEmpty()) }
    val parsed = parseBirthDate(day, month, year, LocalDate.now())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My birthday") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Everyone who sees your calendar sees your birthday. Leave out the year to keep your age to yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BirthDateFields(day, month, year, onChange = { d, m, y -> day = d; month = m; year = y })
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("Save") } },
        dismissButton = {
            if (current != null) TextButton(onClick = onRemove) { Text("Remove") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
