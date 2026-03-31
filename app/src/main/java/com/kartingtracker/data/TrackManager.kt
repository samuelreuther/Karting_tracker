package com.kartingtracker.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class TrackManager(
    context: Context,
    private val sessionStorageManager: SessionStorageManager,
    private val trackProfileManager: TrackProfileManager,
    private val trackLayoutManager: TrackLayoutManager
) {
    private val preferences = context.getSharedPreferences("karting_tracks", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

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

        val existingTracks = readTrackNames()
        if (existingTracks.any { existing -> existing.equals(normalizedName, ignoreCase = true) }) {
            return false
        }

        val updatedTrack = Track(name = normalizedName)
        persistTrackNames(existingTracks + normalizedName)
        persistTrack(updatedTrack)
        return true
    }

    fun getTracksList(): List<String> {
        return getTracks().map(Track::name)
    }

    fun getTracks(): List<Track> {
        return readTrackNames()
            .map(::normalizeTrackName)
            .filter { name -> name.isNotBlank() }
            .distinctBy { name -> name.lowercase() }
            .map { trackName ->
                getTrack(trackName) ?: Track(name = trackName)
            }
            .sortedWith(
                compareByDescending<Track> { track -> track.lastUsedEpochMs ?: 0L }
                    .thenBy { track -> track.name.lowercase() }
            )
    }

    fun getTrack(trackName: String): Track? {
        val normalizedName = normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }

        val persistedTrackName = readTrackNames()
            .firstOrNull { existing -> existing.equals(normalizedName, ignoreCase = true) }
            ?: return null
        return readPersistedTrack(persistedTrackName) ?: Track(name = persistedTrackName)
    }

    fun saveTrack(trackName: String): Track? {
        val normalizedName = normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }

        val existingTrack = getTrack(normalizedName)
        return if (existingTrack != null) {
            existingTrack
        } else {
            val createdTrack = Track(name = normalizedName)
            persistTrackNames(readTrackNames() + normalizedName)
            persistTrack(createdTrack)
            createdTrack
        }
    }

    fun saveTrack(track: Track): Track? {
        val normalizedName = normalizeTrackName(track.name)
        if (normalizedName.isBlank()) {
            return null
        }

        val normalizedTrack = track.copy(name = normalizedName)
        val existingNames = readTrackNames()
        if (existingNames.none { existing -> existing.equals(normalizedName, ignoreCase = true) }) {
            persistTrackNames(existingNames + normalizedName)
        }
        persistTrack(normalizedTrack)
        return normalizedTrack
    }

    fun renameTrack(oldName: String, newName: String): Boolean {
        val normalizedOld = normalizeTrackName(oldName)
        val normalizedNew = normalizeTrackName(newName)
        if (normalizedOld.isBlank() || normalizedNew.isBlank()) {
            return false
        }
        if (normalizedOld.equals(normalizedNew, ignoreCase = true)) {
            return true
        }

        val existingTracks = readTrackNames()
        val oldPersistedName = existingTracks.firstOrNull { existing ->
            existing.equals(normalizedOld, ignoreCase = true)
        } ?: return false

        if (existingTracks.any { existing -> existing.equals(normalizedNew, ignoreCase = true) }) {
            return false
        }

        val oldTrack = readPersistedTrack(oldPersistedName) ?: Track(name = oldPersistedName)
        val renamedTrack = oldTrack.copy(name = normalizedNew)

        val updatedNames = existingTracks.map { existing ->
            if (existing.equals(oldPersistedName, ignoreCase = true)) normalizedNew else existing
        }

        val editor = preferences.edit()
            .putStringSet(KEY_TRACKS, updatedNames.toSet())
            .remove(buildTrackKey(oldPersistedName))
            .putString(buildTrackKey(normalizedNew), gson.toJson(renamedTrack))

        val selectedTrack = readSelectedTrackName()
        if (selectedTrack.equals(oldPersistedName, ignoreCase = true)) {
            editor.putString(KEY_SELECTED_TRACK, normalizedNew)
        }
        editor.apply()

        return true
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

        val persistedTrack = getTracksList()
            .firstOrNull { existing -> existing.equals(normalizedName, ignoreCase = true) }
            ?: run {
                saveTrack(normalizedName)
                normalizedName
            }

        val now = System.currentTimeMillis()
        val existingTrack = getTrack(persistedTrack) ?: Track(name = persistedTrack)
        persistTrack(existingTrack.copy(lastUsedEpochMs = now))
        preferences.edit().putString(KEY_SELECTED_TRACK, persistedTrack).apply()
    }

    fun clearSelectedTrack() {
        preferences.edit().remove(KEY_SELECTED_TRACK).apply()
    }

    fun deleteTrack(trackName: String): Boolean {
        val normalizedName = normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return false
        }

        val existingTracks = readTrackNames()
        val persistedTrack = existingTracks.firstOrNull { existing ->
            existing.equals(normalizedName, ignoreCase = true)
        } ?: return false

        val updatedTracks = existingTracks.filterNot { existing ->
            existing.equals(persistedTrack, ignoreCase = true)
        }

        val selectedTrack = readSelectedTrackName()
        val editor = preferences.edit().putStringSet(KEY_TRACKS, updatedTracks.toSet())
            .remove(buildTrackKey(persistedTrack))
        if (selectedTrack.equals(persistedTrack, ignoreCase = true)) {
            val fallbackTrack = updatedTracks
                .map(::normalizeTrackName)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
                .firstOrNull()
            if (fallbackTrack == null) {
                editor.remove(KEY_SELECTED_TRACK)
            } else {
                editor.putString(KEY_SELECTED_TRACK, fallbackTrack)
            }
        }
        editor.apply()

        sessionStorageManager.deleteSessionsForTrack(persistedTrack)
        trackProfileManager.deleteProfile(persistedTrack)
        trackLayoutManager.deleteLayout(persistedTrack)
        return true
    }

    private fun readSelectedTrackName(): String {
        return normalizeTrackName(preferences.getString(KEY_SELECTED_TRACK, null).orEmpty())
    }

    private fun readTrackNames(): List<String> {
        return preferences.getStringSet(KEY_TRACKS, emptySet())
            .orEmpty()
            .toList()
    }

    private fun persistTrackNames(trackNames: List<String>) {
        preferences.edit().putStringSet(KEY_TRACKS, trackNames.toSet()).apply()
    }

    private fun persistTrack(track: Track) {
        preferences.edit()
            .putString(buildTrackKey(track.name), gson.toJson(track))
            .apply()
    }

    private fun readPersistedTrack(trackName: String): Track? {
        val rawTrack = preferences.getString(buildTrackKey(trackName), null).orEmpty()
        if (rawTrack.isBlank()) {
            return null
        }
        return runCatching { gson.fromJson(rawTrack, Track::class.java) }
            .getOrNull()
            ?.copy(name = normalizeTrackName(trackName))
    }

    private fun buildTrackKey(trackName: String): String {
        return "track_${sanitizeTrackName(trackName)}"
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    companion object {
        private const val KEY_TRACKS = "tracks"
        private const val KEY_SELECTED_TRACK = "selected_track"
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
