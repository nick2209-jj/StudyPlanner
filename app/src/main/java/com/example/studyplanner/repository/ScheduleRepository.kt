package com.example.studyplanner.repository

import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.notifications.StudyReminderScheduler
import com.example.studyplanner.scheduler.StudyInterval
import com.example.studyplanner.scheduler.StudyScheduleEngine
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

class ScheduleRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val engine = StudyScheduleEngine()
    private fun currentUid() = requireNotNull(auth.currentUser?.uid) {
        "You must be signed in to access your schedule"
    }
    private fun sessionsRef() = firestore.collection("schedules")

    suspend fun getSessions(startDate: String, endDate: String): List<ScheduleSession> {
        val uid = currentUid()
        return sessionsRef().whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", startDate)
            .whereLessThanOrEqualTo("sessionDate", endDate)
            .get()
            .await()
            .documents.mapNotNull { it.toObject(ScheduleSession::class.java) }
            .sortedWith(compareBy({ it.sessionDate }, { it.startMinutes }))
    }

    fun observeSessions(
        startDate: String,
        endDate: String,
        onUpdate: (List<ScheduleSession>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val uid = currentUid()
        return sessionsRef().whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", startDate)
            .whereLessThanOrEqualTo("sessionDate", endDate)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> onError(error)
                    snapshot != null -> onUpdate(
                        snapshot.documents.mapNotNull { it.toObject(ScheduleSession::class.java) }
                            .sortedWith(compareBy({ it.sessionDate }, { it.startMinutes }))
                    )
                    else -> onError(IllegalStateException("Firestore returned no schedule snapshot"))
                }
            }
    }

    suspend fun saveManualSession(session: ScheduleSession) {
        val uid = currentUid()
        validate(session)
        require(session.subjectId in ownedDocumentIds("subjects", uid)) {
            "Select one of your saved subjects"
        }
        if (session.taskId.isNotBlank()) {
            require(session.taskId in ownedDocumentIds("tasks", uid)) {
                "Select one of your saved tasks"
            }
        }
        val ref = if (session.id.isBlank()) sessionsRef().document()
        else sessionsRef().document(session.id)
        val subjectRef = firestore.collection("subjects").document(session.subjectId)
        val dayQuery = sessionsRef().whereEqualTo("userId", uid)
            .whereEqualTo("sessionDate", session.sessionDate)
        val sameDayRefs = dayQuery.get().await().documents
            .map { it.reference }
            .filterNot { it.id == ref.id }

        firestore.runTransaction { transaction ->
            val subject = transaction.get(subjectRef).toObject(
                com.example.studyplanner.model.Subject::class.java
            )
            check(subject?.userId == uid) { "Select one of your saved subjects" }
            if (session.id.isNotBlank()) {
                val existingDocument = transaction.get(ref)
                check(existingDocument.getString("userId") == uid) {
                    "Schedule session not found for the current user"
                }
            }
            val sameDay = sameDayRefs.mapNotNull { otherRef ->
                transaction.get(otherRef).toObject(ScheduleSession::class.java)
            }
            val candidate = StudyInterval(session.startMinutes, session.endMinutes)
            check(sameDay.none {
                engine.hasOverlap(candidate, StudyInterval(it.startMinutes, it.endMinutes))
            }) { "This session overlaps another scheduled session" }
            transaction.set(ref, session.copy(id = ref.id, userId = uid, generatedActivityKey = ""))
            true
        }.await()
        refreshStudyReminders()
    }

    suspend fun deleteSession(sessionId: String) {
        val uid = currentUid()
        val ref = sessionsRef().document(sessionId)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(ref)
            check(existing.getString("userId") == uid) {
                "Schedule session not found for the current user"
            }
            transaction.delete(ref)
            true
        }.await()
        refreshStudyReminders()
    }

    suspend fun updateSessionCompletion(sessionId: String, isCompleted: Boolean) {
        val uid = currentUid()
        val ref = sessionsRef().document(sessionId)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(ref)
            check(existing.getString("userId") == uid) {
                "Schedule session not found for the current user"
            }
            check(!existing.getString("generatedActivityKey").isNullOrBlank()) {
                "Only generated study sessions can be completed from the task list"
            }
            transaction.update(ref, "isCompleted", isCompleted)
            true
        }.await()
    }

    suspend fun saveGeneratedSessions(proposal: List<ScheduleSession>) {
        if (proposal.isEmpty()) return
        require(proposal.size <= 400) { "Schedule proposal contains too many sessions to save at once" }
        val uid = currentUid()
        proposal.forEach(::validate)
        require(proposal.all { it.generatedActivityKey.isNotBlank() }) {
            "Generated session is missing its activity key"
        }
        require(proposal.map { it.generatedActivityKey }.distinct().size == proposal.size) {
            "The study plan contains duplicate activities"
        }
        val ownedSubjects = ownedDocumentIds("subjects", uid)
        val ownedTasks = ownedDocumentIds("tasks", uid)
        require(proposal.all { it.subjectId in ownedSubjects }) {
            "A proposed session refers to a subject that is not yours"
        }
        require(proposal.all { it.taskId.isBlank() || it.taskId in ownedTasks }) {
            "A proposed session refers to a task that is not yours"
        }
        val proposalIntervals = mutableMapOf<String, MutableList<StudyInterval>>()
        proposal.forEach { session ->
            val interval = StudyInterval(session.startMinutes, session.endMinutes)
            val dayIntervals = proposalIntervals.getOrPut(session.sessionDate) { mutableListOf() }
            check(dayIntervals.none { engine.hasOverlap(interval, it) }) {
                "The proposed sessions overlap each other"
            }
            dayIntervals += interval
        }
        val start = proposal.minOf { it.sessionDate }
        val end = proposal.maxOf { it.sessionDate }
        val query = sessionsRef().whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", start)
            .whereLessThanOrEqualTo("sessionDate", end)
        val existingDocuments = query.get().await().documents
        val existingRefs = existingDocuments.map { it.reference }
        val existingGeneratedRefs = existingDocuments.mapNotNull { document ->
            document.getString("generatedActivityKey")
                ?.takeIf(String::isNotBlank)
                ?.let { it to document.reference }
        }.toMap()

        proposal.chunked(MAX_GENERATED_SESSIONS_PER_TRANSACTION).forEach { chunk ->
            firestore.runTransaction { transaction ->
                val existing = existingRefs.mapNotNull { existingRef ->
                    transaction.get(existingRef).toObject(ScheduleSession::class.java)
                }
                val references = chunk.map { session ->
                    val stableId = UUID.nameUUIDFromBytes(
                        "$uid|${session.generatedActivityKey}".toByteArray(Charsets.UTF_8)
                    ).toString()
                    existingGeneratedRefs[session.generatedActivityKey]
                        ?: sessionsRef().document("${uid}_$stableId")
                }
                val existingGenerated = references.map { transaction.get(it) }
                val subjects = chunk.map { it.subjectId }.distinct().associateWith { subjectId ->
                    transaction.get(firestore.collection("subjects").document(subjectId))
                        .getString("userId")
                }
                val tasks = chunk.mapNotNull { it.taskId.takeIf(String::isNotBlank) }
                    .distinct()
                    .associateWith { taskId ->
                        transaction.get(firestore.collection("tasks").document(taskId))
                            .getString("userId")
                    }

                chunk.forEachIndexed { index, session ->
                    val generatedDocument = existingGenerated[index]
                    val expectedSession = session.copy(
                        id = references[index].id,
                        userId = uid,
                        isCompleted = generatedDocument
                            .takeIf { it.exists() }
                            ?.getBoolean("isCompleted")
                            ?: false
                    )
                    if (generatedDocument.exists()) {
                        check(
                            generatedDocument.toObject(ScheduleSession::class.java) ==
                                expectedSession
                        ) {
                            "A session with this generated ID already exists; nothing was overwritten"
                        }
                    }
                    val interval = StudyInterval(session.startMinutes, session.endMinutes)
                    check(existing.none {
                        it.generatedActivityKey != session.generatedActivityKey &&
                            it.sessionDate == session.sessionDate &&
                            engine.hasOverlap(interval, StudyInterval(it.startMinutes, it.endMinutes))
                    }) { "The proposal conflicts with an existing schedule session" }
                    check(subjects[session.subjectId] == uid) {
                        "A proposed session refers to a subject that is not yours"
                    }
                    check(session.taskId.isBlank() || tasks[session.taskId] == uid) {
                        "A proposed session refers to a task that is not yours"
                    }
                }

                chunk.forEachIndexed { index, session ->
                    if (!existingGenerated[index].exists()) {
                        val ref = references[index]
                        transaction.set(ref, session.copy(id = ref.id, userId = uid))
                    }
                }
                true
            }
                .await()
        }
        refreshStudyReminders()
    }

    private suspend fun ownedDocumentIds(collection: String, uid: String): Set<String> =
        firestore.collection(collection)
            .whereEqualTo("userId", uid)
            .get()
            .await()
            .documents
            .mapTo(mutableSetOf()) { it.id }

    private fun refreshStudyReminders() {
        StudyReminderScheduler.refreshInBackground(
            FirebaseApp.getInstance().applicationContext
        )
    }

    private fun validate(session: ScheduleSession) {
        require(session.subjectId.isNotBlank()) { "A subject is required for each study session" }
        try {
            LocalDate.parse(session.sessionDate)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Choose a valid session date")
        }
        require(engine.isValidInterval(StudyInterval(session.startMinutes, session.endMinutes))) {
            "The end time must be later than the start time"
        }
    }

    private companion object {
        const val MAX_GENERATED_SESSIONS_PER_TRANSACTION = 3
    }
}
