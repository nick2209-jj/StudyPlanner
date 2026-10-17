package com.example.studyplanner.repository

import com.example.studyplanner.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val usersRef = FirebaseFirestore.getInstance().collection("users")

    private val currentUserId: String? get() = auth.currentUser?.uid

    suspend fun signup(name: String, email: String, password: String): Result<Unit> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val createdUser = result.user ?: error("Firebase did not return a user after signup")
            val user = User(uid = createdUser.uid, name = name, email = email)
            try {
                usersRef.document(createdUser.uid).set(user).await()
            } catch (e: CancellationException) {
                auth.signOut()
                throw e
            } catch (e: Exception) {
                try {
                    createdUser.delete().await()
                } catch (cleanupException: Exception) {
                    e.addSuppressed(cleanupException)
                } finally {
                    auth.signOut()
                }
                throw e
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCurrentUser(): User? {
        val uid = currentUserId ?: return null
        return usersRef.document(uid).get().await().toObject(User::class.java)
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateCurrentUserName(name: String) {
        require(name.isNotBlank()) { "Name is required" }
        val user = requireNotNull(auth.currentUser) { "You must be signed in to update your profile" }
        val profileRef = usersRef.document(user.uid)
        val profileSnapshot = profileRef.get().await()
        if (profileSnapshot.exists()) {
            profileRef.update("name", name.trim()).await()
        } else {
            profileRef.set(
                User(
                    uid = user.uid,
                    name = name.trim(),
                    email = user.email.orEmpty()
                )
            ).await()
        }
        user.updateProfile(
            UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()
        ).await()
    }

    fun logout() = auth.signOut()

    fun isLoggedIn(): Boolean = auth.currentUser != null

    fun currentAuthUserName(): String? = auth.currentUser?.displayName

    fun currentAuthUserEmail(): String? = auth.currentUser?.email
}