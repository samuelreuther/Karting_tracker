package com.kartingtracker.data

import android.content.Context

class TrackManager(
    context: Context
) {
    private val preferences = context.getSharedPreferences("karting_tracks", Context.MODE_PRIVATE)

    fun getTracks(): List<Track> {
        val names = preferences.getStringSet(KEY_TRACKS, setOf(DEFAULT_TRACK_NAME)).orEmpty()
        return names
            .map { name -> Track(name) }
            .sortedBy { track -> track.name.lowercase() }
    }

    fun saveTrack(trackName: String): Track? {
        val cleanedName = trackName.trim()
        if (cleanedName.isBlank()) {
            return null
        }

        val updatedTracks = preferences.getStringSet(KEY_TRACKS, setOf(DEFAULT_TRACK_NAME)).orEmpty().toMutableSet()
        updatedTracks += cleanedName
        preferences.edit().putStringSet(KEY_TRACKS, updatedTracks).apply()
        return Track(cleanedName)
    }

    fun getSelectedTrackName(): String {
        val selected = preferences.getString(KEY_SELECTED_TRACK, null)
        if (!selected.isNullOrBlank()) {
            return selected
        }
        val fallback = getTracks().firstOrNull()?.name ?: DEFAULT_TRACK_NAME
        setSelectedTrack(fallback)
        return fallback
    }

    fun setSelectedTrack(trackName: String) {
        saveTrack(trackName)
        preferences.edit().putString(KEY_SELECTED_TRACK, trackName).apply()
    }

    companion object {
        private const val KEY_TRACKS = "tracks"
        private const val KEY_SELECTED_TRACK = "selected_track"
        private const val DEFAULT_TRACK_NAME = "General Track"
    }
}
