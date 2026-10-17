package com.example.studyplanner

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.studyplanner.adapter.FreeTimeAdapter
import com.example.studyplanner.databinding.FragmentFreeTimeBinding
import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.repository.FreeTimeRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class FreeTimeFragment : Fragment() {

    private var _binding: FragmentFreeTimeBinding? = null
    private val binding get() = _binding!!
    private val repository = FreeTimeRepository()
    private lateinit var adapter: FreeTimeAdapter
    private var listener: ListenerRegistration? = null
    private var isLoading = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFreeTimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = FreeTimeAdapter(::showSlotEditor, ::confirmDelete)
        binding.rvFreeTime.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFreeTime.adapter = adapter
        binding.fabAddFreeTime.setOnClickListener { showSlotEditor(null) }
        binding.btnBackFreeTime.setOnClickListener { findNavController().navigateUp() }
        renderState()
        listener = repository.observeSlots(
            onUpdate = { slots ->
                if (_binding != null) {
                    isLoading = false
                    adapter.submitSlots(slots)
                    renderState(slots.isEmpty())
                }
            },
            onError = { exception ->
                isLoading = false
                renderState()
                showError("Could not load availability", exception)
            }
        )
    }

    private fun showSlotEditor(existing: FreeTimeSlot?) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val kindSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Repeats every week", "Date-specific (one time)")
            )
            setSelection(if (existing?.isRecurring == false) 1 else 0)
        }
        val weekdays = DayOfWeek.values().map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
        val daySpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                weekdays
            )
            setSelection((existing?.dayOfWeek ?: LocalDate.now().dayOfWeek.value) - 1)
        }
        var selectedDate = existing?.date?.let(LocalDate::parse) ?: LocalDate.now()
        val dateButton = Button(requireContext()).apply {
            text = selectedDate.toString()
        }
        var startMinutes = existing?.startMinutes ?: 16 * 60
        var endMinutes = existing?.endMinutes ?: 17 * 60
        val startButton = Button(requireContext()).apply { text = formatTime(startMinutes) }
        val endButton = Button(requireContext()).apply { text = formatTime(endMinutes) }
        val description = EditText(requireContext()).apply {
            hint = "Description (optional)"
            setText(existing?.description.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
        }

        dateButton.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    selectedDate = LocalDate.of(year, month + 1, day)
                    dateButton.text = selectedDate.toString()
                },
                selectedDate.year,
                selectedDate.monthValue - 1,
                selectedDate.dayOfMonth
            ).show()
        }
        startButton.setOnClickListener {
            chooseTime(startMinutes) { value ->
                startMinutes = value
                startButton.text = formatTime(value)
            }
        }
        endButton.setOnClickListener {
            chooseTime(endMinutes) { value ->
                endMinutes = value
                endButton.text = formatTime(value)
            }
        }
        val dateSelector = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(daySpinner)
            addView(dateButton)
        }
        fun updateKindVisibility() {
            daySpinner.visibility = if (kindSpinner.selectedItemPosition == 0) View.VISIBLE else View.GONE
            dateButton.visibility = if (kindSpinner.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        kindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateKindVisibility()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(kindSpinner, dateSelector, startButton, endButton, description).forEach {
            content.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
        updateKindVisibility()

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Add availability" else "Edit availability")
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (endMinutes <= startMinutes) {
                    Toast.makeText(
                        requireContext(),
                        "End time must be later than start time",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val recurring = kindSpinner.selectedItemPosition == 0
                val slot = FreeTimeSlot(
                    id = existing?.id.orEmpty(),
                    userId = existing?.userId.orEmpty(),
                    isRecurring = recurring,
                    dayOfWeek = if (recurring) daySpinner.selectedItemPosition + 1 else 1,
                    date = if (recurring) "" else selectedDate.toString(),
                    startMinutes = startMinutes,
                    endMinutes = endMinutes,
                    description = description.text.toString().trim()
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.saveSlot(slot)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not save availability", exception)
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(slot: FreeTimeSlot) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete availability")
            .setMessage("Remove this available study time?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.deleteSlot(slot.id)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not delete availability", exception)
                    }
                }
            }
            .show()
    }

    private fun chooseTime(initial: Int, onSelected: (Int) -> Unit) {
        TimePickerDialog(
            requireContext(),
            { _, hour, minute -> onSelected(hour * 60 + minute) },
            initial / 60,
            initial % 60,
            false
        ).show()
    }

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    private fun renderState(empty: Boolean = false) {
        if (_binding == null) return
        binding.progressFreeTime.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.rvFreeTime.visibility =
            if (!isLoading && !empty && adapter.itemCount > 0) View.VISIBLE else View.GONE
        binding.tvFreeTimeEmpty.visibility =
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
