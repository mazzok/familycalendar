package com.familycalendar.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    fun observeUser(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signIn(email: String, password: String) {
        val user = auth.signInWithEmailAndPassword(email.trim(), password).await().user
            ?: error("Sign-in failed")
        writeProfile(user, user.displayName ?: user.email.orEmpty().substringBefore('@'))
    }

    suspend fun register(name: String, email: String, password: String) {
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
            ?: error("Registration failed")
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        writeProfile(user, name.trim())
    }

    fun signOut() = auth.signOut()

    /** Re-publishes the profile so others can find this user by email, e.g. if an earlier write was rejected. */
    fun refreshProfile(user: FirebaseUser) = writeProfile(user, user.nameOrEmail())

    // Not awaited: the Task only completes once the server acknowledges, which never happens while
    // offline or before the Firestore database exists — that used to leave the UI stuck in "busy".
    private fun writeProfile(user: FirebaseUser, name: String) {
        val email = user.email.orEmpty()
        db.collection("users").document(user.uid).set(
            mapOf(
                "displayName" to name,
                "email" to email,
                "emailLower" to email.lowercase(),
            ),
            SetOptions.merge(),
        ).addOnFailureListener { Log.w(TAG, "Writing profile failed", it) }
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}

fun FirebaseUser.nameOrEmail(): String = displayName?.takeIf { it.isNotBlank() } ?: email.orEmpty()
