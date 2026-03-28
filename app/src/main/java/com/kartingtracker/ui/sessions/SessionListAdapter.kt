package com.kartingtracker.ui.sessions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kartingtracker.data.Session
import com.kartingtracker.databinding.ItemSessionBinding
import com.kartingtracker.ui.common.formatSessionDate

class SessionListAdapter(
    private val onSessionClicked: (Session) -> Unit
) : RecyclerView.Adapter<SessionListAdapter.SessionViewHolder>() {
    private var items: List<Session> = emptyList()

    fun submitList(sessions: List<Session>) {
        items = sessions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SessionViewHolder(binding, onSessionClicked)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class SessionViewHolder(
        private val binding: ItemSessionBinding,
        private val onSessionClicked: (Session) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(session: Session) {
            binding.sessionTitle.text = session.trackName
            binding.sessionDate.text = formatSessionDate(session.startTimeEpochMs)
            binding.sessionMeta.text = "${session.laps.size} laps - ${session.samples.size} samples"
            binding.root.setOnClickListener {
                onSessionClicked(session)
            }
        }
    }
}
