package com.example.studyplanner

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class StudyPlannerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    companion object {
        const val SETTINGS_FILE = "study_planner_settings"
    }
}
