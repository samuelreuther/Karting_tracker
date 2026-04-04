package com.kartingtracker.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SessionStorageManager(
    context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val sessionDirectory = File(context.filesDir, "sessions").apply { mkdirs() }
    private val corruptDirectory = File(context.filesDir, "corrupt_sessions").apply { mkdirs() }

    fun saveSession(session: Session): Boolean {
        return try {
            val file = File(sessionDirectory, buildSessionFileName(session))
            if (session.isPartial) {
                Log.i(TAG, "$LOG_TAG: autosave partial session=${session.id} samples=${session.samples.size}")
            } else {
                Log.i(TAG, "$LOG_TAG: saving final session=${session.id} laps=${session.laps.size}")
            }
            writeAtomically(file, gson.toJson(session))
            if (!file.exists() || !file.canRead() || file.length() <= 0L) {
                throw IllegalStateException("Saved file verification failed for ${file.name}")
            }
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to save session ${session.id}", exception)
            false
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

    fun getSessionFileSize(sessionId: Long): Long {
        return findSessionFile(sessionId)?.length() ?: 0L
    }

    fun deleteSession(sessionId: Long): Boolean {
        val targetFile = findSessionFile(sessionId) ?: return false
        return targetFile.delete()
    }

    fun deleteSessionsForTrack(trackName: String): Int {
        val deletedFiles = listSessionFiles().filter { file ->
            readSessionMetadata(file)?.trackName?.equals(trackName, ignoreCase = true) == true
        }

        var deleteCount = 0
        deletedFiles.forEach { file ->
            if (file.delete()) {
                deleteCount += 1
            }
        }
        return deleteCount
    }

    fun deletePartialSnapshot(trackName: String, startTimeEpochMs: Long): Boolean {
        val partialFile = File(sessionDirectory, buildPartialFileName(trackName, startTimeEpochMs))
        return !partialFile.exists() || partialFile.delete()
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
                insights = parseInsights(jsonObject),
                coachingInsights = parseCoachingInsights(jsonObject),
                theoreticalBestLapTimeMs = parseTheoreticalBestLapTime(jsonObject),
                topTimeLossSegments = parseTopTimeLossSegments(jsonObject),
                segmentMarkers = parseSegmentMarkers(jsonObject),
                cornerCoachingInsights = parseCornerCoachingInsights(jsonObject),
                cornerCoachingSummary = parseCornerCoachingSummary(jsonObject),
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

    private fun parseInsights(jsonObject: com.google.gson.JsonObject): List<String> {
        val rawInsights = jsonObject.getAsJsonArray(INSIGHTS_FIELD) ?: return emptyList()
        return rawInsights.mapNotNull { element ->
            element?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }
    }


    private fun parseCoachingInsights(jsonObject: com.google.gson.JsonObject): List<CoachingInsight> {
        val rawInsights = jsonObject.getAsJsonArray(COACHING_INSIGHTS_FIELD) ?: return emptyList()
        return rawInsights.mapNotNull { element ->
            runCatching { gson.fromJson(element, CoachingInsight::class.java) }.getOrNull()
        }
    }

    private fun parseTheoreticalBestLapTime(jsonObject: com.google.gson.JsonObject): Long? {
        return jsonObject.get(THEORETICAL_BEST_LAP_TIME_FIELD)
            ?.takeIf { element -> !element.isJsonNull }
            ?.asLong
    }

    private fun parseTopTimeLossSegments(jsonObject: com.google.gson.JsonObject): List<TimeLossSegment> {
        val rawSegments = jsonObject.getAsJsonArray(TOP_TIME_LOSS_SEGMENTS_FIELD) ?: return emptyList()
        return rawSegments.mapNotNull { element ->
            runCatching { gson.fromJson(element, TimeLossSegment::class.java) }.getOrNull()
        }
    }

    private fun parseSegmentMarkers(jsonObject: com.google.gson.JsonObject): List<SegmentMarker> {
        val rawMarkers = jsonObject.getAsJsonArray(SEGMENT_MARKERS_FIELD) ?: return emptyList()
        return rawMarkers.mapNotNull { element ->
            runCatching { gson.fromJson(element, SegmentMarker::class.java) }.getOrNull()
        }
    }

    private fun parseCornerCoachingInsights(jsonObject: com.google.gson.JsonObject): List<CornerCoachingInsight> {
        val rawInsights = jsonObject.getAsJsonArray(CORNER_COACHING_INSIGHTS_FIELD) ?: return emptyList()
        return rawInsights.mapNotNull { element ->
            runCatching { gson.fromJson(element, CornerCoachingInsight::class.java) }.getOrNull()
        }
    }

    private fun parseCornerCoachingSummary(jsonObject: com.google.gson.JsonObject): CornerCoachingSummary? {
        val rawSummary = jsonObject.get(CORNER_COACHING_SUMMARY_FIELD) ?: return null
        return runCatching { gson.fromJson(rawSummary, CornerCoachingSummary::class.java) }.getOrNull()
    }

    private fun listSessionFiles(): List<File> {
        return sessionDirectory
            .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            .orEmpty()
            .toList()
    }

    private fun findSessionFile(sessionId: Long): File? {
        return listSessionFiles().firstOrNull { file ->
            readSessionMetadata(file)?.id == sessionId
        }
    }

    private fun readSessionMetadata(file: File): SessionFileMetadata? {
        return try {
            val jsonObject = JsonParser.parseString(file.readText()).asJsonObject
            SessionFileMetadata(
                id = jsonObject.get(ID_FIELD)?.asLong ?: return null,
                trackName = jsonObject.get(TRACK_NAME_FIELD)?.asString ?: return null
            )
        } catch (_: Exception) {
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
        if (contents.isBlank() || contents.toByteArray().size < MIN_PLAUSIBLE_JSON_BYTES) {
            throw IllegalArgumentException("Refusing to write implausibly small session file ${targetFile.name}")
        }
        runCatching { JsonParser.parseString(contents).asJsonObject }.getOrElse { parseException ->
            throw IllegalArgumentException("Session JSON validation failed for ${targetFile.name}", parseException)
        }
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tempFile.outputStream().use { output ->
            output.write(contents.toByteArray())
            output.flush()
            runCatching { output.fd.sync() }
                .onFailure { exception -> Log.w(TAG, "Failed to fsync temp file ${tempFile.name}", exception) }
        }
        if (!tempFile.exists() || tempFile.length() < MIN_PLAUSIBLE_JSON_BYTES) {
            throw IllegalStateException("Temp write is too small for ${targetFile.name}")
        }
        val previousBackup = if (targetFile.exists()) File(targetFile.parentFile, "${targetFile.name}.bak") else null
        if (targetFile.exists()) {
            targetFile.copyTo(previousBackup!!, overwrite = true)
        }
        runCatching {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        if (targetFile.length() < MIN_PLAUSIBLE_JSON_BYTES) {
            previousBackup?.takeIf { it.exists() }?.copyTo(targetFile, overwrite = true)
            throw IllegalStateException("Target file became implausibly small ${targetFile.name}")
        }
        previousBackup?.delete()
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return FileNameNormalizer.normalize(trimmed)
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
        private const val ID_FIELD = "id"
        private const val TRACK_NAME_FIELD = "trackName"
        private const val INSIGHTS_FIELD = "insights"
        private const val COACHING_INSIGHTS_FIELD = "coachingInsights"
        private const val THEORETICAL_BEST_LAP_TIME_FIELD = "theoreticalBestLapTimeMs"
        private const val TOP_TIME_LOSS_SEGMENTS_FIELD = "topTimeLossSegments"
        private const val SEGMENT_MARKERS_FIELD = "segmentMarkers"
        private const val PROCESSING_VERSION_FIELD = "processingVersion"
        private const val CORNER_COACHING_INSIGHTS_FIELD = "cornerCoachingInsights"
        private const val CORNER_COACHING_SUMMARY_FIELD = "cornerCoachingSummary"
        private const val IS_PARTIAL_FIELD = "isPartial"
        private const val JSON_SUFFIX = ".json"
        private const val PARTIAL_SUFFIX = "_partial.json"
        private const val MAX_SESSION_FILE_SIZE_BYTES = 64L * 1024L * 1024L
        private const val MIN_PLAUSIBLE_JSON_BYTES = 32
        private const val LOG_TAG = "KartingTracker"
    }

    private data class SessionFileMetadata(
        val id: Long,
        val trackName: String
    )
}
