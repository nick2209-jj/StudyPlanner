package com.example.studyplanner.validation

import com.example.studyplanner.model.Task
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

object TaskValidator {

    fun validate(task: Task) {
        require(task.title.isNotBlank()) { "Task title is required" }
        require(task.subject.isNotBlank()) { "Task subject is required" }
        if (task.dueTime.isNotBlank()) {
            try {
                LocalDate.parse(task.dueTime)
            } catch (_: DateTimeParseException) {
                try {
                    LocalTime.parse(task.dueTime)
                } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException(
                        "Enter the due date as YYYY-MM-DD or time as HH:MM"
                    )
                }
            }
        }
    }
}
