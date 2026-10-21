package com.familycalendar.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.familycalendar.MainActivity
import com.familycalendar.R
import com.familycalendar.data.SharedCalendar

/** Tells the user (once per invitation) that someone wants to share a calendar with them. */
object InvitationNotifier {
    private const val CHANNEL_ID = "sharing_invitations"
    private const val PREFS = "invitation_prefs"
    private const val KEY_NOTIFIED = "notified_owner_ids"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Sharing invitations", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When someone wants to share their calendar with you" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyNew(context: Context, invitations: List<SharedCalendar>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        val pendingIds = invitations.map { it.ownerId }.toSet()
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        if (allowed) {
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            invitations.filter { it.ownerId !in notified }.forEach { invitation ->
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("${invitation.ownerName.ifBlank { "Someone" }} wants to share their calendar")
                    .setContentText("Open Family Calendar to accept or decline.")
                    .setContentIntent(openApp)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(context).notify(("invite/" + invitation.ownerId).hashCode(), notification)
            }
        }
        // Forget answered invitations, so a later re-invite notifies again.
        prefs.edit().putStringSet(KEY_NOTIFIED, if (allowed) pendingIds else notified intersect pendingIds).apply()
    }
}
