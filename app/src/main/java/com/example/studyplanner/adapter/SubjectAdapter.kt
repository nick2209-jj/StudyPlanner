package com.example.studyplanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.studyplanner.databinding.ItemSubjectBinding
import com.example.studyplanner.model.Subject

class SubjectAdapter(
    private val onEdit: (Subject) -> Unit,
    private val onDelete: (Subject) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    private val subjects = mutableListOf<Subject>()

    fun submitSubjects(items: List<Subject>) {
        subjects.clear()
        subjects.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val binding = ItemSubjectBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SubjectViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        holder.binding.tvSubjectName.text = subject.name
        holder.binding.tvSubjectDetails.text = buildString {
            if (subject.code.isNotBlank()) append("${subject.code} · ")
            append("${subject.totalHoursPerWeek} hrs/week · ${subject.priority.lowercase().replaceFirstChar(Char::uppercase)} priority")
        }
        holder.binding.btnEditSubject.setOnClickListener { onEdit(subject) }
        holder.binding.btnDeleteSubject.setOnClickListener { onDelete(subject) }
    }

    override fun getItemCount(): Int = subjects.size

    class SubjectViewHolder(val binding: ItemSubjectBinding) :
        RecyclerView.ViewHolder(binding.root)
}
