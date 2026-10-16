package com.example.studyplanner.validation

import com.example.studyplanner.model.Subject

object SubjectValidator {
    fun validate(subject: Subject) {
        require(subject.name.isNotBlank()) { "Subject name is required" }
        require(subject.totalHoursPerWeek in 1..80) {
            "Weekly study hours must be between 1 and 80"
        }
        require(subject.priority in setOf("LOW", "MEDIUM", "HIGH")) {
            "Select a valid subject priority"
        }
    }
}
