package com.example.studyplanner.scheduler

import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Subject
import com.example.studyplanner.model.Task
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.ceil

data class StudyInterval(val startMinutes: Int, val endMinutes: Int) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

data class SchedulingResult(
    val proposedSessions: List<ScheduleSession>,
    val unscheduledActivities: List<String>,
    val restDays: List<LocalDate> = emptyList(),
    val breaksByDate: Map<LocalDate, List<StudyInterval>> = emptyMap()
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

        val availableMinutes = windowsByDate.values.flatten()
            .sumOf(::effectiveStudyMinutes)
        val restDays = windowsByDate.filterValues { it.isEmpty() }.keys.sorted()
        val capacityUnits = availableMinutes / TIME_UNIT_MINUTES
        if (capacityUnits == 0) {
            return SchedulingResult(
                emptyList(),
                buildCandidates(today, subjects, tasks, existingSessions).map { it.title } +
                    unmatchedTasks(subjects, tasks, existingSessions),
                restDays
            )
        }

        val candidates = buildCandidates(today, subjects, tasks, existingSessions)
        val selected = selectCandidates(candidates, capacityUnits)
        val selectedKeys = selected.mapTo(mutableSetOf()) { it.key }
        val unscheduled = candidates.filter { it.key !in selectedKeys }
            .map { "${it.title} (not enough available study time)" }
            .toMutableList()
            .apply { addAll(unmatchedTasks(subjects, tasks, existingSessions)) }

        val remaining = windowsByDate.mapValues { (_, intervals) -> intervals.toMutableList() }
        val breaksByDate = mutableMapOf<LocalDate, MutableList<StudyInterval>>()
        val dailySessionCounts = dates.associateWith { date ->
            existingSessions.count { it.sessionDate == date.toString() }
        }.toMutableMap()
        val datesByString = dates.associateBy(LocalDate::toString)
        val existingByDate = existingSessions
            .filter { it.sessionDate in datesByString }
            .groupBy { datesByString.getValue(it.sessionDate) }
        val continuousStudyMinutes = dates.associateWith { date ->
            trailingContinuousStudyMinutes(existingByDate[date].orEmpty())
        }.toMutableMap()
        val lastStudyEnd = existingByDate.mapNotNull { (date, sessions) ->
            sessions.maxByOrNull { it.endMinutes }?.let { date to it.endMinutes }
        }.toMap().toMutableMap()
        val subjectDailyCounts = existingSessions
            .mapNotNull { session ->
                datesByString[session.sessionDate]?.let { date ->
                    (date to session.subjectId) to 1
                }
            }
            .groupingBy { it.first }
            .eachCount()
            .toMutableMap()
        val placed = mutableListOf<ScheduleSession>()
        interleaveBySubject(selected).forEach { candidate ->
            val placement = findBestFit(
                remaining = remaining,
                dates = dates,
                candidate = candidate,
                dailySessionCounts = dailySessionCounts,
                subjectDailyCounts = subjectDailyCounts,
                continuousStudyMinutes = continuousStudyMinutes,
                lastStudyEnd = lastStudyEnd
            )
            if (placement == null) {
                unscheduled += "${candidate.title} (no single available slot is long enough)"
            } else {
                val date = placement.date
                val interval = remaining.getValue(date)[placement.intervalIndex]
                val sessionStart = interval.startMinutes +
                    if (placement.needsBreak) BREAK_MINUTES else 0
                val end = sessionStart + candidate.durationMinutes
                if (placement.needsBreak) {
                    breaksByDate.getOrPut(date) { mutableListOf() } += StudyInterval(
                        interval.startMinutes,
                        sessionStart
                    )
                    continuousStudyMinutes[date] = 0
                } else if (lastStudyEnd[date] != interval.startMinutes) {
                    continuousStudyMinutes[date] = 0
                }
                remaining.getValue(date)[placement.intervalIndex] =
                    StudyInterval(end, interval.endMinutes)
                dailySessionCounts[date] = (dailySessionCounts[date] ?: 0) + 1
                val subjectDay = date to candidate.subject.id
                subjectDailyCounts[subjectDay] = (subjectDailyCounts[subjectDay] ?: 0) + 1
                continuousStudyMinutes[date] =
                    (continuousStudyMinutes[date] ?: 0) + candidate.durationMinutes
                lastStudyEnd[date] = end
                placed += ScheduleSession(
                    subjectId = candidate.subject.id,
                    subjectName = candidate.subject.name,
                    taskId = candidate.task?.id.orEmpty(),
                    sessionDate = date.toString(),
                    startMinutes = sessionStart,
                    endMinutes = end,
                    description = candidate.title,
                    generatedActivityKey = "v2:$monday:${candidate.key}",
                    breakBeforeMinutes = if (placement.needsBreak) BREAK_MINUTES else 0
                )
            }
        }
        return SchedulingResult(
            proposedSessions = placed,
            unscheduledActivities = unscheduled,
            restDays = restDays,
            breaksByDate = breaksByDate.mapValues { (_, breaks) -> breaks.toList() }
        )
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
            val priorityMultiplier = when (subject.priority.uppercase()) {
                "HIGH" -> 2.0
                "MEDIUM" -> 1.5
                else -> 1.0
            }
            val targetMinutes = ceil(
                subject.totalHoursPerWeek.coerceAtLeast(0) * 60 * priorityMultiplier
            ).toInt()
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

    private fun selectCandidates(
        candidates: List<Candidate>,
        capacityUnits: Int
    ): List<Candidate> {
        val blockUnits = STANDARD_BLOCK_MINUTES / TIME_UNIT_MINUTES
        val coverageSlots = capacityUnits / blockUnits
        if (coverageSlots == 0 || candidates.isEmpty()) return emptyList()

        val coverageCandidates = candidates
            .groupBy { it.subject.id }
            .values
            .mapNotNull { subjectCandidates ->
                subjectCandidates.maxWithOrNull(
                    compareBy<Candidate> { it.value }
                        .thenBy { it.urgency }
                        .thenBy { it.key }
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { priorityValue(it.subject.priority) }
                    .thenByDescending { it.value }
                    .thenBy { it.subject.id }
            )
            .take(coverageSlots)

        val selectedKeys = coverageCandidates.mapTo(mutableSetOf()) { it.key }
        val remainingCapacity = capacityUnits - coverageCandidates.size * blockUnits
        val additional = chooseByKnapsack(
            candidates.filterNot { it.key in selectedKeys },
            remainingCapacity
        )
        return coverageCandidates + additional
    }

    private fun interleaveBySubject(candidates: List<Candidate>): List<Candidate> {
        val bySubject = candidates
            .groupBy { it.subject.id }
            .mapValues { (_, subjectCandidates) ->
                subjectCandidates.sortedWith(
                    compareByDescending<Candidate> { it.value }
                        .thenByDescending { it.urgency }
                        .thenBy { it.key }
                )
            }
        val subjectOrder = bySubject.keys.sortedWith(
            compareByDescending<String> { subjectId ->
                priorityValue(bySubject.getValue(subjectId).first().subject.priority)
            }.thenBy { it }
        )
        val result = mutableListOf<Candidate>()
        repeat(bySubject.values.maxOfOrNull { it.size } ?: 0) { round ->
            subjectOrder.forEach { subjectId ->
                bySubject.getValue(subjectId).getOrNull(round)?.let(result::add)
            }
        }
        return result
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

    private fun findBestFit(
        remaining: Map<LocalDate, MutableList<StudyInterval>>,
        dates: List<LocalDate>,
        candidate: Candidate,
        dailySessionCounts: Map<LocalDate, Int>,
        subjectDailyCounts: Map<Pair<LocalDate, String>, Int>,
        continuousStudyMinutes: Map<LocalDate, Int>,
        lastStudyEnd: Map<LocalDate, Int>
    ): Fit? =
        dates.mapNotNull { date ->
            val intervals = remaining.getValue(date)
            val index = intervals.indexOfFirst { interval ->
                val needsBreak = (continuousStudyMinutes[date] ?: 0) >=
                    MAX_CONTINUOUS_STUDY_MINUTES &&
                    lastStudyEnd[date] == interval.startMinutes
                interval.durationMinutes >= candidate.durationMinutes +
                    if (needsBreak) BREAK_MINUTES else 0
            }
            if (index < 0) {
                null
            } else {
                val interval = intervals[index]
                val needsBreak = (continuousStudyMinutes[date] ?: 0) >=
                    MAX_CONTINUOUS_STUDY_MINUTES &&
                    lastStudyEnd[date] == interval.startMinutes
                Fit(date, index, needsBreak)
            }
        }.minWithOrNull(
            compareBy<Fit> { subjectDailyCounts[it.date to candidate.subject.id] ?: 0 }
                .thenBy { dailySessionCounts[it.date] ?: 0 }
                .thenBy { it.date }
        )

    private fun effectiveStudyMinutes(interval: StudyInterval): Int {
        var studyBlocks = 0
        while (
            (studyBlocks + 1) * STANDARD_BLOCK_MINUTES +
            (studyBlocks / 2) * BREAK_MINUTES <= interval.durationMinutes
        ) {
            studyBlocks++
        }
        return studyBlocks * STANDARD_BLOCK_MINUTES
    }

    private fun trailingContinuousStudyMinutes(sessions: List<ScheduleSession>): Int {
        var total = 0
        var nextStart: Int? = null
        sessions.sortedByDescending { it.startMinutes }.forEach { session ->
            if (nextStart != null && session.endMinutes != nextStart) return total
            val duration = session.endMinutes - session.startMinutes
            if (duration <= 0) return@forEach
            total += duration
            nextStart = session.startMinutes
        }
        return total
    }

    private data class Fit(
        val date: LocalDate,
        val intervalIndex: Int,
        val needsBreak: Boolean
    )

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
        const val MAX_CONTINUOUS_STUDY_MINUTES = 120
        const val BREAK_MINUTES = 15
        const val MINUTES_PER_DAY = 24 * 60
    }
}
