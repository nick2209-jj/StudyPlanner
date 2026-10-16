package com.example.studyplanner

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.studyplanner.databinding.ActivityMainBinding
import com.example.studyplanner.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (authRepository.isLoggedIn()) {
            openDashboard()
            return
        }

        setupListeners()
        runEntranceAnimation()

        setupPressAnimation(binding.btnLogin)
    }

    private fun runEntranceAnimation() {
        val views = listOf(
            binding.lottieStudy,
            binding.tvTitle,
            binding.tvSubtitle,
            binding.emailLayout,
            binding.passwordLayout,
            binding.tvForgotPassword,
            binding.btnLogin,
            binding.signupRow
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

    // The stray views.forEachIndexed block that was here has been removed.

    private fun setupPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .setDuration(120L)
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .start()

                    if (event.action == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    private fun setupListeners() {
        binding.tvSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.tvForgotPassword.setOnClickListener {
            val email = binding.etEmail.text?.toString()?.trim().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.emailLayout.error = "Enter a valid email to reset your password"
                return@setOnClickListener
            }
            binding.emailLayout.error = null
            sendPasswordReset(email)
        }

        binding.btnLogin.setOnClickListener {
            if (validateInputs()) {
                val email = binding.etEmail.text.toString().trim()
                val password = binding.etPassword.text.toString()

                performLogin(email, password)
            }
        }
    }

    private fun performLogin(email: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                authRepository.login(email, password)
                    .onSuccess {
                        Toast.makeText(this@MainActivity, "Login successful", Toast.LENGTH_SHORT).show()
                        openDashboard()
                    }
                    .onFailure(::handleLoginError)
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun sendPasswordReset(email: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        lifecycleScope.launch {
            try {
                authRepository.sendPasswordReset(email)
                    .onSuccess {
                        Toast.makeText(
                            this@MainActivity,
                            "Password reset email sent",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .onFailure { exception ->
                        Toast.makeText(
                            this@MainActivity,
                            "Could not send reset email: ${exception.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    private fun handleLoginError(exception: Throwable) {
        when (exception) {
            is FirebaseAuthInvalidUserException -> {
                binding.emailLayout.error = "No account found with this email"
            }
            is FirebaseAuthInvalidCredentialsException -> {
                binding.passwordLayout.error = "Incorrect password"
            }
            else -> {
                Toast.makeText(this, "Login failed: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validateInputs(): Boolean {
        var valid = true
        val email = binding.etEmail.text?.toString()?.trim()
        val password = binding.etPassword.text?.toString()?.trim()

        // Use isNullOrEmpty() for the email check
        // Use ?: "" (Elvis operator) to provide an empty string to the matcher if email is null
        if (email.isNullOrEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = "Enter a valid email"
            valid = false
        } else {
            binding.emailLayout.error = null
        }

        // You already used the safe check here correctly!
        if (password.isNullOrEmpty()) {
            binding.passwordLayout.error = "Password is required"
            valid = false
        } else {
            binding.passwordLayout.error = null
        }

        return valid
    }
}