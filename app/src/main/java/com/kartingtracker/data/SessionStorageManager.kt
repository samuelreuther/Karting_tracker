package com.kartingtracker.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

class SessionStorageManager(
    context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val sessionDirectory = File(context.filesDir, "sessions").apply { mkdirs() }

    fun saveSession(session: Session) {
        val file = File(
            sessionDirectory,
            buildString {
                append("session_")
                append(sanitizeTrackName(session.trackName))
                append("_")
                append(session.startTimeEpochMs)
                append(".json")
            }
        )
        file.writeText(gson.toJson(session))
    }

    fun loadAllSessions(): List<Session> {
        return sessionDirectory
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .mapNotNull { file -> parseSession(file) }
            .sortedByDescending { session -> session.startTimeEpochMs }
    }

    fun loadSessionsForTrack(trackName: String): List<Session> {
        return loadAllSessions().filter { session ->
            session.trackName.equals(trackName, ignoreCase = true)
        }
    }

    fun loadLastSession(): Session? {
        return loadAllSessions().maxByOrNull { session -> session.startTimeEpochMs }
    }

    private fun parseSession(file: File): Session? {
        return try {
            gson.fromJson(file.readText(), Session::class.java)
        } catch (exception: Exception) {
            Log.w("SessionStorageManager", "Failed to parse session file ${file.name}", exception)
            null
        }
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }
}
