# Birthdays: Design

Date: 2026-10-22
Status: draft, awaiting approval

## Intent

The family calendar should remind everyone of birthdays without anybody having to keep a separate list. On
the day itself the phone says who has a birthday; in the calendar a birthday stands out from appointments
and checklists at a glance.

There are two sources of birthdays:

1. **Account birthday.** When registering, a person can (optionally) enter their date of birth. Everyone who
   can see that person's calendar sees their birthday.
2. **Entered birthdays.** I can enter birthdays of anyone myself (grandma, friends, people without an
   account). They are private by default. I can share each one with individual people I share my calendar
   with.

## Goals

- Registration has an optional date-of-birth field; the year is optional too (so nobody has to show their
  age to everyone they share with). Users who already have an account can add, change or remove it later in
  the birthday list ("My birthday").
- My own account birthday shows in my own calendar, but I get no notification for it.
- An account birthday is visible to every person with an **accepted** share of that user's calendar. This
  follows the existing sharing direction: if I share my calendar with Lisa, Lisa sees my birthday; I only see
  Lisa's once she shares hers with me. The account birthday has no per-person setting.
- Entered birthdays:
  - have a name, day and month, and an optional year.
  - are visible only to me until I share them.
  - can be shared **per person** with people who have an accepted share of my calendar. The same setting is
    reachable from two places:
    - **On the birthday** (create/edit form): a checklist "Share with …" listing my viewers. Nobody is
      ticked by default.
    - **On the person** (sharing screen): per viewer, a list of my entered birthdays with checkboxes.
  - A newly invited viewer sees none of my entered birthdays until I tick them.
  - When I remove a viewer (or they leave), they lose access to all my birthdays.
- Two ways to create and manage entered birthdays:
  - **"+" in the calendar**: next to event and checklist, a new kind "Birthday" with a short form (name,
    day, month, optional year, "Share with …").
  - **Birthday list screen** ("Birthdays"), reachable from the same place as the sharing screen. Sections:
    1. "My birthday" (my account birthday, editable).
    2. My entered birthdays, sorted by next occurrence; tap to edit, with delete.
    3. Birthdays shared with me (account birthdays of people whose calendars I see, and entered birthdays
       others shared with me), read-only, labelled "from <name>".
- Tapping a birthday chip in the calendar opens the edit form if it's mine, otherwise a read-only view.
- Calendar display:
  - all-day, repeats yearly, in every view (month, week, day, schedule).
  - its own color, distinct from all person colors (builder's call on the exact shade; intent: a warm
    pink/rose), with a 🎂 before the name.
  - if the birth year is known, the age that person turns that year: `🎂 Lisa (40)`.
  - a birthday on Feb 29 shows on Feb 28 in non-leap years.
  - hiding a shared calendar (existing `hiddenOwners`) also hides that owner's birthdays (their account
    birthday and the ones they shared with me).
- Notification: on the day itself at 08:00 local time, "🎂 Today is Lisa's birthday" (with age if known).
  Several birthdays on the same day are combined into one notification. This applies to every birthday I
  can see (my entered ones, shared ones, account birthdays of others) except:
  - my own account birthday;
  - birthdays from an owner whose calendar I muted (existing `NotificationPrefs.mutedOwners`).
- A switch "Birthday notifications" on the birthday list screen turns them off on this device (default:
  on; stored in `NotificationPrefs`). There is no separate settings screen.

## Non-goals

- No advance reminder (e.g. "in 3 days"). Only on the day.
- No merging of duplicates: if Lisa has an account birthday and I also entered her myself, both show.
- No linking of an entered birthday to an e-mail address or account.
- No per-person selection for the account birthday.
- No server-side push (FCM, Cloud Functions). Notifications stay client-side, as today.
- No custom notification time.

## Anti-goals (a failure even if it technically works)

- A date of birth readable by any signed-in user. `users/{uid}` is readable by everyone signed in (used to
  find people by e-mail), so birth dates **must not** be stored there.
- Privacy that only exists in the UI: an entered birthday not shared with someone must be unreadable for
  them in Firestore, enforced by `firestore.rules`.
- A pending (not yet accepted) invitation exposing birthdays.
- Removing someone from sharing, then re-inviting them later, silently giving back access to birthdays I
  had shared before.
- The same birthday notification firing twice on one day, or every past birthday firing after install.

## Constraints from the existing app

- Native Android (Kotlin, Jetpack Compose), Firebase Auth (e-mail/password) and Firestore.
- Registration: `AuthRepository.register(name, email, password)` + `AuthScreen`.
- Sharing: `calendars/{ownerId}/viewers/{uid}` with `status` pending/accepted; `isViewer()` in
  `firestore.rules` only counts accepted shares. `CalendarRepository.removeViewer` revokes.
- Events: `calendars/{ownerId}/events/{id}`, `EventKind { EVENT, CHECKLIST }`, chips rendered via
  `CalendarCommon.kt` / `EventLanes`.
- Notifications are computed on the phone: `ReminderSyncWorker` fetches, `ReminderPlanner` /
  `ReminderScheduler` arm alarms, `NotificationPrefs` holds per-device settings.
- Existing user-facing strings are English; the new screens and the notification use English too.

## Decided how

- **Storage:** a new subcollection `calendars/{ownerId}/birthdays/{id}`, not events. Fields: `name`, `month`,
  `day`, `year` (nullable), `sharedWith` (array of viewer uids). The account birthday is the document with
  the fixed id `self` in the user's own calendar (`name` = display name, `sharedWith` unused).
  - Birthdays are not `CalendarEvent`s in Firestore. In the app they are expanded into yearly all-day
    occurrences for display, so the existing views can show them.
- **Rules:**
  - owner: full read/write of their own `birthdays`.
  - viewer (accepted): may read `self`, and may read any other birthday whose `sharedWith` contains their
    uid. No viewer writes.
  - Viewers must query with `where("sharedWith", "array-contains", myUid)` (Firestore rules are not
    filters) and fetch `self` with a separate document get.
- **Revoking:** `removeViewer` (and a viewer leaving) also removes that uid from `sharedWith` of all my
  birthdays. A viewer who leaves cannot write my birthdays, but the rule check already blocks their access
  (no accepted viewer document). To guarantee a re-invite starts with nothing shared, `invite` removes the
  invitee's uid from `sharedWith` of all my birthdays before creating the invitation.
- **Registration:** the date-of-birth field is optional; when filled, write the `self` document after the
  account is created. A stale uid left in `sharedWith` after a viewer leaves needs no extra cleanup: rules
  block it, and `invite` clears it before any re-invite.

## Left to the builder

- Exact birthday color and how it fits light/dark theme.
- Date input widget (date picker vs. day/month fields with optional year); must allow leaving the year out
  for entered birthdays.
- How the age is computed and shown in narrow chips (truncation rules like the checklist chip split).
- Notification channel, id scheme and how "already notified today" is tracked so nothing fires twice.
- Menu placement of the birthday list.
- Whether the `self` document's `name` is kept in sync with display-name changes.
