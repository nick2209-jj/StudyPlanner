package com.example.studyplanner.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StudyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == StudyReminderScheduler.ACTION_SHOW_REMINDER) {
            StudyReminderScheduler.showReminder(context, intent)
        }
    }
}
