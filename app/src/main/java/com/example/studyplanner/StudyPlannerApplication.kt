package com.example.studyplanner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class StudyPlannerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        applySavedTheme()
    }

    fun applySavedTheme() {
        val darkModeEnabled = getSharedPreferences(SETTINGS_FILE, MODE_PRIVATE)
            .getBoolean(DARK_MODE_KEY, false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
    }

    companion object {
        const val SETTINGS_FILE = "study_planner_settings"
        const val DARK_MODE_KEY = "dark_mode"
    }
}
