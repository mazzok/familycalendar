# Birthdays Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optional account birthdays and private, per-person shareable entered birthdays, shown as yearly 🎂 chips in a birthday color, with a same-day 08:00 notification.

**Architecture:** Birthdays live in a new subcollection `calendars/{ownerId}/birthdays/{id}` (account birthday = id `self`), guarded by rules that let viewers read `self` plus documents whose `sharedWith` contains them. They are **not** events: the app turns them into synthetic all-day `Occurrence`s (`EventKind.BIRTHDAY`) only for the visible date range in `CalendarScreen`, so `viewModel.events`, reminders and change/checklist notifiers never see them. Notifications come from a pure `BirthdayPlanner` and a `BirthdayScheduler` that arms one AlarmManager alarm per day with birthdays, the same way `ReminderScheduler` works.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Firebase Firestore (Android SDK), WorkManager, AlarmManager, JUnit 4 (JVM unit tests only).

**Spec:** `docs/superpowers/specs/2026-10-22-birthdays-design.md`

## Global Constraints

- Storage: `calendars/{ownerId}/birthdays/{id}` with `name: string`, `ownerName: string`, `month: int 1–12`, `day: int 1–31`, `year: int|null`, `sharedWith: string[]` (viewer uids). Account birthday: fixed id `self`, `sharedWith` always `[]`. **Never** write a birth date into `users/{uid}`.
- Rules: owner reads/writes own `birthdays`; an accepted viewer (`isViewer()`) reads `self` and any doc with `request.auth.uid in sharedWith`; viewers never write.
- Viewer reads: `self` by document get; others by `whereArrayContains("sharedWith", myUid)` (rules are not filters).
- Feb 29 shows on Feb 28 in non-leap years. Year optional everywhere (registration, "My birthday", entered birthdays).
- Chip and title copy: `🎂 <name>` or `🎂 <name> (<age>)`, age = the age turned in that year.
- Birthday color: `CalendarColor("Birthday", Color(0xFFEC407A))`, regardless of calendar color.
- Notification at 08:00 local time on the day; channel id `birthdays`, name `Birthdays`, description `On the day someone has a birthday`. Copy: one → `🎂 Today is Lisa's birthday` / `🎂 Today is Lisa's birthday (40)`; several → `🎂 Birthdays today: Lisa (40), Oma`.
- Notified: all visible birthdays except my own `self`, except owners in `NotificationPrefs.mutedOwners()`, and nothing if the per-device switch `Birthday notifications` (default on) is off.
- `hiddenOwners` hides that owner's birthdays from the calendar view only.
- English UI strings throughout ("Birthday", "Birthdays", "My birthday", "Share with …", "from <name>", "Birthday notifications").
- `invite` removes the invitee's uid from `sharedWith` of all my birthdays before creating the invitation; `revoke` removes it after deleting the viewer doc.
- **Plan decision (spec silent):** notifications look 14 days ahead (`ReminderPlanner.HORIZON`) and only arm future instants; a sync after 08:00 on the day itself does not fire late.
- **Plan decision:** the account birthday's `name`/`ownerName` is rewritten from the display name whenever "My birthday" is saved; no other sync.
- **Plan decision:** viewers of a shared birthday see it read-only; the "Share with …" list only offers **accepted** viewers.
- Per `~/.claude/CLAUDE.md`: every "Commit" step and the rules deploy need explicit user approval first.

## Review Focus

1. **Feb 29 birthdays and invalid dates (31.02., 29.02. with a non-leap year)** — shown on Feb 28 in non-leap years; invalid input rejected. Pinned in Task 1 (`feb 29 falls on feb 28 in non-leap years`, `invalid dates are rejected`).
2. **A viewer who is removed and invited again** — must not see previously shared birthdays. Pinned by repository order in Task 4 (unshare before invite) and checked manually in Task 9.
3. **Several birthdays on one day, one of them mine, one from a muted calendar** — exactly one notification naming only the others. Pinned in Task 3 (`own and muted birthdays are left out`, `several birthdays share one notice`).
4. **Birthday chips reaching reminders or change notifiers** — must never happen. Pinned by keeping them out of `viewModel.events` (Task 5) and `synthetic birthday events have no reminder` (Task 2).
5. **Age when the year is unknown or the occurrence is before the birth year** — no age; no occurrence before the birth year. Pinned in Task 1 (`no age without year`, `no birthday before birth`).

---

## File Structure

All source paths are relative to `app/src/main/java/com/familycalendar/`; tests to `app/src/test/java/com/familycalendar/`.

- `data/Birthday.kt` (new) — `Birthday` model, date/age logic, validation, label.
- `data/Models.kt` — `EventKind.BIRTHDAY`.
- `data/Mappers.kt` — birthday ↔ Firestore map.
- `data/CalendarRepository.kt` — birthday reads/writes, unshare on invite.
- `data/AuthRepository.kt` — register with optional birthday.
- `firestore.rules` (repo root) — `birthdays` match block.
- `ui/calendar/BirthdayOccurrences.kt` (new) — birthdays → synthetic occurrences.
- `ui/calendar/CalendarCommon.kt`, `ScheduleView.kt`, `TimeGridView.kt`, `ui/theme/Theme.kt` — color + label.
- `notifications/BirthdayPlanner.kt` (new, pure), `notifications/BirthdayScheduler.kt` (new, AlarmManager + receiver), `NotificationPrefs.kt`, `ReminderSyncWorker.kt`, `FamilyCalendarApp.kt`, `AndroidManifest.xml`.
- `MainViewModel.kt` — birthday flows and actions.
- `ui/birthday/BirthdayListScreen.kt`, `ui/birthday/BirthdayEditScreen.kt` (new), `ui/Navigation.kt`, `ui/calendar/CalendarScreen.kt`, `ui/sharing/SharingScreen.kt`, `ui/auth/AuthScreen.kt`.

---

### Task 1: Birthday model and date logic

**Files:**
- Create: `data/Birthday.kt`
- Test: `data/BirthdayTest.kt` (new)

**Interfaces:**
- Produces:
  - `const val SELF_BIRTHDAY_ID = "self"`
  - `data class Birthday(val id: String = "", val ownerId: String = "", val ownerName: String = "", val name: String = "", val month: Int = 1, val day: Int = 1, val year: Int? = null, val sharedWith: Set<String> = emptySet())` with `val isAccount: Boolean get() = id == SELF_BIRTHDAY_ID`
  - `fun Birthday.dateIn(year: Int): LocalDate?` — `null` if `year < this.year`; Feb 29 → Feb 28 in non-leap years.
  - `fun Birthday.ageIn(year: Int): Int?` — `year - this.year`, `null` without year.
  - `fun Birthday.label(year: Int): String` — `🎂 Lisa` / `🎂 Lisa (40)`.
  - `fun validBirthDate(day: Int, month: Int, year: Int?, today: LocalDate): Boolean` — month 1–12; day valid for that month (Feb 29 allowed without year, with year only in leap years); year, if given, in `1900..today.year` and the date not after `today`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class BirthdayTest {
    private val lisa = Birthday(id = "b1", name = "Lisa", month = 3, day = 14, year = 1986)
    private val leap = Birthday(id = "b2", name = "Leo", month = 2, day = 29, year = 2004)
    private val today = LocalDate.of(2026, 10, 22)

    @Test fun `date and age in a year`() {
        assertEquals(LocalDate.of(2026, 3, 14), lisa.dateIn(2026)); assertEquals(40, lisa.ageIn(2026))
        assertEquals("🎂 Lisa (40)", lisa.label(2026))
    }
    @Test fun `no age without year`() {
        val oma = lisa.copy(name = "Oma", year = null)
        assertNull(oma.ageIn(2026)); assertEquals("🎂 Oma", oma.label(2026)); assertEquals(LocalDate.of(1900, 3, 14), oma.dateIn(1900))
    }
    @Test fun `no birthday before birth`() { assertNull(lisa.dateIn(1985)); assertEquals(0, lisa.ageIn(1986)) }
    @Test fun `feb 29 falls on feb 28 in non-leap years`() {
        assertEquals(LocalDate.of(2026, 2, 28), leap.dateIn(2026)); assertEquals(LocalDate.of(2028, 2, 29), leap.dateIn(2028))
    }
    @Test fun `invalid dates are rejected`() {
        assertFalse(validBirthDate(31, 2, null, today)); assertFalse(validBirthDate(29, 2, 2023, today))
        assertTrue(validBirthDate(29, 2, null, today)); assertTrue(validBirthDate(29, 2, 2024, today))
        assertFalse(validBirthDate(1, 13, null, today)); assertFalse(validBirthDate(1, 1, 1899, today))
        assertFalse(validBirthDate(23, 10, 2026, today)); assertTrue(validBirthDate(22, 10, 2026, today))
    }
    @Test fun `self is the account birthday`() { assertTrue(Birthday(id = SELF_BIRTHDAY_ID).isAccount); assertFalse(lisa.isAccount) }
}
```

- [ ] **Step 2: Run, expect FAIL (unresolved references)**
  Run: `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.data.BirthdayTest"`
- [ ] **Step 3: Implement `data/Birthday.kt`** per the Interfaces block (`java.time.Year.isLeap`, `YearMonth.lengthOfMonth`).
- [ ] **Step 4: Run, expect PASS** (same command).
- [ ] **Step 5: Commit** (after user approval) — `feat: birthday model with leap-day and age rules`

---

### Task 2: Birthdays as calendar occurrences, color and label

**Files:**
- Modify: `data/Models.kt` (`enum class EventKind { EVENT, CHECKLIST, BIRTHDAY }`, `val isBirthday: Boolean get() = kind == EventKind.BIRTHDAY` on `CalendarEvent`)
- Create: `ui/calendar/BirthdayOccurrences.kt`
- Modify: `ui/theme/Theme.kt`, `ui/calendar/CalendarCommon.kt`, `ui/calendar/ScheduleView.kt:113`, `ui/calendar/TimeGridView.kt:218`
- Test: `ui/calendar/BirthdayOccurrencesTest.kt` (new)

**Interfaces:**
- Consumes: Task 1 `Birthday`, `dateIn`, `label`.
- Produces:
  - `val BirthdayColor = CalendarColor("Birthday", Color(0xFFEC407A))` in `Theme.kt`.
  - `fun birthdayOccurrences(birthdays: List<Birthday>, range: ClosedRange<LocalDate>, zone: ZoneId): List<Occurrence>` — one per birthday per year whose date falls in `range`; synthetic `CalendarEvent(id = b.id, ownerId = b.ownerId, ownerName = b.ownerName, title = b.label(year), start = date.atStartOfDay(zone), end = date.plusDays(1).atStartOfDay(zone), allDay = true, reminderMinutes = NO_REMINDER, kind = EventKind.BIRTHDAY)`.
  - `fun Map<String, Int>.colorFor(event: CalendarEvent): CalendarColor` in `CalendarCommon.kt` — `BirthdayColor` for birthdays, else `colorFor(event.ownerId)`. Replace the three `colors.colorFor(occ.event.ownerId)` / `occurrence.event.ownerId` look-ups in `EventChip`, `ScheduleView`, `TimeGridView` with it.
  - `displayTitle` / `chipLabel` need no change (title already carries `🎂`).

- [ ] **Step 1: Write the failing tests**

```kotlin
class BirthdayOccurrencesTest {
    private val zone = ZoneOffset.UTC
    private val lisa = Birthday(id = "b1", ownerId = "anna", name = "Lisa", month = 1, day = 2, year = 1986)

    @Test fun `range across new year yields each year's date`() {
        val occ = birthdayOccurrences(listOf(lisa, lisa.copy(id = "b2", name = "Max", month = 12, day = 30, year = null)),
            LocalDate.of(2026, 12, 28)..LocalDate.of(2027, 1, 5), zone)
        assertEquals(listOf("🎂 Max", "🎂 Lisa (41)"), occ.sortedBy { it.start }.map { it.event.title })
    }
    @Test fun `synthetic birthday events have no reminder`() {
        val e = birthdayOccurrences(listOf(lisa), LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 1, 31), zone).single().event
        assertEquals(NO_REMINDER, e.reminderMinutes); assertTrue(e.allDay); assertTrue(e.isBirthday); assertEquals("anna/b1", e.key)
    }
    @Test fun `outside the range nothing`() {
        assertTrue(birthdayOccurrences(listOf(lisa), LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 12, 31), zone).isEmpty())
    }
    @Test fun `birthday color wins over calendar color`() {
        val colors = mapOf("anna" to 2)
        val e = CalendarEvent(ownerId = "anna", kind = EventKind.BIRTHDAY)
        assertEquals(BirthdayColor, colors.colorFor(e)); assertEquals(paletteColor(2), colors.colorFor(e.copy(kind = EventKind.EVENT)))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.ui.calendar.BirthdayOccurrencesTest"`
- [ ] **Step 3: Implement** per Interfaces; check `Mappers.toEvent` still maps unknown kinds to `EVENT` (BIRTHDAY is never stored).
- [ ] **Step 4: Run full unit suite, expect PASS** — `./gradlew :app:testDebugUnitTest`
- [ ] **Step 5: Commit** (after approval) — `feat: render birthdays as colored cake chips`

---

### Task 3: Birthday notification planner

**Files:**
- Create: `notifications/BirthdayPlanner.kt`
- Test: `notifications/BirthdayPlannerTest.kt` (new)

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `data class BirthdayNotice(val date: LocalDate, val at: Instant, val text: String)` with `val key: String get() = "birthday/$date"`
  - `object BirthdayPlanner { fun plan(birthdays: List<Birthday>, now: Instant, myUid: String?, mutedOwners: Set<String>, enabled: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<BirthdayNotice>; fun text(birthdays: List<Birthday>, year: Int): String }`
  - Rules: nothing when `!enabled`; drop `b.isAccount && b.ownerId == myUid` and `b.ownerId in mutedOwners`; one notice per date at `date.atTime(8, 0)` in `zone`; only `at` in `(now, now + ReminderPlanner.HORIZON)`; names sorted by name; same person appearing twice (account + entered) is not merged.

- [ ] **Step 1: Write the failing tests**

```kotlin
class BirthdayPlannerTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private fun b(id: String, name: String, day: Int, owner: String = "me", year: Int? = null) =
        Birthday(id = id, ownerId = owner, name = name, month = 10, day = day, year = year)
    private fun plan(list: List<Birthday>, muted: Set<String> = emptySet(), enabled: Boolean = true) =
        BirthdayPlanner.plan(list, now, "me", muted, enabled, zone)

    @Test fun `notice at eight on the day`() {
        val n = plan(listOf(b("1", "Lisa", 22, year = 1986))).single()
        assertEquals(Instant.parse("2026-10-22T08:00:00Z"), n.at); assertEquals("🎂 Today is Lisa's birthday (40)", n.text)
    }
    @Test fun `several birthdays share one notice`() {
        assertEquals("🎂 Birthdays today: Lisa (40), Oma", plan(listOf(b("2", "Oma", 22), b("1", "Lisa", 22, year = 1986))).single().text)
    }
    @Test fun `own and muted birthdays are left out`() {
        val list = listOf(b(SELF_BIRTHDAY_ID, "Me", 22), b(SELF_BIRTHDAY_ID, "Ben", 22, owner = "ben"), b("x", "Max", 22, owner = "muted"))
        assertEquals("🎂 Today is Ben's birthday", plan(list, muted = setOf("muted")).single().text)
    }
    @Test fun `today after eight and beyond the horizon are not planned`() {
        assertTrue(plan(listOf(b("1", "Lisa", 20))).isEmpty())
        assertTrue(plan(listOf(Birthday(id = "1", ownerId = "me", name = "X", month = 12, day = 24))).isEmpty())
    }
    @Test fun `switched off plans nothing`() { assertTrue(plan(listOf(b("1", "Lisa", 22)), enabled = false).isEmpty()) }
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.notifications.BirthdayPlannerTest"`
- [ ] **Step 3: Implement** — iterate days `today..today+15` and use `dateIn` (covers Feb 28 / year boundary).
- [ ] **Step 4: Run, expect PASS**.
- [ ] **Step 5: Commit** (after approval) — `feat: plan same-day birthday notifications`

---

### Task 4: Firestore storage, rules and repository

**Files:**
- Modify: `data/Mappers.kt`, `data/CalendarRepository.kt`, `data/AuthRepository.kt`, `firestore.rules` (repo root; also update its data-layout header comment)
- Test: `data/MappersTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces (Mappers):
  - `fun Birthday.toMap(): Map<String, Any?>` — keys `name, ownerName, month, day, year, sharedWith` (`sharedWith` sorted list).
  - `fun parseBirthday(id: String, ownerId: String, data: Map<String, Any?>): Birthday?` — `null` if `name`, `month` or `day` missing; `DocumentSnapshot.toBirthday()` delegates to it with `reference.parent.parent!!.id` as owner.
- Produces (CalendarRepository):
  - `fun observeOwnBirthdays(ownerId: String): Flow<List<Birthday>>` — whole subcollection.
  - `fun observeBirthdaysSharedWith(ownerId: String, myUid: String): Flow<List<Birthday>>` — `self` doc listener combined with `whereArrayContains("sharedWith", myUid)`; errors → empty list (like `observeEvents`).
  - `suspend fun fetchOwnBirthdays(ownerId: String): List<Birthday>`, `suspend fun fetchBirthdaysSharedWith(ownerId: String, myUid: String): List<Birthday>`.
  - `fun saveBirthday(birthday: Birthday): Task<Void>` — blank id → new doc id; not awaited (same reasoning as `saveEvent`).
  - `fun deleteBirthday(birthday: Birthday): Task<Void>`.
  - `fun setBirthdayShared(ownerId: String, birthdayId: String, viewerUid: String, shared: Boolean): Task<Void>` — `arrayUnion` / `arrayRemove`.
  - `suspend fun unshareBirthdays(ownerId: String, viewerUid: String)` — query `whereArrayContains("sharedWith", viewerUid)`, batch `arrayRemove`.
  - In private `invite(...)`: call `unshareBirthdays(ownerId, match.id)` before writing the viewer doc.
- Produces (AuthRepository): `suspend fun register(name: String, email: String, password: String, birthday: Birthday?)` — when non-null, after `writeProfile`, write `calendars/{uid}/birthdays/self` with `name`/`ownerName` = trimmed name (via `CalendarRepository().saveBirthday(birthday.copy(id = SELF_BIRTHDAY_ID, ownerId = uid, ...))`, not awaited).
- Rules (inside `match /calendars/{ownerId}`):

```
match /birthdays/{birthdayId} {
  function validBirthday() {
    let b = request.resource.data;
    return b.name is string && b.month is int && b.month >= 1 && b.month <= 12
      && b.day is int && b.day >= 1 && b.day <= 31 && b.sharedWith is list
      && (birthdayId != 'self' || b.sharedWith.size() == 0);
  }
  allow read: if isOwner()
    || (isViewer() && (birthdayId == 'self' || request.auth.uid in resource.data.get('sharedWith', [])));
  allow create, update: if isOwner() && validBirthday();
  allow delete: if isOwner();
}
```

- [ ] **Step 1: Write the failing mapper tests**

```kotlin
@Test fun `birthday round-trips through the Firestore shape`() {
    val b = Birthday(id = "b1", ownerId = "anna", ownerName = "Anna", name = "Oma", month = 2, day = 29, year = null, sharedWith = setOf("ben", "cara"))
    assertEquals(b, parseBirthday("b1", "anna", b.toMap()))
    assertEquals(listOf("ben", "cara"), b.toMap()["sharedWith"])
}
@Test fun `birthday without day is skipped`() {
    assertNull(parseBirthday("b1", "anna", mapOf("name" to "Oma", "month" to 2L)))
}
```

- [ ] **Step 2: Run, expect FAIL** — `./gradlew :app:testDebugUnitTest --tests "com.familycalendar.data.MappersTest"`
- [ ] **Step 3: Implement mappers, repository methods, `register` overload, rules** (Firestore returns numbers as `Long`; read via `(x as? Number)?.toInt()`).
- [ ] **Step 4: Run full suite + build, expect PASS** — `./gradlew :app:testDebugUnitTest :app:assembleDebug`
- [ ] **Step 5: Commit** (after approval) — `feat: store birthdays with per-viewer sharing rules`

---

### Task 5: View model, scheduler and background sync

**Files:**
- Create: `notifications/BirthdayScheduler.kt` (`object BirthdayScheduler` + `class BirthdayReceiver : BroadcastReceiver`)
- Modify: `notifications/NotificationPrefs.kt`, `notifications/ReminderSyncWorker.kt`, `FamilyCalendarApp.kt` (create channel), `AndroidManifest.xml` (register `BirthdayReceiver`, not exported), `MainViewModel.kt`

**Interfaces:**
- Consumes: Tasks 1, 3, 4.
- Produces:
  - `NotificationPrefs`: `var birthdaysEnabled: Boolean` (key `birthdays_enabled`, default `true`); `fun scheduledBirthdayKeys(): Set<String>` / `fun setScheduledBirthdayKeys(keys: Set<String>)` (key `scheduled_birthday_keys`).
  - `BirthdayScheduler.createChannel(context)`; `BirthdayScheduler.sync(context: Context, birthdays: List<Birthday>, myUid: String?)` — plans with prefs (`mutedOwners`, `birthdaysEnabled`), cancels keys no longer planned, arms the rest with `PendingIntent` data `familycalendar://birthday/<date>` and extra `text`; exact alarm when allowed (copy `ReminderScheduler`'s branch). `BirthdayReceiver` posts `text` as title on channel `birthdays`, notification id = data string hash, tapping opens `MainActivity`; checks `POST_NOTIFICATIONS` like `ReminderReceiver`.
  - `MainViewModel`:
    - `val birthdays: StateFlow<List<Birthday>>` — own (`observeOwnBirthdays(uid)`) + per accepted `sharedWithMe` owner `observeBirthdaysSharedWith(owner, uid)`; empty when signed out. Kept **separate** from `events`/`visible`.
    - `val myBirthday: StateFlow<Birthday?>` (own `self`), `val birthdayNotifications: StateFlow<Boolean>`.
    - `fun saveBirthday(birthday: Birthday, onDone: () -> Unit)` — stamps `ownerId`/`ownerName`; `fun saveMyBirthday(day: Int, month: Int, year: Int?)`; `fun deleteMyBirthday()`; `fun deleteBirthday(birthday: Birthday, onDone: () -> Unit)`; `fun setBirthdayShared(birthday: Birthday, viewerUid: String, shared: Boolean)`; `fun setBirthdayNotifications(enabled: Boolean)` (re-syncs).
    - `register(name, email, password, birthday: Birthday?)` passes through.
    - `revoke(viewer)`: after `removeViewer`, `repository.unshareBirthdays(u.uid, viewer.uid)`.
    - `init`: `combine(birthdays, mutedOwners, birthdayNotifications)` → `BirthdayScheduler.sync(app, birthdays, uid)`.
  - `ReminderSyncWorker.doWork`: fetch own + shared birthdays for `owners`, call `BirthdayScheduler.sync`; signed out → `sync(emptyList(), null)`.

- [ ] **Step 1: Build** — `./gradlew :app:testDebugUnitTest :app:assembleDebug`, expect PASS (no new pure logic; planner is covered by Task 3).
- [ ] **Step 2: Manual check on device** — set a birthday on tomorrow's date, run `adb shell dumpsys alarm | grep familycalendar` and expect an alarm for `birthday/<tomorrow>` at 08:00; toggle notifications off (Task 7 UI, or temporarily via prefs) and expect it gone.
- [ ] **Step 3: Commit** (after approval) — `feat: schedule birthday notifications`

---

### Task 6: Show birthdays in the calendar

**Files:**
- Modify: `ui/calendar/CalendarScreen.kt:96-104`, `ui/Navigation.kt`

**Interfaces:**
- Consumes: Task 2 `birthdayOccurrences`, Task 5 `viewModel.birthdays`.
- Produces: route `Routes.birthday(ownerId: String, id: String?)` = `birthday?owner=…&id=…` (no id = new), used by Tasks 7–8.

- [ ] **Step 1:** In `CalendarScreen`, collect `viewModel.birthdays`; `occurrences` = expanded events + `birthdayOccurrences(birthdays.filter { it.ownerId !in hidden }, visibleRange, zone)`, sorted by start (add `birthdays` to the `remember` keys).
- [ ] **Step 2:** In `Navigation.onOpenOccurrence`, birthday occurrences (`it.event.isBirthday`) navigate to `Routes.birthday(it.event.ownerId, it.event.id)`; other events as before.
- [ ] **Step 3: Verify on device** — `./gradlew :app:installDebug`; a birthday shows as a pink `🎂 Name (age)` chip in month, week, day and schedule view, hides with its owner's filter chip, and appears in no reminder.
- [ ] **Step 4: Commit** (after approval) — `feat: show birthdays in all calendar views`

---

### Task 7: Birthday list and edit screens

**Files:**
- Create: `ui/birthday/BirthdayListScreen.kt`, `ui/birthday/BirthdayEditScreen.kt`
- Modify: `ui/Navigation.kt` (routes `birthdays`, `birthday?owner=&id=`), `ui/calendar/CalendarScreen.kt` (`+` menu item "Birthday" → new; overflow item "Birthdays" next to "Sharing & colors")

**Interfaces:**
- Consumes: Task 5 view-model API, Task 1 `validBirthDate`, `dateIn`.
- Produces: `@Composable fun BirthdayListScreen(viewModel: MainViewModel, onOpen: (Birthday) -> Unit, onNew: () -> Unit, onBack: () -> Unit)`; `@Composable fun BirthdayEditScreen(viewModel: MainViewModel, ownerId: String?, birthdayId: String?, onBack: () -> Unit)`.

Behaviour (copy from spec):
- List sections: **My birthday** (date or "Not set"; tap opens a dialog with day/month/optional year, Save / Remove), **Birthday notifications** switch, **My birthdays** (entered ones, sorted by next date from today; FAB/"Add" → new), **Shared with me** (read-only rows `name · date · from <ownerName>`).
- Edit screen: name, day, month, optional year (number fields; Save disabled until `name` non-blank and `validBirthDate`), "Share with …" checkboxes for **accepted** `myViewers` (nobody ticked for a new one), Delete in the top bar for existing own birthdays. For my own `self` (tapped in the calendar): name is the display name and not editable, no "Share with …" list (it's visible to all viewers), Save goes through `saveMyBirthday`, Delete through `deleteMyBirthday`. For a birthday not owned by me: read-only (title "<ownerName>'s birthday entry", no fields editable).

- [ ] **Step 1: Implement** both screens and routes.
- [ ] **Step 2: Verify on device** — create via `+` → Birthday and via the list; edit, share with one viewer, delete; open a shared one read-only; change "My birthday" and remove it.
- [ ] **Step 3: Commit** (after approval) — `feat: birthday list and editor`

---

### Task 8: Per-person sharing on the sharing screen

**Files:**
- Modify: `ui/sharing/SharingScreen.kt` (viewer row, around line 148)

- [ ] **Step 1:** For each **accepted** viewer row add a "Birthdays" action (icon button with a cake/`Cake` icon or text button) opening a dialog: title "Birthdays <viewer name> sees", checkbox per own entered birthday (not `self`; note "Your own birthday is always visible"), each toggle calls `viewModel.setBirthdayShared(...)` immediately; empty state "You haven't added any birthdays yet."
- [ ] **Step 2: Verify on device** — ticking in the dialog shows up as ticked in the birthday's edit screen and vice versa.
- [ ] **Step 3: Commit** (after approval) — `feat: choose per person which birthdays to share`

---

### Task 9: Registration field, rules deploy and end-to-end check

**Files:**
- Modify: `ui/auth/AuthScreen.kt`

- [ ] **Step 1:** In registering mode add optional "Birthday (optional)" day / month / year fields under "Your name". `canSubmit` additionally requires: all empty, or day+month filled and `validBirthDate`. Pass `Birthday(month, day, year)` or `null` to `viewModel.register`.
- [ ] **Step 2: Build + tests** — `./gradlew :app:testDebugUnitTest :app:assembleDebug`, expect PASS.
- [ ] **Step 3: Deploy rules (ask user first)** — `firebase deploy --only firestore:rules`.
- [ ] **Step 4: Two-account check on device(s)**:
  1. Register B with a birthday; A shares with B, B shares with A → each sees the other's account birthday.
  2. A enters "Oma", shares only with B → B sees it labelled "from A"; a third viewer C does not.
  3. A revokes B → B no longer sees Oma or A's birthday; A re-invites B, B accepts → B still doesn't see Oma.
  4. Pending invitation (not accepted) → no birthdays visible.
- [ ] **Step 5: Commit** (after approval) — `feat: optional birthday at registration`
