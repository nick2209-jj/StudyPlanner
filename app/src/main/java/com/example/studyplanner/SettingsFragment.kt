package com.example.studyplanner

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.studyplanner.databinding.FragmentSettingsBinding
import com.example.studyplanner.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()
    private val settings by lazy {
        requireContext().getSharedPreferences("study_planner_settings", 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRows()
        runEntranceAnimation()
        loadProfile()

        binding.btnLogout.setOnClickListener { showLogoutConfirmation() }
    }

    private fun loadProfile() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = authRepository.getCurrentUser()
                val name = profile?.name
                    ?: authRepository.currentAuthUserName()
                    ?: "Student"
                val email = profile?.email ?: authRepository.currentAuthUserEmail().orEmpty()
                binding.tvProfileName.text = name
                binding.tvProfileEmail.text = email
                binding.tvAvatarInitial.text = name.firstOrNull()?.uppercase() ?: "S"
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Toast.makeText(
                    requireContext(),
                    "Could not load profile: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupRows() {
        // Notifications — toggle row
        binding.rowNotifications.rowLabel.text = getString(R.string.notifications)
        binding.rowNotifications.rowIcon.setImageResource(R.drawable.ic_notifications)
        binding.rowNotifications.rowArrow.visibility = View.GONE
        binding.rowNotifications.rowSwitch.visibility = View.VISIBLE
        binding.rowNotifications.rowSwitch.isChecked =
            settings.getBoolean("notifications_enabled", true)
        binding.rowNotifications.rowSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edit().putBoolean("notifications_enabled", enabled).apply()
        }

        // Dark Mode — toggle row
        binding.rowDarkMode.rowLabel.text = getString(R.string.dark_mode)
        binding.rowDarkMode.rowIcon.setImageResource(R.drawable.ic_dark_mode)
        binding.rowDarkMode.rowArrow.visibility = View.GONE
        binding.rowDarkMode.rowSwitch.visibility = View.VISIBLE
        binding.rowDarkMode.rowSwitch.isChecked = settings.getBoolean("dark_mode", false)
        applyDarkMode(binding.rowDarkMode.rowSwitch.isChecked)
        binding.rowDarkMode.rowSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edit().putBoolean("dark_mode", enabled).apply()
            applyDarkMode(enabled)
        }

        // Reminders — toggle row
        binding.rowReminders.rowLabel.text = getString(R.string.study_reminders)
        binding.rowReminders.rowIcon.setImageResource(R.drawable.ic_alarm)
        binding.rowReminders.rowArrow.visibility = View.GONE
        binding.rowReminders.rowSwitch.visibility = View.VISIBLE
        binding.rowReminders.rowSwitch.isChecked =
            settings.getBoolean("study_reminders_enabled", true)
        binding.rowReminders.rowSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edit().putBoolean("study_reminders_enabled", enabled).apply()
        }

        // Edit Profile — navigation row
        binding.rowEditProfile.rowLabel.text = getString(R.string.edit_profile)
        binding.rowEditProfile.rowIcon.setImageResource(R.drawable.ic_person)

        // Change Password — navigation row
        binding.rowChangePassword.rowLabel.text = getString(R.string.change_password)
        binding.rowChangePassword.rowIcon.setImageResource(R.drawable.ic_lock)

        // About — navigation row
        binding.rowAbout.rowLabel.text = getString(R.string.about_studyplanner)
        binding.rowAbout.rowIcon.setImageResource(R.drawable.ic_info)

        binding.rowFreeTime.rowLabel.text = getString(R.string.free_time)
        binding.rowFreeTime.rowIcon.setImageResource(R.drawable.ic_alarm)

        binding.rowEditProfile.settingsRowRoot.setOnClickListener {
            showEditProfile()
        }
        binding.rowChangePassword.settingsRowRoot.setOnClickListener {
            sendPasswordReset()
        }
        binding.rowAbout.settingsRowRoot.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.about_studyplanner))
                .setMessage("StudyPlanner helps you organize subjects, tasks, study sessions, and available time.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.rowFreeTime.settingsRowRoot.setOnClickListener {
            findNavController().navigate(R.id.freeTimeFragment)
        }
    }

    private fun showEditProfile() {
        val nameField = EditText(requireContext()).apply {
            hint = "Full name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(binding.tvProfileName.text)
            setSelection(text?.length ?: 0)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.edit_profile))
            .setView(nameField)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameField.text.toString().trim()
                        if (name.isBlank()) {
                            nameField.error = "Name is required"
                            return@setOnClickListener
                        }
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                authRepository.updateCurrentUserName(name)
                                binding.tvProfileName.text = name
                                binding.tvAvatarInitial.text = name.first().uppercase()
                                Toast.makeText(
                                    requireContext(),
                                    "Profile updated",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (exception: Exception) {
                                if (exception is CancellationException) throw exception
                                showError("Could not update profile", exception)
                            }
                        }
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun sendPasswordReset() {
        val email = authRepository.currentAuthUserEmail()
        if (email.isNullOrBlank()) {
            showError("No email is available for this account", null)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.change_password))
            .setMessage("Send a password reset link to $email?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send link") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    authRepository.sendPasswordReset(email)
                        .onSuccess {
                            Toast.makeText(
                                requireContext(),
                                "Password reset email sent",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .onFailure { exception ->
                            showError("Could not send reset email", exception)
                        }
                }
            }
            .show()
    }

    private fun applyDarkMode(enabled: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun showError(message: String, exception: Throwable?) {
        if (_binding != null) {
            Toast.makeText(
                requireContext(),
                if (exception?.message.isNullOrBlank()) message else "$message: ${exception?.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun runEntranceAnimation() {
        val sections = listOf(
            binding.tvSettingsTitle,
            binding.profileCard,
            binding.tvPreferencesLabel,
            binding.preferencesCard,
            binding.tvAccountLabel,
            binding.accountCard,
            binding.btnLogout,
            binding.tvAppVersion
        )

        sections.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60L + index * 70L)
                .setDuration(400L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out") { _, _ ->
                authRepository.logout()
                startActivity(android.content.Intent(requireContext(), MainActivity::class.java))
                requireActivity().finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}