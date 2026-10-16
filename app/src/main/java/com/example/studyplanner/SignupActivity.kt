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
import com.example.studyplanner.databinding.ActivitySignupBinding
import com.example.studyplanner.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.launch

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runEntranceAnimation()
        setupPressAnimation(binding.btnSignup)
        setupListeners()
    }

    private fun runEntranceAnimation() {
        val views = listOf(
            binding.lottieSignup,
            binding.tvSignupTitle,
            binding.tvSignupSubtitle,
            binding.nameLayout,
            binding.emailLayout,
            binding.passwordLayout,
            binding.confirmPasswordLayout,
            binding.btnSignup,
            binding.loginRow
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

    private fun setupPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    if (event.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.tvLogin.setOnClickListener { finish() }

        binding.btnSignup.setOnClickListener {
            if (validateInputs()) {
                val name = binding.etName.text.toString().trim()
                val email = binding.etEmail.text.toString().trim()
                val password = binding.etPassword.text.toString()

                performSignup(name, email, password)
            }
        }
    }

    private fun performSignup(name: String, email: String, password: String) {
        binding.btnSignup.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                authRepository.signup(name, email, password)
                    .onSuccess {
                        Toast.makeText(
                            this@SignupActivity,
                            "Account created successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                        val intent = Intent(this@SignupActivity, DashboardActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    }
                    .onFailure(::handleSignupError)
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnSignup.isEnabled = true
            }
        }
    }

    private fun handleSignupError(exception: Throwable) {
        when (exception) {
            is FirebaseAuthUserCollisionException -> {
                binding.emailLayout.error = "This email is already registered"
            }
            else -> {
                Toast.makeText(this, "Signup failed: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun validateInputs(): Boolean {
        var valid = true
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        if (name.isEmpty()) { binding.nameLayout.error = "Name is required"; valid = false }
        else binding.nameLayout.error = null

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailLayout.error = "Enter a valid email"; valid = false
        } else binding.emailLayout.error = null

        if (password.length < 6) { binding.passwordLayout.error = "Min 6 characters"; valid = false }
        else binding.passwordLayout.error = null

        if (confirmPassword != password) { binding.confirmPasswordLayout.error = "Passwords don't match"; valid = false }
        else binding.confirmPasswordLayout.error = null

        return valid
    }
}