# Family Calendar (Android)

Each person keeps their own calendar and shares it with family members. Everyone sees the shared
calendars in one agenda and gets a notification before an event starts, including events in calendars
other people shared with them.

## Features

- Email/password accounts (Firebase Auth)
- Google-Calendar-style views: month, week, 3 days, day and schedule; swipe to move, tap a slot to create
- Events with all-day and multi-day support (continuous bars), repeat rules (daily/weekly on chosen days/
  monthly by day or weekday/yearly, custom intervals, end date), single occurrences deletable
- Sharing by invitation: the other person has to accept before seeing your calendar; offer to share back
- Distinct color per calendar, changeable per device; filter chips per person
- Date requests: ask someone to confirm an event (checkerboard until answered). They can accept/decline
  the whole series or single dates; both sides are notified about answers, changes and cancellations
- Reminder notifications for your and shared events (not for dates you declined); mute per calendar
- Works offline (Firestore cache); reminders are re-armed after reboot and by a background sync every 30 min

## Tech

Kotlin, Jetpack Compose (Material 3), Navigation Compose, Firebase Auth + Cloud Firestore,
AlarmManager + WorkManager for reminders. minSdk 26, targetSdk 35.

```
calendars/{ownerId}/events/{eventId}     owner writes, owner + viewers read
calendars/{ownerId}/viewers/{viewerUid}  who may see the owner's calendar
users/{uid}                              profile, used to find people by email
```

Access is enforced by [`firestore.rules`](firestore.rules).

Reminders run on each device: every phone arms local alarms for all events it can see, so no server
or push setup is needed. The trade-off is that a new event in someone else's calendar reaches your phone
when the app is open, or at the next background sync (≤ 30 min, at Android's discretion).

## Setup

1. Create a project in the [Firebase console](https://console.firebase.google.com/).
2. Add an Android app with package name `com.familycalendar`, download `google-services.json`
   and put it in `app/` (it is git-ignored).
3. **Authentication → Sign-in method:** enable *Email/Password*.
4. **Firestore Database:** create a database, then deploy the rules and index:
   ```
   npm install -g firebase-tools
   firebase login
   firebase use --add          # pick your project
   firebase deploy --only firestore
   ```
   The index entry is needed for the "calendars shared with me" collection-group query.
5. Open the folder in Android Studio and run the `app` configuration, or `./gradlew installDebug`.

## Tests

```
./gradlew testDebugUnitTest
```
