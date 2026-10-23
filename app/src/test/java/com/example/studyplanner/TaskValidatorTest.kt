package com.example.studyplanner

import com.example.studyplanner.model.Task
import com.example.studyplanner.validation.TaskValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskValidatorTest {

    @Test
    fun acceptsOptionalOrIsoDueDate() {
        TaskValidator.validate(Task(title = "Review", subject = "Math"))
        TaskValidator.validate(
            Task(title = "Review", subject = "Math", dueTime = "2026-10-21")
        )
        TaskValidator.validate(Task(title = "Review", subject = "Math", dueTime = "17:30"))
    }

    @Test
    fun rejectsUnparseableDueDate() {
        try {
            TaskValidator.validate(
                Task(title = "Review", subject = "Math", dueTime = "next Friday")
            )
            throw AssertionError("Expected an invalid due date to be rejected")
        } catch (exception: IllegalArgumentException) {
            assertEquals(
                "Enter the due date as YYYY-MM-DD or time as HH:MM",
                exception.message
            )
        }
    }
}
