package com.kartingtracker.domain

import com.kartingtracker.data.CurveDefinition
import com.kartingtracker.data.Lap

class CurveDetector {
    fun detectCurves(
        lap: Lap,
        pointCount: Int = LapNormalizer.DEFAULT_POINT_COUNT
    ): List<CurveDefinition> {
        if (lap.samples.size < 3) {
            return emptyList()
        }

        val normalizedYaw = LapNormalizer.normalizeSignal(lap, pointCount) { sample -> sample.yawRateAbs }
            .smooth(windowRadius = smoothingRadius)
            .normalizeToUnit()
        val normalizedAcceleration = LapNormalizer.normalizeSignal(lap, pointCount) { sample -> sample.totalAcceleration }
            .smooth(windowRadius = smoothingRadius)
            .normalizeToUnit()
        if (normalizedYaw.size < 3 || normalizedAcceleration.size != normalizedYaw.size) {
            return emptyList()
        }

        val candidates = mutableListOf<CurveCandidate>()
        var index = 1
        while (index < normalizedYaw.lastIndex) {
            val current = normalizedYaw[index]
            if (
                current < peakThreshold ||
                current < normalizedYaw[index - 1] ||
                current < normalizedYaw[index + 1]
            ) {
                index += 1
                continue
            }

            var peakIndex = index
            var peakValue = current
            var forwardIndex = index + 1
            while (forwardIndex < normalizedYaw.lastIndex && forwardIndex - index <= minimumPeakDistancePoints) {
                val candidateValue = normalizedYaw[forwardIndex]
                if (
                    candidateValue >= peakThreshold &&
                    candidateValue >= normalizedYaw[forwardIndex - 1] &&
                    candidateValue >= normalizedYaw[forwardIndex + 1] &&
                    candidateValue > peakValue
                ) {
                    peakIndex = forwardIndex
                    peakValue = candidateValue
                }
                forwardIndex += 1
            }

            val boundaryThreshold = peakValue * boundaryThresholdFactor
            var startIndex = peakIndex
            while (startIndex > 0 && normalizedYaw[startIndex] > boundaryThreshold) {
                startIndex -= 1
            }
            var endIndex = peakIndex
            while (endIndex < normalizedYaw.lastIndex && normalizedYaw[endIndex] > boundaryThreshold) {
                endIndex += 1
            }

            val averageAcceleration = normalizedAcceleration
                .subList(startIndex, endIndex.coerceAtLeast(startIndex + 1) + 1)
                .average()
                .toFloat()
            val intensity = (peakValue * averageAcceleration).coerceIn(0f, 1f)
            if (intensity >= minimumIntensityThreshold) {
                candidates += CurveCandidate(
                    startIndex = startIndex,
                    endIndex = endIndex,
                    peakIndex = peakIndex,
                    peakValue = peakValue,
                    intensity = intensity
                )
            }
            index = peakIndex + minimumPeakDistancePoints
        }

        if (candidates.isEmpty()) {
            return emptyList()
        }

        val mergedCandidates = mergeAdjacentCandidates(candidates, normalizedAcceleration)
        val strongestIntensity = mergedCandidates.maxOfOrNull(CurveCandidate::intensity)?.coerceAtLeast(minimumIntensityThreshold)
            ?: minimumIntensityThreshold
        return mergedCandidates
            .filter { candidate -> candidate.intensity >= (strongestIntensity * minimumRelativeIntensity).coerceAtLeast(minimumIntensityThreshold) }
            .mapIndexed { indexInList, candidate ->
                CurveDefinition(
                    index = indexInList + 1,
                    startPercent = candidate.startIndex.toPercent(normalizedYaw.lastIndex),
                    endPercent = candidate.endIndex.toPercent(normalizedYaw.lastIndex),
                    peakPercent = candidate.peakIndex.toPercent(normalizedYaw.lastIndex),
                    intensity = candidate.intensity.coerceIn(0f, 1f)
                )
            }
    }

    private fun mergeAdjacentCandidates(
        candidates: List<CurveCandidate>,
        normalizedAcceleration: List<Float>
    ): List<CurveCandidate> {
        if (candidates.size < 2) {
            return candidates
        }

        val merged = mutableListOf<CurveCandidate>()
        candidates.sortedBy(CurveCandidate::peakIndex).forEach { candidate ->
            val previous = merged.lastOrNull()
            if (previous == null) {
                merged += candidate
                return@forEach
            }

            val isClose = candidate.startIndex - previous.endIndex <= mergeGapPoints ||
                candidate.peakIndex - previous.peakIndex <= mergeGapPoints
            if (!isClose) {
                merged += candidate
                return@forEach
            }

            val peakIndex = if (candidate.peakValue >= previous.peakValue) candidate.peakIndex else previous.peakIndex
            val peakValue = maxOf(candidate.peakValue, previous.peakValue)
            val mergedStart = minOf(previous.startIndex, candidate.startIndex)
            val mergedEnd = maxOf(previous.endIndex, candidate.endIndex)
            val averageAcceleration = normalizedAcceleration
                .subList(mergedStart, mergedEnd.coerceAtLeast(mergedStart + 1) + 1)
                .average()
                .toFloat()
            merged[merged.lastIndex] = CurveCandidate(
                startIndex = mergedStart,
                endIndex = mergedEnd,
                peakIndex = peakIndex,
                peakValue = peakValue,
                intensity = (peakValue * averageAcceleration).coerceIn(0f, 1f)
            )
        }
        return merged
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

    private fun List<Float>.normalizeToUnit(): List<Float> {
        if (isEmpty()) {
            return emptyList()
        }
        val minValue = minOrNull() ?: 0f
        val maxValue = maxOrNull() ?: 0f
        val range = (maxValue - minValue).coerceAtLeast(minimumRange)
        return map { value -> ((value - minValue) / range).coerceIn(0f, 1f) }
    }

    private fun Int.toPercent(lastIndex: Int): Float {
        if (lastIndex <= 0) {
            return 0f
        }
        return (toFloat() / lastIndex.toFloat()) * 100f
    }

    private data class CurveCandidate(
        val startIndex: Int,
        val endIndex: Int,
        val peakIndex: Int,
        val peakValue: Float,
        val intensity: Float
    )

    companion object {
        private const val smoothingRadius = 2
        private const val peakThreshold = 0.6f
        private const val boundaryThresholdFactor = 0.3f
        private const val minimumIntensityThreshold = 0.18f
        private const val minimumRelativeIntensity = 0.45f
        private const val minimumRange = 1e-4f
        private const val minimumPeakDistancePercent = 0.05f
        private const val mergeGapPercent = 0.035f

        private val minimumPeakDistancePoints: Int
            get() = (LapNormalizer.DEFAULT_POINT_COUNT * minimumPeakDistancePercent).toInt().coerceAtLeast(3)

        private val mergeGapPoints: Int
            get() = (LapNormalizer.DEFAULT_POINT_COUNT * mergeGapPercent).toInt().coerceAtLeast(2)
    }
}
