package com.example.studyplanner.model

data class Subject(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val colorHex: String = "#6C4AB6",
    val totalHoursPerWeek: Int = 0,
    val priority: String = "MEDIUM",
    val userId: String = ""
)