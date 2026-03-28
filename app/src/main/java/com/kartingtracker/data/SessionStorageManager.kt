package com.kartingtracker.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

class SessionStorageManager(
    context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val sessionDirectory = File(context.filesDir, "sessions").apply { mkdirs() }

    fun saveSession(session: Session) {
        val file = File(sessionDirectory, buildSessionFileName(session))
        val partialFile = File(sessionDirectory, buildPartialFileName(session.trackName, session.startTimeEpochMs))
        if (session.isPartial) {
            Log.i(TAG, "Saving partial session ${session.id}")
        } else {
            Log.i(TAG, "Saving FINAL session ${session.id}")
        }
        file.writeText(gson.toJson(session))
        if (!session.isPartial) {
            partialFile.delete()
        }
    }

    fun loadAllSessions(): List<Session> {
        val files = sessionDirectory
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .filterNot { file ->
                file.name.endsWith(PARTIAL_SUFFIX) && File(
                    sessionDirectory,
                    file.name.removeSuffix(PARTIAL_SUFFIX) + JSON_SUFFIX
                ).exists()
            }

        return files
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
            val rawJson = file.readText()
            val jsonObject = JsonParser.parseString(rawJson).asJsonObject
            val parsedSession = gson.fromJson(jsonObject, Session::class.java)
            parsedSession.copy(
                processingVersion = if (!jsonObject.has(PROCESSING_VERSION_FIELD) || parsedSession.processingVersion <= 0) {
                    Session.DEFAULT_PROCESSING_VERSION
                } else {
                    parsedSession.processingVersion
                },
                isPartial = if (jsonObject.has(IS_PARTIAL_FIELD)) parsedSession.isPartial else false
            )
        } catch (exception: Exception) {
            Log.w("SessionStorageManager", "Failed to parse session file ${file.name}", exception)
            null
        }
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    private fun buildSessionFileName(session: Session): String {
        return if (session.isPartial) {
            buildPartialFileName(session.trackName, session.startTimeEpochMs)
        } else {
            buildFinalFileName(session.trackName, session.startTimeEpochMs)
        }
    }

    private fun buildFinalFileName(trackName: String, startTimeEpochMs: Long): String {
        return buildString {
            append("session_")
            append(sanitizeTrackName(trackName))
            append("_")
            append(startTimeEpochMs)
            append(JSON_SUFFIX)
        }
    }

    private fun buildPartialFileName(trackName: String, startTimeEpochMs: Long): String {
        return buildString {
            append("session_")
            append(sanitizeTrackName(trackName))
            append("_")
            append(startTimeEpochMs)
            append(PARTIAL_SUFFIX)
        }
    }

    companion object {
        private const val TAG = "SessionStorageManager"
        private const val PROCESSING_VERSION_FIELD = "processingVersion"
        private const val IS_PARTIAL_FIELD = "isPartial"
        private const val JSON_SUFFIX = ".json"
        private const val PARTIAL_SUFFIX = "_partial.json"
    }
}
