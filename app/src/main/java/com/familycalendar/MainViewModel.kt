package com.familycalendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.familycalendar.data.AuthRepository
import com.familycalendar.data.CalendarEvent
import com.familycalendar.data.CalendarRepository
import com.familycalendar.notifications.ChangeNotifier
import com.familycalendar.notifications.RequestNotifier
import com.familycalendar.data.SharedCalendar
import com.familycalendar.data.Viewer
import com.familycalendar.data.nameOrEmail
import com.familycalendar.notifications.NotificationPrefs
import com.familycalendar.notifications.ReminderScheduler
import com.familycalendar.ui.calendar.CalendarPrefs
import com.familycalendar.ui.calendar.CalendarViewMode
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val auth = AuthRepository()
    private val repository = CalendarRepository()
    private val notificationPrefs = NotificationPrefs(app)
    private val calendarPrefs = CalendarPrefs(app)

    val user: StateFlow<FirebaseUser?> =
        auth.observeUser().stateIn(viewModelScope, SharingStarted.Eagerly, auth.currentUser)

    private val sharedWithMeIncludingInvitations: StateFlow<List<SharedCalendar>> = user
        .flatMapLatest { u -> if (u == null) flowOf(emptyList()) else repository.observeSharedWithMe(u.uid) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Calendars others shared with the current user and the user accepted. */
    val sharedWithMe: StateFlow<List<SharedCalendar>> = sharedWithMeIncludingInvitations
        .map { list -> list.filter { it.accepted } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Calendars others offered to share; nothing of them is visible until accepted. */
    val invitations: StateFlow<List<SharedCalendar>> = sharedWithMeIncludingInvitations
        .map { list -> list.filterNot { it.accepted } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val myViewers: StateFlow<List<Viewer>> = user
        .flatMapLatest { u -> if (u == null) flowOf(emptyList()) else repository.observeViewers(u.uid) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Everything stored, including tombstones of cancelled requests. `null` while loading. */
    private val allEvents: StateFlow<List<CalendarEvent>?> = combine(user, sharedWithMe) { u, shared -> u to shared }
        .flatMapLatest { (u, shared) ->
            if (u == null) return@flatMapLatest flowOf(emptyList<CalendarEvent>())
            val owners = listOf(u.uid) + shared.map { it.ownerId }.filter { it != u.uid }
            combine(owners.distinct().map { repository.observeEvents(it) }) { perCalendar ->
                perCalendar.flatMap { it }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** All visible event series across own and shared calendars. `null` while loading. */
    val events: StateFlow<List<CalendarEvent>?> = allEvents
        .map { list -> list?.filterNot { it.cancelled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _colorOverrides = MutableStateFlow(calendarPrefs.colorOverrides())

    /**
     * Palette index per calendar owner. Defaults keep calendars distinct: own calendar first,
     * shared calendars in name order after it; a color picked by the user wins.
     */
    val calendarColors: StateFlow<Map<String, Int>> = combine(user, sharedWithMe, _colorOverrides) { u, shared, overrides ->
        val owners = listOfNotNull(u?.uid) + shared.map { it.ownerId }
        owners.withIndex().associate { (i, owner) -> owner to (overrides[owner] ?: i) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _viewMode = MutableStateFlow(calendarPrefs.viewMode)
    val viewMode: StateFlow<CalendarViewMode> = _viewMode.asStateFlow()

    /** The date the current view is anchored on (first visible day, or any day in the visible month). */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _mutedOwners = MutableStateFlow(notificationPrefs.mutedOwners())
    val mutedOwners: StateFlow<Set<String>> = _mutedOwners.asStateFlow()

    /** Calendars hidden from the agenda via the filter chips (view only, reminders unaffected). */
    private val _hiddenOwners = MutableStateFlow(emptySet<String>())
    val hiddenOwners: StateFlow<Set<String>> = _hiddenOwners.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        auth.currentUser?.let(auth::refreshProfile)
        viewModelScope.launch {
            combine(events.filterNotNull(), mutedOwners) { list, _ -> list }
                .collect { ReminderScheduler.sync(getApplication(), it, user.value?.uid) }
        }
        viewModelScope.launch {
            combine(user, allEvents.filterNotNull()) { u, list -> u to list }.collect { (u, list) ->
                if (u == null) return@collect
                ChangeNotifier.notifyChanges(getApplication(), u.uid, list)
                ChangeNotifier.notifyAnswers(getApplication(), u.uid, list)
                // Tombstones only need to live until the requested person's phone had a chance to see them.
                val expired = Instant.now().minus(Duration.ofDays(30))
                list.filter { it.ownerId == u.uid && it.cancelledAt?.isBefore(expired) == true }
                    .forEach { repository.deleteEvent(it) }
            }
        }
    }

    val myName: StateFlow<String> = user.map { it?.nameOrEmail().orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun signIn(email: String, password: String) = launchBusy { auth.signIn(email, password) }

    fun register(name: String, email: String, password: String) = launchBusy { auth.register(name, email, password) }

    fun signOut() {
        auth.signOut()
        _hiddenOwners.value = emptySet()
    }

    fun saveEvent(event: CalendarEvent, onDone: () -> Unit) {
        val u = user.value ?: return
        val previous = events.value?.firstOrNull { it.ownerId == u.uid && it.id == event.id && event.id.isNotBlank() }
        val toSave = event.copy(ownerId = u.uid, ownerName = u.nameOrEmail())
            .withRequestStateFrom(previous)
            .withChangeNoticeFrom(previous)
        repository.saveEvent(toSave)
            .addOnFailureListener { _message.value = "Could not save: ${it.localizedMessage}" }
        onDone()
    }

    /** Date requests addressed to the current user that still wait for an answer. */
    val openRequests: StateFlow<List<CalendarEvent>> = combine(user, events) { u, list -> openRequestsFor(u?.uid, list.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun openRequestsFor(uid: String?, list: List<CalendarEvent>): List<CalendarEvent> {
        val now = Instant.now()
        return list
            .filter { it.requestedUid == uid && it.awaitingAnswer }
            .filter { it.recurrence.repeats || it.end.isAfter(now) }
            .sortedBy { it.start }
    }

    init {
        // Informs immediately while the app process lives; the background sync covers the rest.
        // Only loaded data counts: an empty list while loading would reset the "already notified" memory.
        viewModelScope.launch {
            combine(user, events.filterNotNull()) { u, list -> openRequestsFor(u?.uid, list) }
                .collect { RequestNotifier.notifyNew(getApplication(), it) }
        }
    }

    /** Accepts or declines the whole request (all dates of a repeating one). */
    fun answerRequest(event: CalendarEvent, accept: Boolean) {
        val summary = "${if (accept) "accepted" else "declined"} \"${event.title}\"" +
            if (event.recurrence.repeats) " (all dates)" else ""
        repository.answerRequest(event, accept, summary)
            .addOnFailureListener { _message.value = "Could not answer: ${it.localizedMessage}" }
    }

    /** Declines, or accepts again, only the occurrence on [date] of a repeating request. */
    fun answerDate(event: CalendarEvent, date: LocalDate, decline: Boolean) {
        val day = date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.ENGLISH))
        val summary = if (decline) "declined \"${event.title}\" on $day" else "accepted \"${event.title}\" on $day again"
        repository.answerDate(event, date, decline, summary)
            .addOnFailureListener { _message.value = "Could not answer: ${it.localizedMessage}" }
    }

    fun deleteEvent(event: CalendarEvent, onDone: () -> Unit) {
        // Navigate away first so the screen doesn't flash "event no longer exists".
        onDone()
        // A requested person is told about the cancellation, so requests are tombstoned instead of deleted.
        val task = if (event.isRequest) repository.cancelRequest(event) else repository.deleteEvent(event)
        task.addOnFailureListener { _message.value = "Could not delete: ${it.localizedMessage}" }
    }

    /** Deletes one occurrence of a repeating event, identified by its local start date. */
    fun deleteOccurrence(event: CalendarEvent, date: LocalDate, onDone: () -> Unit) {
        onDone()
        repository.deleteOccurrence(event, date)
            .addOnFailureListener { _message.value = "Could not delete: ${it.localizedMessage}" }
    }

    fun setViewMode(mode: CalendarViewMode) {
        calendarPrefs.viewMode = mode
        _viewMode.value = mode
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun step(direction: Int) {
        _selectedDate.update { viewMode.value.step(it, direction) }
    }

    fun goToToday() {
        _selectedDate.value = LocalDate.now()
    }

    /** Opens the day view on [date], e.g. after tapping a day in the month grid. */
    fun openDay(date: LocalDate) {
        _selectedDate.value = date
        setViewMode(CalendarViewMode.DAY)
    }

    fun setCalendarColor(ownerId: String, paletteIndex: Int) {
        calendarPrefs.setColor(ownerId, paletteIndex)
        _colorOverrides.value = calendarPrefs.colorOverrides()
    }

    fun shareWith(email: String, onDone: () -> Unit) {
        val u = user.value ?: return
        launchBusy(onSuccess = onDone) {
            val name = repository.invite(u.uid, u.nameOrEmail(), email)
            _message.value = "Invitation sent. $name will see your calendar after accepting."
        }
    }

    private val _shareBackOffer = MutableStateFlow<SharedCalendar?>(null)

    /** Set right after accepting an invitation from someone the user doesn't share with yet. */
    val shareBackOffer: StateFlow<SharedCalendar?> = _shareBackOffer.asStateFlow()

    fun accept(invitation: SharedCalendar) {
        val u = user.value ?: return
        launchBusy {
            repository.acceptInvitation(invitation.ownerId, u.uid)
            if (myViewers.value.none { it.uid == invitation.ownerId }) _shareBackOffer.value = invitation
        }
    }

    /** Invites the person whose calendar was just accepted; they still have to accept in turn. */
    fun shareBack() {
        val u = user.value ?: return
        val other = _shareBackOffer.value ?: return
        _shareBackOffer.value = null
        launchBusy {
            val name = repository.inviteUser(u.uid, u.nameOrEmail(), other.ownerId)
            _message.value = "Invitation sent. $name will see your calendar after accepting."
        }
    }

    fun dismissShareBack() {
        _shareBackOffer.value = null
    }

    fun decline(invitation: SharedCalendar) = leave(invitation)

    fun revoke(viewer: Viewer) {
        val u = user.value ?: return
        launchBusy { repository.removeViewer(u.uid, viewer.uid) }
    }

    fun leave(calendar: SharedCalendar) {
        val u = user.value ?: return
        launchBusy { repository.removeViewer(calendar.ownerId, u.uid) }
    }

    fun setMuted(ownerId: String, muted: Boolean) {
        notificationPrefs.setMuted(ownerId, muted)
        _mutedOwners.value = notificationPrefs.mutedOwners()
    }

    fun toggleHidden(ownerId: String) {
        _hiddenOwners.update { if (ownerId in it) it - ownerId else it + ownerId }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun launchBusy(onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                onSuccess()
            } catch (e: Exception) {
                _message.value = e.localizedMessage ?: "Something went wrong"
            } finally {
                _busy.value = false
            }
        }
    }
}
