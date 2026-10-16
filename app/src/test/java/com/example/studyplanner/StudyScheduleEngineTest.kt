package com.example.studyplanner

import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Subject
import com.example.studyplanner.model.Task
import com.example.studyplanner.scheduler.StudyInterval
import com.example.studyplanner.scheduler.StudyScheduleEngine
import com.example.studyplanner.validation.SubjectValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudyScheduleEngineTest {

    private val monday = LocalDate.parse("2026-10-19")
    private val subject = Subject(
        id = "math",
        name = "Mathematics",
        totalHoursPerWeek = 0
    )
    private val engine = StudyScheduleEngine()

    @Test
    fun knapsackSelectsHighValueTaskWhenOnlyOneHourIsAvailable() {
        val result = engine.generateWeek(
            monday,
            listOf(subject),
            listOf(
                Task("important", "Important", "Mathematics", priority = Priority.HIGH),
                Task("routine", "Routine", "Mathematics", priority = Priority.LOW)
            ),
            listOf(weeklySlot(9 * 60, 10 * 60)),
            emptyList(),
            today = monday
        )

        assertEquals(listOf("important"), result.proposedSessions.map { it.taskId })
        assertTrue(result.unscheduledActivities.any { it.startsWith("Routine") })
    }

    @Test
    fun greedyPlacementOrdersSelectedSessionsByPriorityAndUsesAvailableWindow() {
        val result = engine.generateWeek(
            monday,
            listOf(subject),
            listOf(
                Task("low", "Low", "Mathematics", priority = Priority.LOW),
                Task("high", "High", "Mathematics", priority = Priority.HIGH)
            ),
            listOf(weeklySlot(9 * 60, 11 * 60)),
            emptyList(),
            today = monday
        )

        assertEquals("high", result.proposedSessions.first().taskId)
        assertEquals(9 * 60, result.proposedSessions.first().startMinutes)
        assertEquals(11 * 60, result.proposedSessions.last().endMinutes)
    }

    @Test
    fun intervalConflictUsesHalfOpenIntervals() {
        assertTrue(engine.hasOverlap(StudyInterval(60, 120), StudyInterval(90, 150)))
        assertFalse(engine.hasOverlap(StudyInterval(60, 120), StudyInterval(120, 180)))
    }

    @Test
    fun generatedSessionsStayInsideAvailabilityAndAvoidFixedCommitments() {
        val result = engine.generateWeek(
            monday,
            listOf(subject),
            listOf(
                Task("first", "First", "Mathematics", priority = Priority.HIGH),
                Task("second", "Second", "Mathematics", priority = Priority.MEDIUM)
            ),
            listOf(weeklySlot(9 * 60, 12 * 60)),
            listOf(
                ScheduleSession(
                    subjectId = subject.id,
                    sessionDate = monday.toString(),
                    startMinutes = 10 * 60,
                    endMinutes = 10 * 60 + 30
                )
            ),
            today = monday
        )

        assertEquals(2, result.proposedSessions.size)
        result.proposedSessions.forEach { session ->
            assertTrue(session.startMinutes >= 9 * 60)
            assertTrue(session.endMinutes <= 12 * 60)
            assertFalse(
                engine.hasOverlap(
                    StudyInterval(session.startMinutes, session.endMinutes),
                    StudyInterval(10 * 60, 10 * 60 + 30)
                )
            )
        }
    }

    @Test
    fun insufficientAvailabilityReportsActivitiesRatherThanDroppingThem() {
        val result = engine.generateWeek(
            monday,
            listOf(subject),
            listOf(Task("long", "Long assignment", "Mathematics")),
            listOf(weeklySlot(9 * 60, 9 * 60 + 30)),
            emptyList(),
            today = monday
        )

        assertTrue(result.proposedSessions.isEmpty())
        assertTrue(result.unscheduledActivities.any { it.contains("Long assignment") })
    }

    @Test
    fun taskAlreadyOnScheduleIsNotDuplicated() {
        val result = engine.generateWeek(
            monday,
            listOf(subject),
            listOf(Task("existing-task", "Already planned", "Mathematics")),
            listOf(weeklySlot(9 * 60, 11 * 60)),
            listOf(
                ScheduleSession(
                    subjectId = subject.id,
                    taskId = "existing-task",
                    sessionDate = monday.toString(),
                    startMinutes = 9 * 60,
                    endMinutes = 10 * 60
                )
            ),
            today = monday
        )

        assertTrue(result.proposedSessions.none { it.taskId == "existing-task" })
    }

    @Test
    fun recurringAndDateSpecificFreeTimeConflictsAreDetected() {
        val recurring = weeklySlot(9 * 60, 10 * 60)
        val sameDay = FreeTimeSlot(
            isRecurring = false,
            date = monday.toString(),
            startMinutes = 9 * 60 + 30,
            endMinutes = 10 * 60 + 30
        )

        assertTrue(engine.hasFreeTimeConflict(sameDay, listOf(recurring)))
        assertFalse(
            engine.hasFreeTimeConflict(
                sameDay.copy(startMinutes = 10 * 60, endMinutes = 11 * 60),
                listOf(recurring)
            )
        )
    }

    @Test
    fun invalidSubjectAndEmptySchedulingInputsAreHandled() {
        try {
            SubjectValidator.validate(subject.copy(name = "", totalHoursPerWeek = 2))
            throw AssertionError("Blank subject name should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
        try {
            SubjectValidator.validate(subject.copy(totalHoursPerWeek = 81))
            throw AssertionError("Out-of-range target should be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }

        val empty = engine.generateWeek(monday, emptyList(), emptyList(), emptyList(), emptyList(), monday)
        assertTrue(empty.proposedSessions.isEmpty())
        assertTrue(empty.unscheduledActivities.isEmpty())
    }

    private fun weeklySlot(start: Int, end: Int) = FreeTimeSlot(
        isRecurring = true,
        dayOfWeek = monday.dayOfWeek.value,
        startMinutes = start,
        endMinutes = end
    )
}
