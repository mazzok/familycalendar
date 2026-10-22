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
    "kind" to kind.name.lowercase(),
    // No "checklist": items are written one by one, so a form save can't wipe someone else's ticks.
)

fun ChecklistItem.toFirestore(): Map<String, Any?> = mapOf(
    "text" to text,
    "order" to order,
    "checkedBy" to checkedBy,
    "checkedByName" to checkedByName,
    "checkedAt" to checkedAt?.toTimestamp(),
)

/** The `checklist` map field: item id -> item. */
fun List<ChecklistItem>.toFirestore(): Map<String, Map<String, Any?>> = associate { it.id to it.toFirestore() }

/** Reads the `checklist` map field; entries that aren't maps are skipped. */
fun parseChecklist(raw: Any?): List<ChecklistItem> =
    (raw as? Map<*, *>).orEmpty().mapNotNull { (id, value) ->
        val item = value as? Map<*, *> ?: return@mapNotNull null
        ChecklistItem(
            id = id as? String ?: return@mapNotNull null,
            // No text: an offline tick landed after the item was removed and recreated only the tick fields.
            text = item["text"] as? String ?: return@mapNotNull null,
            order = (item["order"] as? Number)?.toLong() ?: 0,
            checkedBy = (item["checkedBy"] as? String)?.takeIf { it.isNotBlank() },
            checkedByName = item["checkedByName"] as? String ?: "",
            checkedAt = (item["checkedAt"] as? Timestamp)?.toInstant(),
        )
    }.sortedWith(compareBy({ it.order }, { it.id }))

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
        kind = if (getString("kind").equals("checklist", ignoreCase = true)) EventKind.CHECKLIST else EventKind.EVENT,
        // A tick made offline has no server time yet; the estimate keeps it ordered as the newest.
        checklist = parseChecklist(get("checklist", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)),
    )
}

fun Birthday.toMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "ownerName" to ownerName,
    "month" to month,
    "day" to day,
    "year" to year,
    "sharedWith" to sharedWith.sorted(),
)

/** Reads a birthday document's fields; `null` if name, month or day is missing. */
fun parseBirthday(id: String, ownerId: String, data: Map<String, Any?>): Birthday? {
    return Birthday(
        id = id,
        ownerId = ownerId,
        ownerName = data["ownerName"] as? String ?: "",
        name = data["name"] as? String ?: return null,
        month = (data["month"] as? Number)?.toInt() ?: return null,
        day = (data["day"] as? Number)?.toInt() ?: return null,
        year = (data["year"] as? Number)?.toInt(),
        sharedWith = (data["sharedWith"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
    )
}

/** The owner is the calendar the `birthdays` subcollection belongs to. */
fun DocumentSnapshot.toBirthday(): Birthday? {
    val ownerId = reference.parent.parent?.id ?: return null
    return parseBirthday(id, ownerId, data ?: return null)
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
