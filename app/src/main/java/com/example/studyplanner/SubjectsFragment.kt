package com.example.studyplanner

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.studyplanner.adapter.SubjectAdapter
import com.example.studyplanner.databinding.FragmentSubjectsBinding
import com.example.studyplanner.model.Subject
import com.example.studyplanner.repository.SubjectRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class SubjectsFragment : Fragment() {

    private var _binding: FragmentSubjectsBinding? = null
    private val binding get() = _binding!!
    private val repository = SubjectRepository()
    private lateinit var adapter: SubjectAdapter
    private var listener: ListenerRegistration? = null
    private var isLoading = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubjectsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SubjectAdapter(::showSubjectEditor, ::confirmDelete)
        binding.rvSubjects.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubjects.adapter = adapter
        binding.fabAddSubject.setOnClickListener { showSubjectEditor(null) }
        binding.btnBackSubjects.setOnClickListener { findNavController().navigateUp() }
        renderState()
        listener = repository.observeSubjects(
            onUpdate = { subjects ->
                if (_binding != null) {
                    isLoading = false
                    adapter.submitSubjects(subjects)
                    renderState(subjects.isEmpty())
                }
            },
            onError = { exception ->
                isLoading = false
                renderState()
                showError("Could not load subjects", exception)
            }
        )
    }

    private fun showSubjectEditor(existing: Subject?) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = field("Subject name", existing?.name.orEmpty())
        val code = field("Subject code (optional)", existing?.code.orEmpty())
        val hours = field(
            "Weekly study target (1-80 hours)",
            existing?.totalHoursPerWeek?.toString().orEmpty(),
            InputType.TYPE_CLASS_NUMBER
        )
        val priorities = listOf("LOW", "MEDIUM", "HIGH")
        val priority = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                priorities.map { it.lowercase().replaceFirstChar(Char::uppercase) }
            )
            setSelection(priorities.indexOf(existing?.priority ?: "MEDIUM").coerceAtLeast(0))
        }
        val priorityTitle = TextView(requireContext()).apply {
            text = getString(R.string.study_priority)
            setTextColor(requireContext().getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val priorityExplanation = TextView(requireContext()).apply {
            text = getString(R.string.study_priority_explanation)
            setTextColor(requireContext().getColor(R.color.text_secondary))
            textSize = 13f
        }
        listOf(name, code, hours, priorityTitle, priorityExplanation, priority).forEach {
            content.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Add subject" else "Edit subject")
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val subjectName = name.text.toString().trim()
                val weeklyHours = hours.text.toString().toIntOrNull()
                if (subjectName.isBlank()) {
                    name.error = "Subject name is required"
                    return@setOnClickListener
                }
                if (weeklyHours !in 1..80) {
                    hours.error = "Enter a target from 1 to 80 hours"
                    return@setOnClickListener
                }
                val subject = Subject(
                    id = existing?.id.orEmpty(),
                    name = subjectName,
                    code = code.text.toString().trim(),
                    colorHex = existing?.colorHex ?: "#6C4AB6",
                    totalHoursPerWeek = weeklyHours!!,
                    priority = priorities[priority.selectedItemPosition],
                    userId = existing?.userId.orEmpty()
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        if (existing == null) repository.addSubject(subject)
                        else repository.updateSubject(subject)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not save subject", exception)
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(subject: Subject) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete subject")
            .setMessage(
                "Delete ${subject.name}? Its scheduled sessions will also be removed."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.deleteSubject(subject.id)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not delete subject", exception)
                    }
                }
            }
            .show()
    }

    private fun field(
        hintText: String,
        value: String,
        type: Int = InputType.TYPE_CLASS_TEXT
    ) = EditText(requireContext()).apply {
        hint = hintText
        setText(value)
        inputType = type
    }

    private fun renderState(empty: Boolean = false) {
        if (_binding == null) return
        binding.progressSubjects.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.rvSubjects.visibility =
            if (!isLoading && !empty && adapter.itemCount > 0) View.VISIBLE else View.GONE
        binding.tvSubjectsEmpty.visibility =
            if (!isLoading && empty) View.VISIBLE else View.GONE
    }

    private fun showError(prefix: String, exception: Exception) {
        if (_binding != null) {
            Toast.makeText(
                requireContext(),
                "$prefix: ${exception.message ?: "Unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        listener?.remove()
        listener = null
        super.onDestroyView()
        _binding = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
