package com.kartingtracker.ui.laps

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kartingtracker.data.Lap
import com.kartingtracker.databinding.ItemLapBinding
import com.kartingtracker.ui.common.formatLapTime

class LapListAdapter : RecyclerView.Adapter<LapListAdapter.LapViewHolder>() {
    private var items: List<Lap> = emptyList()

    fun submitList(laps: List<Lap>) {
        items = laps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LapViewHolder {
        val binding = ItemLapBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LapViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LapViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    class LapViewHolder(
        private val binding: ItemLapBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(lap: Lap, position: Int) {
            binding.lapTitle.text = "Lap ${position + 1}"
            binding.lapTime.text = formatLapTime(lap.lapTimeMs)
            binding.lapMeta.text =
                "${lap.samples.size} samples - ${lap.brakingPeakIndices.size} braking peaks - ${lap.corneringPeakIndices.size} cornering peaks"
        }
    }
}
