package com.example.studyplanner.repository

import com.example.studyplanner.model.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.example.studyplanner.validation.TaskValidator
import kotlinx.coroutines.tasks.await

class TaskRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private fun currentUid() = requireNotNull(auth.currentUser?.uid) {
        "You must be signed in to access tasks"
    }
    private fun tasksRef() = firestore.collection("tasks")

    suspend fun addTask(task: Task) {
        TaskValidator.validate(task)
        val uid = currentUid()
        val document = tasksRef().document()
        document.set(task.copy(id = document.id, userId = uid)).await()
    }

    suspend fun updateTask(task: Task) {
        TaskValidator.validate(task)
        val uid = currentUid()
        require(task.userId == uid) { "Cannot update another user's task" }
        tasksRef().document(task.id).set(task).await()
    }

    suspend fun deleteTask(taskId: String) {
        val uid = currentUid()
        val document = tasksRef().document(taskId)
        val existing = document.get().await().toObject(Task::class.java)
        require(existing?.userId == uid) { "Task not found for the current user" }
        document.delete().await()
    }

    suspend fun getTasks(): List<Task> {
        val uid = currentUid()
        return tasksRef().whereEqualTo("userId", uid).get().await()
            .documents.mapNotNull { it.toObject(Task::class.java) }
    }

    fun observeTasks(
        onUpdate: (List<Task>) -> Unit,
        onError: (Exception) -> Unit
    ): ListenerRegistration {
        val uid = currentUid()
        return tasksRef().whereEqualTo("userId", uid).addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
            } else if (snapshot != null) {
                onUpdate(snapshot.documents.mapNotNull { it.toObject(Task::class.java) })
            } else {
                onError(IllegalStateException("Firestore returned no task snapshot"))
            }
        }
    }
}