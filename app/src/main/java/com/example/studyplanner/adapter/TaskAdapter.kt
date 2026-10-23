package com.example.studyplanner.adapter

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.studyplanner.databinding.ItemTaskBinding
import com.example.studyplanner.model.Priority
import com.example.studyplanner.model.Task

class TaskAdapter(
    private val tasks: MutableList<Task>,
    private val onTaskChecked: (Task, Boolean) -> Unit,
    private val onTaskClick: (Task) -> Unit
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    inner class TaskViewHolder(val binding: ItemTaskBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = tasks[position]
        val binding = holder.binding

        binding.tvTaskTitle.text = task.title
        binding.tvTaskSubject.text = buildString {
            append(task.subject)
            if (task.dueTime.isNotBlank()) append(" · Due ${task.dueTime}")
        }
        binding.checkTask.setOnCheckedChangeListener(null)
        binding.checkTask.isChecked = task.isCompleted
        applyCompletedStyle(binding, task.isCompleted)
        binding.root.setOnClickListener { onTaskClick(task) }

        binding.tvPriorityTag.text = if (task.scheduleSessionId.isNotBlank()) {
            "Scheduled"
        } else {
            when (task.priority) {
                Priority.HIGH -> "High"
                Priority.MEDIUM -> "Medium"
                Priority.LOW -> "Low"
            }
        }
        binding.tvPriorityTag.setBackgroundColor(
            if (task.scheduleSessionId.isNotBlank()) {
                Color.parseColor("#6C4AB6")
            } else {
                when (task.priority) {
                    Priority.HIGH -> Color.parseColor("#E53935")
                    Priority.MEDIUM -> Color.parseColor("#FB8C00")
                    Priority.LOW -> Color.parseColor("#43A047")
                }
            }
        )

        binding.checkTask.setOnCheckedChangeListener { _, isChecked ->
            task.isCompleted = isChecked
            applyCompletedStyle(binding, isChecked)
            onTaskChecked(task, isChecked)
        }

        // Staggered entrance for each row
        binding.root.alpha = 0f
        binding.root.translationY = 30f
        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(position * 60L)
            .setDuration(300L)
            .start()
    }

    private fun applyCompletedStyle(binding: ItemTaskBinding, isCompleted: Boolean) {
        if (isCompleted) {
            binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            binding.tvTaskTitle.alpha = 0.5f
            binding.tvTaskSubject.alpha = 0.5f
        } else {
            binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.tvTaskTitle.alpha = 1f
            binding.tvTaskSubject.alpha = 1f
        }
    }

    override fun getItemCount() = tasks.size
}