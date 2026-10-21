package com.familycalendar.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

fun Instant.toTimestamp(): Timestamp = Timestamp(epochSecond, nano)

fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

fun CalendarEvent.toMap(): Map<String, Any?> = mapOf(
    "ownerId" to ownerId,
    "ownerName" to ownerName,
    "title" to title,
    "description" to description,
    "location" to location,
    "start" to start.toTimestamp(),
    "end" to end.toTimestamp(),
    "allDay" to allDay,
    "reminderMinutes" to reminderMinutes,
    "repeat" to recurrence.frequency.name,
    "repeatInterval" to recurrence.interval,
    "repeatWeekdays" to recurrence.weekdays.sorted().map { it.name },
    "repeatMonthlyOrdinal" to recurrence.monthlyOrdinal,
    "repeatUntil" to recurrence.until?.toString(),
    "exceptions" to recurrence.exceptions.map { it.toString() }.sorted(),
    "requestedUid" to requestedUid,
    "requestedName" to requestedName.takeIf { requestedUid != null }.orEmpty(),
    "requestStatus" to requestStatus?.takeIf { requestedUid != null }?.name?.lowercase(),
    "declinedDates" to declinedDates.map { it.toString() }.sorted(),
    "answerSeq" to answerSeq,
    "lastAnswer" to lastAnswer,
    "changeSeq" to changeSeq,
    "changedFields" to changedFields,
    "cancelledAt" to cancelledAt?.toTimestamp(),
)

fun DocumentSnapshot.toEvent(): CalendarEvent? {
    val start = getTimestamp("start") ?: return null
    val end = getTimestamp("end") ?: start
    return CalendarEvent(
        id = id,
        ownerId = getString("ownerId").orEmpty(),
        ownerName = getString("ownerName").orEmpty(),
        title = getString("title").orEmpty(),
        description = getString("description").orEmpty(),
        location = getString("location").orEmpty(),
        start = start.toInstant(),
        end = end.toInstant(),
        allDay = getBoolean("allDay") ?: false,
        reminderMinutes = getLong("reminderMinutes")?.toInt() ?: NO_REMINDER,
        recurrence = Recurrence(
            frequency = getString("repeat")?.let { runCatching { Frequency.valueOf(it) }.getOrNull() } ?: Frequency.NONE,
            interval = getLong("repeatInterval")?.toInt() ?: 1,
            weekdays = (get("repeatWeekdays") as? List<*>).orEmpty()
                .mapNotNull { (it as? String)?.let { s -> runCatching { DayOfWeek.valueOf(s) }.getOrNull() } }
                .toSet(),
            monthlyOrdinal = getLong("repeatMonthlyOrdinal")?.toInt() ?: 0,
            until = getString("repeatUntil")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            exceptions = (get("exceptions") as? List<*>).orEmpty()
                .mapNotNull { (it as? String)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } }
                .toSet(),
        ),
        requestedUid = getString("requestedUid")?.takeIf { it.isNotBlank() },
        requestedName = getString("requestedName").orEmpty(),
        requestStatus = getString("requestStatus")
            ?.let { s -> RequestStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } },
        declinedDates = (get("declinedDates") as? List<*>).orEmpty()
            .mapNotNull { (it as? String)?.let { s -> runCatching { LocalDate.parse(s) }.getOrNull() } }
            .toSet(),
        answerSeq = getLong("answerSeq")?.toInt() ?: 0,
        lastAnswer = getString("lastAnswer").orEmpty(),
        changeSeq = getLong("changeSeq")?.toInt() ?: 0,
        changedFields = (get("changedFields") as? List<*>).orEmpty().filterIsInstance<String>(),
        cancelledAt = getTimestamp("cancelledAt")?.toInstant(),
    )
}

const val STATUS_PENDING = "pending"
const val STATUS_ACCEPTED = "accepted"

// Shares created before invitations existed have no status and count as pending, so they need consent too.
private fun DocumentSnapshot.isAccepted() = getString("status") == STATUS_ACCEPTED

fun DocumentSnapshot.toSharedCalendar(): SharedCalendar? {
    val ownerId = getString("ownerId") ?: return null
    return SharedCalendar(
        ownerId = ownerId,
        ownerName = getString("ownerName").orEmpty(),
        // A just-tapped "Accept" is visible locally before the server has it; reading the calendar
        // that early is rejected by the rules and kills the events listener, so wait for the server.
        accepted = isAccepted() && !metadata.hasPendingWrites(),
    )
}

fun DocumentSnapshot.toViewer(): Viewer = Viewer(
    uid = id,
    name = getString("viewerName").orEmpty(),
    email = getString("viewerEmail").orEmpty(),
    accepted = isAccepted(),
)
