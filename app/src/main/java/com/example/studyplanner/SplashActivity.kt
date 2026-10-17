package com.example.studyplanner

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.studyplanner.databinding.ActivitySplashBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var navigationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runEntranceAnimation()
        resolveAuthenticationAndNavigate()
    }

    private fun runEntranceAnimation() {
        val views = listOf(
            binding.lottieSplash,
            binding.tvSplashTitle,
            binding.tvSplashTagline,
            binding.progressSplash
        )
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 50f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80L + index * 60L)
                .setDuration(420L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun resolveAuthenticationAndNavigate() {
        lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var authenticationFailed = false
            val auth = try {
                FirebaseAuth.getInstance()
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to initialize Firebase Authentication", exception)
                authenticationFailed = true
                null
            }

            val restoredUser = if (auth == null) {
                null
            } else {
                try {
                    val authState = withTimeoutOrNull(AUTH_STATE_TIMEOUT_MS) {
                        InitialAuthState(awaitInitialAuthState(auth))
                    }
                    if (authState == null) {
                        Log.w(TAG, "Timed out waiting for the initial Firebase auth state")
                    }
                    authState?.user ?: auth.currentUser
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to determine Firebase auth state", exception)
                    authenticationFailed = true
                    auth.currentUser
                }
            }

            val remainingSplashTime =
                MIN_SPLASH_DURATION_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remainingSplashTime > 0) delay(remainingSplashTime)

            if (authenticationFailed) {
                Toast.makeText(
                    this@SplashActivity,
                    R.string.splash_authentication_error,
                    Toast.LENGTH_LONG
                ).show()
            }
            navigateOnce(restoredUser)
        }
    }

    private suspend fun awaitInitialAuthState(auth: FirebaseAuth): FirebaseUser? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : FirebaseAuth.AuthStateListener {
                override fun onAuthStateChanged(firebaseAuth: FirebaseAuth) {
                    if (continuation.isActive) {
                        firebaseAuth.removeAuthStateListener(this)
                        continuation.resume(firebaseAuth.currentUser)
                    }
                }
            }
            continuation.invokeOnCancellation {
                auth.removeAuthStateListener(listener)
            }
            auth.addAuthStateListener(listener)
        }

    private fun navigateOnce(user: FirebaseUser?) {
        if (navigationStarted || isFinishing || isDestroyed) return
        navigationStarted = true

        val destination = if (user != null) {
            DashboardActivity::class.java
        } else {
            MainActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }

    companion object {
        private const val TAG = "SplashActivity"
        private const val MIN_SPLASH_DURATION_MS = 2_000L
        private const val AUTH_STATE_TIMEOUT_MS = 5_000L
    }

    private data class InitialAuthState(val user: FirebaseUser?)
}
