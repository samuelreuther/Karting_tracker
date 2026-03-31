package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.TrackDirection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AutoDetectedStart(
    val startPercent: Float,
    val trackDirection: TrackDirection,
    val confidence: Float
)

class AutoStartDetector {
    fun detectStart(laps: List<Lap>): AutoDetectedStart? {
        val candidateLaps = laps
            .filter { lap -> lap.isNormalPhase && !lap.isDisturbed && lap.confidenceScore >= minimumConfidence }
            .ifEmpty { laps.filter { lap -> lap.samples.size >= minimumSampleCount }.take(maxFallbackLaps) }
        if (candidateLaps.isEmpty()) {
            return null
        }

        val yawSeries = candidateLaps.map { lap ->
            LapNormalizer.normalizeSignal(lap) { sample -> sample.yawRateAbs }.smooth(windowRadius = 2)
        }
        val accelerationSeries = candidateLaps.map { lap ->
            LapNormalizer.normalizeSignal(lap) { sample -> sample.totalAcceleration }.smooth(windowRadius = 2)
        }
        val signedYawSeries = candidateLaps.map { lap ->
            LapNormalizer.normalizeSignal(lap) { sample -> sample.gyroZ }.smooth(windowRadius = 2)
        }
        val pointCount = yawSeries.minOfOrNull(List<Float>::size) ?: return null
        if (pointCount < minimumPointCount) {
            return null
        }

        var bestIndex = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (index in 2 until pointCount - 2) {
            val yawWindow = yawSeries.map { series -> series[index] }
            val accelerationSharpness = accelerationSeries.map { series ->
                abs(series[index + 2] - series[index - 2])
            }.average().toFloat()
            val yawMean = yawWindow.average().toFloat()
            val yawStdDev = standardDeviation(yawWindow)
            val straightScore = (1f - yawMean.normalizeWith(maxYawReference)).coerceIn(0f, 1f)
            val repeatabilityScore = (1f - yawStdDev.normalizeWith(maxYawStdDevReference)).coerceIn(0f, 1f)
            val boundarySharpness = accelerationSharpness.normalizeWith(maxBoundarySharpnessReference)
            val totalScore = (boundarySharpness * 0.5f) + (repeatabilityScore * 0.3f) + (straightScore * 0.2f)
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestIndex = index
            }
        }

        val windowSize = max(3, (pointCount * directionWindowFraction).toInt())
        val signedYawMean = signedYawSeries.map { series ->
            var sum = 0f
            for (offset in 0 until windowSize) {
                sum += series[(bestIndex + offset) % pointCount]
            }
            sum / windowSize.toFloat()
        }.average().toFloat()
        val trackDirection = if (signedYawMean >= 0f) {
            TrackDirection.COUNTER_CLOCKWISE
        } else {
            TrackDirection.CLOCKWISE
        }

        return AutoDetectedStart(
            startPercent = (bestIndex.toFloat() / (pointCount - 1).toFloat()) * 100f,
            trackDirection = trackDirection,
            confidence = bestScore.coerceIn(0f, 1f)
        )
    }

    private fun List<Float>.smooth(windowRadius: Int): List<Float> {
        if (isEmpty() || windowRadius <= 0) {
            return this
        }

        return indices.map { index ->
            val start = (index - windowRadius).coerceAtLeast(0)
            val end = (index + windowRadius).coerceAtMost(lastIndex)
            subList(start, end + 1).average().toFloat()
        }
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

    private fun Float.normalizeWith(maxReference: Float): Float {
        return (this / maxReference).coerceIn(0f, 1f)
    }

    companion object {
        private const val minimumConfidence = 0.65f
        private const val minimumSampleCount = 24
        private const val minimumPointCount = 16
        private const val maxFallbackLaps = 3
        private const val directionWindowFraction = 0.10f
        private const val maxYawReference = 1.6f
        private const val maxYawStdDevReference = 0.8f
        private const val maxBoundarySharpnessReference = 1.2f
    }
}
