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
    private val corruptDirectory = File(context.filesDir, "corrupt_sessions").apply { mkdirs() }

    fun saveSession(session: Session) {
        try {
            val file = File(sessionDirectory, buildSessionFileName(session))
            val partialFile = File(sessionDirectory, buildPartialFileName(session.trackName, session.startTimeEpochMs))
            if (session.isPartial) {
                Log.i(TAG, "Saving partial session ${session.id}")
            } else {
                Log.i(TAG, "Saving FINAL session ${session.id}")
            }
            writeAtomically(file, gson.toJson(session))
            if (!session.isPartial && partialFile.exists() && !partialFile.delete()) {
                Log.w(TAG, "Failed to delete partial session file ${partialFile.name}")
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save session ${session.id}", exception)
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
        if (file.length() <= 0L) {
            Log.w(TAG, "Session file ${file.name} is empty")
            quarantineCorruptFile(file, "empty session file")
            return null
        }
        if (file.length() > MAX_SESSION_FILE_SIZE_BYTES) {
            Log.e(TAG, "Session file ${file.name} is too large (${file.length()} bytes)")
            quarantineCorruptFile(file, "oversized session file")
            return null
        }

        return try {
            val rawJson = file.readText()
            if (rawJson.isBlank()) {
                Log.w(TAG, "Session file ${file.name} contains only whitespace")
                quarantineCorruptFile(file, "blank session file")
                return null
            }
            val jsonObject = JsonParser.parseString(rawJson).asJsonObject
            val parsedSession = gson.fromJson(jsonObject, Session::class.java)
            if (!isPlausibleSession(parsedSession)) {
                Log.w(TAG, "Session file ${file.name} parsed but contains implausible data")
                quarantineCorruptFile(file, "implausible session data")
                return null
            }
            parsedSession.copy(
                processingVersion = if (!jsonObject.has(PROCESSING_VERSION_FIELD) || parsedSession.processingVersion <= 0) {
                    Session.DEFAULT_PROCESSING_VERSION
                } else {
                    parsedSession.processingVersion
                },
                isPartial = if (jsonObject.has(IS_PARTIAL_FIELD)) parsedSession.isPartial else false
            )
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to parse session file ${file.name}", exception)
            quarantineCorruptFile(file, "unreadable session file")
            null
        }
    }

    private fun isPlausibleSession(session: Session?): Boolean {
        if (session == null) {
            return false
        }
        if (session.id <= 0L) {
            return false
        }
        if (session.startTimeEpochMs <= 0L || session.endTimeEpochMs <= 0L) {
            return false
        }
        if (session.endTimeEpochMs < session.startTimeEpochMs) {
            return false
        }
        if (session.endTimestampNs < session.startTimestampNs) {
            return false
        }
        return true
    }

    private fun quarantineCorruptFile(file: File, reason: String) {
        if (!file.exists()) {
            return
        }

        val quarantineName = buildString {
            append(file.nameWithoutExtension)
            append("_")
            append(System.currentTimeMillis())
            append(".")
            append(file.extension.ifBlank { "json" })
        }
        val destination = File(corruptDirectory, quarantineName)

        try {
            if (!file.renameTo(destination)) {
                file.copyTo(destination, overwrite = true)
                if (!file.delete()) {
                    Log.w(TAG, "Failed to delete quarantined source file ${file.name}")
                }
            }
            Log.w(TAG, "Moved ${file.name} to corrupt_sessions because of $reason")
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to quarantine corrupt file ${file.name}", exception)
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete corrupt file ${file.name} after quarantine failure")
            }
        }
    }

    private fun writeAtomically(targetFile: File, contents: String) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tempFile.writeText(contents)
        if (targetFile.exists() && !targetFile.delete()) {
            throw IllegalStateException("Failed to replace existing file ${targetFile.name}")
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            if (!tempFile.delete()) {
                Log.w(TAG, "Failed to delete temp file ${tempFile.name}")
            }
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
        private const val MAX_SESSION_FILE_SIZE_BYTES = 64L * 1024L * 1024L
    }
}
