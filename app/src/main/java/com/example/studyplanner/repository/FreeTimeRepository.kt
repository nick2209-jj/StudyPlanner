package com.example.studyplanner.repository

import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.scheduler.StudyInterval
import com.example.studyplanner.scheduler.StudyScheduleEngine
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeParseException

class FreeTimeRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val engine = StudyScheduleEngine()
    private fun currentUid() = requireNotNull(auth.currentUser?.uid) {
        "You must be signed in to access free time"
    }
    private fun slotsRef() = firestore.collection("freeTime")

    suspend fun getSlots(): List<FreeTimeSlot> {
        val uid = currentUid()
        return slotsRef().whereEqualTo("userId", uid).get().await()
            .documents.mapNotNull { it.toObject(FreeTimeSlot::class.java) }
            .sortedWith(compareBy({ it.isRecurring }, { it.dayOfWeek }, { it.date }, { it.startMinutes }))
    }

    fun observeSlots(
        onUpdate: (List<FreeTimeSlot>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val uid = currentUid()
        return slotsRef().whereEqualTo("userId", uid).addSnapshotListener { snapshot, error ->
            when {
                error != null -> onError(error)
                snapshot != null -> onUpdate(
                    snapshot.documents.mapNotNull { it.toObject(FreeTimeSlot::class.java) }
                        .sortedWith(compareBy({ it.isRecurring }, { it.dayOfWeek }, { it.date }, { it.startMinutes }))
                )
                else -> onError(IllegalStateException("Firestore returned no free-time snapshot"))
            }
        }
    }

    suspend fun saveSlot(slot: FreeTimeSlot) {
        validate(slot)
        val uid = currentUid()
        val existing = getSlots()
        require(!engine.hasFreeTimeConflict(slot, existing)) {
            "This free-time slot overlaps an existing availability slot"
        }
        val ref = if (slot.id.isBlank()) slotsRef().document() else slotsRef().document(slot.id)
        if (slot.id.isNotBlank()) {
            val stored = ref.get().await().toObject(FreeTimeSlot::class.java)
            require(stored?.userId == uid) { "Free-time slot not found for the current user" }
        }
        ref.set(slot.copy(id = ref.id, userId = uid)).await()
    }

    suspend fun deleteSlot(slotId: String) {
        val uid = currentUid()
        val ref = slotsRef().document(slotId)
        val stored = ref.get().await().toObject(FreeTimeSlot::class.java)
        require(stored?.userId == uid) { "Free-time slot not found for the current user" }
        ref.delete().await()
    }

    private fun validate(slot: FreeTimeSlot) {
        require(engine.isValidInterval(StudyInterval(slot.startMinutes, slot.endMinutes))) {
            "The end time must be later than the start time"
        }
        if (slot.isRecurring) {
            require(slot.dayOfWeek in 1..7) { "Choose a valid weekday" }
        } else {
            try {
                LocalDate.parse(slot.date)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("Choose a valid date")
            }
        }
    }
}
