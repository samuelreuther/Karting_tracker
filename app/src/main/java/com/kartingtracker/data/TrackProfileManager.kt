package com.kartingtracker.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kartingtracker.domain.LapNormalizer
import com.kartingtracker.domain.SectorDetector
import com.kartingtracker.domain.SessionQualityEvaluator
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

    fun deleteProfile(trackName: String): Boolean {
        val file = File(profileDirectory, buildFileName(trackName))
        return !file.exists() || file.delete()
    }

    fun updateProfile(trackName: String, sessions: List<Session>): TrackProfile {
        val existingProfile = loadProfile(trackName)
        val updateThresholds = resolveUpdateThresholds(existingProfile?.confidenceScore ?: 0f)
        val contributions = sessions.mapNotNull { session ->
            buildSessionContribution(session, updateThresholds)
        }

        if (contributions.isEmpty()) {
            return existingProfile ?: emptyProfile(trackName)
        }

        val averageSessionQuality = contributions
            .map { contribution -> contribution.quality.overallScore }
            .average()
            .toFloat()
            .coerceIn(0f, 1f)
        val detectedAverageTotalAcceleration = weightedAverageSignals(
            contributions.map { contribution -> contribution.averageTotalAcceleration to contribution.weight }
        )
        val detectedAverageYawRateAbs = weightedAverageSignals(
            contributions.map { contribution -> contribution.averageYawRateAbs to contribution.weight }
        )
        val detectedSectorBoundaries = computeTypicalSectorBoundaries(
            contributions = contributions,
            averageTotalAcceleration = detectedAverageTotalAcceleration,
            averageYawRateAbs = detectedAverageYawRateAbs
        )
        val typicalSectorBoundaries = stabilizeSectorBoundaries(
            existingProfile = existingProfile,
            detectedBoundaries = detectedSectorBoundaries,
            sessionQualityWeight = averageSessionQuality
        )
        val detectedAverageLapTimeMs = weightedAverage(
            contributions.map { contribution -> contribution.averageLapTimeMs to contribution.weight }
        )
        val detectedLapTimeStdDevMs = weightedAverage(
            contributions.map { contribution -> contribution.lapTimeStdDevMs to contribution.weight }
        )
        val detectedAverageLapLengthSamples = weightedAverage(
            contributions.map { contribution -> contribution.averageLapLengthSamples to contribution.weight }
        )

        val profile = TrackProfile(
            trackName = trackName,
            averageLapTimeMs = blendLong(existingProfile?.averageLapTimeMs, detectedAverageLapTimeMs, averageSessionQuality),
            lapTimeStdDevMs = blendLong(existingProfile?.lapTimeStdDevMs, detectedLapTimeStdDevMs, averageSessionQuality),
            averageLapLengthSamples = blendInt(existingProfile?.averageLapLengthSamples, detectedAverageLapLengthSamples, averageSessionQuality),
            averageTotalAcceleration = blendSignalSeries(
                existingProfile?.averageTotalAcceleration.orEmpty(),
                detectedAverageTotalAcceleration,
                averageSessionQuality
            ),
            averageYawRateAbs = blendSignalSeries(
                existingProfile?.averageYawRateAbs.orEmpty(),
                detectedAverageYawRateAbs,
                averageSessionQuality
            ),
            typicalBrakingZones = detectLocalMinima(
                blendSignalSeries(
                    existingProfile?.averageTotalAcceleration.orEmpty(),
                    detectedAverageTotalAcceleration,
                    averageSessionQuality
                )
            ),
            typicalCorneringZones = detectLocalMaxima(
                blendSignalSeries(
                    existingProfile?.averageYawRateAbs.orEmpty(),
                    detectedAverageYawRateAbs,
                    averageSessionQuality
                )
            ),
            typicalSectorBoundaries = typicalSectorBoundaries,
            sessionCount = contributions.size,
            confidenceScore = updateConfidenceScore(existingProfile?.confidenceScore ?: 0f, averageSessionQuality)
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

    private fun weightedAverageSignals(weightedSignals: List<Pair<List<Float>, Float>>): List<Float> {
        val signalSeries = weightedSignals.filter { (series, weight) ->
            series.isNotEmpty() && weight > 0f
        }
        if (signalSeries.isEmpty()) {
            return emptyList()
        }

        val pointCount = signalSeries.first().first.size
        return List(pointCount) { index ->
            val weightedSum = signalSeries.sumOf { (series, weight) ->
                series.getOrElse(index) { 0f }.toDouble() * weight.toDouble()
            }
            val totalWeight = signalSeries.sumOf { (_, weight) -> weight.toDouble() }.coerceAtLeast(1e-6)
            (weightedSum / totalWeight).toFloat()
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
            sessionCount = 0,
            confidenceScore = 0f
        )
    }

    private fun computeTypicalSectorBoundaries(
        contributions: List<SessionContribution>,
        averageTotalAcceleration: List<Float>,
        averageYawRateAbs: List<Float>
    ): List<Int> {
        val lapSectorCandidates = contributions
            .mapNotNull { contribution ->
                contribution.detectedSectorBoundaries
                    .takeIf { boundaries -> boundaries.isNotEmpty() }
                    ?.let { boundaries -> boundaries to contribution.weight }
            }

        if (lapSectorCandidates.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        val targetBoundaryCount = lapSectorCandidates
            .groupBy(keySelector = { (boundaries, _) -> boundaries.size }, valueTransform = { (_, weight) -> weight })
            .maxByOrNull { (_, weights) -> weights.sum() }
            ?.key
            ?: return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)

        val comparableBoundaries = lapSectorCandidates.filter { (boundaries, _) -> boundaries.size == targetBoundaryCount }
        if (comparableBoundaries.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        return List(targetBoundaryCount) { index ->
            val weightedSum = comparableBoundaries.sumOf { (boundaries, weight) ->
                boundaries[index].toDouble() * weight.toDouble()
            }
            val totalWeight = comparableBoundaries.sumOf { (_, weight) -> weight.toDouble() }.coerceAtLeast(1e-6)
            ((weightedSum / totalWeight).toInt()).coerceIn(1, 99)
        }
    }

    private fun stabilizeSectorBoundaries(
        existingProfile: TrackProfile?,
        detectedBoundaries: List<Int>,
        sessionQualityWeight: Float
    ): List<Int> {
        val existingBoundaries = existingProfile?.typicalSectorBoundaries.orEmpty()
        if (detectedBoundaries.isEmpty()) {
            return existingBoundaries.takeIf(::isConsistentBoundaryLayout).orEmpty()
        }
        if (!isConsistentBoundaryLayout(existingBoundaries)) {
            return detectedBoundaries
        }
        if (existingBoundaries.isEmpty()) {
            return detectedBoundaries
        }
        if (existingBoundaries.size != detectedBoundaries.size) {
            return existingBoundaries
        }

        val deviation = existingBoundaries
            .zip(detectedBoundaries)
            .maxOfOrNull { (existing, detected) -> abs(existing - detected) }
            ?: 0
        if (deviation > maximumAcceptedSectorDeviationPercent) {
            return existingBoundaries
        }

        val adjustedWeight = if (deviation > reducedWeightSectorDeviationPercent) {
            sessionQualityWeight * sectorDeviationWeightPenalty
        } else {
            sessionQualityWeight
        }
        val influence = (profileUpdateBaseInfluence * adjustedWeight).coerceIn(0f, profileUpdateBaseInfluence)
        if (influence <= 0f) {
            return existingBoundaries
        }

        return existingBoundaries.mapIndexed { index, oldBoundary ->
            val detectedBoundary = detectedBoundaries[index]
            (((1f - influence) * oldBoundary) + (influence * detectedBoundary)).toInt().coerceIn(1, 99)
        }
    }

    private fun buildSessionContribution(
        session: Session,
        updateThresholds: UpdateThresholds
    ): SessionContribution? {
        val quality = session.quality ?: SessionQualityEvaluator.evaluate(session.laps) ?: return null
        if (quality.overallScore < updateThresholds.minimumOverallScore ||
            quality.validLapRatio < updateThresholds.minimumValidLapRatio
        ) {
            return null
        }

        val candidateLaps = session.laps.filter { lap ->
            SessionQualityEvaluator.isValidLap(lap) &&
                !lap.isInlap &&
                !lap.isInterrupted &&
                hasEnoughPeaks(lap)
        }
        if (candidateLaps.size < minimumValidLapsPerSession) {
            return null
        }

        val filteredLaps = rejectOutlierLaps(candidateLaps)
        if (filteredLaps.size < minimumValidLapsPerSession) {
            return null
        }

        val lapWeights = filteredLaps.map { lap -> lapContributionWeight(lap) }
        val averageLapTimeMs = weightedAverage(
            filteredLaps.mapIndexed { index, lap -> lap.lapTimeMs.toDouble() to lapWeights[index] }
        )
        if (averageLapTimeMs !in minimumLapTimeMs.toDouble()..maximumLapTimeMs.toDouble()) {
            return null
        }

        val averageTotalAcceleration = weightedAverageSignals(
            filteredLaps.mapIndexed { index, lap ->
                LapNormalizer.normalizeSignal(lap, PROFILE_POINT_COUNT) { sample -> sample.totalAcceleration } to lapWeights[index]
            }
        )
        val averageYawRateAbs = weightedAverageSignals(
            filteredLaps.mapIndexed { index, lap ->
                LapNormalizer.normalizeSignal(lap, PROFILE_POINT_COUNT) { sample -> sample.yawRateAbs } to lapWeights[index]
            }
        )
        val averageLapConfidence = filteredLaps.mapIndexed { index, lap -> lap.confidenceScore * lapWeights[index] }
            .sum()
            .div(lapWeights.sum().coerceAtLeast(1e-6f))

        return SessionContribution(
            quality = quality,
            weight = (quality.overallScore * averageLapConfidence).coerceIn(0f, 1f),
            averageTotalAcceleration = averageTotalAcceleration,
            averageYawRateAbs = averageYawRateAbs,
            averageLapTimeMs = averageLapTimeMs,
            lapTimeStdDevMs = computeLapTimeStdDevMs(filteredLaps),
            averageLapLengthSamples = weightedAverage(
                filteredLaps.mapIndexed { index, lap -> lap.samples.size.toDouble() to lapWeights[index] }
            ),
            detectedSectorBoundaries = computeSessionSectorBoundaries(filteredLaps, averageTotalAcceleration, averageYawRateAbs)
        )
    }

    private fun rejectOutlierLaps(laps: List<Lap>): List<Lap> {
        if (laps.size < minimumValidLapsPerSession) {
            return emptyList()
        }

        val lapTimes = laps.map { lap -> lap.lapTimeMs.toDouble() }
        val meanLapTimeMs = lapTimes.average()
        val lapTimeStdDevMs = sqrt(
            lapTimes
                .map { lapTimeMs -> (lapTimeMs - meanLapTimeMs) * (lapTimeMs - meanLapTimeMs) }
                .average()
        )

        return laps.filter { lap ->
            val withinLapTimeWindow = if (lapTimeStdDevMs == 0.0) {
                true
            } else {
                abs(lap.lapTimeMs.toDouble() - meanLapTimeMs) <= (2.0 * lapTimeStdDevMs)
            }
            withinLapTimeWindow &&
                lap.confidenceScore >= minimumOutlierConfidence &&
                !lap.isInlap &&
                !lap.isInterrupted &&
                hasEnoughPeaks(lap)
        }
    }

    private fun computeSessionSectorBoundaries(
        laps: List<Lap>,
        averageTotalAcceleration: List<Float>,
        averageYawRateAbs: List<Float>
    ): List<Int> {
        val detectedBoundaries = laps
            .map { lap ->
                lap.sectorBoundaries.takeIf { boundaries -> boundaries.isNotEmpty() }
                    ?: SectorDetector.detectSectors(lap)
            }
            .filter { boundaries -> boundaries.isNotEmpty() }

        if (detectedBoundaries.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        val targetBoundaryCount = detectedBoundaries
            .groupingBy { boundaries -> boundaries.size }
            .eachCount()
            .maxByOrNull { (_, count) -> count }
            ?.key
            ?: return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)

        val comparableBoundaries = detectedBoundaries.filter { boundaries -> boundaries.size == targetBoundaryCount }
        if (comparableBoundaries.isEmpty()) {
            return SectorDetector.detectSectors(averageTotalAcceleration, averageYawRateAbs)
        }

        return List(targetBoundaryCount) { index ->
            comparableBoundaries.map { boundaries -> boundaries[index] }.average().toInt().coerceIn(1, 99)
        }
    }

    private fun computeLapTimeStdDevMs(laps: List<Lap>): Double {
        val lapTimes = laps.map { lap -> lap.lapTimeMs.toDouble() }
        val meanLapTimeMs = lapTimes.average()
        return sqrt(
            lapTimes
                .map { lapTimeMs -> (lapTimeMs - meanLapTimeMs) * (lapTimeMs - meanLapTimeMs) }
                .average()
        )
    }

    private fun weightedAverage(weightedValues: List<Pair<Double, Float>>): Double {
        val validPairs = weightedValues.filter { (_, weight) -> weight > 0f }
        if (validPairs.isEmpty()) {
            return 0.0
        }

        val weightedSum = validPairs.sumOf { (value, weight) -> value * weight.toDouble() }
        val totalWeight = validPairs.sumOf { (_, weight) -> weight.toDouble() }.coerceAtLeast(1e-6)
        return weightedSum / totalWeight
    }

    private fun blendSignalSeries(
        existingSeries: List<Float>,
        detectedSeries: List<Float>,
        sessionQualityWeight: Float
    ): List<Float> {
        if (detectedSeries.isEmpty()) {
            return existingSeries
        }
        if (existingSeries.isEmpty() || existingSeries.size != detectedSeries.size) {
            return detectedSeries
        }

        val influence = (profileUpdateBaseInfluence * sessionQualityWeight).coerceIn(0f, profileUpdateBaseInfluence)
        return existingSeries.mapIndexed { index, existingValue ->
            ((1f - influence) * existingValue) + (influence * detectedSeries[index])
        }
    }

    private fun blendLong(existingValue: Long?, detectedValue: Double, sessionQualityWeight: Float): Long {
        if (existingValue == null || existingValue <= 0L) {
            return detectedValue.toLong()
        }
        val influence = (profileUpdateBaseInfluence * sessionQualityWeight).coerceIn(0f, profileUpdateBaseInfluence)
        return (((1f - influence) * existingValue.toFloat()) + (influence * detectedValue.toFloat())).toLong()
    }

    private fun blendInt(existingValue: Int?, detectedValue: Double, sessionQualityWeight: Float): Int {
        if (existingValue == null || existingValue <= 0) {
            return detectedValue.toInt()
        }
        val influence = (profileUpdateBaseInfluence * sessionQualityWeight).coerceIn(0f, profileUpdateBaseInfluence)
        return (((1f - influence) * existingValue.toFloat()) + (influence * detectedValue.toFloat())).toInt()
    }

    private fun updateConfidenceScore(currentConfidence: Float, sessionQuality: Float): Float {
        return min(1f, max(0f, currentConfidence) + (profileConfidenceGrowthFactor * sessionQuality))
    }

    private fun hasEnoughPeaks(lap: Lap): Boolean {
        return lap.brakingPeakIndices.size >= minimumPeaksPerType &&
            lap.corneringPeakIndices.size >= minimumPeaksPerType
    }

    private fun lapContributionWeight(lap: Lap): Float {
        val confidence = lap.confidenceScore.coerceIn(0f, 1f)
        return (confidence * confidence).coerceAtLeast(minimumLapContributionWeight)
    }

    private fun resolveUpdateThresholds(profileConfidence: Float): UpdateThresholds {
        return when {
            profileConfidence > matureProfileThreshold -> UpdateThresholds(
                minimumOverallScore = 0.75f,
                minimumValidLapRatio = 0.6f
            )
            else -> UpdateThresholds(
                minimumOverallScore = 0.65f,
                minimumValidLapRatio = 0.55f
            )
        }
    }

    private fun isConsistentBoundaryLayout(boundaries: List<Int>): Boolean {
        if (boundaries.isEmpty()) {
            return false
        }
        val sortedBoundaries = boundaries.sorted()
        if (sortedBoundaries.any { boundary -> boundary !in 1..99 }) {
            return false
        }
        return sortedBoundaries.zipWithNext().all { (previous, next) ->
            next - previous >= minimumBoundarySpacingPercent
        }
    }

    private data class UpdateThresholds(
        val minimumOverallScore: Float,
        val minimumValidLapRatio: Float
    )

    private data class SessionContribution(
        val quality: SessionQuality,
        val weight: Float,
        val averageTotalAcceleration: List<Float>,
        val averageYawRateAbs: List<Float>,
        val averageLapTimeMs: Double,
        val lapTimeStdDevMs: Double,
        val averageLapLengthSamples: Double,
        val detectedSectorBoundaries: List<Int>
    )

    companion object {
        private const val TAG = "TrackProfileManager"
        const val PROFILE_POINT_COUNT = 101
        private const val minimumZoneSpacing = 6
        private const val maximumStoredZones = 6
        private const val minimumLapTimeMs = 15_000L
        private const val maximumLapTimeMs = 120_000L
        private const val minimumValidLapsPerSession = 3
        private const val minimumPeaksPerType = 2
        private const val minimumOutlierConfidence = 0.75f
        private const val minimumLapContributionWeight = 0.10f
        private const val minimumBoundarySpacingPercent = 10
        private const val reducedWeightSectorDeviationPercent = 15
        private const val maximumAcceptedSectorDeviationPercent = 30
        private const val sectorDeviationWeightPenalty = 0.5f
        private const val profileUpdateBaseInfluence = 0.2f
        private const val profileConfidenceGrowthFactor = 0.1f
        private const val matureProfileThreshold = 0.7f
    }
}
