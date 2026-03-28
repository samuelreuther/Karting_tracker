package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import kotlin.math.abs

object SectorDetector {
    private const val minimumSpacingPercent = 15
    private const val minimumInternalBoundaries = 1
    private const val maximumInternalBoundaries = 3
    private const val minimumPointCount = 8

    fun detectSectors(lap: Lap): List<Int> {
        val totalAcceleration = LapNormalizer.normalizeSignal(lap) { sample -> sample.totalAcceleration }
        val yawRateAbs = LapNormalizer.normalizeSignal(lap) { sample -> sample.yawRateAbs }
        return detectSectors(totalAcceleration, yawRateAbs)
    }

    fun detectSectors(
        totalAcceleration: List<Float>,
        yawRateAbs: List<Float>
    ): List<Int> {
        val pointCount = minOf(totalAcceleration.size, yawRateAbs.size)
        if (pointCount < minimumPointCount) {
            return emptyList()
        }

        val brakingPeaks = detectLocalMinima(totalAcceleration).map { peak ->
            WeightedPoint(index = peak, score = (totalAcceleration.maxOrNull() ?: 0f) - totalAcceleration[peak])
        }
        val corneringPeaks = detectLocalMaxima(yawRateAbs).map { peak ->
            WeightedPoint(index = peak, score = yawRateAbs[peak])
        }

        val selected = selectStablePoints(brakingPeaks + corneringPeaks, pointCount)
        return selected.sorted()
    }

    fun computeSectorTimes(lap: Lap, sectorBoundaries: List<Int>): List<Long> {
        if (lap.samples.size < 2) {
            return emptyList()
        }

        val internalIndices = sectorBoundaries
            .map { boundary ->
                (((boundary.coerceIn(0, 100) / 100f) * (lap.samples.lastIndex)).toInt()).coerceIn(0, lap.samples.lastIndex)
            }
            .distinct()
            .sorted()
        val fullBoundaries = listOf(0) + internalIndices + listOf(lap.samples.lastIndex)

        return fullBoundaries.zipWithNext { startIndex, endIndex ->
            val startTimestampNs = lap.samples[startIndex].timestampNs
            val endTimestampNs = lap.samples[endIndex].timestampNs
            ((endTimestampNs - startTimestampNs) / 1_000_000L).coerceAtLeast(0L)
        }
    }

    private fun detectLocalMinima(values: List<Float>): List<Int> {
        return findLocalExtrema(values) { current, previous, next ->
            current <= previous && current <= next
        }
    }

    private fun detectLocalMaxima(values: List<Float>): List<Int> {
        return findLocalExtrema(values) { current, previous, next ->
            current >= previous && current >= next
        }
    }

    private fun findLocalExtrema(
        values: List<Float>,
        comparator: (Float, Float, Float) -> Boolean
    ): List<Int> {
        if (values.size < 3) {
            return emptyList()
        }

        val points = mutableListOf<Int>()
        for (index in 1 until values.lastIndex) {
            if (comparator(values[index], values[index - 1], values[index + 1])) {
                points += index
            }
        }
        return points
    }

    private fun selectStablePoints(points: List<WeightedPoint>, pointCount: Int): List<Int> {
        if (points.isEmpty()) {
            return fallbackBoundaries(pointCount)
        }

        val maxIndex = (pointCount - 1).coerceAtLeast(1)
        val selected = mutableListOf<Int>()
        points.sortedByDescending { point -> point.score }.forEach { point ->
            val normalizedPercent = normalizeIndex(point.index, maxIndex)
            val tooClose = selected.any { existing ->
                abs(existing - normalizedPercent) < minimumSpacingPercent
            }
            if (!tooClose) {
                selected += normalizedPercent
            }
        }

        val trimmed = selected.take(maximumInternalBoundaries).sorted()
        if (trimmed.size < minimumInternalBoundaries) {
            return fallbackBoundaries(pointCount)
        }
        return trimmed
    }

    private fun fallbackBoundaries(pointCount: Int): List<Int> {
        return if (pointCount >= minimumPointCount) {
            listOf(50)
        } else {
            emptyList()
        }
    }

    private fun normalizeIndex(index: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) {
            return 0
        }
        return (((index.toFloat() / maxIndex.toFloat()) * 100f).toInt()).coerceIn(1, 99)
    }

    private data class WeightedPoint(
        val index: Int,
        val score: Float
    )
}
