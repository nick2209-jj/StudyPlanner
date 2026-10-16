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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.studyplanner.adapter.TaskAdapter
import com.example.studyplanner.databinding.FragmentTasksBinding
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.Task
import com.example.studyplanner.repository.TaskRepository
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch

class TasksFragment : Fragment() {

    private var _binding: FragmentTasksBinding? = null
    private val binding get() = _binding!!

    private val repository = TaskRepository()
    private val taskList = mutableListOf<Task>()
    private lateinit var adapter: TaskAdapter
    private var tasksListener: ListenerRegistration? = null

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

        tasksListener = repository.observeTasks(
            onUpdate = { tasks ->
                if (_binding != null) {
                    taskList.clear()
                    taskList.addAll(tasks)
                    adapter.notifyDataSetChanged()
                    updateSummary()
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

        binding.fabAddTask.setOnClickListener { showTaskEditor() }
    }

    private fun updateTaskCompletion(task: Task, isChecked: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.updateTask(task.copy(isCompleted = isChecked))
            } catch (exception: Exception) {
                task.isCompleted = !isChecked
                adapter.notifyDataSetChanged()
                Toast.makeText(
                    requireContext(),
                    "Could not update task: ${exception.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateSummary()
        }
    }

    private fun showTaskActions(task: Task) {
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
        val dueField = createField("Due date or time (optional)", existing?.dueTime.orEmpty())
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

    private fun toggleEmptyState() {
        binding.emptyState.visibility = if (taskList.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTasks.visibility = if (taskList.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        tasksListener?.remove()
        tasksListener = null
        super.onDestroyView()
        _binding = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}