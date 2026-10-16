package com.example.studyplanner

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.studyplanner.adapter.DayAdapter
import com.example.studyplanner.adapter.ScheduleAdapter
import com.example.studyplanner.databinding.FragmentScheduleBinding
import com.example.studyplanner.model.DayItem
import com.example.studyplanner.model.FreeTimeSlot
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Subject
import com.example.studyplanner.model.Task
import com.example.studyplanner.repository.FreeTimeRepository
import com.example.studyplanner.repository.ScheduleRepository
import com.example.studyplanner.repository.SubjectRepository
import com.example.studyplanner.repository.TaskRepository
import com.example.studyplanner.scheduler.SchedulingResult
import com.example.studyplanner.scheduler.StudyScheduleEngine
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val scheduleRepository = ScheduleRepository()
    private val subjectRepository = SubjectRepository()
    private val taskRepository = TaskRepository()
    private val freeTimeRepository = FreeTimeRepository()
    private val scheduleEngine = StudyScheduleEngine()
    private var currentWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private var selectedDate = LocalDate.now()
    private var subjects = emptyList<Subject>()
    private var weekSessions = emptyList<ScheduleSession>()
    private var scheduleListener: ListenerRegistration? = null
    private var subjectListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvDays.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvTimeline.layoutManager = LinearLayoutManager(requireContext())
        binding.btnPreviousWeek.setOnClickListener { changeWeek(-1) }
        binding.btnNextWeek.setOnClickListener { changeWeek(1) }
        binding.fabAddSchedule.setOnClickListener { showSessionEditor(null) }
        binding.btnGenerateSchedule.setOnClickListener { generateSchedule() }

        subjectListener = subjectRepository.observeSubjects(
            onUpdate = {
                if (_binding != null) {
                    subjects = it
                    renderSelectedDay()
                }
            },
            onError = { exception -> showError("Could not load subjects", exception) }
        )
        loadWeek()
    }

    private fun changeWeek(offset: Long) {
        currentWeek = currentWeek.plusWeeks(offset)
        selectedDate = currentWeek
        loadWeek()
    }

    private fun loadWeek() {
        val weekEnd = currentWeek.plusDays(6)
        binding.tvWeekRange.text = buildString {
            append(currentWeek.format(DateTimeFormatter.ofPattern("MMM d")))
            append(" – ")
            append(weekEnd.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
        }
        val days = (0L..6L).map { offset ->
            val date = currentWeek.plusDays(offset)
            DayItem(
                date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
                date.dayOfMonth.toString(),
                isSelected = date == selectedDate
            )
        }
        binding.rvDays.adapter = DayAdapter(days) { position ->
            selectedDate = currentWeek.plusDays(position.toLong())
            renderSelectedDay()
        }
        scheduleListener?.remove()
        scheduleListener = scheduleRepository.observeSessions(
            currentWeek.toString(),
            weekEnd.toString(),
            onUpdate = { sessions ->
                if (_binding != null) {
                    weekSessions = sessions
                    renderSelectedDay()
                }
            },
            onError = { exception -> showError("Could not load schedule", exception) }
        )
        renderSelectedDay()
    }

    private fun renderSelectedDay() {
        if (_binding == null) return
        val sessions = weekSessions.filter { it.sessionDate == selectedDate.toString() }
        binding.tvScheduleSubtitle.text =
            "${sessions.size} ${if (sessions.size == 1) "session" else "sessions"} · ${selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}"
        val sessionsWithCurrentNames = sessions.map { session ->
            session.copy(
                subjectName = subjects.firstOrNull { it.id == session.subjectId }?.name
                    ?: session.subjectName
            )
        }
        binding.rvTimeline.adapter = ScheduleAdapter(sessionsWithCurrentNames, ::showSessionActions)
        binding.emptyState.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTimeline.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showSessionActions(session: ScheduleSession) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("Edit session", "Delete session")) { _, choice ->
                if (choice == 0) {
                    showSessionEditor(session)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete session")
                        .setMessage("Remove ${session.subjectName} from your schedule?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> deleteSession(session) }
                        .show()
                }
            }
            .show()
    }

    private fun showSessionEditor(existing: ScheduleSession?) {
        if (subjects.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "Add a subject before scheduling a study session",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val subjectSpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                subjects.map { it.name }
            )
            val selectedIndex = subjects.indexOfFirst { it.id == existing?.subjectId }
            if (selectedIndex >= 0) setSelection(selectedIndex)
        }
        var date = existing?.sessionDate?.let(LocalDate::parse) ?: selectedDate
        val dateButton = Button(requireContext()).apply { text = date.toString() }
        var start = existing?.startMinutes ?: 16 * 60
        var end = existing?.endMinutes ?: 17 * 60
        val startButton = Button(requireContext()).apply { text = formatTime(start) }
        val endButton = Button(requireContext()).apply { text = formatTime(end) }
        val description = EditText(requireContext()).apply {
            hint = "Description (optional)"
            setText(existing?.description.orEmpty())
        }

        dateButton.setOnClickListener {
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    date = LocalDate.of(year, month + 1, day)
                    dateButton.text = date.toString()
                },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth
            ).show()
        }
        startButton.setOnClickListener {
            chooseTime(start) { value ->
                start = value
                startButton.text = formatTime(value)
            }
        }
        endButton.setOnClickListener {
            chooseTime(end) { value ->
                end = value
                endButton.text = formatTime(value)
            }
        }
        listOf(subjectSpinner, dateButton, startButton, endButton, description).forEach {
            content.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Add study session" else "Edit study session")
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (end <= start) {
                    Toast.makeText(
                        requireContext(),
                        "End time must be later than start time",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                val subject = subjects[subjectSpinner.selectedItemPosition]
                val session = ScheduleSession(
                    id = existing?.id.orEmpty(),
                    subjectId = subject.id,
                    subjectName = subject.name,
                    sessionDate = date.toString(),
                    startMinutes = start,
                    endMinutes = end,
                    description = description.text.toString().trim()
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        scheduleRepository.saveManualSession(session)
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not save session", exception)
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun deleteSession(session: ScheduleSession) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                scheduleRepository.deleteSession(session.id)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                showError("Could not delete session", exception)
            }
        }
    }

    private fun generateSchedule() {
        binding.btnGenerateSchedule.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val subjects = subjectRepository.getSubjects()
                val tasks = taskRepository.getTasks()
                val availability = freeTimeRepository.getSlots()
                val sessions = scheduleRepository.getSessions(
                    currentWeek.toString(),
                    currentWeek.plusDays(6).toString()
                )
                val result = scheduleEngine.generateWeek(
                    monday = currentWeek,
                    subjects = subjects,
                    tasks = tasks,
                    freeTime = availability,
                    existingSessions = sessions
                )
                showSchedulePreview(result)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                showError("Could not generate a study plan", exception)
            } finally {
                if (_binding != null) binding.btnGenerateSchedule.isEnabled = true
            }
        }
    }

    private fun showSchedulePreview(result: SchedulingResult) {
        if (result.proposedSessions.isEmpty()) {
            val detail = result.unscheduledActivities.takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "\n") { "• $it" }
                ?: "Add subjects, study-hour targets, and free-time slots to generate a plan."
            AlertDialog.Builder(requireContext())
                .setTitle("No sessions to schedule")
                .setMessage(detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val preview = buildString {
            result.proposedSessions.forEach { session ->
                append(session.sessionDate)
                append(" · ")
                append(formatTime(session.startMinutes))
                append("–")
                append(formatTime(session.endMinutes))
                append(" · ")
                append(session.description)
                append('\n')
            }
            if (result.unscheduledActivities.isNotEmpty()) {
                append("\nNot scheduled:\n")
                result.unscheduledActivities.forEach {
                    append("• ")
                    append(it)
                    append('\n')
                }
            }
        }
        val text = TextView(requireContext()).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            this.text = preview
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Review proposed study plan")
            .setView(ScrollView(requireContext()).apply { addView(text) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Accept & save") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        scheduleRepository.saveGeneratedSessions(result.proposedSessions)
                        Toast.makeText(
                            requireContext(),
                            "Study plan saved",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (exception: Exception) {
                        if (exception is CancellationException) throw exception
                        showError("Could not save study plan", exception)
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
        scheduleListener?.remove()
        scheduleListener = null
        subjectListener?.remove()
        subjectListener = null
        super.onDestroyView()
        _binding = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
