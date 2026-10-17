package com.example.studyplanner

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.studyplanner.databinding.FragmentProfileBinding
import com.example.studyplanner.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val authRepository = AuthRepository()
    private var originalName = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBackProfile.setOnClickListener { findNavController().navigateUp() }
        binding.btnEditProfile.setOnClickListener { setEditing(true) }
        binding.btnCancelProfile.setOnClickListener {
            binding.etProfileName.setText(originalName)
            setEditing(false)
        }
        binding.btnSaveProfile.setOnClickListener { saveProfile() }
        setEditing(arguments?.getBoolean(ARG_EDIT_MODE) == true)
        loadProfile()
    }

    private fun loadProfile() {
        binding.progressProfile.visibility = View.VISIBLE
        binding.tvProfileLoading.visibility = View.VISIBLE
        binding.profileForm.visibility = View.INVISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = authRepository.getCurrentUser()
                originalName = profile?.name?.takeIf(String::isNotBlank)
                    ?: authRepository.currentAuthUserName().orEmpty()
                binding.etProfileName.setText(originalName)
                binding.tvProfileEmail.text =
                    profile?.email?.takeIf(String::isNotBlank)
                        ?: authRepository.currentAuthUserEmail().orEmpty()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Toast.makeText(
                    requireContext(),
                    R.string.profile_load_failed,
                    Toast.LENGTH_LONG
                ).show()
                Log.e(TAG, "Could not load the profile", exception)
                originalName = authRepository.currentAuthUserName().orEmpty()
                binding.etProfileName.setText(originalName)
                binding.tvProfileEmail.text = authRepository.currentAuthUserEmail().orEmpty()
            } finally {
                if (_binding != null) {
                    binding.progressProfile.visibility = View.GONE
                    binding.tvProfileLoading.visibility = View.GONE
                    binding.profileForm.visibility = View.VISIBLE
                    setEditing(arguments?.getBoolean(ARG_EDIT_MODE) == true)
                }
            }
        }
    }

    private fun saveProfile() {
        val name = binding.etProfileName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            binding.nameLayout.error = getString(R.string.profile_name_required)
            return
        }
        binding.nameLayout.error = null
        binding.btnSaveProfile.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                authRepository.updateCurrentUserName(name)
                originalName = name
                Toast.makeText(
                    requireContext(),
                    R.string.profile_updated,
                    Toast.LENGTH_SHORT
                ).show()
                setEditing(false)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Toast.makeText(
                    requireContext(),
                    R.string.profile_save_failed,
                    Toast.LENGTH_LONG
                ).show()
                Log.e(TAG, "Could not save the profile", exception)
            } finally {
                if (_binding != null) binding.btnSaveProfile.isEnabled = true
            }
        }
    }

    private fun setEditing(editing: Boolean) {
        if (_binding == null) return
        binding.tvProfileTitle.setText(
            if (editing) R.string.edit_profile else R.string.profile_title
        )
        binding.etProfileName.isEnabled = editing
        binding.btnEditProfile.visibility = if (editing) View.GONE else View.VISIBLE
        binding.profileEditActions.visibility = if (editing) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ProfileFragment"
        private const val ARG_EDIT_MODE = "editMode"
    }
}
