package com.example.studyplanner

import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.core.app.NotificationManagerCompat
import com.example.studyplanner.databinding.FragmentSettingsBinding
import com.example.studyplanner.repository.AuthRepository
import com.example.studyplanner.notifications.StudyReminderScheduler
import com.example.studyplanner.StudyPlannerApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()
    private val settings by lazy {
        requireContext().getSharedPreferences("study_planner_settings", 0)
    }
    private var pendingNotificationPreference: String? = null
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val key = pendingNotificationPreference ?: return@registerForActivityResult
            pendingNotificationPreference = null
            if (granted && NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                settings.edit().putBoolean(key, true).apply()
                StudyReminderScheduler.refreshInBackground(requireContext())
            } else {
                settings.edit().putBoolean(key, false).apply()
                if (key == KEY_NOTIFICATIONS_ENABLED) {
                    settings.edit().putBoolean(KEY_REMINDERS_ENABLED, false).apply()
                    binding.rowReminders.rowSwitch.isChecked = false
                    binding.rowNotifications.rowSwitch.isChecked = false
                } else {
                    binding.rowReminders.rowSwitch.isChecked = false
                }
                Toast.makeText(
                    requireContext(),
                    R.string.notifications_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }
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
                    R.string.profile_load_failed,
                    Toast.LENGTH_LONG
                ).show()
                Log.e(TAG, "Could not load the settings profile", exception)
            }
        }
    }

    private fun setupRows() {
        // Notifications — toggle row
        binding.rowNotifications.rowLabel.text = getString(R.string.notifications)
        binding.rowNotifications.rowDescription.text =
            getString(R.string.notifications_description)
        binding.rowNotifications.rowDescription.visibility = View.VISIBLE
        binding.rowNotifications.rowIcon.setImageResource(R.drawable.ic_notifications)
        binding.rowNotifications.rowArrow.visibility = View.GONE
        binding.rowNotifications.rowSwitch.visibility = View.VISIBLE
        val notificationsAvailable = hasNotificationPermission() &&
            NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        val notificationsEnabled =
            settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false) && notificationsAvailable
        binding.rowNotifications.rowSwitch.isChecked = notificationsEnabled
        binding.rowNotifications.rowSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                enableNotificationPreference(KEY_NOTIFICATIONS_ENABLED)
            } else {
                settings.edit()
                    .putBoolean(KEY_NOTIFICATIONS_ENABLED, false)
                    .putBoolean(KEY_REMINDERS_ENABLED, false)
                    .apply()
                binding.rowReminders.rowSwitch.isChecked = false
                StudyReminderScheduler.cancelAll(requireContext())
            }
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
        binding.rowReminders.rowDescription.text =
            getString(R.string.study_reminders_description)
        binding.rowReminders.rowDescription.visibility = View.VISIBLE
        binding.rowReminders.rowIcon.setImageResource(R.drawable.ic_alarm)
        binding.rowReminders.rowArrow.visibility = View.GONE
        binding.rowReminders.rowSwitch.visibility = View.VISIBLE
        binding.rowReminders.rowSwitch.isChecked = notificationsEnabled &&
            settings.getBoolean(KEY_REMINDERS_ENABLED, false)
        binding.rowReminders.rowSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!enabled) {
                settings.edit().putBoolean(KEY_REMINDERS_ENABLED, false).apply()
                StudyReminderScheduler.cancelAll(requireContext())
            } else if (!settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)) {
                binding.rowReminders.rowSwitch.isChecked = false
                Toast.makeText(
                    requireContext(),
                    R.string.notifications_disabled_reminders,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                enableNotificationPreference(KEY_REMINDERS_ENABLED)
            }
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

        binding.profileCard.setOnClickListener {
            openProfile(editMode = false)
        }
        binding.btnEditProfile.setOnClickListener {
            openProfile(editMode = true)
        }
        binding.rowEditProfile.settingsRowRoot.setOnClickListener {
            openProfile(editMode = true)
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

    private fun openProfile(editMode: Boolean) {
        findNavController().navigate(
            R.id.profileFragment,
            Bundle().apply { putBoolean("editMode", editMode) }
        )
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
        settings.edit()
            .putBoolean(StudyPlannerApplication.DARK_MODE_KEY, enabled)
            .apply()
        AppCompatDelegate.setDefaultNightMode(
            if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun enableNotificationPreference(key: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            !hasNotificationPermission()
        ) {
            pendingNotificationPreference = key
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            if (key == KEY_NOTIFICATIONS_ENABLED) {
                binding.rowNotifications.rowSwitch.isChecked = false
            } else {
                binding.rowReminders.rowSwitch.isChecked = false
            }
            Toast.makeText(
                requireContext(),
                R.string.notifications_permission_required,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        settings.edit().putBoolean(key, true).apply()
        StudyReminderScheduler.refreshInBackground(requireContext())
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

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
                StudyReminderScheduler.cancelAll(requireContext())
                authRepository.logout()
                startActivity(android.content.Intent(requireContext(), MainActivity::class.java))
                requireActivity().finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "SettingsFragment"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_REMINDERS_ENABLED = "study_reminders_enabled"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}