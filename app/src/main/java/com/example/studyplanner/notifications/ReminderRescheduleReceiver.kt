package com.example.studyplanner.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                StudyReminderScheduler.refresh(appContext)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not restore study reminders after ${intent.action}", exception)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReschedule"
    }
}
