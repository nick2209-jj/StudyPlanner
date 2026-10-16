package com.example.studyplanner.model

data class ScheduleSession(
    val id: String = "",
    val userId: String = "",
    val subjectId: String = "",
    val subjectName: String = "",
    val taskId: String = "",
    val sessionDate: String = "",
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val description: String = "",
    val generatedActivityKey: String = ""
)
