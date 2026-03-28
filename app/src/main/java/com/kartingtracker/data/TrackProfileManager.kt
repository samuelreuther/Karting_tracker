package com.kartingtracker.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kartingtracker.domain.LapNormalizer
import com.kartingtracker.domain.SectorDetector
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class TrackProfileManager(
    context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val profileDirectory = File(context.filesDir, "track_profiles").apply { mkdirs() }

    fun loadProfile(trackName: String): TrackProfile? {
        val file = File(profileDirectory, buildFileName(trackName))
        if (!file.exists()) {
            return null
        }
        return try {
            gson.fromJson(file.readText(), TrackProfile::class.java)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to parse track profile for $trackName", exception)
            null
        }
    }

    fun saveProfile(profile: TrackProfile) {
        val file = File(profileDirectory, buildFileName(profile.trackName))
        file.writeText(gson.toJson(profile))
    }

    fun updateProfile(trackName: String, sessions: List<Session>): TrackProfile {
        val existingProfile = loadProfile(trackName)
        val totalLapCount = sessions.sumOf { session -> session.laps.size }
        val disturbedLapCount = sessions.sumOf { session -> session.laps.count { lap -> lap.isDisturbed } }
        val disturbedRatio = if (totalLapCount == 0) 0f else disturbedLapCount.toFloat() / totalLapCount.toFloat()

        val qualifyingLapsBySession = sessions.mapNotNull { session ->
            val validLaps = session.laps.filter { lap ->
                !lap.isOutlap && !lap.isDisturbed && lap.confidenceScore >= minimumLapConfidence
            }
            if (validLaps.size < 2) {
                return@mapNotNull null
            }
            val averageLapTimeMs = validLaps.map { lap -> lap.lapTimeMs }.average()
            if (averageLapTimeMs !in 15_000.0..120_000.0) {
                return@mapNotNull null
            }
            validLaps
        }

        if (qualifyingLapsBySession.isEmpty()) {
            return existingProfile ?: emptyProfile(trackName)
        }

        val validLaps = qualifyingLapsBySession.flatten()
        if (validLaps.isEmpty()) {
            return existingProfile ?: emptyProfile(trackName)
        }

        val averageTotalAcceleration = averageSignals(
            validLaps.map { lap ->
                LapNormalizer.normalizeSignal(lap, PROFILE_POINT_COUNT) { sample -> sample.totalAcceleration }
            }
        )
        val averageYawRateAbs = averageSignals(
            validLaps.map { lap ->
                LapNormalizer.normalizeSignal(lap, PROFILE_POINT_COUNT) { sample -> sample.yawRateAbs }
            }
        )
        val detectedSectorBoundaries = computeTypicalSectorBoundaries(validLaps, averageTotalAcceleration, averageYawRateAbs)
        val typicalSectorBoundaries = stabilizeSectorBoundaries(
            existingProfile = existingProfile,
            detectedBoundaries = detectedSectorBoundaries,
            validLapCount = validLaps.size,
            disturbedRatio = disturbedRatio
        )

        val lapTimes = validLaps.map { lap -> lap.lapTimeMs.toDouble() }
        val meanLapTimeMs = lapTimes.average()
        val lapTimeStdDevMs = sqrt(
            lapTimes
                .map { lapTimeMs -> (lapTimeMs - meanLapTimeMs) * (lapTimeMs - meanLapTimeMs) }
                .average()
        )

        val profile = TrackProfile(
            trackName = trackName,
            averageLapTimeMs = meanLapTimeMs.toLong(),
            lapTimeStdDevMs = lapTimeStdDevMs.toLong(),
            averageLapLengthSamples = validLaps.map { lap -> lap.samples.size }.average().toInt(),
            averageTotalAcceleration = averageTotalAcceleration,
            averageYawRateAbs = averageYawRateAbs,
            typicalBrakingZones = detectLocalMinima(averageTotalAcceleration),
            typicalCorneringZones = detectLocalMaxima(averageYawRateAbs),
            typicalSectorBoundaries = typicalSectorBoundaries,
            sessionCount = qualifyingLapsBySession.size
        )

        saveProfile(profile)
        return profile
    }

    private fun averageSignals(signalSeries: List<List<Float>>): List<Float> {
        if (signalSeries.isEmpty()) {
            return emptyList()
        }

        val pointCount = signalSeries.first().size
        return List(pointCount) { index ->
            signalSeries.map { series -> series.getOrElse(index) { 0f } }.average().toFloat()
        }
    }

    private fun detectLocalMinima(values: List<Float>): List<Int> {
        return detectZones(
            values = values,
            comparator = { current, previous, next -> current <= previous && current <= next },
            sortBy = { index -> values[index] }
        )
    }

    private fun detectLocalMaxima(values: List<Float>): List<Int> {
        return detectZones(
            values = values,
            comparator = { current, previous, next -> current >= previous && current >= next },
            sortBy = { index -> -values[index] }
        )
    }

    private fun detectZones(
        values: List<Float>,
        comparator: (Float, Float, Float) -> Boolean,
        sortBy: (Int) -> Float
    ): List<Int> {
        if (values.size < 3) {
            return emptyList()
        }

        val candidates = mutableListOf<Int>()
        for (index in 1 until values.lastIndex) {
            if (comparator(values[index], values[index - 1], values[index + 1])) {
                candidates += index
            }
        }

        val selected = mutableListOf<Int>()
        candidates.sortedBy(sortBy).forEach { index ->
            val tooClose = selected.any { selectedIndex ->
                abs(selectedIndex - index) < minimumZoneSpacing
            }
            if (!tooClose) {
                selected += index
            }
        }
        return selected.sorted().take(maximumStoredZones)
    }

    private fun buildFileName(trackName: String): String {
        return buildString {
            append("track_")
            append(sanitizeTrackName(trackName))
            append(".json")
        }
    }

    private fun sanitizeTrackName(trackName: String): String {
        val trimmed = trackName.trim().ifBlank { "track" }
        return trimmed.replace(Regex("[^A-Za-z0-9_-]+"), "_")
    }

    private fun emptyProfile(trackName: String): TrackProfile {
        return TrackProfile(
            trackName = trackName,
            averageLapTimeMs = 0L,
            lapTimeStdDevMs = 0L,
            averageLapLengthSamples = 0,
            averageTotalAcceleration = emptyList(),
            averageYawRateAbs = emptyList(),
            typicalBrakingZones = emptyList(),
            typicalCorneringZones = emptyList(),
            typicalSectorBoundaries = emptyList(),
            sessionCount = 0
        )
    }

    private fun computeTypicalSectorBoundaries(
        validLaps: List<Lap>,
        averageTotalAcceleration: List<Float>,
        averageYawRateAbs: List<Float>
    ): List<Int> {
        val lapSectorCandidates = validLaps
            .map { lap ->
                lap.sectorBoundaries.takeIf { boundaries -> boundaries.isNotEmpty() }
                    ?: SectorDetector.detectSectors(lap)
            }
            .filter { boundaries -> boundaries.isNotEmpty() }

        if (lapSectorCandidates.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        val targetBoundaryCount = lapSectorCandidates
            .groupingBy { boundaries -> boundaries.size }
            .eachCount()
            .maxByOrNull { entry -> entry.value }
            ?.key
            ?: return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)

        val comparableBoundaries = lapSectorCandidates.filter { boundaries -> boundaries.size == targetBoundaryCount }
        if (comparableBoundaries.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        return List(targetBoundaryCount) { index ->
            comparableBoundaries.map { boundaries -> boundaries[index] }.average().toInt()
        }
    }

    private fun stabilizeSectorBoundaries(
        existingProfile: TrackProfile?,
        detectedBoundaries: List<Int>,
        validLapCount: Int,
        disturbedRatio: Float
    ): List<Int> {
        val existingBoundaries = existingProfile?.typicalSectorBoundaries.orEmpty()
        if (validLapCount < minimumLapsForSectorUpdate || disturbedRatio > maximumDisturbedRatioForSectorUpdate) {
            return existingBoundaries.ifEmpty { detectedBoundaries }
        }
        if (existingBoundaries.isEmpty() || existingBoundaries.size != detectedBoundaries.size) {
            return detectedBoundaries
        }

        return existingBoundaries.mapIndexed { index, oldBoundary ->
            val detectedBoundary = detectedBoundaries[index]
            (((oldBoundary * sectorBoundarySmoothingOldWeight) + (detectedBoundary * sectorBoundarySmoothingNewWeight)).toInt())
                .coerceIn(1, 99)
        }
    }

    companion object {
        private const val TAG = "TrackProfileManager"
        const val PROFILE_POINT_COUNT = 101
        private const val minimumLapConfidence = 0.6f
        private const val minimumZoneSpacing = 6
        private const val maximumStoredZones = 6
        private const val minimumLapsForSectorUpdate = 3
        private const val maximumDisturbedRatioForSectorUpdate = 0.35f
        private const val sectorBoundarySmoothingOldWeight = 0.8f
        private const val sectorBoundarySmoothingNewWeight = 0.2f
    }
}
