# Shared Checklists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shared, live checklists on events and as standalone "check-it" entries, with per-person colored checkmarks and create/complete notifications for standalone lists.

**Architecture:** A checklist is a map field `checklist` on the existing event document, keyed by item id; a standalone checklist is an all-day event with `kind = "checklist"`. Item edits are field-path updates written immediately by anyone who can see the calendar (new Firestore rule); owner form saves use a merge write that never touches `checklist`. A pure `ChecklistTracker` decides notifications from a per-phone seen state, run by a new `ChecklistNotifier` from `ReminderSyncWorker` and `MainViewModel`.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Firebase Firestore (Android SDK), WorkManager, JUnit 4 (JVM unit tests only).

**Spec:** `docs/superpowers/specs/2026-10-21-shared-checklists-design.md`

## Global Constraints

- Storage: `checklist: { "<itemId>": { text, order, checkedBy: uid|null, checkedByName, checkedAt: timestamp|null } }` on `calendars/{ownerId}/events/{id}`. No new collection, no stored completion counter.
- `kind` field: `"checklist"` for standalone lists, `"event"` (or missing) for normal events. Missing = event.
- Every item edit is a field-path update (`checklist.<itemId>.…`); removing uses `FieldValue.delete()` on `checklist.<itemId>`. Never a whole-document write of `checklist` except in the create write of a new event.
- Owner `saveEvent` of an existing event must not write `checklist` (merge write without that key).
- Item changes on an existing event are written immediately (not part of Save/Cancel). Items typed into a new event go into its create write.
- Viewers may update an event only if `affectedKeys().hasOnly(['checklist'])` (plus the existing request-answer rule). Create/delete stay owner-only.
- Standalone checklists: title, due date (today by default), items. No time, request, reminder (`reminderMinutes = NO_REMINDER`) or repeat. Rendered as all-day on the due date; they don't roll over.
- Chip copy: standalone `☑ <title> <done>/<total>` (e.g. `☑ Einkauf 3/7`); event with attached list: `<title> ☑`.
- Checkmark color: `calendarColors[checkedBy]`; fallback a stable palette color derived from the uid.
- Notification copy (channel name "Shared checklists"): `Anna created checklist "Einkauf" for Fri, Oct 23` and `"Einkauf" is done. The last item was ticked by Martin`. Date format `EEE, MMM d`, `Locale.ENGLISH` (as in `MainViewModel.answerDate`).
- No notifications for single ticks/adds/edits, nor for attached checklists. `mutedOwners` suppresses checklist notifications.
- **Plan decision (spec silent):** a checklist attached to a repeating event is one list shared by all occurrences.
- **Plan decision (spec silent):** no version gate. Older app versions overwrite the whole document on owner save (would wipe `checklist`) and show standalone lists as plain all-day events — all family phones must be updated together with the rules deploy.
- Per `~/.claude/CLAUDE.md`: every "Commit" step and the rules deploy require explicit user approval first.

## Review Focus

1. **Owner saves the event form after someone else ticked an item** — the ticks must survive. Pinned by `toMap does not contain checklist` (Task 2) plus the merge write in Task 3.
2. **Ticking offline (supermarket)** — the pending server timestamp must not make the item look unticked or break "who ticked last". Pinned by `pending checkedAt still counts as checked` (Task 1) and reading with `ServerTimestampBehavior.ESTIMATE` (Task 2).
3. **A newly accepted calendar that already holds checklists** — must not fire a burst of "created" notices. Pinned by `checklists of a newly visible calendar are only remembered` (Task 4).
4. **A list emptied, then refilled and ticked** — empty is not complete; completing again notifies. Pinned by `an empty list is not complete` (Task 1) and `emptied then completed notifies` (Task 4).
5. **Blank or whitespace item text** (add or rename) — ignored on add, rename to blank keeps the old text. Pinned by `blank item text is rejected` (Task 1).

---

## File Structure

- `data/Models.kt` — add `EventKind`, `ChecklistItem`, checklist fields/derived values on `CalendarEvent`, `itemText()`.
- `data/Mappers.kt` — serialize `kind`; `ChecklistItem.toFirestore()`, `parseChecklist()`; `toMap()` excludes `checklist`.
- `data/CalendarRepository.kt` — merge save, create with items, item write methods.
- `firestore.rules` — viewer checklist update rule.
- `notifications/ChecklistNotifier.kt` (new) — `ChecklistTracker` (pure) + `ChecklistNotifier` (Android).
- `notifications/ReminderSyncWorker.kt`, `FamilyCalendarApp.kt`, `MainViewModel.kt` — wiring.
- `ui/calendar/CalendarCommon.kt`, `ScheduleView.kt` — chip label, check color.
- `ui/event/ChecklistSection.kt` (new) — shared checklist editor composable.
- `ui/event/EventScreen.kt`, `ui/Navigation.kt`, `ui/calendar/CalendarScreen.kt` — forms, route, "+" choice.

All source paths below are relative to `app/src/main/java/com/familycalendar/`; tests to `app/src/test/java/com/familycalendar/`.

---

### Task 1: Checklist model

**Files:**
- Modify: `data/Models.kt`
- Test: `data/ChecklistTest.kt` (new), `data/RequestStateTest.kt`

**Interfaces:**
- Produces:
  - `enum class EventKind { EVENT, CHECKLIST }`
  - `data class ChecklistItem(val id: String, val text: String, val order: Long = 0, val checkedBy: String? = null, val checkedByName: String = "", val checkedAt: Instant? = null) : Serializable` with `val checked: Boolean get() = checkedBy != null`
  - On `CalendarEvent`: `val kind: EventKind = EventKind.EVENT`, `val checklist: List<ChecklistItem> = emptyList()` (always sorted by `order`, then `id`), and
    `val isChecklist: Boolean`, `val hasChecklist: Boolean` (= `checklist.isNotEmpty()`), `val checklistDone: Int`, `val checklistComplete: Boolean` (non-empty and all checked), `fun lastTicked(): ChecklistItem?` (checked item with newest `checkedAt`; a `null` `checkedAt` counts as newest), `fun nextItemOrder(): Long` (max order + 1, or 0).
  - Top-level `fun itemText(raw: String): String?` — trimmed text, `null` if blank.

- [ ] **Step 1: Write the failing tests** in `ChecklistTest.kt`

```kotlin
class ChecklistTest {
    private val t = Instant.parse("2026-10-23T10:00:00Z")
    private fun item(id: String, by: String? = null, at: Instant? = null, order: Long = 0) =
        ChecklistItem(id = id, text = id, order = order, checkedBy = by, checkedByName = by.orEmpty(), checkedAt = at)
    private val list = CalendarEvent(id = "c", ownerId = "anna", title = "Einkauf", kind = EventKind.CHECKLIST)

    @Test fun `an empty list is not complete`() {
        assertFalse(list.checklistComplete); assertEquals(0, list.checklistDone); assertFalse(list.hasChecklist)
    }
    @Test fun `complete when every item is checked`() {
        val e = list.copy(checklist = listOf(item("a", "anna", t), item("b", "ben", t.plusSeconds(5))))
        assertTrue(e.checklistComplete); assertEquals(2, e.checklistDone)
        assertEquals("ben", e.lastTicked()?.checkedBy)
    }
    @Test fun `pending checkedAt still counts as checked`() {
        val e = list.copy(checklist = listOf(item("a", "anna", t), item("b", "ben", null)))
        assertTrue(e.checklistComplete); assertEquals("ben", e.lastTicked()?.checkedBy)
    }
    @Test fun `next item goes to the bottom`() {
        assertEquals(0L, list.nextItemOrder())
        assertEquals(8L, list.copy(checklist = listOf(item("a", order = 7), item("b", order = 2))).nextItemOrder())
    }
    @Test fun `blank item text is rejected`() {
        assertNull(itemText("   ")); assertEquals("Milch", itemText("  Milch "))
    }
}
```

Add to `RequestStateTest`:

```kotlin
@Test
fun `a different checklist is neither a change notice nor a reopened request`() {
    val ticked = accepted.copy(checklist = listOf(ChecklistItem("a", "Brot", checkedBy = "ben")))
    val saved = ticked.withRequestStateFrom(accepted).withChangeNoticeFrom(accepted)
    assertEquals(RequestStatus.ACCEPTED, saved.requestStatus)
    assertEquals(accepted.changeSeq, saved.changeSeq)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.data.ChecklistTest" --tests "com.familycalendar.data.RequestStateTest"`
Expected: compilation FAIL (unresolved `ChecklistItem`, `EventKind`).

- [ ] **Step 3: Implement the Interfaces above in `data/Models.kt`**

KDoc in the file's style. `withChangeNoticeFrom` / `whenDiffersFrom` stay untouched (they must not look at `checklist`).

- [ ] **Step 4: Run tests to verify they pass** — same command, expected PASS (all tests in both classes).

- [ ] **Step 5: Commit** (after user approval)

```bash
git add app/src/main/java/com/familycalendar/data/Models.kt app/src/test/java/com/familycalendar/data/ChecklistTest.kt app/src/test/java/com/familycalendar/data/RequestStateTest.kt
git commit -m "feat: checklist model on events"
```

---

### Task 2: Firestore mapping

**Files:**
- Modify: `data/Mappers.kt`
- Test: `data/MappersTest.kt` (new)

**Interfaces:**
- Consumes: Task 1 types.
- Produces:
  - `CalendarEvent.toMap()` now includes `"kind" to "checklist"|"event"` and **never** a `"checklist"` key.
  - `fun ChecklistItem.toFirestore(): Map<String, Any?>` — keys `text`, `order`, `checkedBy`, `checkedByName`, `checkedAt` (`Timestamp?`); the id is the map key, not a value.
  - `fun List<ChecklistItem>.toFirestore(): Map<String, Map<String, Any?>>` — keyed by item id.
  - `fun parseChecklist(raw: Any?): List<ChecklistItem>` — accepts the raw `checklist` value; skips entries that aren't maps; `text` default `""`, `order` from `Number` default 0, `checkedAt` from `Timestamp`; result sorted by `order`, then `id`.
  - `toEvent()` sets `kind` (case-insensitive `"checklist"` → CHECKLIST, anything else/missing → EVENT) and `checklist = parseChecklist(get("checklist", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE))`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class MappersTest {
    @Test fun `toMap does not contain checklist`() {
        val e = CalendarEvent(ownerId = "anna", title = "Grillabend", checklist = listOf(ChecklistItem("a", "Kohle")))
        assertFalse("checklist" in e.toMap())
        assertEquals("event", e.toMap()["kind"])
        assertEquals("checklist", e.copy(kind = EventKind.CHECKLIST).toMap()["kind"])
    }
    @Test fun `checklist round-trips through the Firestore shape`() {
        val at = Instant.parse("2026-10-23T10:00:00Z")
        val items = listOf(
            ChecklistItem("b", "Brot", order = 1, checkedBy = "ben", checkedByName = "Ben", checkedAt = at),
            ChecklistItem("a", "Milch", order = 0),
        )
        assertEquals(items.sortedBy { it.order }, parseChecklist(items.toFirestore()))
    }
    @Test fun `malformed checklist entries are skipped`() {
        assertEquals(emptyList<ChecklistItem>(), parseChecklist(null))
        assertEquals(listOf("ok"), parseChecklist(mapOf("x" to "junk", "ok" to mapOf("text" to "Eier"))).map { it.id })
    }
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.data.MappersTest"` — expected FAIL (unresolved `parseChecklist`, `toFirestore`).
- [ ] **Step 3: Implement the Interfaces above in `data/Mappers.kt`.**
- [ ] **Step 4: Run** the same command — expected PASS. Then `./gradlew :app:testDebugUnitTest` — all PASS.
- [ ] **Step 5: Commit** (after user approval): `git commit -m "feat: map checklists and event kind to Firestore"`

---

### Task 3: Write path — repository and rules

**Files:**
- Modify: `data/CalendarRepository.kt`, `firestore.rules`

**Interfaces:**
- Consumes: `ChecklistItem.toFirestore()`, `List<ChecklistItem>.toFirestore()` (Task 2).
- Produces (all non-awaited `Task<Void>`, like the existing event writes):
  - `saveEvent(event)`: new doc (`event.id` blank) → `set(toMap() + ("checklist" to event.checklist.toFirestore()))`; existing doc → `set(toMap(), SetOptions.merge())`.
  - `addItem(event: CalendarEvent, item: ChecklistItem)` → `update(FieldPath.of("checklist", item.id), item.toFirestore())`
  - `renameItem(event: CalendarEvent, itemId: String, text: String)` → `FieldPath.of("checklist", itemId, "text")`
  - `setChecked(event: CalendarEvent, itemId: String, byUid: String?, byName: String)` → updates `checkedBy`, `checkedByName`, and `checkedAt` = `FieldValue.serverTimestamp()` when ticking, `null` when unticking (unticking also sets `checkedByName = ""`), in one `update` call.
  - `removeItem(event: CalendarEvent, itemId: String)` → `FieldPath.of("checklist", itemId)` to `FieldValue.delete()`.
  - `fun newItemId(): String` — `UUID.randomUUID().toString()`.
  - Use `FieldPath.of(...)`, never dotted strings (ids contain `-`).

- [ ] **Step 1: Add the viewer rule to `firestore.rules`** inside `match /events/{eventId}`, after the requested-person rule:

```
        // Shared checklists: anyone who can see the calendar ticks and edits items; nothing else changes.
        allow update: if isViewer()
          && request.resource.data.diff(resource.data).affectedKeys().hasOnly(['checklist'])
          && request.resource.data.checklist is map;
```

Also update the data-layout comment at the top to mention `checklist` and `kind`.

- [ ] **Step 2: Compile the rules without deploying**

Run: `firebase deploy --only firestore:rules --dry-run` (use `npx firebase-tools` in place of `firebase` if the CLI isn't on PATH)
Expected: "rules file firestore.rules compiled successfully", no deploy.

- [ ] **Step 3: Implement the repository Interfaces above.**

- [ ] **Step 4: Build and run all unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 5: Commit** (after user approval): `git commit -m "feat: checklist writes and viewer rule"`

Deploying the rules (`firebase deploy --only firestore:rules`) is outward-facing: do it only when the user approves, at the end of Task 7.

---

### Task 4: Checklist notifications

**Files:**
- Create: `notifications/ChecklistNotifier.kt`
- Modify: `notifications/ReminderSyncWorker.kt`, `FamilyCalendarApp.kt`, `MainViewModel.kt`
- Test: `notifications/ChecklistTrackerTest.kt` (new)

**Interfaces:**
- Consumes: `CalendarEvent.isChecklist`, `checklistComplete`, `lastTicked()` (Task 1).
- Produces:
  - `data class ChecklistSeen(val initialized: Boolean = false, val owners: Set<String> = emptySet(), val complete: Map<String, Boolean> = emptyMap())` — `complete` keyed by `CalendarEvent.key`.
  - `data class ChecklistDiff(val created: List<CalendarEvent>, val completed: List<CalendarEvent>, val seen: ChecklistSeen)`
  - `object ChecklistTracker`:
    - `fun diff(seen: ChecklistSeen, owners: Collection<String>, events: List<CalendarEvent>, myUid: String, muted: Set<String>): ChecklistDiff`
    - `fun createdMessage(event: CalendarEvent, zone: ZoneId): String`
    - `fun completedMessage(event: CalendarEvent): String`
  - `object ChecklistNotifier { fun createChannel(context: Context); fun notify(context: Context, myUid: String, owners: Collection<String>, events: List<CalendarEvent>) }`

`diff` rules (the algorithm the tests pin):
- Considered lists: `isChecklist && !cancelled && ownerId in owners`.
- New `seen` = `ChecklistSeen(initialized = true, owners = owners.toSet(), complete = considered.associate { it.key to it.checklistComplete })` — always returned, notified or not.
- If `!seen.initialized`: notify nothing.
- `created`: key not in `seen.complete`, `ownerId in seen.owners` (calendar already known), `ownerId != myUid`, `ownerId !in muted`.
- `completed`: `seen.complete[key] == false`, now complete, `lastTicked()?.checkedBy != myUid`, `ownerId !in muted`.

- [ ] **Step 1: Write the failing tests** in `ChecklistTrackerTest.kt`

Fixture: `zone = ZoneId.of("Europe/Vienna")`; `list = CalendarEvent(id = "c", ownerId = "anna", ownerName = "Anna", title = "Einkauf", kind = EventKind.CHECKLIST, allDay = true, start = Instant.parse("2026-10-22T22:00:00Z"))` (Fri Oct 23 local); `known = ChecklistSeen(true, setOf("anna", "me"), emptyMap())`; helper `items(vararg by: String?)` builds items `"i0"`, `"i1"`… with `checkedAt` increasing per index.

```kotlin
@Test fun `first run only remembers`() {
    val d = ChecklistTracker.diff(ChecklistSeen(), listOf("anna", "me"), listOf(list), "me", emptySet())
    assertTrue(d.created.isEmpty()); assertEquals(mapOf("anna/c" to false), d.seen.complete); assertTrue(d.seen.initialized)
}
@Test fun `a new list in a known calendar is announced`() {
    val d = ChecklistTracker.diff(known, listOf("anna", "me"), listOf(list), "me", emptySet())
    assertEquals(listOf(list), d.created)
    assertEquals("Anna created checklist \"Einkauf\" for Fri, Oct 23", ChecklistTracker.createdMessage(list, zone))
}
@Test fun `checklists of a newly visible calendar are only remembered`() {
    val carla = list.copy(id = "x", ownerId = "carla")
    val d = ChecklistTracker.diff(known, listOf("anna", "me", "carla"), listOf(carla), "me", emptySet())
    assertTrue(d.created.isEmpty()); assertTrue("carla" in d.seen.owners)
}
@Test fun `my own new list and muted calendars stay quiet`() {
    val mine = list.copy(ownerId = "me")
    assertTrue(ChecklistTracker.diff(known, listOf("anna", "me"), listOf(mine), "me", emptySet()).created.isEmpty())
    assertTrue(ChecklistTracker.diff(known, listOf("anna", "me"), listOf(list), "me", setOf("anna")).created.isEmpty())
}
@Test fun `completion by someone else notifies once`() {
    val open = known.copy(complete = mapOf("anna/c" to false))
    val done = list.copy(checklist = items("me", "martin")) // last tick: martin
    val d = ChecklistTracker.diff(open, listOf("anna", "me"), listOf(done), "me", emptySet())
    assertEquals(listOf(done), d.completed)
    assertEquals("\"Einkauf\" is done. The last item was ticked by martin", ChecklistTracker.completedMessage(done))
    assertTrue(ChecklistTracker.diff(d.seen, listOf("anna", "me"), listOf(done), "me", emptySet()).completed.isEmpty())
}
@Test fun `completing it myself stays quiet`() {
    val open = known.copy(complete = mapOf("anna/c" to false))
    assertTrue(ChecklistTracker.diff(open, listOf("anna", "me"), listOf(list.copy(checklist = items("martin", "me"))), "me", emptySet()).completed.isEmpty())
}
@Test fun `emptied then completed notifies`() {
    val emptied = ChecklistTracker.diff(known.copy(complete = mapOf("anna/c" to true)), listOf("anna", "me"), listOf(list), "me", emptySet()).seen
    val d = ChecklistTracker.diff(emptied, listOf("anna", "me"), listOf(list.copy(checklist = items("martin"))), "me", emptySet())
    assertEquals(1, d.completed.size)
}
@Test fun `attached checklists never notify`() {
    val event = list.copy(kind = EventKind.EVENT, checklist = items("martin"))
    val d = ChecklistTracker.diff(known.copy(complete = mapOf("anna/c" to false)), listOf("anna", "me"), listOf(event), "me", emptySet())
    assertTrue(d.created.isEmpty() && d.completed.isEmpty())
}
```

(`checkedByName` in `items()` equals the uid, hence "martin" in the message.)

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.notifications.ChecklistTrackerTest"` — expected FAIL (unresolved `ChecklistTracker`).

- [ ] **Step 3: Implement `ChecklistTracker` and `ChecklistNotifier`** in `notifications/ChecklistNotifier.kt`.
  - Completed message name fallback: `lastTicked()?.checkedByName` blank → `"someone"`. Created: `ownerName` blank → `"Someone"`. Date: event start in `zone`, `DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)`.
  - Channel id `"shared_checklists"`, name `"Shared checklists"`, description `"When someone creates or finishes a shared checklist"`, `IMPORTANCE_DEFAULT`.
  - Seen state in SharedPreferences `"checklist_prefs"`: boolean `"initialized"`, string set `"owners"`, string set `"complete"` with entries `"<eventKey>|1"` / `"<eventKey>|0"` (split on last `|`, as `ChangeNotifier.load`).
  - Muted owners from `NotificationPrefs(context).mutedOwners()`.
  - Post like `ChangeNotifier.notifyGrown`: title = message, body `"Open Family Calendar to see the list."`, open-app intent, ids `("checklist-created/" + key).hashCode()` / `("checklist-done/" + key).hashCode()`. Without notification permission still save the seen state.

- [ ] **Step 4: Run** the tracker tests — expected PASS.

- [ ] **Step 5: Wire it up**
  - `FamilyCalendarApp`: `ChecklistNotifier.createChannel(this)` next to the others.
  - `ReminderSyncWorker.doWork`: `ChecklistNotifier.notify(applicationContext, user.uid, owners.distinct(), all)` after `notifyAnswers`.
  - `MainViewModel`: the owners must come from the same emission as the events, otherwise a just-accepted calendar is marked "seen" before its lists load and they are announced later. Introduce `private data class Visible(val owners: List<String>, val events: List<CalendarEvent>)` and `private val visible: StateFlow<Visible?>` built exactly like today's `allEvents` (emitting `Visible(owners.distinct(), perCalendar.flatten())`, and `Visible(emptyList(), emptyList())` when signed out); derive `allEvents = visible.map { it?.events }`. In the existing `combine(user, allEvents…)` collector, switch to `visible.filterNotNull()` and call `ChecklistNotifier.notify(getApplication(), u.uid, v.owners, v.events)` next to `notifyAnswers`.

- [ ] **Step 6: Build and run all tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest`, expected BUILD SUCCESSFUL, all PASS.

- [ ] **Step 7: Commit** (after user approval): `git commit -m "feat: notify on new and completed shared checklists"`

---

### Task 5: Calendar rendering

**Files:**
- Modify: `ui/calendar/CalendarCommon.kt`, `ui/calendar/ScheduleView.kt`
- Test: `ui/calendar/ChecklistLookTest.kt` (new)

**Interfaces:**
- Consumes: `isChecklist`, `hasChecklist`, `checklistDone` (Task 1); `paletteColor`, `CalendarPalette` (`ui/theme/Theme.kt`).
- Produces:
  - `fun displayTitle(event: CalendarEvent): String` — standalone: `"☑ ${title} ${done}/${total}"`; attached: `"${title} ☑"`; otherwise `title`.
  - `fun Map<String, Int>.checkColor(uid: String): CalendarColor` — `this[uid]` if present, else `paletteColor(uid.hashCode().mod(CalendarPalette.size))`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class ChecklistLookTest {
    private val items = listOf(ChecklistItem("a", "Milch", checkedBy = "ben"), ChecklistItem("b", "Brot"), ChecklistItem("c", "Eier"))
    @Test fun `standalone list shows progress`() =
        assertEquals("☑ Einkauf 1/3", displayTitle(CalendarEvent(title = "Einkauf", kind = EventKind.CHECKLIST, checklist = items)))
    @Test fun `attached list gets a mark`() =
        assertEquals("Grillabend ☑", displayTitle(CalendarEvent(title = "Grillabend", checklist = items)))
    @Test fun `plain events are unchanged`() = assertEquals("Dinner", displayTitle(CalendarEvent(title = "Dinner")))
    @Test fun `check color follows the calendar color, else is stable per uid`() {
        assertEquals(paletteColor(4), mapOf("ben" to 4).checkColor("ben"))
        assertEquals(emptyMap<String, Int>().checkColor("zoe"), emptyMap<String, Int>().checkColor("zoe"))
    }
}
```

- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.ui.calendar.ChecklistLookTest"` — expected FAIL.
- [ ] **Step 3: Implement both functions in `CalendarCommon.kt`**; use `displayTitle(occurrence.event)` in place of `occurrence.event.title` in `EventChip`'s label and in `ScheduleRow`'s title. Standalone lists are all-day, so they already land in the all-day lane of every view.
- [ ] **Step 4: Run** all unit tests — expected PASS.
- [ ] **Step 5: Commit** (after user approval): `git commit -m "feat: checklist marks and progress on calendar chips"`

---

### Task 6: Checklist section on the event screen

**Files:**
- Create: `ui/event/ChecklistSection.kt`
- Modify: `ui/event/EventScreen.kt`, `MainViewModel.kt`

**Interfaces:**
- Consumes: repository item methods + `newItemId()` (Task 3); `itemText`, `nextItemOrder` (Task 1); `checkColor` (Task 5).
- Produces:
  - `class ChecklistActions(val onToggle: (ChecklistItem) -> Unit, val onAdd: (String) -> Unit, val onRename: (ChecklistItem, String) -> Unit, val onRemove: (ChecklistItem) -> Unit)`
  - `@Composable fun ChecklistSection(items: List<ChecklistItem>, colors: Map<String, Int>, actions: ChecklistActions, modifier: Modifier = Modifier)` — header "Checklist", one row per item: checkbox (checked fill = `colors.checkColor(item.checkedBy!!).color`), editable text (commit rename on focus loss / IME Done), the ticker's name in the same color under checked items, a delete icon; at the bottom an "+ Add item" text field (IME Done adds and keeps focus).
  - `@Composable fun rememberDraftChecklist(myUid: String, myName: String): Pair<List<ChecklistItem>, ChecklistActions>` — in-memory list (`rememberSaveable`, `ChecklistItem` is `Serializable`) for unsaved events; ticks use `Instant.now()`.
  - `MainViewModel`: `fun addItem(event: CalendarEvent, text: String)`, `fun renameItem(event: CalendarEvent, item: ChecklistItem, text: String)`, `fun toggleItem(event: CalendarEvent, item: ChecklistItem)`, `fun removeItem(event: CalendarEvent, item: ChecklistItem)`, and `fun liveChecklistActions(event: CalendarEvent): ChecklistActions`. Add/rename go through `itemText()` (blank add ignored, blank rename keeps the old text); new items get `nextItemOrder()`; toggle ticks with the user's uid and `nameOrEmail()`, unticks with `null`. Failures set `_message` ("Could not update the checklist: …").

- [ ] **Step 1: Implement `ChecklistSection.kt` and the view-model methods.**

- [ ] **Step 2: Integrate into `EventScreen.kt`**
  - Owner, existing event: below Notes, if `existing.hasChecklist` or the user tapped an "+ Add checklist" `OutlinedButton`, show `ChecklistSection(existing.checklist, colors, viewModel.liveChecklistActions(existing))`. Items come from the live `existing`, not from `initial`, so others' ticks show up while editing.
  - Owner, new event: the same button shows the section backed by `rememberDraftChecklist`; Save passes `checklist = draftItems` in the copied event.
  - Viewer (`EventDetails`): if `event.hasChecklist`, show the live section with `liveChecklistActions(event)`. The "only X can change this event" line stays.

- [ ] **Step 3: Build and run all tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest`, expected BUILD SUCCESSFUL, all PASS.

- [ ] **Step 4: Check on device** (`./gradlew :app:installDebug`, two emulators signed in as owner and viewer who share): owner adds a checklist to an event → viewer sees it live, ticks an item → owner sees the tick in the viewer's calendar color with their name; owner then edits the title and saves → the viewer's tick is still there; on a requested event, a viewer tick shows no "changed" notice and the request state is unchanged.

- [ ] **Step 5: Commit** (after user approval): `git commit -m "feat: shared checklist section on events"`

---

### Task 7: Standalone checklists

**Files:**
- Modify: `ui/calendar/CalendarScreen.kt`, `ui/Navigation.kt`, `ui/event/EventScreen.kt`

**Interfaces:**
- Consumes: `ChecklistSection`, `rememberDraftChecklist`, `liveChecklistActions` (Task 6); `EventKind` (Task 1).
- Produces:
  - `CalendarScreen` param becomes `onNewEvent: (start: LocalDateTime, allDay: Boolean, kind: EventKind) -> Unit`; grid slot taps pass `EventKind.EVENT`.
  - Route `EVENT = "event?key={key}&occ={occ}&start={start}&allDay={allDay}&kind={kind}"`, `Routes.newEvent(start, allDay, kind)` appends `&kind=${kind.name}`; `EventScreen` gets `newKind: EventKind`.

- [ ] **Step 1: "+" offers the choice.** The FAB opens a `DropdownMenu` with "Event" and "Checklist"; "Event" keeps today's start logic, "Checklist" calls `onNewEvent(LocalDate.now().atStartOfDay(), true, EventKind.CHECKLIST)` (due today by default, regardless of the viewed date).

- [ ] **Step 2: Checklist form in `EventScreen.kt`.** `private fun ChecklistEditor(initial: CalendarEvent, items: List<ChecklistItem>, actions: ChecklistActions, colors: Map<String, Int>, onSave: (CalendarEvent) -> Unit)` with a Title field, a "Due" date button (`DateTimeRow(showTime = false)`), the `ChecklistSection`, and Save (enabled when the title isn't blank). Save writes `kind = CHECKLIST`, `allDay = true`, start = due date at start of day, end = next day's start, `reminderMinutes = NO_REMINDER`, `recurrence = Recurrence()`, `requestedUid = null`, and for a new list `checklist = draftItems`. `EventScreen` routes `existing?.kind ?: newKind == CHECKLIST` (owner) to `ChecklistEditor` — with live items/actions for an existing list, draft ones for a new list.
  - Top bar: "New checklist" / "Edit checklist" / "${ownerName}'s checklist".
  - Viewer `EventDetails` for a checklist: "Due <date>" instead of the time line, no reminder line, live section.
  - `DeleteDialog`: title "Delete checklist?" for checklists, same body text otherwise.

- [ ] **Step 3: Build and run all tests** — `./gradlew :app:assembleDebug :app:testDebugUnitTest`, expected BUILD SUCCESSFUL, all PASS.

- [ ] **Step 4: Ask the user to approve deploying the rules**, then run `firebase deploy --only firestore:rules`. Expected: "Deploy complete!".

- [ ] **Step 5: Check on device** (both emulators): owner creates "Einkauf" for today with 3 items → it shows as `☑ Einkauf 0/3` in month, week, 3-day, day and schedule views; the viewer gets `<owner> created checklist "Einkauf" for <date>` (after the app runs or the sync worker fires); the viewer ticks all three items → owner gets `"Einkauf" is done. The last item was ticked by <viewer>`, the viewer gets no notice; the chip reads `☑ Einkauf 3/3`; tomorrow's view doesn't show it. Viewer cannot change title or date. Reinstalling the app on the viewer fires no "created" notices.

- [ ] **Step 6: Commit** (after user approval): `git commit -m "feat: standalone shared checklists"`
