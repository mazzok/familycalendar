package com.familycalendar.data

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CalendarRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private fun calendar(ownerId: String) = db.collection("calendars").document(ownerId)
    private fun events(ownerId: String) = calendar(ownerId).collection("events")
    private fun viewers(ownerId: String) = calendar(ownerId).collection("viewers")
    private fun birthdays(ownerId: String) = calendar(ownerId).collection("birthdays")

    // Viewers may only read the account birthday and the ones shared with them (firestore.rules), and rules
    // are not filters: the shared ones must be queried with exactly that constraint.
    private fun sharedBirthdaysQuery(ownerId: String, myUid: String): Query =
        birthdays(ownerId).whereArrayContains("sharedWith", myUid)

    private fun sharedWithMeQuery(uid: String): Query =
        db.collectionGroup("viewers").whereEqualTo("viewerUid", uid)

    // A whole calendar is loaded: repeating series start in the past, and family calendars are small.
    // Occurrences are computed on the device by RecurrenceExpander.

    /** Live event series of one calendar. Emits an empty list if access is lost. */
    fun observeEvents(ownerId: String): Flow<List<CalendarEvent>> = callbackFlow {
        val registration = events(ownerId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Events listener for $ownerId failed", error)
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toEvent() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    suspend fun fetchEvents(ownerId: String): List<CalendarEvent> =
        events(ownerId).get().await().documents.mapNotNull { it.toEvent() }

    fun observeSharedWithMe(uid: String): Flow<List<SharedCalendar>> = callbackFlow {
        // Metadata changes are included so we hear when an accepted invitation reaches the server.
        val registration = sharedWithMeQuery(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Shared-with-me listener failed", error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toSharedCalendar() }.orEmpty().sortedBy { it.ownerName })
        }
        awaitClose { registration.remove() }
    }

    suspend fun fetchSharedWithMe(uid: String): List<SharedCalendar> =
        sharedWithMeQuery(uid).get().await().documents.mapNotNull { it.toSharedCalendar() }

    fun observeViewers(ownerId: String): Flow<List<Viewer>> = callbackFlow {
        val registration = viewers(ownerId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Viewers listener failed", error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.map { it.toViewer() }.orEmpty().sortedBy { it.name })
        }
        awaitClose { registration.remove() }
    }

    // Event writes are not awaited: Firestore applies them to the local cache immediately and
    // syncs when online, whereas the returned Task only completes once the server acknowledges.
    fun saveEvent(event: CalendarEvent): Task<Void> {
        if (event.id.isBlank()) {
            // Items typed before the first save go into the create write.
            val doc = events(event.ownerId).document()
            return doc.set(event.copy(id = doc.id).toMap() + ("checklist" to event.checklist.toFirestore()))
        }
        // Merge without "checklist", so ticks others made meanwhile survive the owner's save.
        return events(event.ownerId).document(event.id).set(event.toMap(), SetOptions.merge())
    }

    // Checklist items are written one field path at a time, so concurrent edits of different items never collide.

    fun newItemId(): String = UUID.randomUUID().toString()

    fun addItem(event: CalendarEvent, item: ChecklistItem): Task<Void> =
        events(event.ownerId).document(event.id).update(FieldPath.of("checklist", item.id), item.toFirestore())

    fun renameItem(event: CalendarEvent, itemId: String, text: String): Task<Void> =
        events(event.ownerId).document(event.id).update(FieldPath.of("checklist", itemId, "text"), text)

    /** Ticks the item for [byUid], or unticks it when [byUid] is `null`. */
    fun setChecked(event: CalendarEvent, itemId: String, byUid: String?, byName: String): Task<Void> =
        events(event.ownerId).document(event.id).update(
            FieldPath.of("checklist", itemId, "checkedBy"), byUid,
            FieldPath.of("checklist", itemId, "checkedByName"), if (byUid == null) "" else byName,
            FieldPath.of("checklist", itemId, "checkedAt"), if (byUid == null) null else FieldValue.serverTimestamp(),
        )

    fun removeItem(event: CalendarEvent, itemId: String): Task<Void> =
        events(event.ownerId).document(event.id).update(FieldPath.of("checklist", itemId), FieldValue.delete())

    fun deleteEvent(event: CalendarEvent): Task<Void> =
        events(event.ownerId).document(event.id).delete()

    /** The requested person answers for the whole series. [summary] tells the owner what happened. */
    fun answerRequest(event: CalendarEvent, accept: Boolean, summary: String): Task<Void> =
        events(event.ownerId).document(event.id).update(
            mapOf(
                "requestStatus" to if (accept) "accepted" else "declined",
                "answerSeq" to FieldValue.increment(1),
                "lastAnswer" to summary,
            )
        )

    /** The requested person declines ([decline] = true) or re-accepts a single date of a repeating request. */
    fun answerDate(event: CalendarEvent, date: LocalDate, decline: Boolean, summary: String): Task<Void> =
        events(event.ownerId).document(event.id).update(
            mapOf(
                "declinedDates" to if (decline) FieldValue.arrayUnion(date.toString()) else FieldValue.arrayRemove(date.toString()),
                "answerSeq" to FieldValue.increment(1),
                "lastAnswer" to summary,
            )
        )

    /** Removes a single occurrence (identified by its local start date) from a repeating series. */
    fun deleteOccurrence(event: CalendarEvent, date: LocalDate): Task<Void> {
        val doc = events(event.ownerId).document(event.id)
        return if (event.isRequest) {
            doc.update(
                mapOf(
                    "exceptions" to FieldValue.arrayUnion(date.toString()),
                    "changeSeq" to FieldValue.increment(1),
                    "changedFields" to listOf("removed $date"),
                )
            )
        } else {
            doc.update("exceptions", FieldValue.arrayUnion(date.toString()))
        }
    }

    /** Hides a requested event and leaves a tombstone so the requested person learns it was cancelled. */
    fun cancelRequest(event: CalendarEvent): Task<Void> =
        events(event.ownerId).document(event.id).update(
            mapOf(
                "cancelledAt" to Instant.now().toTimestamp(),
                "changeSeq" to FieldValue.increment(1),
                "changedFields" to listOf("cancelled"),
            )
        )

    /** All birthdays of [ownerId]'s own calendar, the account birthday included. */
    fun observeOwnBirthdays(ownerId: String): Flow<List<Birthday>> = callbackFlow {
        val registration = birthdays(ownerId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Birthdays listener for $ownerId failed", error)
                trySend(emptyList())
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toBirthday() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    /** [ownerId]'s account birthday plus the birthdays they shared with [myUid]. Empty parts if access is lost. */
    fun observeBirthdaysSharedWith(ownerId: String, myUid: String): Flow<List<Birthday>> = callbackFlow {
        var self: Birthday? = null
        var shared = emptyList<Birthday>()
        fun emit() = trySend(listOfNotNull(self) + shared)
        val selfRegistration = birthdays(ownerId).document(SELF_BIRTHDAY_ID).addSnapshotListener { snapshot, error ->
            if (error != null) Log.w(TAG, "Account birthday listener for $ownerId failed", error)
            self = snapshot?.takeIf { it.exists() }?.toBirthday()
            emit()
        }
        val sharedRegistration = sharedBirthdaysQuery(ownerId, myUid).addSnapshotListener { snapshot, error ->
            if (error != null) Log.w(TAG, "Shared birthdays listener for $ownerId failed", error)
            shared = snapshot?.documents?.mapNotNull { it.toBirthday() }.orEmpty()
            emit()
        }
        awaitClose {
            selfRegistration.remove()
            sharedRegistration.remove()
        }
    }

    suspend fun fetchOwnBirthdays(ownerId: String): List<Birthday> =
        birthdays(ownerId).get().await().documents.mapNotNull { it.toBirthday() }

    suspend fun fetchBirthdaysSharedWith(ownerId: String, myUid: String): List<Birthday> {
        val self = birthdays(ownerId).document(SELF_BIRTHDAY_ID).get().await().takeIf { it.exists() }?.toBirthday()
        return listOfNotNull(self) + sharedBirthdaysQuery(ownerId, myUid).get().await().documents.mapNotNull { it.toBirthday() }
    }

    // Not awaited, like event writes.
    fun saveBirthday(birthday: Birthday): Task<Void> {
        val doc = if (birthday.id.isBlank()) birthdays(birthday.ownerId).document() else birthdays(birthday.ownerId).document(birthday.id)
        return doc.set(birthday.toMap())
    }

    fun deleteBirthday(birthday: Birthday): Task<Void> =
        birthdays(birthday.ownerId).document(birthday.id).delete()

    fun setBirthdayShared(ownerId: String, birthdayId: String, viewerUid: String, shared: Boolean): Task<Void> =
        birthdays(ownerId).document(birthdayId).update(
            "sharedWith", if (shared) FieldValue.arrayUnion(viewerUid) else FieldValue.arrayRemove(viewerUid),
        )

    /** Takes all of [ownerId]'s birthdays away from [viewerUid], e.g. when they stop seeing the calendar. */
    suspend fun unshareBirthdays(ownerId: String, viewerUid: String) {
        val docs = birthdays(ownerId).whereArrayContains("sharedWith", viewerUid).get().await().documents
        if (docs.isEmpty()) return
        val batch = db.batch()
        docs.forEach { batch.update(it.reference, "sharedWith", FieldValue.arrayRemove(viewerUid)) }
        batch.commit().await()
    }

    /**
     * Invites the user registered with [email] to see [ownerId]'s calendar. They get access only after
     * accepting ([acceptInvitation]). Returns the invitee's display name.
     */
    suspend fun invite(ownerId: String, ownerName: String, email: String): String {
        val match = db.collection("users")
            .whereEqualTo("emailLower", email.trim().lowercase())
            .limit(1)
            .get().await()
            .documents.firstOrNull()
            ?: throw IllegalArgumentException("Nobody with that email uses Family Calendar yet. Ask them to sign up first.")
        return invite(ownerId, ownerName, match)
    }

    /** Invites a known user, e.g. to share back with someone whose invitation was just accepted. */
    suspend fun inviteUser(ownerId: String, ownerName: String, uid: String): String {
        val profile = db.collection("users").document(uid).get().await()
        require(profile.exists()) { "That person's profile could not be found." }
        return invite(ownerId, ownerName, profile)
    }

    private suspend fun invite(ownerId: String, ownerName: String, match: DocumentSnapshot): String {
        require(match.id != ownerId) { "That's you — your own calendar is always visible to you." }
        val name = match.getString("displayName").orEmpty().ifBlank { match.getString("email").orEmpty() }
        val existing = viewers(ownerId).document(match.id).get().await()
        if (existing.exists()) {
            throw IllegalStateException(
                if (existing.toViewer().accepted) "You already share your calendar with $name."
                else "$name hasn't answered your invitation yet."
            )
        }
        // Someone invited again starts with nothing shared, even if they left on their own (they can't edit our birthdays).
        unshareBirthdays(ownerId, match.id)
        viewers(ownerId).document(match.id).set(
            mapOf(
                "ownerId" to ownerId,
                "ownerName" to ownerName,
                "viewerUid" to match.id,
                "viewerName" to match.getString("displayName").orEmpty(),
                "viewerEmail" to match.getString("email").orEmpty(),
                "status" to STATUS_PENDING,
            )
        ).await()
        return name
    }

    suspend fun acceptInvitation(ownerId: String, myUid: String) {
        viewers(ownerId).document(myUid).update("status", STATUS_ACCEPTED).await()
    }

    /** Used by the owner (revoke / withdraw invitation) and by the viewer (leave / decline). */
    suspend fun removeViewer(ownerId: String, viewerUid: String) {
        viewers(ownerId).document(viewerUid).delete().await()
    }

    private companion object {
        const val TAG = "CalendarRepository"
    }
}
