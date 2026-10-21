package com.familycalendar.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.familycalendar.data.ChecklistItem
import com.familycalendar.data.itemText
import com.familycalendar.data.plusItem
import com.familycalendar.ui.calendar.checkColor
import java.time.Instant
import java.util.UUID

/** What the checklist section can do: write live to Firestore, or edit a draft of an unsaved event. */
class ChecklistActions(
    val onToggle: (ChecklistItem) -> Unit,
    val onAdd: (String) -> Unit,
    val onRename: (ChecklistItem, String) -> Unit,
    val onRemove: (ChecklistItem) -> Unit,
    /** Drafts take every keystroke, so a Save right after typing can't miss a rename. */
    val renameWhileTyping: Boolean = false,
)

/**
 * A shared checklist: tick boxes in the color of whoever ticked them, editable texts and an add field.
 * Used on events and on standalone checklists, by the owner and by viewers alike. [newText] is the add
 * field's text, hoisted so a form's Save can still take an item that was typed but not added.
 */
@Composable
fun ChecklistSection(
    items: List<ChecklistItem>,
    colors: Map<String, Int>,
    actions: ChecklistActions,
    newText: String,
    onNewTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun add() {
        itemText(newText)?.let(actions.onAdd)
        onNewTextChange("")
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Checklist", style = MaterialTheme.typography.labelLarge)
        items.forEach { item -> ChecklistRow(item, colors, actions) }
        OutlinedTextField(
            value = newText,
            onValueChange = onNewTextChange,
            placeholder = { Text("+ Add item") },
            singleLine = true,
            trailingIcon = {
                if (itemText(newText) != null) IconButton(onClick = ::add) { Icon(Icons.Default.Add, "Add item") }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            // Keeps focus after adding, so the next item can be typed right away.
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** For screens without a Save button (viewers): the add field's text lives here. */
@Composable
fun ChecklistSection(items: List<ChecklistItem>, colors: Map<String, Int>, actions: ChecklistActions) {
    var newText by rememberSaveable { mutableStateOf("") }
    ChecklistSection(items, colors, actions, newText, { newText = it })
}

@Composable
private fun ChecklistRow(item: ChecklistItem, colors: Map<String, Int>, actions: ChecklistActions) {
    val tickColor = item.checkedBy?.let { colors.checkColor(it).color }
    var text by remember(item.id) { mutableStateOf(item.text) }
    var focused by remember { mutableStateOf(false) }
    // Follows renames by others, but never overwrites what is being typed here.
    LaunchedEffect(item.text) { if (!focused) text = item.text }
    fun commit() {
        if (itemText(text) == null) text = item.text
        else if (text.trim() != item.text) actions.onRename(item, text)
    }
    // Leaving the screen (Back, or Save of a live list) doesn't move focus, so commit on the way out.
    val latestCommit by rememberUpdatedState(::commit)
    DisposableEffect(item.id) { onDispose { if (focused) latestCommit() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = item.checked,
            onCheckedChange = { actions.onToggle(item) },
            colors = if (tickColor != null) CheckboxDefaults.colors(checkedColor = tickColor) else CheckboxDefaults.colors(),
        )
        Column(Modifier.weight(1f)) {
            TextField(
                value = text,
                onValueChange = {
                    text = it
                    if (actions.renameWhileTyping && itemText(it) != null) actions.onRename(item, it)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            )
            if (tickColor != null) {
                Text(item.checkedByName.ifBlank { "Someone" }, color = tickColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = { actions.onRemove(item) }) { Icon(Icons.Default.Close, "Remove ${item.text}") }
    }
}

/** Checklist of an event that isn't saved yet; its items go into the event's create write. */
@Composable
fun rememberDraftChecklist(myUid: String, myName: String): Pair<List<ChecklistItem>, ChecklistActions> {
    var items by rememberSaveable { mutableStateOf(emptyList<ChecklistItem>()) }
    fun update(item: ChecklistItem, change: (ChecklistItem) -> ChecklistItem) {
        items = items.map { if (it.id == item.id) change(it) else it }
    }
    val actions = ChecklistActions(
        onToggle = { item ->
            update(item) {
                if (it.checked) it.copy(checkedBy = null, checkedByName = "", checkedAt = null)
                else it.copy(checkedBy = myUid, checkedByName = myName, checkedAt = Instant.now())
            }
        },
        onAdd = { raw -> items = items.plusItem(raw, UUID.randomUUID().toString()) },
        onRename = { item, raw -> itemText(raw)?.let { text -> update(item) { it.copy(text = text) } } },
        onRemove = { item -> items = items.filterNot { it.id == item.id } },
        renameWhileTyping = true,
    )
    return items to actions
}
