package com.familycalendar.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.familycalendar.MainViewModel
import com.familycalendar.ui.calendar.ColorDot
import com.familycalendar.ui.calendar.ColorPickerDialog
import com.familycalendar.ui.calendar.colorFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val viewers by viewModel.myViewers.collectAsStateWithLifecycle()
    val shared by viewModel.sharedWithMe.collectAsStateWithLifecycle()
    val muted by viewModel.mutedOwners.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val colors by viewModel.calendarColors.collectAsStateWithLifecycle()
    val invitations by viewModel.invitations.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    /** ownerId whose color is being picked, or null. */
    var pickingColorFor by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    pickingColorFor?.let { ownerId ->
        ColorPickerDialog(
            title = if (ownerId == user?.uid) "My calendar color" else "Color for ${shared.firstOrNull { it.ownerId == ownerId }?.ownerName.orEmpty()}",
            selected = colors[ownerId] ?: 0,
            onPick = { viewModel.setCalendarColor(ownerId, it); pickingColorFor = null },
            onDismiss = { pickingColorFor = null },
        )
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (invitations.isNotEmpty()) {
                item { SectionTitle("Invitations") }
                items(invitations, key = { "invite-${it.ownerId}" }) { invitation ->
                    InvitationCard(
                        invitation = invitation,
                        onAccept = { viewModel.accept(invitation) },
                        onDecline = { viewModel.decline(invitation) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            user?.uid?.let { myUid ->
                item {
                    ListItem(
                        headlineContent = { Text("My calendar") },
                        supportingContent = { Text("Tap to change its color (only on this phone)") },
                        leadingContent = { ColorDot(colors.colorFor(myUid).color, size = 24.dp) },
                        modifier = Modifier.clickable { pickingColorFor = myUid },
                    )
                }
            }
            item { SectionTitle("Share my calendar") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The person gets an invitation. Only after they accept can they see your events and get their reminders. They need a Family Calendar account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = email, onValueChange = { email = it },
                            label = { Text("Their email") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { viewModel.shareWith(email, onDone = { email = "" }) },
                            enabled = !busy && email.contains('@'),
                        ) { Text("Invite") }
                    }
                }
            }
            if (viewers.isEmpty()) {
                item { EmptyLine("You haven't shared your calendar with anyone yet.") }
            }
            items(viewers, key = { "viewer-${it.uid}" }) { viewer ->
                ListItem(
                    headlineContent = { Text(viewer.name.ifBlank { viewer.email }) },
                    supportingContent = {
                        Text(if (viewer.accepted) viewer.email else "Invitation pending · ${viewer.email}")
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.revoke(viewer) }) {
                            Icon(Icons.Default.Close, if (viewer.accepted) "Stop sharing" else "Withdraw invitation")
                        }
                    },
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionTitle("Calendars shared with me") }
            if (shared.isEmpty()) {
                item { EmptyLine("Nobody has shared a calendar with you yet.") }
            }
            items(shared, key = { "shared-${it.ownerId}" }) { calendar ->
                val notify = calendar.ownerId !in muted
                ListItem(
                    headlineContent = { Text(calendar.ownerName) },
                    leadingContent = {
                        ColorDot(
                            colors.colorFor(calendar.ownerId).color,
                            size = 24.dp,
                            modifier = Modifier.clickable { pickingColorFor = calendar.ownerId },
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp).size(20.dp),
                            )
                            Switch(
                                checked = notify,
                                onCheckedChange = { viewModel.setMuted(calendar.ownerId, !it) },
                                modifier = Modifier.semantics { contentDescription = "Reminders from ${calendar.ownerName}" },
                            )
                            TextButton(onClick = { viewModel.leave(calendar) }) { Text("Leave") }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
