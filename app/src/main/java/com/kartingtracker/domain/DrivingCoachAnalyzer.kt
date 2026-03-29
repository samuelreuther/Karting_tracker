package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.Session
import kotlin.math.abs
import kotlin.math.sqrt

class DrivingCoachAnalyzer {
    fun generateSessionInsights(session: Session): List<String> {
        val candidateLaps = session.laps.filter { lap ->
            lap.isNormalPhase &&
                lap.confidenceScore >= minimumConfidence &&
                lap.samples.size >= minimumSamplesPerLap
        }
        if (candidateLaps.size < 2) {
            return emptyList()
        }

        val orderedLaps = candidateLaps.sortedBy { lap -> lap.lapTimeMs }
        val groupSize = (orderedLaps.size / 3).coerceIn(1, 3)
        val bestLaps = orderedLaps.take(groupSize)
        val slowerLaps = orderedLaps.takeLast(groupSize).filterNot { lap -> bestLaps.any { it.id == lap.id } }
            .ifEmpty { orderedLaps.drop(groupSize).takeLast(groupSize).ifEmpty { orderedLaps.takeLast(groupSize) } }

        if (slowerLaps.isEmpty()) {
            return emptyList()
        }

        val insights = mutableListOf<InsightCandidate>()

        buildEarlierBrakingInsight(bestLaps, slowerLaps)?.let(insights::add)
        buildBrakingConsistencyInsight(candidateLaps, bestLaps, slowerLaps)?.let(insights::add)
        buildCorneringStabilityInsight(bestLaps, slowerLaps)?.let(insights::add)
        buildAccelerationInsight(bestLaps, slowerLaps)?.let(insights::add)
        buildSectorTimeInsight(bestLaps, slowerLaps)?.let(insights::add)

        return insights
            .sortedByDescending { candidate -> candidate.priority }
            .map { candidate -> candidate.message }
            .distinct()
            .take(maximumInsightCount)
    }

    private fun buildEarlierBrakingInsight(bestLaps: List<Lap>, slowerLaps: List<Lap>): InsightCandidate? {
        val zoneCount = minOf(
            bestLaps.minOfOrNull { lap -> lap.brakingPeakIndices.size } ?: 0,
            slowerLaps.minOfOrNull { lap -> lap.brakingPeakIndices.size } ?: 0
        )
        if (zoneCount <= 0) {
            return null
        }

        return (0 until zoneCount).mapNotNull { zoneIndex ->
            val bestPhase = averagePeakPhase(bestLaps, zoneIndex, PeakType.BRAKING)
            val slowPhase = averagePeakPhase(slowerLaps, zoneIndex, PeakType.BRAKING)
            val phaseDelta = slowPhase - bestPhase
            val exitGain = averageExitAcceleration(bestLaps, zoneIndex) - averageExitAcceleration(slowerLaps, zoneIndex)
            val sectorDeltaMs = estimateSectorDelta(bestLaps, slowerLaps, bestPhase)
            if (phaseDelta > minimumEarlierBrakeDelta && exitGain > minimumExitGain && sectorDeltaMs > minimumSectorGainMs) {
                val gainSeconds = sectorDeltaMs / 1000f
                InsightCandidate(
                    priority = 1.0f + gainSeconds,
                    message = "Earlier braking before Turn ${zoneIndex + 1} improved exit speed (+${"%.1f".format(gainSeconds)}s)"
                )
            } else {
                null
            }
        }.maxByOrNull { candidate -> candidate.priority }
    }

    private fun buildBrakingConsistencyInsight(
        candidateLaps: List<Lap>,
        bestLaps: List<Lap>,
        slowerLaps: List<Lap>
    ): InsightCandidate? {
        val zoneCount = candidateLaps.minOfOrNull { lap -> lap.brakingPeakIndices.size } ?: 0
        if (zoneCount <= 0) {
            return null
        }

        return (0 until zoneCount).mapNotNull { zoneIndex ->
            val globalConsistency = peakPhaseStdDev(candidateLaps, zoneIndex, PeakType.BRAKING)
            val bestConsistency = peakPhaseStdDev(bestLaps, zoneIndex, PeakType.BRAKING)
            val slowConsistency = peakPhaseStdDev(slowerLaps, zoneIndex, PeakType.BRAKING)
            val representativePhase = averagePeakPhase(candidateLaps, zoneIndex, PeakType.BRAKING)
            val sectorDeltaMs = estimateSectorDelta(bestLaps, slowerLaps, representativePhase)

            if (globalConsistency > minimumInconsistentBrakeStdDev &&
                slowConsistency - bestConsistency > minimumConsistencyImprovement &&
                sectorDeltaMs > minimumSectorGainMs
            ) {
                val sectorNumber = resolveSectorNumber(candidateLaps.first(), representativePhase)
                InsightCandidate(
                    priority = 0.95f + (sectorDeltaMs / 1000f),
                    message = "Inconsistent braking in Sector $sectorNumber is causing time loss."
                )
            } else {
                null
            }
        }.maxByOrNull { candidate -> candidate.priority }
    }

    private fun buildCorneringStabilityInsight(bestLaps: List<Lap>, slowerLaps: List<Lap>): InsightCandidate? {
        val zoneCount = minOf(
            bestLaps.minOfOrNull { lap -> lap.corneringPeakIndices.size } ?: 0,
            slowerLaps.minOfOrNull { lap -> lap.corneringPeakIndices.size } ?: 0
        )
        if (zoneCount <= 0) {
            return null
        }

        return (0 until zoneCount).mapNotNull { zoneIndex ->
            val stabilityGain = averageCornerStability(slowerLaps, zoneIndex) - averageCornerStability(bestLaps, zoneIndex)
            val peakGain = averagePeakMagnitude(bestLaps, zoneIndex, PeakType.CORNERING) -
                averagePeakMagnitude(slowerLaps, zoneIndex, PeakType.CORNERING)
            if (stabilityGain > minimumCornerStabilityGain && peakGain > minimumCornerPeakGain) {
                InsightCandidate(
                    priority = 0.9f + stabilityGain + peakGain,
                    message = "Higher cornering stability in faster laps carried more speed through Turn ${zoneIndex + 1}."
                )
            } else {
                null
            }
        }.maxByOrNull { candidate -> candidate.priority }
    }

    private fun buildAccelerationInsight(bestLaps: List<Lap>, slowerLaps: List<Lap>): InsightCandidate? {
        val zoneCount = minOf(
            bestLaps.minOfOrNull { lap -> lap.corneringPeakIndices.size } ?: 0,
            slowerLaps.minOfOrNull { lap -> lap.corneringPeakIndices.size } ?: 0
        )
        if (zoneCount <= 0) {
            return null
        }

        return (0 until zoneCount).mapNotNull { zoneIndex ->
            val smoothnessGain = averageAccelerationSmoothness(slowerLaps, zoneIndex) -
                averageAccelerationSmoothness(bestLaps, zoneIndex)
            val accelerationGain = averageExitAcceleration(bestLaps, zoneIndex) -
                averageExitAcceleration(slowerLaps, zoneIndex)
            if (smoothnessGain > minimumSmoothnessGain && accelerationGain > minimumExitGain) {
                InsightCandidate(
                    priority = 0.85f + smoothnessGain + accelerationGain,
                    message = "Smoother acceleration after Turn ${zoneIndex + 1} reduced wheelspin and improved the run onto the next straight."
                )
            } else {
                null
            }
        }.maxByOrNull { candidate -> candidate.priority }
    }

    private fun buildSectorTimeInsight(bestLaps: List<Lap>, slowerLaps: List<Lap>): InsightCandidate? {
        val sectorCount = minOf(
            bestLaps.minOfOrNull { lap -> lap.sectorTimesMs.size } ?: 0,
            slowerLaps.minOfOrNull { lap -> lap.sectorTimesMs.size } ?: 0
        )
        if (sectorCount <= 0) {
            return null
        }

        return (0 until sectorCount).mapNotNull { sectorIndex ->
            val bestAverage = bestLaps.map { lap -> lap.sectorTimesMs[sectorIndex] }.average().toFloat()
            val slowAverage = slowerLaps.map { lap -> lap.sectorTimesMs[sectorIndex] }.average().toFloat()
            val deltaMs = slowAverage - bestAverage
            if (deltaMs > minimumSectorGainMs) {
                InsightCandidate(
                    priority = 0.8f + (deltaMs / 1000f),
                    message = "Sector ${sectorIndex + 1} is the main time-loss area (${formatDelta(deltaMs)} slower than your best laps)."
                )
            } else {
                null
            }
        }.maxByOrNull { candidate -> candidate.priority }
    }

    private fun averagePeakPhase(laps: List<Lap>, zoneIndex: Int, peakType: PeakType): Float {
        val phases = laps.mapNotNull { lap ->
            val peakIndex = peakIndices(lap, peakType).getOrNull(zoneIndex) ?: return@mapNotNull null
            normalizedPhase(lap.samples.size, peakIndex)
        }
        return phases.average().toFloat()
    }

    private fun averagePeakMagnitude(laps: List<Lap>, zoneIndex: Int, peakType: PeakType): Float {
        val values = laps.mapNotNull { lap ->
            val peakIndex = peakIndices(lap, peakType).getOrNull(zoneIndex) ?: return@mapNotNull null
            val sample = lap.samples.getOrNull(peakIndex) ?: return@mapNotNull null
            when (peakType) {
                PeakType.BRAKING -> -sample.totalAcceleration
                PeakType.CORNERING -> sample.yawRateAbs
            }
        }
        return values.average().toFloat()
    }

    private fun averageExitAcceleration(laps: List<Lap>, zoneIndex: Int): Float {
        val values = laps.mapNotNull { lap ->
            val anchorIndex = lap.brakingPeakIndices.getOrNull(zoneIndex)
                ?: lap.corneringPeakIndices.getOrNull(zoneIndex)
                ?: return@mapNotNull null
            windowSamples(lap.samples, anchorIndex + 8, anchorIndex + 30)
                .map { sample -> sample.longitudinalAccel }
                .average()
                .takeIf { it.isFinite() }
                ?.toFloat()
        }
        return values.average().toFloat()
    }

    private fun averageCornerStability(laps: List<Lap>, zoneIndex: Int): Float {
        val values = laps.mapNotNull { lap ->
            val peakIndex = lap.corneringPeakIndices.getOrNull(zoneIndex) ?: return@mapNotNull null
            val yawWindow = windowSamples(lap.samples, peakIndex - 10, peakIndex + 10).map { sample -> sample.yawRateAbs }
            if (yawWindow.size < 3) {
                null
            } else {
                (1f / (standardDeviation(yawWindow) + 0.05f))
            }
        }
        return values.average().toFloat()
    }

    private fun averageAccelerationSmoothness(laps: List<Lap>, zoneIndex: Int): Float {
        val values = laps.mapNotNull { lap ->
            val peakIndex = lap.corneringPeakIndices.getOrNull(zoneIndex)
                ?: lap.brakingPeakIndices.getOrNull(zoneIndex)
                ?: return@mapNotNull null
            val window = windowSamples(lap.samples, peakIndex + 5, peakIndex + 35).map { sample -> sample.longitudinalAccel }
            if (window.size < 3) {
                null
            } else {
                (1f / (averageJerk(window) + 0.05f))
            }
        }
        return values.average().toFloat()
    }

    private fun peakPhaseStdDev(laps: List<Lap>, zoneIndex: Int, peakType: PeakType): Float {
        val phases = laps.mapNotNull { lap ->
            val peakIndex = peakIndices(lap, peakType).getOrNull(zoneIndex) ?: return@mapNotNull null
            normalizedPhase(lap.samples.size, peakIndex)
        }
        return standardDeviation(phases)
    }

    private fun peakIndices(lap: Lap, peakType: PeakType): List<Int> {
        return when (peakType) {
            PeakType.BRAKING -> lap.brakingPeakIndices
            PeakType.CORNERING -> lap.corneringPeakIndices
        }
    }

    private fun estimateSectorDelta(bestLaps: List<Lap>, slowerLaps: List<Lap>, normalizedPhase: Float): Float {
        val referenceLap = bestLaps.firstOrNull() ?: return 0f
        val sectorIndex = resolveSectorIndex(referenceLap, normalizedPhase)
        val comparableBest = bestLaps.filter { lap -> lap.sectorTimesMs.indices.contains(sectorIndex) }
        val comparableSlow = slowerLaps.filter { lap -> lap.sectorTimesMs.indices.contains(sectorIndex) }
        if (comparableBest.isEmpty() || comparableSlow.isEmpty()) {
            return 0f
        }
        val bestAverage = comparableBest.map { lap -> lap.sectorTimesMs[sectorIndex] }.average().toFloat()
        val slowAverage = comparableSlow.map { lap -> lap.sectorTimesMs[sectorIndex] }.average().toFloat()
        return slowAverage - bestAverage
    }

    private fun resolveSectorNumber(lap: Lap, normalizedPhase: Float): Int {
        return resolveSectorIndex(lap, normalizedPhase) + 1
    }

    private fun resolveSectorIndex(lap: Lap, normalizedPhase: Float): Int {
        val boundaries = lap.sectorBoundaries.sorted()
        return boundaries.count { boundary -> normalizedPhase >= boundary / 100f }
    }

    private fun normalizedPhase(sampleCount: Int, peakIndex: Int): Float {
        if (sampleCount <= 1) {
            return 0f
        }
        return peakIndex.toFloat() / (sampleCount - 1).toFloat()
    }

    private fun windowSamples(samples: List<SensorSample>, startInclusive: Int, endInclusive: Int): List<SensorSample> {
        if (samples.isEmpty()) {
            return emptyList()
        }
        val startIndex = startInclusive.coerceIn(0, samples.lastIndex)
        val endIndex = endInclusive.coerceIn(startIndex, samples.lastIndex)
        return samples.subList(startIndex, endIndex + 1)
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val mean = values.average().toFloat()
        val variance = values
            .map { value -> (value - mean) * (value - mean) }
            .average()
        return sqrt(variance).toFloat()
    }

    private fun averageJerk(values: List<Float>): Float {
        if (values.size < 2) {
            return 0f
        }
        val deltas = values.zipWithNext { previous, next -> abs(next - previous) }
        return deltas.average().toFloat()
    }

    private fun formatDelta(deltaMs: Float): String {
        val seconds = deltaMs / 1000f
        return "${"%.1f".format(seconds)}s"
    }

    private data class InsightCandidate(
        val priority: Float,
        val message: String
    )

    private enum class PeakType {
        BRAKING,
        CORNERING
    }

    companion object {
        private const val maximumInsightCount = 4
        private const val minimumConfidence = 0.6f
        private const val minimumSamplesPerLap = 60
        private const val minimumEarlierBrakeDelta = 0.01f
        private const val minimumExitGain = 0.10f
        private const val minimumSectorGainMs = 120f
        private const val minimumInconsistentBrakeStdDev = 0.012f
        private const val minimumConsistencyImprovement = 0.004f
        private const val minimumCornerStabilityGain = 0.12f
        private const val minimumCornerPeakGain = 0.08f
        private const val minimumSmoothnessGain = 0.10f
    }
}
