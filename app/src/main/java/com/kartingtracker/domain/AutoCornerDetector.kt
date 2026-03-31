package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import kotlin.math.roundToInt

data class DetectedCorner(
    val startPercent: Float,
    val endPercent: Float,
    val peakPercent: Float,
    val strength: Float
)

class AutoCornerDetector {
    fun detectCorners(lap: Lap): List<DetectedCorner> {
        val yawRateSeries = LapNormalizer.normalizeSignal(lap, DETECTION_POINT_COUNT) { sample ->
            sample.yawRateAbs
        }
        if (yawRateSeries.size < 3) {
            return emptyList()
        }

        val activationThreshold = percentile(yawRateSeries, 0.70f).coerceAtLeast(minimumYawThreshold)
        val localPeakIndices = detectLocalPeaks(yawRateSeries, activationThreshold)
        if (localPeakIndices.isEmpty()) {
            return emptyList()
        }

        val clusteredPeaks = clusterPeakIndices(localPeakIndices)
        val detectedCorners = clusteredPeaks.mapNotNull { cluster ->
            cluster.toDetectedCorner(yawRateSeries, activationThreshold)
        }

        return mergeCorners(detectedCorners).sortedBy(DetectedCorner::peakPercent)
    }

    private fun detectLocalPeaks(values: List<Float>, threshold: Float): List<Int> {
        val peaks = mutableListOf<Int>()
        for (index in 1 until values.lastIndex) {
            val currentValue = values[index]
            if (currentValue < threshold) {
                continue
            }
            if (currentValue >= values[index - 1] && currentValue >= values[index + 1]) {
                peaks += index
            }
        }
        return peaks
    }

    private fun clusterPeakIndices(peakIndices: List<Int>): List<List<Int>> {
        if (peakIndices.isEmpty()) {
            return emptyList()
        }

        val clusters = mutableListOf<MutableList<Int>>()
        peakIndices.sorted().forEach { peakIndex ->
            val activeCluster = clusters.lastOrNull()
            if (activeCluster == null || peakIndex - activeCluster.last() > clusterGapIndices) {
                clusters += mutableListOf(peakIndex)
            } else {
                activeCluster += peakIndex
            }
        }
        return clusters
    }

    private fun List<Int>.toDetectedCorner(values: List<Float>, threshold: Float): DetectedCorner? {
        if (isEmpty()) {
            return null
        }

        val peakIndex = maxByOrNull { index -> values[index] } ?: return null
        val startSeed = (first() - cornerPaddingIndices).coerceAtLeast(0)
        val endSeed = (last() + cornerPaddingIndices).coerceAtMost(values.lastIndex)
        val startIndex = expandBoundary(values, startSeed, threshold * boundaryThresholdFactor, step = -1)
        val endIndex = expandBoundary(values, endSeed, threshold * boundaryThresholdFactor, step = 1)
        val width = endIndex - startIndex
        if (width < minimumCornerWidthIndices && values[peakIndex] < (threshold * weakPeakPenaltyFactor)) {
            return null
        }

        val strength = values.subList(startIndex, endIndex + 1).average().toFloat()
        return DetectedCorner(
            startPercent = indexToPercent(startIndex, values.lastIndex),
            endPercent = indexToPercent(endIndex, values.lastIndex),
            peakPercent = indexToPercent(peakIndex, values.lastIndex),
            strength = strength
        )
    }

    private fun mergeCorners(corners: List<DetectedCorner>): List<DetectedCorner> {
        if (corners.isEmpty()) {
            return emptyList()
        }

        val sortedCorners = corners.sortedBy(DetectedCorner::peakPercent)
        val merged = mutableListOf<DetectedCorner>()
        sortedCorners.forEach { candidate ->
            val previous = merged.lastOrNull()
            if (previous != null && candidate.startPercent - previous.endPercent <= mergeGapPercent) {
                val mergedCorner = if (candidate.strength >= previous.strength) {
                    candidate.copy(
                        startPercent = previous.startPercent,
                        endPercent = maxOf(previous.endPercent, candidate.endPercent),
                        strength = ((previous.strength + candidate.strength) * 0.5f)
                    )
                } else {
                    previous.copy(
                        endPercent = maxOf(previous.endPercent, candidate.endPercent),
                        strength = ((previous.strength + candidate.strength) * 0.5f)
                    )
                }
                merged[merged.lastIndex] = mergedCorner
            } else {
                merged += candidate
            }
        }
        return merged
    }

    private fun expandBoundary(
        values: List<Float>,
        seedIndex: Int,
        threshold: Float,
        step: Int
    ): Int {
        var index = seedIndex.coerceIn(0, values.lastIndex)
        while (true) {
            val nextIndex = index + step
            if (nextIndex !in values.indices) {
                return index
            }
            if (values[nextIndex] < threshold) {
                return index
            }
            index = nextIndex
        }
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val sortedValues = values.sorted()
        val targetIndex = (fraction.coerceIn(0f, 1f) * (sortedValues.lastIndex)).roundToInt()
        return sortedValues[targetIndex.coerceIn(0, sortedValues.lastIndex)]
    }

    private fun indexToPercent(index: Int, maxIndex: Int): Float {
        if (maxIndex <= 0) {
            return 0f
        }
        return (index.toFloat() / maxIndex.toFloat()) * 100f
    }

    companion object {
        private const val DETECTION_POINT_COUNT = 101
        private const val minimumYawThreshold = 0.35f
        private const val clusterGapIndices = 5
        private const val cornerPaddingIndices = 2
        private const val minimumCornerWidthIndices = 3
        private const val mergeGapPercent = 4f
        private const val boundaryThresholdFactor = 0.58f
        private const val weakPeakPenaltyFactor = 1.05f
    }
}
