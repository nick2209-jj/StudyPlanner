package com.example.studyplanner.repository

import com.example.studyplanner.model.Subject
import com.example.studyplanner.notifications.StudyReminderScheduler
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.example.studyplanner.validation.SubjectValidator
import kotlinx.coroutines.tasks.await

class SubjectRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private fun currentUid() = requireNotNull(auth.currentUser?.uid) {
        "You must be signed in to access subjects"
    }
    private fun subjectsRef() = firestore.collection("subjects")

    suspend fun addSubject(subject: Subject) {
        SubjectValidator.validate(subject)
        val uid = currentUid()
        val document = subjectsRef().document()
        document.set(subject.copy(id = document.id, userId = uid)).await()
    }

    suspend fun updateSubject(subject: Subject) {
        SubjectValidator.validate(subject)
        val uid = currentUid()
        require(subject.userId == uid) { "Cannot update another user's subject" }
        subjectsRef().document(subject.id).set(subject).await()
    }

    suspend fun deleteSubject(subjectId: String) {
        val uid = currentUid()
        val document = subjectsRef().document(subjectId)
        val existing = document.get().await().toObject(Subject::class.java)
        require(existing?.userId == uid) { "Subject not found for the current user" }

        val relatedSessions = firestore.collection("schedules")
            .whereEqualTo("userId", uid)
            .whereEqualTo("subjectId", subjectId)
            .get()
            .await()
        relatedSessions.documents.chunked(400).forEach { chunk ->
            firestore.batch().also { batch ->
                chunk.forEach { batch.delete(it.reference) }
            }.commit().await()
        }
        document.delete().await()
        StudyReminderScheduler.refreshInBackground(
            FirebaseApp.getInstance().applicationContext
        )
    }

    suspend fun getSubjects(): List<Subject> {
        val uid = currentUid()
        return subjectsRef().whereEqualTo("userId", uid).get().await()
            .documents.mapNotNull { it.toObject(Subject::class.java) }
    }

    fun observeSubjects(
        onUpdate: (List<Subject>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val uid = currentUid()
        return subjectsRef().whereEqualTo("userId", uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
            } else if (snapshot != null) {
                onUpdate(snapshot.documents.mapNotNull { it.toObject(Subject::class.java) })
            } else {
                onError(IllegalStateException("Firestore returned no subject snapshot"))
            }
        }
    }
}