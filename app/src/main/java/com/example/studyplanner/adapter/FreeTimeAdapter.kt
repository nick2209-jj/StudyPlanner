package com.example.studyplanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.studyplanner.databinding.ItemFreeTimeBinding
import com.example.studyplanner.model.FreeTimeSlot
import java.time.DayOfWeek

class FreeTimeAdapter(
    private val onEdit: (FreeTimeSlot) -> Unit,
    private val onDelete: (FreeTimeSlot) -> Unit
) : RecyclerView.Adapter<FreeTimeAdapter.FreeTimeViewHolder>() {

    private val slots = mutableListOf<FreeTimeSlot>()

    fun submitSlots(items: List<FreeTimeSlot>) {
        slots.clear()
        slots.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FreeTimeViewHolder {
        val binding = ItemFreeTimeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FreeTimeViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FreeTimeViewHolder, position: Int) {
        val slot = slots[position]
        holder.binding.tvFreeTimeWhen.text = buildString {
            if (slot.isRecurring) {
                val day = DayOfWeek.of(slot.dayOfWeek).name.lowercase()
                    .replaceFirstChar(Char::uppercase)
                append("Every $day")
            } else {
                append(slot.date)
            }
            append(" · ${formatTime(slot.startMinutes)}–${formatTime(slot.endMinutes)}")
        }
        holder.binding.tvFreeTimeDescription.text =
            slot.description.ifBlank { if (slot.isRecurring) "Repeats weekly" else "One-time availability" }
        holder.binding.btnEditFreeTime.setOnClickListener { onEdit(slot) }
        holder.binding.btnDeleteFreeTime.setOnClickListener { onDelete(slot) }
    }

    override fun getItemCount(): Int = slots.size

    private fun formatTime(minutes: Int): String {
        val hour = minutes / 60
        val minute = minutes % 60
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = when (hour % 12) {
            0 -> 12
            else -> hour % 12
        }
        return "%d:%02d %s".format(hour12, minute, suffix)
    }

    class FreeTimeViewHolder(val binding: ItemFreeTimeBinding) :
        RecyclerView.ViewHolder(binding.root)
}
