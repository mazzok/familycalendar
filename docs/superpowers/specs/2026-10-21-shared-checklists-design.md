# Shared Checklists: Design

Date: 2026-10-21
Status: draft, awaiting approval

## Intent

The family calendar should also work as a shared reminder for things to buy, cook or do. A shared list that
everyone can see and tick off works better than one person's memory: you can see what's done and who did it.

There are two ways to use a checklist:

1. **A checklist attached to an event.** While creating or editing an event, tap **+ Add checklist**. The
   list belongs to that event, for example "Grillabend" with the things to bring.
2. **A standalone checklist event ("check-it").** A new kind of entry with only a title, a due date and a
   list of items: a shopping list, a recipe, chores for that day.

## Goals

- Everyone who can see a calendar can view a checklist on it and tick or untick its items. Changes sync to
  everyone live, using the existing Firestore listeners.
- A ticked item shows its checkmark in the color of the person who ticked it, plus their name.
- Anyone who can see the calendar can add, rename and remove items, not only the creator.
- Standalone checklists:
  - have a title, a due date (today by default) and items. They have no time, no request/approval, no
    reminder and no repeat.
  - show in the calendar on their due date like an all-day chip with a ☑ and a progress count, for example
    `☑ Einkauf 3/7` (mockup option A).
  - notify everyone who can see the calendar **when one is created** and **when it is completed**.
    "Completed" means the last open item was ticked. There is no separate "done" button.
  - stay on the day they were due when they aren't finished. They don't roll over.
- Events with an attached checklist show a small ☑ on their chip. The event works as before, and that
  includes requests and approvals.

## Non-goals

- No notification for ticking single items, adding items or editing items. In the supermarket that would
  be noise.
- Notifications for attached checklists (create or complete). The event itself already has its own
  request and change notifications.
- Repeating standalone checklists.
- Quantities, categories, reordering by drag, or recipe import. A plain list of text items is enough.
- Server-side push (FCM or Cloud Functions). Notifications stay client-side, as they are today.

## Anti-goals (this would count as a failure even if it technically works)

- Two people ticking different items at the same moment and one of the ticks gets lost.
- The owner saving an event and wiping ticks that someone else made in the meantime.
- Ticking an item on a requested event reopening the request, or sending the requested person a "changed"
  notice.
- A fresh install, or the first sync after updating, firing "new checklist" notifications for every
  existing list.

## Constraints from the existing app

- Events live in `calendars/{ownerId}/events/{id}`. Only the owner writes an event. Viewers (people with an
  accepted share) may only write the request-answer fields (`firestore.rules`).
- Notifications are computed on the phone: `ReminderSyncWorker` fetches everything, then `ChangeNotifier`
  and `ChangeTracker` compare counters with "seen" state kept in SharedPreferences. First-seen entries are
  only remembered, never announced.
- Person colors already exist. `MainViewModel.calendarColors` maps each calendar owner uid to a palette
  index, and the user can change it on the Sharing screen (stored locally per phone). Checkmarks must reuse
  these colors. **Decided by the user.** A side effect: if you recolored Anna's calendar on your phone, her
  ticks look different for you than for her. That's intended, because it matches the calendar you're
  looking at.

## How it works (decided parts)

### Where it lives

A checklist is part of its event document, so no new collection is needed. A standalone checklist is an
event in the creator's own calendar with a new `kind = "checklist"`. Normal events have `kind = "event"`,
or no field at all, which keeps old data working.

### Data (proposed, my call)

Items are stored as a **map** field on the event, keyed by a random item id, so that concurrent edits of
different items never collide:

```
checklist: {
  "<itemId>": { text, order, checkedBy: uid|null, checkedByName, checkedAt: timestamp|null }
}
```

There is no completion counter stored. Each phone works out "completed" from the items (see Notifications).
A stored counter would get lost or doubled when two people tick the last items at the same time, or offline,
and a transaction to prevent that would make ticking fail offline, which is the supermarket case (my call).

Every edit to an item uses a field-path update (`checklist.<itemId>.checkedBy`, and so on), never a whole
document write. Deleting an item uses `FieldValue.delete()` on `checklist.<itemId>`.

**When item edits are written (my call):** on an existing event or checklist, every item change (tick, add,
rename, remove) is written immediately, by anyone, the owner included. The checklist section is a live
shared list and is not part of the form's Save/Cancel. Cancel only discards the event fields. On a new event
or a new standalone checklist, the items typed before the first Save go into the create write.

Owner edits of the event itself (`saveEvent`) must **not** write the `checklist` field. Either use
`set(..., merge)` without `checklist`, or an update of the other fields only. This prevents the "owner save
wipes ticks" failure.

### Firestore rules (my call)

- Viewers get a second update rule: allowed when `affectedKeys().hasOnly(['checklist'])`.
- The owner's existing update rule keeps working. Checklist changes don't touch `sameWhen()` or the answer
  fields, so the request state stays as it is.
- Creating and deleting a standalone checklist: owner only, like any event.

### Colors and names

A ticked item shows a filled checkbox in `calendarColors[checkedBy]` and the name `checkedByName`. If the
person who ticked it has no calendar visible on this phone, fall back to a stable palette color derived from
their uid (my call).

### Notifications (standalone checklists only)

A new `ChecklistNotifier` with its own channel, "Shared checklists", runs from `ReminderSyncWorker` next to
the existing notifiers:

- **Created:** announce each `kind == checklist` event key you haven't seen before, unless you created it.
  On the very first run after install or update, **and the first time a given calendar is seen** (for
  example right after accepting a new share), only remember what you see (my call).
- **Completed:** each phone keeps a per-checklist "was complete" flag in its seen state. It notifies when a
  checklist goes from not complete (or empty) to all items ticked, unless you ticked the last item, meaning
  the item with the newest `checkedAt` has your uid in `checkedBy`. Unticking and re-completing notifies
  again (my call; that should be rare). This works offline and can't be lost or doubled by simultaneous ticks.
- The notifiers run in the same places as the existing ones: `ReminderSyncWorker` and the live path in
  `MainViewModel`.
- The existing mute per calendar (`mutedOwners`) applies to these too. Today it only affects reminders, so
  this part is new.

Messages, for example:
- `Anna created checklist "Einkauf" for Fri, Oct 23`
- `"Einkauf" is done. The last item was ticked by Martin`

### UI

- **Calendar views (month, week, 3-day, day, schedule):** a standalone checklist renders in the all-day lane
  on its due date, with `☑ <title> <done>/<total>` in the owner's calendar color. An event with an attached
  list gets a ☑ after its title.
- **Event screen:** a "+ Add checklist" button opens an inline checklist section with the items, a checkbox
  per item, the name of the person who ticked it in their color, and an "+ Add item" field.
- **Creating a standalone checklist:** the existing "+" action offers "Event" or "Checklist". The checklist
  form has a title, a due date (today by default) and items.
- **Viewers** opening someone else's event or checklist can tick and edit items, but can't edit the event's
  other fields (as today).

## Left to the builder

- Exact composable structure, and whether the checklist editor is shared between both forms.
- How "+" offers the choice (bottom sheet, speed-dial, or a toggle in the form).
- Item ordering details (the `order` field is a hint; new items go at the bottom).
- Test coverage. Pure logic (completion detection, notifier diffing, rule shapes) should get unit tests like
  the existing `ChangeTracker` tests.
