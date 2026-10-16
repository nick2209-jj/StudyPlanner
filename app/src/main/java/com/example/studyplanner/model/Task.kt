package com.example.studyplanner.model

data class Task(
    val id: String = "",
    val title: String = "",
    val subject: String = "",
    val dueTime: String = "",
    val priority: Priority = Priority.MEDIUM,
    var isCompleted: Boolean = false,
    val description: String = "",
    val userId: String = ""
)

enum class Priority { HIGH, MEDIUM, LOW }