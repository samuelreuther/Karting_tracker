package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.SessionQuality
import kotlin.math.sqrt

object SessionQualityEvaluator {
    fun evaluate(laps: List<Lap>): SessionQuality? {
        if (laps.isEmpty()) {
            return null
        }

        val validLapRatio = laps.count(::isValidLap).toFloat() / laps.size.toFloat()
        val avgConfidence = laps.map { lap -> lap.confidenceScore }.average().toFloat().coerceIn(0f, 1f)
        val disturbedLapRatio = laps.count { lap -> lap.isDisturbed }.toFloat() / laps.size.toFloat()
        val normalizedVariance = computeNormalizedVariance(laps)

        val overallScore = (
            (0.35f * validLapRatio) +
                (0.25f * avgConfidence) +
                (0.20f * (1f - disturbedLapRatio)) +
                (0.20f * (1f - normalizedVariance))
            ).coerceIn(0f, 1f)

        return SessionQuality(
            overallScore = overallScore,
            validLapRatio = validLapRatio.coerceIn(0f, 1f),
            avgConfidence = avgConfidence,
            disturbedLapRatio = disturbedLapRatio.coerceIn(0f, 1f),
            lapTimeVariance = normalizedVariance
        )
    }

    fun isValidLap(lap: Lap): Boolean {
        return !lap.isOutlap && !lap.isDisturbed && lap.confidenceScore >= minimumValidConfidence
    }

    private fun computeNormalizedVariance(laps: List<Lap>): Float {
        val lapTimes = laps.map { lap -> lap.lapTimeMs.toDouble() }
        val averageLapTime = lapTimes.average()
        if (averageLapTime <= 0.0 || averageLapTime.isNaN()) {
            return 1f
        }

        val variance = lapTimes
            .map { lapTime -> (lapTime - averageLapTime) * (lapTime - averageLapTime) }
            .average()
        val standardDeviation = sqrt(variance)
        return (standardDeviation / averageLapTime).toFloat().coerceIn(0f, 1f)
    }

    private const val minimumValidConfidence = 0.6f
}
