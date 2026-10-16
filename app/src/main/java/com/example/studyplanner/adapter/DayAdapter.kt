package com.example.studyplanner.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.studyplanner.databinding.ItemDayChipBinding
import com.example.studyplanner.model.DayItem

class DayAdapter(
    private val days: List<DayItem>,
    private val onDaySelected: (Int) -> Unit
) : RecyclerView.Adapter<DayAdapter.DayViewHolder>() {

    private var selectedPosition = days.indexOfFirst { it.isSelected }.coerceAtLeast(0)

    inner class DayViewHolder(val binding: ItemDayChipBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val binding = ItemDayChipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        val day = days[position]
        val binding = holder.binding
        val isSelected = position == selectedPosition

        binding.tvDayLabel.text = day.label
        binding.tvDayDate.text = day.date
        binding.dayChipRoot.isSelected = isSelected
        binding.tvDayLabel.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF546E7A.toInt())
        binding.tvDayDate.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF2D1B4E.toInt())

        binding.root.setOnClickListener {
            val previous = selectedPosition
            selectedPosition = position
            notifyItemChanged(previous)
            notifyItemChanged(selectedPosition)
            onDaySelected(position)
        }

        binding.root.animate().scaleX(if (isSelected) 1.05f else 1f).scaleY(if (isSelected) 1.05f else 1f).setDuration(200L).start()
    }

    override fun getItemCount() = days.size
}