package com.example.studyplanner.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.studyplanner.DashboardActivity
import com.example.studyplanner.R
import com.example.studyplanner.model.ScheduleSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

object StudyReminderScheduler {

    const val ACTION_SHOW_REMINDER = "com.example.studyplanner.SHOW_STUDY_REMINDER"
    const val EXTRA_SESSION_ID = "sessionId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SESSION_DATE = "sessionDate"
    const val EXTRA_START_MINUTES = "startMinutes"
    const val CHANNEL_ID = "study_session_reminders"

    private const val TAG = "StudyReminderScheduler"
    private const val SETTINGS_FILE = "study_planner_settings"
    private const val NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val REMINDERS_ENABLED = "study_reminders_enabled"
    private const val SCHEDULED_IDS = "scheduled_reminder_ids"
    private const val REMINDER_LEAD_MINUTES = 10L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()

    suspend fun refresh(context: Context) = refreshMutex.withLock {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        val oldIds = preferences.getStringSet(SCHEDULED_IDS, emptySet()).orEmpty().toSet()

        if (!remindersEnabled(preferences) || !canPostNotifications(appContext)) {
            oldIds.forEach { cancelAlarm(appContext, it) }
            preferences.edit().putStringSet(SCHEDULED_IDS, emptySet()).apply()
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            oldIds.forEach { cancelAlarm(appContext, it) }
            preferences.edit().putStringSet(SCHEDULED_IDS, emptySet()).apply()
            return
        }

        val today = LocalDate.now().toString()
        val documents = FirebaseFirestore.getInstance()
            .collection("schedules")
            .whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", today)
            .get()
            .await()

        createNotificationChannel(appContext)
        oldIds.forEach { cancelAlarm(appContext, it) }
        val scheduledIds = mutableSetOf<String>()
        documents.documents.forEach { document ->
            val session = document.toObject(ScheduleSession::class.java)
                ?.copy(id = document.id)
            if (session == null) {
                Log.w(TAG, "Skipping unreadable schedule session ${document.id}")
                return@forEach
            }
            val triggerAt = reminderTime(session) ?: return@forEach
            if (triggerAt <= System.currentTimeMillis()) return@forEach
            scheduleAlarm(appContext, session, triggerAt)
            scheduledIds += session.id
        }
        preferences.edit().putStringSet(SCHEDULED_IDS, scheduledIds).apply()
    }

    fun refreshInBackground(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                refresh(appContext)
            } catch (exception: Exception) {
                Log.e(TAG, "Could not refresh scheduled study reminders", exception)
            }
        }
    }

    fun cancelAll(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        preferences.getStringSet(SCHEDULED_IDS, emptySet()).orEmpty().toSet()
            .forEach { cancelAlarm(appContext, it) }
        preferences.edit().putStringSet(SCHEDULED_IDS, emptySet()).apply()
    }

    fun showReminder(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
        if (!remindersEnabled(preferences) || !canPostNotifications(context)) return

        createNotificationChannel(context)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.study_reminder_default_title)
        val date = intent.getStringExtra(EXTRA_SESSION_DATE).orEmpty()
        val startMinutes = intent.getIntExtra(EXTRA_START_MINUTES, 0)
        val time = formatTime(startMinutes)
        val body = context.getString(R.string.study_reminder_message, date, time)
        val openSchedule = Intent(context, DashboardActivity::class.java)
            .putExtra(DashboardActivity.EXTRA_OPEN_SCHEDULE, true)
        val contentIntent = PendingIntent.getActivity(
            context,
            reminderRequestCode(sessionId),
            openSchedule,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            NotificationManagerCompat.from(context).notify(
                reminderRequestCode(sessionId),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notifications)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
            )
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission was revoked before delivery", exception)
        }
    }

    private fun remindersEnabled(preferences: android.content.SharedPreferences): Boolean =
        preferences.getBoolean(NOTIFICATIONS_ENABLED, false) &&
            preferences.getBoolean(REMINDERS_ENABLED, false)

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun scheduleAlarm(context: Context, session: ScheduleSession, triggerAt: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = reminderPendingIntent(context, session)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun cancelAlarm(context: Context, sessionId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, StudyReminderReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
            .setData(Uri.parse("studyplanner://reminder/$sessionId"))
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderRequestCode(sessionId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun reminderPendingIntent(
        context: Context,
        session: ScheduleSession
    ): PendingIntent {
        val intent = Intent(context, StudyReminderReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
            .setData(Uri.parse("studyplanner://reminder/${session.id}"))
            .putExtra(EXTRA_SESSION_ID, session.id)
            .putExtra(
                EXTRA_TITLE,
                session.description.ifBlank { session.subjectName }
            )
            .putExtra(EXTRA_SESSION_DATE, session.sessionDate)
            .putExtra(EXTRA_START_MINUTES, session.startMinutes)
        return PendingIntent.getBroadcast(
            context,
            reminderRequestCode(session.id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun reminderTime(session: ScheduleSession): Long? {
        return try {
            val date = LocalDate.parse(session.sessionDate)
            require(session.startMinutes in 0 until 24 * 60)
            LocalDateTime.of(
                date,
                LocalTime.of(session.startMinutes / 60, session.startMinutes % 60)
            ).atZone(ZoneId.systemDefault())
                .minusMinutes(REMINDER_LEAD_MINUTES)
                .toInstant()
                .toEpochMilli()
        } catch (exception: DateTimeParseException) {
            Log.w(TAG, "Skipping schedule session with invalid date: ${session.id}", exception)
            null
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Skipping schedule session with invalid start time: ${session.id}", exception)
            null
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.study_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.study_reminder_channel_description)
            }
        )
    }

    private fun reminderRequestCode(sessionId: String): Int = sessionId.hashCode()

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, minute, suffix)
    }
}
