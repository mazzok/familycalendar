package com.familycalendar

import android.app.Application
import com.familycalendar.notifications.ChangeNotifier
import com.familycalendar.notifications.InvitationNotifier
import com.familycalendar.notifications.ReminderScheduler
import com.familycalendar.notifications.RequestNotifier
import com.familycalendar.notifications.ReminderSyncWorker

class FamilyCalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderScheduler.createChannel(this)
        InvitationNotifier.createChannel(this)
        RequestNotifier.createChannel(this)
        ChangeNotifier.createChannel(this)
        ReminderSyncWorker.schedulePeriodic(this)
    }
}
