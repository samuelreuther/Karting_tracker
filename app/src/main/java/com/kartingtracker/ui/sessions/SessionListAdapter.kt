package com.kartingtracker.ui.sessions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kartingtracker.R
import com.kartingtracker.databinding.ItemSessionBinding
import com.kartingtracker.ui.SessionListItemUiState
import com.kartingtracker.ui.common.formatFileSize
import com.kartingtracker.ui.common.formatSessionDate

class SessionListAdapter(
    private val onSessionClicked: (SessionListItemUiState) -> Unit,
    private val onSessionLongPressed: (SessionListItemUiState) -> Unit
) : RecyclerView.Adapter<SessionListAdapter.SessionViewHolder>() {
    private var items: List<SessionListItemUiState> = emptyList()

    fun submitList(sessions: List<SessionListItemUiState>) {
        items = sessions
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onSessionClicked: (SessionListItemUiState) -> Unit,
        private val onSessionLongPressed: (SessionListItemUiState) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SessionListItemUiState) {
            val session = item.session
            binding.sessionTitle.text = session.trackName
            binding.sessionDate.text = formatSessionDate(session.startTimeEpochMs)
            binding.sessionMeta.text = "${session.laps.size} laps - ${item.sampleCount} samples"
            binding.sessionFileSize.text = binding.root.context.getString(R.string.file_size) + ": " +
                formatFileSize(item.fileSizeBytes)
            binding.root.setOnClickListener {
                onSessionClicked(item)
            }
            binding.root.setOnLongClickListener {
                onSessionLongPressed(item)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onSessionClicked, onSessionLongPressed)
    }
}
