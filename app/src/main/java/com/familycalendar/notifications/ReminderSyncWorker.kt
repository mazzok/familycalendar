package com.familycalendar.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.familycalendar.data.CalendarRepository
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Fetches upcoming events from all visible calendars and re-arms reminders, so people get notified
 * about events added to shared calendars even while the app is closed.
 */
class ReminderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            ReminderScheduler.sync(applicationContext, emptyList(), null)
            BirthdayScheduler.sync(applicationContext, emptyList(), null)
            return Result.success()
        }
        return try {
            val repository = CalendarRepository()
            val (accepted, invitations) = repository.fetchSharedWithMe(user.uid).partition { it.accepted }
            InvitationNotifier.notifyNew(applicationContext, invitations)
            // Pending calendars are not readable (firestore.rules), so only accepted ones are fetched.
            val owners = listOf(user.uid) + accepted.map { it.ownerId }
            val all = owners.distinct().flatMap { repository.fetchEvents(it) }
            ChangeNotifier.notifyChanges(applicationContext, user.uid, all)
            ChangeNotifier.notifyAnswers(applicationContext, user.uid, all)
            ChecklistNotifier.notify(applicationContext, user.uid, owners.distinct(), all)
            val events = all.filterNot { it.cancelled }
            ReminderScheduler.sync(applicationContext, events, user.uid)
            // A failing birthday read (e.g. rules not deployed yet) must not hold back the event reminders above.
            runCatching {
                repository.fetchOwnBirthdays(user.uid) +
                    owners.distinct().filter { it != user.uid }.flatMap { repository.fetchBirthdaysSharedWith(it, user.uid) }
            }.onSuccess { BirthdayScheduler.sync(applicationContext, it, user.uid) }
                .onFailure { Log.w(TAG, "Birthday sync failed", it) }
            val now = java.time.Instant.now()
            RequestNotifier.notifyNew(
                applicationContext,
                events.filter { it.requestedUid == user.uid && it.awaitingAnswer && (it.recurrence.repeats || it.end.isAfter(now)) },
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Reminder sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ReminderSyncWorker"

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reminder-sync",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderSyncWorker>(30, TimeUnit.MINUTES).build(),
            )
        }

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-sync-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderSyncWorker>().build(),
            )
        }
    }
}
