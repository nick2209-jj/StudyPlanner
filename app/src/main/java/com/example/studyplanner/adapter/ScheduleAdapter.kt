package com.example.studyplanner.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.studyplanner.databinding.ItemScheduleBinding
import com.example.studyplanner.model.ScheduleSession

class ScheduleAdapter(
    private val items: List<ScheduleSession>,
    private val onSessionClick: (ScheduleSession) -> Unit
) :
    RecyclerView.Adapter<ScheduleAdapter.ScheduleViewHolder>() {

    inner class ScheduleViewHolder(val binding: ItemScheduleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val binding = ItemScheduleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScheduleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        binding.tvStartTime.text = formatTime(item.startMinutes)
        binding.tvSubjectName.text = item.subjectName
        binding.tvTopic.text = item.description
        binding.tvDuration.text =
            "${formatTime(item.startMinutes)} – ${formatTime(item.endMinutes)}"

        val color = Color.parseColor("#6C4AB6")
        binding.subjectColorBar.setBackgroundColor(color)
        binding.dotIndicator.background.setTint(color)
        binding.root.setOnClickListener { onSessionClick(item) }

        binding.root.alpha = 0f
        binding.root.translationX = 40f
        binding.root.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(position * 90L)
            .setDuration(350L)
            .start()
    }

    override fun getItemCount() = items.size

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(hour12, minute, suffix)
    }
}