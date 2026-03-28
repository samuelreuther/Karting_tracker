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
            binding.lapTitle.text = buildString {
                append("Lap ${position + 1}")
                val tags = mutableListOf<String>()
                if (lap.isOutlap) {
                    tags += "OUTLAP"
                }
                if (lap.isDisturbed) {
                    tags += "DISTURBED"
                }
                if (tags.isNotEmpty()) {
                    append(" (")
                    append(tags.joinToString(", "))
                    append(")")
                }
            }
            binding.lapTime.text = formatLapTime(lap.lapTimeMs)
            val sectorSummary = lap.sectorTimesMs
                .mapIndexed { index, sectorTimeMs -> "S${index + 1} ${formatLapTime(sectorTimeMs)}" }
                .joinToString(separator = " | ")
            binding.lapMeta.text =
                buildString {
                    append("${lap.samples.size} samples - ${lap.brakingPeakIndices.size} braking peaks - ${lap.corneringPeakIndices.size} cornering peaks - confidence ${"%.2f".format(lap.confidenceScore)}")
                    if (sectorSummary.isNotBlank()) {
                        append("\n")
                        append(sectorSummary)
                    }
                }
        }
    }
}
