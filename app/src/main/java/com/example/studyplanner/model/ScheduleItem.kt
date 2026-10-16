package com.example.studyplanner.model

data class ScheduleItem(
    val id: Int,
    val subject: String,
    val topic: String,
    val startTime: String,
    val endTime: String,
    val colorHex: String,
    val isNow: Boolean = false
)

data class DayItem(
    val label: String,   // "Mon"
    val date: String,    // "09"
    var isSelected: Boolean = false
)