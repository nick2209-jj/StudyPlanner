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
            val existingDocument = transaction.get(ref)
            if (session.id.isNotBlank()) {
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

    suspend fun saveGeneratedSessions(proposal: List<ScheduleSession>) {
        if (proposal.isEmpty()) return
        require(proposal.size <= 400) { "Schedule proposal contains too many sessions to save at once" }
        val uid = currentUid()
        proposal.forEach(::validate)
        val start = proposal.minOf { it.sessionDate }
        val end = proposal.maxOf { it.sessionDate }
        val query = sessionsRef().whereEqualTo("userId", uid)
            .whereGreaterThanOrEqualTo("sessionDate", start)
            .whereLessThanOrEqualTo("sessionDate", end)
        val existingRefs = query.get().await().documents.map { it.reference }

        firestore.runTransaction { transaction ->
            val existing = existingRefs.mapNotNull { existingRef ->
                transaction.get(existingRef).toObject(ScheduleSession::class.java)
            }
                .toMutableList()
            val proposalIntervals = mutableMapOf<String, MutableList<StudyInterval>>()
            val references = proposal.map { session ->
                val stableId = UUID.nameUUIDFromBytes(
                    "$uid|${session.generatedActivityKey}".toByteArray(Charsets.UTF_8)
                ).toString()
                sessionsRef().document(stableId)
            }
            val existingGeneratedRefs = references.map { transaction.get(it) }
            proposal.forEach { session ->
                check(session.generatedActivityKey.isNotBlank()) {
                    "Generated session is missing its activity key"
                }
                check(existing.none { it.generatedActivityKey == session.generatedActivityKey }) {
                    "This generated activity is already saved"
                }
                val interval = StudyInterval(session.startMinutes, session.endMinutes)
                check(existing.none {
                    it.sessionDate == session.sessionDate &&
                        engine.hasOverlap(interval, StudyInterval(it.startMinutes, it.endMinutes))
                }) { "The proposal conflicts with an existing schedule session" }
                val dayIntervals = proposalIntervals.getOrPut(session.sessionDate) { mutableListOf() }
                check(dayIntervals.none { engine.hasOverlap(interval, it) }) {
                    "The proposed sessions overlap each other"
                }
                dayIntervals += interval
                val subjectRef = firestore.collection("subjects").document(session.subjectId)
                check(transaction.get(subjectRef).getString("userId") == uid) {
                    "A proposed session refers to a subject that is not yours"
                }
            }

            existingGeneratedRefs.forEach { document ->
                check(document.exists().not()) {
                    "A session with this generated ID already exists; nothing was overwritten"
                }
            }
            proposal.forEachIndexed { index, session ->
                val ref = references[index]
                transaction.set(ref, session.copy(id = ref.id, userId = uid))
            }
            true
        }.await()
        refreshStudyReminders()
    }

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
}
