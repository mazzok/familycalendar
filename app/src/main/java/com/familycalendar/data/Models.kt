package com.familycalendar.data

import java.io.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Minutes before start; [NO_REMINDER] disables the reminder. */
const val NO_REMINDER = -1

enum class Frequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY }

data class Recurrence(
    val frequency: Frequency = Frequency.NONE,
    val interval: Int = 1,
    /** WEEKLY only: days the event happens on; empty means the start's weekday. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /**
     * MONTHLY only: 0 repeats on the start's day of month (e.g. the 21st); 1–4 or [LAST] repeat on that
     * occurrence of the start's weekday (e.g. the third Tuesday, the last Friday).
     */
    val monthlyOrdinal: Int = 0,
    /** Last day (inclusive, local date) an occurrence may start on; `null` repeats forever. */
    val until: LocalDate? = null,
    /** Local start dates of occurrences that were deleted individually. */
    val exceptions: Set<LocalDate> = emptySet(),
) : Serializable {
    val repeats: Boolean get() = frequency != Frequency.NONE

    companion object {
        const val LAST = -1
    }
}

enum class RequestStatus { PENDING, ACCEPTED, DECLINED }

/** A normal event, or a standalone checklist ("check-it"): title, due date and items only. */
enum class EventKind { EVENT, CHECKLIST }

/** One entry of a shared checklist. Anyone who can see the calendar may tick or edit it. */
data class ChecklistItem(
    val id: String,
    val text: String,
    /** Sort hint; new items go to the bottom. */
    val order: Long = 0,
    /** Who ticked it; `null` = open. */
    val checkedBy: String? = null,
    val checkedByName: String = "",
    /** `null` while ticked offline and not yet stamped by the server. */
    val checkedAt: Instant? = null,
) : Serializable {
    val checked: Boolean get() = checkedBy != null
}

/** Trimmed item text, or `null` if there is nothing to keep. */
fun itemText(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

/** Appends [raw] as a new open item at the bottom; blank text leaves the list as it is. */
fun List<ChecklistItem>.plusItem(raw: String, id: String): List<ChecklistItem> {
    val text = itemText(raw) ?: return this
    return this + ChecklistItem(id = id, text = text, order = maxOfOrNull { it.order }?.plus(1) ?: 0)
}

/** An event series as stored in Firestore; a non-repeating event is a series of one. */
data class CalendarEvent(
    val id: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val start: Instant = Instant.EPOCH,
    val end: Instant = Instant.EPOCH,
    val allDay: Boolean = false,
    val reminderMinutes: Int = 15,
    val recurrence: Recurrence = Recurrence(),
    /** Person asked to confirm this date (must be able to see the owner's calendar); `null` = no request. */
    val requestedUid: String? = null,
    val requestedName: String = "",
    /** Only the requested person can move this away from PENDING (enforced by firestore.rules). */
    val requestStatus: RequestStatus? = null,
    /** Single dates of a repeating request the requested person declined (local start dates). */
    val declinedDates: Set<LocalDate> = emptySet(),
    /** Bumped by the requested person on each answer; the owner's phone notifies when it grows. */
    val answerSeq: Int = 0,
    /** Human-readable last answer, e.g. "declined \"Dinner\" on Oct 30". */
    val lastAnswer: String = "",
    /** Bumped on each change the requested person should hear about; their phone notifies when it grows. */
    val changeSeq: Int = 0,
    /** What the last noticed change touched, e.g. ["title", "location"]. */
    val changedFields: List<String> = emptyList(),
    /**
     * Deleted requests stay as a hidden tombstone for a while, so the requested person's phone can tell
     * "cancelled" from "no longer visible" and notify them.
     */
    val cancelledAt: Instant? = null,
    val kind: EventKind = EventKind.EVENT,
    /** Shared checklist, sorted by order; written item by item, never by the owner's form save. */
    val checklist: List<ChecklistItem> = emptyList(),
) {
    val cancelled: Boolean get() = cancelledAt != null
    val isChecklist: Boolean get() = kind == EventKind.CHECKLIST
    val hasChecklist: Boolean get() = checklist.isNotEmpty()
    val checklistDone: Int get() = checklist.count { it.checked }
    /** Every item ticked; an empty list is never complete. */
    val checklistComplete: Boolean get() = checklist.isNotEmpty() && checklist.all { it.checked }

    /** The most recently ticked item; one still waiting for its server time counts as newest. */
    fun lastTicked(): ChecklistItem? =
        checklist.filter { it.checked }.maxWithOrNull(compareBy(nullsLast()) { it.checkedAt })

    fun nextItemOrder(): Long = checklist.maxOfOrNull { it.order }?.plus(1) ?: 0
    /** Unique across all calendars. */
    val key: String get() = "$ownerId/$id"

    val isRequest: Boolean get() = requestedUid != null
    val awaitingAnswer: Boolean get() = isRequest && requestStatus == RequestStatus.PENDING

    /** The answer that applies to the occurrence starting on [date]: a single declined date overrides the series. */
    fun requestStatusOn(date: LocalDate): RequestStatus? = when {
        !isRequest -> null
        requestStatus == RequestStatus.DECLINED -> RequestStatus.DECLINED
        date in declinedDates -> RequestStatus.DECLINED
        else -> requestStatus
    }

    /**
     * Request state to store when the owner saves this version over [previous]: a request (re)opens when
     * it is new, goes to someone else, or the date moved; otherwise the existing answer stays.
     * Mirrors the owner-update rule in firestore.rules.
     */
    fun withRequestStateFrom(previous: CalendarEvent?): CalendarEvent {
        if (requestedUid == null) return copy(requestedName = "", requestStatus = null, declinedDates = emptySet())
        val unchanged = previous != null && previous.requestedUid == requestedUid && !previous.whenDiffersFrom(this)
        // A reopened request starts over, including dates declined individually.
        return if (unchanged) copy(requestStatus = previous!!.requestStatus, declinedDates = previous.declinedDates)
        else copy(requestStatus = RequestStatus.PENDING, declinedDates = emptySet())
    }

    /** Date, time, all-day or repeat rule differ — what the requested person actually agreed to. */
    fun whenDiffersFrom(other: CalendarEvent): Boolean =
        start != other.start || end != other.end || allDay != other.allDay ||
            recurrence.copy(exceptions = emptySet()) != other.recurrence.copy(exceptions = emptySet())

    /**
     * Records a change notice for the requested person. Apply after [withRequestStateFrom].
     * A reopened request needs no notice: it shows up as a new request to answer.
     */
    fun withChangeNoticeFrom(previous: CalendarEvent?): CalendarEvent {
        val seq = previous?.changeSeq ?: 0
        if (requestedUid == null || previous == null || previous.requestedUid != requestedUid) {
            return copy(changeSeq = seq, changedFields = emptyList())
        }
        val reopened = previous.requestStatus != RequestStatus.PENDING && requestStatus == RequestStatus.PENDING
        if (reopened) return copy(changeSeq = seq, changedFields = previous.changedFields)
        val changes = buildList {
            // Still pending and the date moved: the open request may already have been announced.
            if (previous.whenDiffersFrom(this@CalendarEvent)) add("date")
            if (previous.title != title) add("title")
            if (previous.location != location) add("location")
            if (previous.description != description) add("notes")
        }
        return if (changes.isEmpty()) copy(changeSeq = seq, changedFields = previous.changedFields)
        else copy(changeSeq = seq + 1, changedFields = changes)
    }
}

/** One concrete instance of an event on the calendar. */
data class Occurrence(
    val event: CalendarEvent,
    val start: Instant,
    val end: Instant,
) {
    val key: String get() = "${event.key}@${start.epochSecond}"

    fun localDate(zone: ZoneId): LocalDate = start.atZone(zone).toLocalDate()

    fun requestStatus(zone: ZoneId): RequestStatus? = event.requestStatusOn(localDate(zone))
}

/**
 * A calendar someone else shared with the current user. Until [accepted], it is only an invitation:
 * nothing of the calendar is visible (enforced by firestore.rules).
 */
data class SharedCalendar(
    val ownerId: String,
    val ownerName: String,
    val accepted: Boolean,
)

/** A person the current user shares (or offered to share) their calendar with. */
data class Viewer(
    val uid: String,
    val name: String,
    val email: String,
    val accepted: Boolean,
)
