package com.example.studyplanner.model

data class FreeTimeSlot(
    val id: String = "",
    val userId: String = "",
    val isRecurring: Boolean = true,
    val dayOfWeek: Int = 1,
    val date: String = "",
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val description: String = ""
)