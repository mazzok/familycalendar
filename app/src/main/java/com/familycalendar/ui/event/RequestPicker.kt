package com.familycalendar.ui.event

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.familycalendar.data.RequestStatus
import com.familycalendar.data.Viewer

/**
 * "Ask for confirmation" field: pick one person who has to accept this date.
 * [status] is the stored answer, shown only while the same person is selected.
 */
@Composable
fun RequestPicker(
    selectedUid: String?,
    selectedName: String,
    candidates: List<Viewer>,
    status: RequestStatus?,
    onChange: (uid: String?, name: String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Text("Ask for confirmation", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = candidates.isNotEmpty() || selectedUid != null) {
                Text(if (selectedUid == null) "Nobody" else selectedName.ifBlank { "Selected person" })
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Nobody") }, onClick = { onChange(null, ""); open = false })
                candidates.forEach { viewer ->
                    val name = viewer.name.ifBlank { viewer.email }
                    DropdownMenuItem(text = { Text(name) }, onClick = { onChange(viewer.uid, name); open = false })
                }
            }
        }
        val hint = when {
            candidates.isEmpty() && selectedUid == null ->
                "Share your calendar with someone first; only they can be asked to confirm."
            selectedUid == null -> null
            status == RequestStatus.ACCEPTED -> "$selectedName accepted. Changing the date or time asks again."
            status == RequestStatus.DECLINED -> "$selectedName declined. Changing the date or time asks again."
            status == RequestStatus.PENDING -> "Waiting for $selectedName to accept."
            else -> "$selectedName will be asked to accept this date and gets notified."
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
