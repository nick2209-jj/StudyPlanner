package com.example.studyplanner

import android.os.Bundle
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.example.studyplanner.databinding.ActivityDashboardBinding
import com.example.studyplanner.notifications.StudyReminderScheduler
import com.example.studyplanner.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.time.LocalDate

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var navController: NavController
    private val authRepository = AuthRepository()
    private var reminderScheduleListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!authRepository.isLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        navController = navHostFragment.navController

        binding.bottomNav.setupWithNavController(navController)
        observeScheduleChangesForReminders()
        if (intent.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false)) {
            navController.navigate(R.id.scheduleFragment)
            intent.removeExtra(EXTRA_OPEN_SCHEDULE)
        }
    }

    private fun observeScheduleChangesForReminders() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        reminderScheduleListener = FirebaseFirestore.getInstance()
            .collection("schedules")
            .whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", LocalDate.now().toString())
            .addSnapshotListener { _, error ->
                if (error != null) {
                    Log.e(TAG, "Could not observe schedule changes for reminders", error)
                } else {
                    StudyReminderScheduler.refreshInBackground(applicationContext)
                }
            }
    }

    override fun onDestroy() {
        reminderScheduleListener?.remove()
        reminderScheduleListener = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DashboardActivity"
        const val EXTRA_OPEN_SCHEDULE = "openSchedule"
    }
}