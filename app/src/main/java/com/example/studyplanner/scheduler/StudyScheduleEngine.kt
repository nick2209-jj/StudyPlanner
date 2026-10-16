package com.example.studyplanner.scheduler

import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Subject
import com.example.studyplanner.model.Task
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class StudyInterval(val startMinutes: Int, val endMinutes: Int) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

data class SchedulingResult(
    val proposedSessions: List<ScheduleSession>,
    val unscheduledActivities: List<String>
)

class StudyScheduleEngine {

    fun generateWeek(
        monday: LocalDate,
        subjects: List<Subject>,
        tasks: List<Task>,
        freeTime: List<FreeTimeSlot>,
        existingSessions: List<ScheduleSession>,
        today: LocalDate = LocalDate.now()
    ): SchedulingResult {
        val weekEnd = monday.plusDays(6)
        val dates = generateSequence(maxOf(monday, today)) { date ->
            date.plusDays(1).takeIf { it <= weekEnd }
        }.toList()
        if (dates.isEmpty()) return SchedulingResult(emptyList(), emptyList())

        val windowsByDate = dates.associateWith { date ->
            val availability = freeTime.asSequence()
                .filter { slot ->
                    if (slot.isRecurring) slot.dayOfWeek == date.dayOfWeek.value
                    else slot.date == date.toString()
                }
                .map { StudyInterval(it.startMinutes, it.endMinutes) }
                .filter(::isValidInterval)
                .toList()
                .let(::mergeIntervals)

            subtractIntervals(
                availability,
                existingSessions.asSequence()
                    .filter { it.sessionDate == date.toString() }
                    .map { StudyInterval(it.startMinutes, it.endMinutes) }
                    .filter(::isValidInterval)
                    .toList()
            )
        }

        val availableMinutes = windowsByDate.values.flatten().sumOf { it.durationMinutes }
        val capacityUnits = availableMinutes / TIME_UNIT_MINUTES
        if (capacityUnits == 0) {
            return SchedulingResult(
                emptyList(),
                buildCandidates(today, subjects, tasks, existingSessions).map { it.title } +
                    unmatchedTasks(subjects, tasks, existingSessions)
            )
        }

        val candidates = buildCandidates(today, subjects, tasks, existingSessions)
        val selected = chooseByKnapsack(candidates, capacityUnits)
        val selectedKeys = selected.mapTo(mutableSetOf()) { it.key }
        val unscheduled = candidates.filter { it.key !in selectedKeys }
            .map { "${it.title} (not enough available study time)" }
            .toMutableList()
            .apply { addAll(unmatchedTasks(subjects, tasks, existingSessions)) }

        val remaining = windowsByDate.mapValues { (_, intervals) -> intervals.toMutableList() }
        val placed = mutableListOf<ScheduleSession>()
        selected.sortedWith(
            compareByDescending<Candidate> { it.value }
                .thenByDescending { it.urgency }
                .thenBy { it.key }
        ).forEach { candidate ->
            val placement = findEarliestFit(remaining, dates, candidate.durationMinutes)
            if (placement == null) {
                unscheduled += "${candidate.title} (no single available slot is long enough)"
            } else {
                val (date, intervalIndex) = placement
                val interval = remaining.getValue(date)[intervalIndex]
                val end = interval.startMinutes + candidate.durationMinutes
                remaining.getValue(date)[intervalIndex] =
                    StudyInterval(end, interval.endMinutes)
                placed += ScheduleSession(
                    subjectId = candidate.subject.id,
                    subjectName = candidate.subject.name,
                    taskId = candidate.task?.id.orEmpty(),
                    sessionDate = date.toString(),
                    startMinutes = interval.startMinutes,
                    endMinutes = end,
                    description = candidate.title,
                    generatedActivityKey = "${monday}:${candidate.key}"
                )
            }
        }
        return SchedulingResult(placed, unscheduled)
    }

    fun hasOverlap(first: StudyInterval, second: StudyInterval): Boolean =
        first.startMinutes < second.endMinutes && second.startMinutes < first.endMinutes

    fun isValidInterval(interval: StudyInterval): Boolean =
        interval.startMinutes in 0 until MINUTES_PER_DAY &&
            interval.endMinutes in 1..MINUTES_PER_DAY &&
            interval.endMinutes > interval.startMinutes

    fun hasFreeTimeConflict(
        candidate: FreeTimeSlot,
        existing: List<FreeTimeSlot>
    ): Boolean {
        val candidateInterval = StudyInterval(candidate.startMinutes, candidate.endMinutes)
        if (!isValidInterval(candidateInterval)) return true
        return existing.any { slot ->
            if (candidate.id.isNotBlank() && slot.id == candidate.id) return@any false
            val sameDate = !candidate.isRecurring && !slot.isRecurring &&
                candidate.date == slot.date
            val sameRecurringDay = candidate.isRecurring && slot.isRecurring &&
                candidate.dayOfWeek == slot.dayOfWeek
            val recurringAndDate = candidate.isRecurring != slot.isRecurring &&
                if (candidate.isRecurring) {
                    runCatching { LocalDate.parse(slot.date).dayOfWeek.value }
                        .getOrNull() == candidate.dayOfWeek
                } else {
                    runCatching { LocalDate.parse(candidate.date).dayOfWeek.value }
                        .getOrNull() == slot.dayOfWeek
                }
            val overlaps = hasOverlap(candidateInterval, StudyInterval(slot.startMinutes, slot.endMinutes))
            (sameDate || sameRecurringDay || recurringAndDate) && overlaps
        }
    }

    private fun buildCandidates(
        today: LocalDate,
        subjects: List<Subject>,
        tasks: List<Task>,
        sessions: List<ScheduleSession>
    ): List<Candidate> {
        val subjectByName = subjects.associateBy { it.name.trim().lowercase() }
        val scheduledTaskIds = sessions.mapNotNullTo(mutableSetOf()) { it.taskId.takeIf(String::isNotBlank) }
        val completedMinutesBySubject = sessions.groupBy { it.subjectId }
            .mapValues { (_, items) -> items.sumOf { (it.endMinutes - it.startMinutes).coerceAtLeast(0) } }
        val candidates = mutableListOf<Candidate>()
        val candidateSubjectNames = tasks.filterNot { it.isCompleted || it.id in scheduledTaskIds }
            .mapTo(mutableSetOf()) { it.subject.trim().lowercase() }

        tasks.asSequence()
            .filterNot { it.isCompleted || it.id.isBlank() || it.id in scheduledTaskIds }
            .forEach { task ->
                val subject = subjectByName[task.subject.trim().lowercase()]
                if (subject != null) {
                    val urgency = deadlineUrgency(task.dueTime, today)
                    val taskPriority = priorityValue(task.priority)
                    val subjectPriority = priorityValue(subject.priority)
                    // A 60-minute block is the standard indivisible estimate because tasks have no duration field.
                    // Priority contributes 100 points per level, ISO-date urgency 60 per level,
                    // and subject priority 25 per level; a near deadline can outrank a lower-urgency task.
                    val value = taskPriority * 100 + urgency * 60 + subjectPriority * 25
                    candidates += Candidate(
                        key = "task:${task.id}",
                        title = task.title.ifBlank { task.subject },
                        subject = subject,
                        task = task,
                        durationMinutes = STANDARD_BLOCK_MINUTES,
                        value = value,
                        urgency = urgency
                    )
                }
            }

        subjects.sortedBy { it.id }.forEach { subject ->
            val targetMinutes = subject.totalHoursPerWeek.coerceAtLeast(0) * 60
            val remainingMinutes = targetMinutes -
                (completedMinutesBySubject[subject.id] ?: 0)
            val blockCount = (remainingMinutes.coerceAtLeast(0) + STANDARD_BLOCK_MINUTES - 1) /
                STANDARD_BLOCK_MINUTES
            repeat(blockCount) { index ->
                val subjectPriority = priorityValue(subject.priority)
                val targetBonus = if (subject.name.trim().lowercase() in candidateSubjectNames) 1 else 0
                candidates += Candidate(
                    key = "subject:${subject.id}:$index",
                    title = "${subject.name} study block ${index + 1}",
                    subject = subject,
                    task = null,
                    durationMinutes = STANDARD_BLOCK_MINUTES,
                    value = subjectPriority * 100 + targetBonus * 25,
                    urgency = 0
                )
            }
        }
        return candidates
    }

    private fun unmatchedTasks(
        subjects: List<Subject>,
        tasks: List<Task>,
        sessions: List<ScheduleSession>
    ): List<String> {
        val subjectsByName = subjects.mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        val scheduledTaskIds = sessions.mapNotNullTo(mutableSetOf()) {
            it.taskId.takeIf(String::isNotBlank)
        }
        return tasks.filter {
            !it.isCompleted &&
                it.id.isNotBlank() &&
                it.id !in scheduledTaskIds &&
                it.subject.trim().lowercase() !in subjectsByName
        }.map { "${it.title.ifBlank { it.subject }} (add its subject before scheduling)" }
    }

    /**
     * Dynamic-programming 0/1 Knapsack in 15-minute units. Descending capacity
     * prevents reusing an activity; the decision table allows reconstruction.
     * Time is O(activityCount * capacityUnits), space is O(activityCount * capacityUnits).
     * Capacity is bounded by one week (672 units), so the byte decision table is small.
     */
    private fun chooseByKnapsack(candidates: List<Candidate>, capacityUnits: Int): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val dp = IntArray(capacityUnits + 1)
        val selectedAt = Array(candidates.size) { ByteArray(capacityUnits + 1) }

        candidates.forEachIndexed { index, candidate ->
            val weight = candidate.durationMinutes / TIME_UNIT_MINUTES
            for (capacity in capacityUnits downTo weight) {
                val proposedValue = dp[capacity - weight] + candidate.value
                if (proposedValue > dp[capacity]) {
                    dp[capacity] = proposedValue
                    selectedAt[index][capacity] = 1
                }
            }
        }

        val selected = mutableListOf<Candidate>()
        var capacity = capacityUnits
        for (index in candidates.indices.reversed()) {
            if (selectedAt[index][capacity].toInt() == 1) {
                selected += candidates[index]
                capacity -= candidates[index].durationMinutes / TIME_UNIT_MINUTES
            }
        }
        return selected
    }

    /**
     * Candidates are value-ordered, then assigned to the earliest interval that
     * can contain the whole block. Greedy placement is deterministic, not globally optimal.
     */
    private fun findEarliestFit(
        remaining: Map<LocalDate, MutableList<StudyInterval>>,
        dates: List<LocalDate>,
        durationMinutes: Int
    ): Pair<LocalDate, Int>? {
        for (date in dates) {
            val intervals = remaining.getValue(date)
            val index = intervals.indexOfFirst { it.durationMinutes >= durationMinutes }
            if (index >= 0) return date to index
        }
        return null
    }

    private fun mergeIntervals(intervals: List<StudyInterval>): List<StudyInterval> {
        val merged = mutableListOf<StudyInterval>()
        intervals.sortedBy { it.startMinutes }.forEach { next ->
            val last = merged.lastOrNull()
            if (last == null || next.startMinutes > last.endMinutes) {
                merged += next
            } else {
                merged[merged.lastIndex] =
                    StudyInterval(last.startMinutes, maxOf(last.endMinutes, next.endMinutes))
            }
        }
        return merged
    }

    /** Sort-and-sweep subtraction: O((A + C) log(A + C) + K), where K is the number of overlaps. */
    private fun subtractIntervals(
        availability: List<StudyInterval>,
        commitments: List<StudyInterval>
    ): List<StudyInterval> {
        val sortedAvailability = availability.sortedBy { it.startMinutes }
        val sortedCommitments = commitments.sortedBy { it.startMinutes }
        val result = mutableListOf<StudyInterval>()
        var firstPossibleCommitment = 0
        sortedAvailability.forEach { window ->
            while (
                firstPossibleCommitment < sortedCommitments.size &&
                sortedCommitments[firstPossibleCommitment].endMinutes <= window.startMinutes
            ) {
                firstPossibleCommitment++
            }
            val freeParts = mutableListOf<StudyInterval>()
            var cursor = window.startMinutes
            var commitmentIndex = firstPossibleCommitment
            while (
                commitmentIndex < sortedCommitments.size &&
                sortedCommitments[commitmentIndex].startMinutes < window.endMinutes
            ) {
                val commitment = sortedCommitments[commitmentIndex]
                val start = maxOf(commitment.startMinutes, window.startMinutes)
                val end = minOf(commitment.endMinutes, window.endMinutes)
                if (end > cursor) {
                    if (start > cursor) freeParts += StudyInterval(cursor, start)
                    cursor = end
                }
                commitmentIndex++
            }
            if (cursor < window.endMinutes) freeParts += StudyInterval(cursor, window.endMinutes)
            result.addAll(freeParts)
            while (
                firstPossibleCommitment < sortedCommitments.size &&
                sortedCommitments[firstPossibleCommitment].endMinutes <= window.endMinutes
            ) {
                firstPossibleCommitment++
            }
        }
        return result
    }

    private fun priorityValue(priority: Priority): Int = when (priority) {
        Priority.HIGH -> 3
        Priority.MEDIUM -> 2
        Priority.LOW -> 1
    }

    private fun priorityValue(priority: String): Int = when (priority.uppercase()) {
        "HIGH" -> 3
        "LOW" -> 1
        else -> 2
    }

    private fun deadlineUrgency(dueTime: String, weekStart: LocalDate): Int {
        val dueDate = try {
            LocalDate.parse(dueTime)
        } catch (_: DateTimeParseException) {
            return 0
        }
        val daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(weekStart, dueDate)
        return when {
            daysUntilDue < 0 -> 4
            daysUntilDue <= 1 -> 3
            daysUntilDue <= 7 -> 2
            else -> 0
        }
    }

    private data class Candidate(
        val key: String,
        val title: String,
        val subject: Subject,
        val task: Task?,
        val durationMinutes: Int,
        val value: Int,
        val urgency: Int
    )

    companion object {
        const val TIME_UNIT_MINUTES = 15
        const val STANDARD_BLOCK_MINUTES = 60
        const val MINUTES_PER_DAY = 24 * 60
    }
}
