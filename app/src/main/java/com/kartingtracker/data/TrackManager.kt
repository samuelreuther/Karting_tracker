package com.kartingtracker.data

import android.content.Context

class TrackManager(
    context: Context
) {
    private val preferences = context.getSharedPreferences("karting_tracks", Context.MODE_PRIVATE)

    fun normalizeTrackName(name: String): String {
        return name
            .trim()
            .replace(WHITESPACE_REGEX, " ")
    }

    fun addTrackSafe(name: String): Boolean {
        val normalizedName = normalizeTrackName(name)
        if (normalizedName.isBlank()) {
            return false
        }

        val existingTracks = preferences.getStringSet(KEY_TRACKS, emptySet()).orEmpty()
        if (existingTracks.any { existing -> existing.equals(normalizedName, ignoreCase = true) }) {
            return false
        }

        val updatedTracks = existingTracks.toMutableSet().apply {
            add(normalizedName)
        }
        preferences.edit().putStringSet(KEY_TRACKS, updatedTracks).apply()
        return true
    }

    fun getTracksList(): List<String> {
        val selectedTrack = readSelectedTrackName()
        val sortedTracks = preferences.getStringSet(KEY_TRACKS, emptySet())
            .orEmpty()
            .map(::normalizeTrackName)
            .filter { name -> name.isNotBlank() }
            .distinctBy { name -> name.lowercase() }
            .sortedBy { name -> name.lowercase() }

        return if (!selectedTrack.isNullOrBlank() && sortedTracks.any { name -> name.equals(selectedTrack, ignoreCase = true) }) {
            val selected = sortedTracks.first { name -> name.equals(selectedTrack, ignoreCase = true) }
            listOf(selected) + sortedTracks.filterNot { name -> name.equals(selected, ignoreCase = true) }
        } else {
            sortedTracks
        }
    }

    fun getTracks(): List<Track> {
        return getTracksList().map(::Track)
    }

    fun saveTrack(trackName: String): Track? {
        val normalizedName = normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }

        if (!addTrackSafe(normalizedName)) {
            val existingTrack = getTracksList()
                .firstOrNull { existing -> existing.equals(normalizedName, ignoreCase = true) }
                ?: return null
            return Track(existingTrack)
        }

        return Track(normalizedName)
    }

    fun getSelectedTrackName(): String? {
        val selected = readSelectedTrackName()
        if (selected.isBlank()) {
            return null
        }

        return getTracksList()
            .firstOrNull { trackName -> trackName.equals(selected, ignoreCase = true) }
            ?: run {
                clearSelectedTrack()
                null
            }
    }

    fun setSelectedTrack(trackName: String) {
        val normalizedName = normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            clearSelectedTrack()
            return
        }

        val persistedTrackName = getTracksList()
            .firstOrNull { existing -> existing.equals(normalizedName, ignoreCase = true) }
            ?: if (addTrackSafe(normalizedName)) normalizedName else normalizedName

        preferences.edit().putString(KEY_SELECTED_TRACK, persistedTrackName).apply()
    }

    fun clearSelectedTrack() {
        preferences.edit().remove(KEY_SELECTED_TRACK).apply()
    }

    private fun readSelectedTrackName(): String {
        return normalizeTrackName(preferences.getString(KEY_SELECTED_TRACK, null).orEmpty())
    }

    companion object {
        private const val KEY_TRACKS = "tracks"
        private const val KEY_SELECTED_TRACK = "selected_track"
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
