package com.kartingtracker.ui.main

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.kartingtracker.R
import com.kartingtracker.data.TrackNameCanonicalizer
import com.kartingtracker.data.Track
import com.kartingtracker.databinding.ItemTrackTileBinding
import java.io.File

class TrackTileAdapter(
    private val onTrackSelected: (String) -> Unit
) : RecyclerView.Adapter<TrackTileAdapter.TrackTileViewHolder>() {

    private var items: List<Track> = emptyList()
    private var selectedTrackName: String = ""

    fun submit(tracks: List<Track>, selected: String) {
        items = tracks
        selectedTrackName = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackTileViewHolder {
        val binding = ItemTrackTileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackTileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackTileViewHolder, position: Int) {
        holder.bind(items[position], items[position].name.equals(selectedTrackName, ignoreCase = true))
    }

    override fun getItemCount(): Int = items.size

    inner class TrackTileViewHolder(
        private val binding: ItemTrackTileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: Track, isSelected: Boolean) {
            binding.trackTileName.text = track.name
            val imagePath = resolveMapImagePath(track)
            val hasImage = !imagePath.isNullOrBlank() && File(imagePath).exists()
            if (hasImage) {
                val bitmap = BitmapFactory.decodeFile(imagePath)
                if (bitmap != null) {
                    binding.trackTileImage.setImageBitmap(bitmap)
                    binding.trackTileImage.isVisible = true
                    binding.trackTileFallback.isVisible = false
                } else {
                    showFallback(track.name)
                }
            } else {
                showFallback(track.name)
            }

            val cardColor = if (isSelected) R.color.karting_primary else R.color.karting_stroke
            val bgColor = if (isSelected) R.color.karting_surface_tint_strong else R.color.karting_panel_alt
            binding.trackTileCard.strokeColor = binding.root.context.getColor(cardColor)
            binding.trackTileCard.setCardBackgroundColor(binding.root.context.getColor(bgColor))
            binding.trackTileCard.strokeWidth = if (isSelected) 2 else 1
            binding.trackTileSelectedBadge.isVisible = isSelected

            binding.root.setOnClickListener {
                onTrackSelected(track.name)
            }
        }

        private fun resolveMapImagePath(track: Track): String? {
            val direct = track.mapImagePath
            if (!direct.isNullOrBlank() && File(direct).exists()) {
                return direct
            }
            val imageDirectory = File(binding.root.context.filesDir, "track_layouts/images")
            if (!imageDirectory.exists()) {
                return direct
            }
            return TrackNameCanonicalizer.possibleStorageKeys(track.name)
                .flatMap { key -> listOf("png", "jpg", "jpeg", "webp").map { ext -> File(imageDirectory, "layout_${key}.$ext") } }
                .firstOrNull(File::exists)
                ?.absolutePath
                ?: direct
        }

        private fun showFallback(trackName: String) {
            binding.trackTileImage.isVisible = false
            binding.trackTileFallback.isVisible = true
            binding.trackTileFallback.text = trackName.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1).uppercase() }
        }
    }
}
