package com.example.studyplanner

import android.app.AlertDialog
import android.app.DatePickerDialog
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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.studyplanner.adapter.TaskAdapter
import com.example.studyplanner.databinding.FragmentTasksBinding
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.ScheduleSession
import com.example.studyplanner.model.Task
import com.example.studyplanner.repository.ScheduleRepository
import com.example.studyplanner.repository.TaskRepository
import com.example.studyplanner.validation.TaskValidator
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val repository = TaskRepository()
    private val scheduleRepository = ScheduleRepository()
    private val savedTasks = mutableListOf<Task>()
    private val scheduledTasks = mutableListOf<Task>()
    private val taskList = mutableListOf<Task>()
    private var selectedDate: LocalDate = LocalDate.now()
    private lateinit var adapter: TaskAdapter
    private var tasksListener: ListenerRegistration? = null
    private var scheduleListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTasksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TaskAdapter(
            taskList,
            onTaskChecked = ::updateTaskCompletion,
            onTaskClick = ::showTaskActions
        )
        binding.rvTasks.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTasks.adapter = adapter
        updateDateButton()
        binding.btnTaskDate.setOnClickListener { showDatePicker() }

        tasksListener = repository.observeTasks(
            onUpdate = { tasks ->
                if (_binding != null) {
                    savedTasks.clear()
                    savedTasks.addAll(tasks)
                    refreshTaskList()
                }
            },
            onError = { exception ->
                if (_binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Could not load tasks: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
        scheduleListener = scheduleRepository.observeSessions(
            "0001-01-01",
            "9999-12-31",
            onUpdate = { sessions ->
                if (_binding != null) {
                    replaceScheduledTasks(sessions.filter {
                        it.generatedActivityKey.isNotBlank()
                    })
                }
            },
            onError = { exception ->
                if (_binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Could not load scheduled tasks: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        binding.fabAddTask.setOnClickListener { showTaskEditor() }
    }

    private fun updateTaskCompletion(task: Task, isChecked: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (task.scheduleSessionId.isNotBlank()) {
                    scheduleRepository.updateSessionCompletion(task.scheduleSessionId, isChecked)
                } else {
                    repository.updateTask(task.copy(isCompleted = isChecked))
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                task.isCompleted = !isChecked
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    requireContext(),
                    "Could not update task completion: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateSummary()
        }
    }

    private fun showTaskActions(task: Task) {
        if (task.scheduleSessionId.isNotBlank()) {
            AlertDialog.Builder(requireContext())
                .setTitle(task.title)
                .setMessage("${task.subject}\n${task.dueTime}")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("Edit task", "Delete task")) { _, choice ->
                if (choice == 0) {
                    showTaskEditor(task)
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Delete task")
                        .setMessage("Delete \"${task.title}\"?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> deleteTask(task) }
                        .show()
                }
            }
            .show()
    }

    private fun showTaskEditor(existing: Task? = null) {
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val titleField = createField("Task title", existing?.title.orEmpty())
        val subjectField = createField("Subject", existing?.subject.orEmpty())
        val dueField = createField(
            "Due date YYYY-MM-DD or time HH:MM (optional)",
            existing?.dueTime ?: selectedDate.toString()
        )
        val descriptionField = createField("Description (optional)", existing?.description.orEmpty())
        val priorities = Priority.values()
        val prioritySpinner = Spinner(requireContext()).apply {
            adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                priorities.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            )
            setSelection(priorities.indexOf(existing?.priority ?: Priority.MEDIUM))
        }
        listOf(titleField, subjectField, dueField, descriptionField, prioritySpinner).forEach { field ->
            content.addView(
                field,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            )
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (existing == null) "Add task" else "Edit task")
            .setView(ScrollView(requireContext()).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleField.text.toString().trim()
                val subject = subjectField.text.toString().trim()
                if (title.isEmpty()) {
                    titleField.error = "Task title is required"
                    return@setOnClickListener
                }
                if (subject.isEmpty()) {
                    subjectField.error = "Subject is required"
                    return@setOnClickListener
                }
                val task = Task(
                    id = existing?.id.orEmpty(),
                    title = title,
                    subject = subject,
                    dueTime = dueField.text.toString().trim(),
                    priority = priorities[prioritySpinner.selectedItemPosition],
                    isCompleted = existing?.isCompleted ?: false,
                    description = descriptionField.text.toString().trim(),
                    userId = existing?.userId.orEmpty()
                )
                try {
                    TaskValidator.validate(task)
                } catch (exception: IllegalArgumentException) {
                    dueField.error = exception.message
                    return@setOnClickListener
                }
                saveTask(task, existing != null)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun createField(hintText: String, value: String): EditText =
        EditText(requireContext()).apply {
            hint = hintText
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT
        }

    private fun saveTask(task: Task, isEditing: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isEditing) repository.updateTask(task) else repository.addTask(task)
            } catch (exception: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Could not save task: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteTask(task: Task) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.deleteTask(task.id)
            } catch (exception: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Could not delete task: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateSummary() {
        val completed = taskList.count { it.isCompleted }
        binding.tvTasksSubtitle.text = "$completed of ${taskList.size} completed"
        binding.taskProgressBar.setProgressCompat(
            if (taskList.isEmpty()) 0 else completed * 100 / taskList.size,
            true
        )
        toggleEmptyState()
    }

    private fun replaceScheduledTasks(sessions: List<ScheduleSession>) {
        val taskById = savedTasks.associateBy { it.id }
        scheduledTasks.clear()
        scheduledTasks.addAll(sessions.map { session ->
            val linkedTask = taskById[session.taskId]
            Task(
                id = session.id,
                title = session.description.ifBlank { "Study session" },
                subject = session.subjectName,
                dueTime = formatSessionTime(session),
                priority = linkedTask?.priority ?: Priority.MEDIUM,
                isCompleted = session.isCompleted,
                description = session.description,
                userId = session.userId,
                scheduleSessionId = session.id,
                scheduleDate = session.sessionDate
            )
        })
        refreshTaskList()
    }

    private fun refreshTaskList() {
        taskList.clear()
        taskList.addAll(savedTasks.filter(::isTaskOnSelectedDate))
        taskList.addAll(scheduledTasks.filter { task ->
            task.scheduleDate == selectedDate.toString()
        })
        adapter.notifyDataSetChanged()
        updateSummary()
    }

    private fun isTaskOnSelectedDate(task: Task): Boolean {
        if (task.dueTime.isBlank()) return selectedDate == LocalDate.now()
        return try {
            LocalDate.parse(task.dueTime) == selectedDate
        } catch (_: DateTimeParseException) {
            // Time-only and legacy free-form tasks belong to today.
            selectedDate == LocalDate.now()
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                selectedDate = LocalDate.of(year, month + 1, day)
                updateDateButton()
                refreshTaskList()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        ).show()
    }

    private fun updateDateButton() {
        val label = if (selectedDate == LocalDate.now()) {
            "Today · ${formatDate(selectedDate)}"
        } else {
            selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault()))
        }
        binding.btnTaskDate.text = label
    }

    private fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))

    private fun formatSessionTime(session: ScheduleSession): String {
        val date = LocalDate.parse(session.sessionDate)
            .format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
        return "$date · ${formatTime(session.startMinutes)}–${formatTime(session.endMinutes)}"
    }

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    private fun toggleEmptyState() {
        if (selectedDate == LocalDate.now()) {
            binding.tvEmptyTasksTitle.setText(R.string.no_tasks_today)
        } else {
            binding.tvEmptyTasksTitle.setText(R.string.no_tasks_for_date)
        }
        binding.emptyState.visibility = if (taskList.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTasks.visibility = if (taskList.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        tasksListener?.remove()
        tasksListener = null
        scheduleListener?.remove()
        scheduleListener = null
        super.onDestroyView()
        _binding = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}