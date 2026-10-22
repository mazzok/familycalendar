package com.familycalendar.ui.birthday

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familycalendar.MainViewModel
import com.familycalendar.data.Birthday
import com.familycalendar.data.SELF_BIRTHDAY_ID
import com.familycalendar.data.label
import java.time.LocalDate

/**
 * Creates or edits one of my entered birthdays, edits my account birthday ([SELF_BIRTHDAY_ID]), or shows
 * someone else's read-only. [birthdayId] `null` = a new one of my own. [viewOnly] shows even my own
 * read-only (opened from the calendar; editing happens from the birthday list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayEditScreen(
    viewModel: MainViewModel,
    ownerId: String?,
    birthdayId: String?,
    onBack: () -> Unit,
    viewOnly: Boolean = false,
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val birthdays by viewModel.birthdays.collectAsStateWithLifecycle()
    val viewers by viewModel.myViewers.collectAsStateWithLifecycle()
    val myName by viewModel.myName.collectAsStateWithLifecycle()
    val isNew = birthdayId == null
    val existing = birthdayId?.let { id -> birthdays.firstOrNull { it.id == id && it.ownerId == ownerId } }
    val mine = ownerId == user?.uid
    val editable = isNew || (mine && !viewOnly)
    val isAccount = birthdayId == SELF_BIRTHDAY_ID
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Re-initialised once the birthday has loaded.
    var name by rememberSaveable(existing != null) { mutableStateOf(existing?.name.orEmpty()) }
    var day by rememberSaveable(existing != null) { mutableStateOf(existing?.day?.toString().orEmpty()) }
    var month by rememberSaveable(existing != null) { mutableStateOf(existing?.month?.toString().orEmpty()) }
    var year by rememberSaveable(existing != null) { mutableStateOf(existing?.year?.toString().orEmpty()) }
    var sharedWith by rememberSaveable(existing != null) { mutableStateOf(existing?.sharedWith.orEmpty().toList()) }
    val parsed = parseBirthDate(day, month, year, LocalDate.now())
    val canSave = parsed != null && (isAccount || name.isNotBlank())
    val accepted = viewers.filter { it.accepted }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (isAccount) "Remove your birthday?" else "Delete ${existing.name}'s birthday?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (isAccount) { viewModel.deleteMyBirthday(); onBack() } else viewModel.deleteBirthday(existing, onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isNew -> "New birthday"
                            isAccount && editable -> "My birthday"
                            editable -> "Edit birthday"
                            else -> "Birthday"
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isNew && existing == null) {
                Text("This birthday no longer exists.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (!editable) {
                val b = existing!!
                Text(b.label(b.nextDate(LocalDate.now()).year), style = MaterialTheme.typography.headlineSmall)
                Text(b.dateText(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        mine && b.isAccount -> "Your birthday · change it under Birthdays in the menu"
                        mine -> "Added by you · change it under Birthdays in the menu"
                        b.isAccount -> "${b.ownerName}'s own birthday"
                        else -> "Added by ${b.ownerName}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            OutlinedTextField(
                value = if (isAccount) myName else name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                enabled = !isAccount,
                modifier = Modifier.fillMaxWidth(),
            )
            BirthDateFields(day, month, year, onChange = { d, m, y -> day = d; month = m; year = y })
            if (isAccount) {
                Text(
                    "Everyone who sees your calendar sees your birthday. Leave out the year to keep your age to yourself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Share with …", style = MaterialTheme.typography.titleSmall)
                if (accepted.isEmpty()) {
                    Text(
                        "Only you see it. Share your calendar with someone to share birthdays with them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                accepted.forEach { viewer ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewer.uid in sharedWith,
                            onCheckedChange = { on -> sharedWith = if (on) sharedWith + viewer.uid else sharedWith - viewer.uid },
                        )
                        Text(viewer.name.ifBlank { viewer.email })
                    }
                }
            }
            Button(
                onClick = {
                    val date = parsed ?: return@Button
                    if (isAccount) {
                        viewModel.saveMyBirthday(date.day, date.month, date.year)
                        onBack()
                    } else {
                        // Not filtered against the viewer list: it may not have loaded yet, and uids of former
                        // viewers are harmless (rules block them, a re-invite clears them).
                        viewModel.saveBirthday(
                            Birthday(id = existing?.id.orEmpty(), name = name.trim(), month = date.month, day = date.day, year = date.year, sharedWith = sharedWith.toSet()),
                            onBack,
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
