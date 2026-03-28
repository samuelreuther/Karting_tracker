package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import kotlin.math.sqrt

data class LapDetectionResult(
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null
)

class LapDetector {
    private data class ResampledPoint(
        val timestampNs: Long,
        val longitudinalAccel: Float,
        val lateralAccel: Float,
        val yawRate: Float
    )

    fun detect(samples: List<SensorSample>): LapDetectionResult {
        if (samples.size < 50) {
            return fallbackLap(samples)
        }

        val bucketNs = 100_000_000L
        val resampled = resample(samples, bucketNs)
        if (resampled.size < 120) {
            return fallbackLap(samples)
        }

        val windowSize = 60
        val minShift = 150
        val maxShift = minOf(1200, resampled.size / 2)
        if (maxShift <= minShift) {
            return fallbackLap(samples)
        }

        val bestShift = (minShift..maxShift step 5)
            .map { shift -> shift to scoreShift(resampled, shift, windowSize) }
            .maxByOrNull { it.second }
            ?: return fallbackLap(samples)

        if (bestShift.second < 0.78f) {
            return fallbackLap(samples)
        }

        val boundaryTimestampsNs = findBoundaryCandidates(
            points = resampled,
            shift = bestShift.first,
            windowSize = windowSize,
            threshold = maxOf(0.8f, bestShift.second * 0.94f)
        )
        val laps = buildLaps(samples, boundaryTimestampsNs)
        if (laps.isEmpty()) {
            return fallbackLap(samples)
        }

        return LapDetectionResult(
            laps = laps,
            estimatedLapTimeMs = bestShift.first * 100L
        )
    }

    private fun fallbackLap(samples: List<SensorSample>): LapDetectionResult {
        if (samples.isEmpty()) {
            return LapDetectionResult(emptyList(), null)
        }

        val lap = Lap(
            id = 1,
            samples = samples,
            lapTimeMs = ((samples.last().timestampNs - samples.first().timestampNs) / 1_000_000L).coerceAtLeast(0L),
            startTimestampNs = samples.first().timestampNs,
            endTimestampNs = samples.last().timestampNs
        )
        return LapDetectionResult(
            laps = listOf(lap),
            estimatedLapTimeMs = lap.lapTimeMs
        )
    }

    private fun resample(samples: List<SensorSample>, bucketNs: Long): List<ResampledPoint> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<ResampledPoint>()
        var bucketStartNs = samples.first().timestampNs
        var count = 0
        var longitudinalSum = 0f
        var lateralSum = 0f
        var yawSum = 0f

        fun flushBucket() {
            if (count == 0) {
                return
            }
            result += ResampledPoint(
                timestampNs = bucketStartNs,
                longitudinalAccel = longitudinalSum / count,
                lateralAccel = lateralSum / count,
                yawRate = yawSum / count
            )
            count = 0
            longitudinalSum = 0f
            lateralSum = 0f
            yawSum = 0f
        }

        samples.forEach { sample ->
            while (sample.timestampNs - bucketStartNs >= bucketNs) {
                flushBucket()
                bucketStartNs += bucketNs
            }
            count += 1
            longitudinalSum += sample.longitudinalAccel
            lateralSum += sample.lateralAccel
            yawSum += sample.gyroZ
        }
        flushBucket()
        return result
    }

    private fun scoreShift(points: List<ResampledPoint>, shift: Int, windowSize: Int): Float {
        val scores = mutableListOf<Float>()
        var endIndex = shift + windowSize
        while (endIndex < points.size) {
            scores += windowSimilarity(points, endIndex, shift, windowSize)
            endIndex += 10
        }
        if (scores.isEmpty()) {
            return 0f
        }
        return scores.sortedDescending().take(6).average().toFloat()
    }

    private fun findBoundaryCandidates(
        points: List<ResampledPoint>,
        shift: Int,
        windowSize: Int,
        threshold: Float
    ): List<Long> {
        val candidates = mutableListOf<Pair<Int, Float>>()
        var endIndex = shift + windowSize
        while (endIndex < points.size) {
            val similarity = windowSimilarity(points, endIndex, shift, windowSize)
            candidates += endIndex to similarity
            endIndex += 5
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -shift
        for (index in 1 until candidates.lastIndex) {
            val previous = candidates[index - 1]
            val current = candidates[index]
            val next = candidates[index + 1]
            val enoughSpacing = current.first - lastPeakIndex >= (shift * 0.7f).toInt()
            if (
                current.second >= threshold &&
                current.second >= previous.second &&
                current.second >= next.second &&
                enoughSpacing
            ) {
                peaks += current.first
                lastPeakIndex = current.first
            }
        }

        return peaks
            .map { peakIndex -> points[peakIndex].timestampNs }
            .distinct()
            .sorted()
    }

    private fun buildLaps(samples: List<SensorSample>, boundaryTimestampsNs: List<Long>): List<Lap> {
        if (boundaryTimestampsNs.size < 2) {
            return emptyList()
        }

        val laps = mutableListOf<Lap>()
        for (index in 0 until boundaryTimestampsNs.lastIndex) {
            val startNs = boundaryTimestampsNs[index]
            val endNs = boundaryTimestampsNs[index + 1]
            val lapSamples = samples.filter { sample ->
                sample.timestampNs >= startNs && sample.timestampNs < endNs
            }
            if (lapSamples.size < 20) {
                continue
            }
            laps += Lap(
                id = laps.size + 1,
                samples = lapSamples,
                lapTimeMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L),
                startTimestampNs = startNs,
                endTimestampNs = endNs
            )
        }
        return laps
    }

    private fun windowSimilarity(
        points: List<ResampledPoint>,
        endIndex: Int,
        shift: Int,
        windowSize: Int
    ): Float {
        val startA = endIndex - windowSize
        val startB = endIndex - shift - windowSize
        if (startA < 0 || startB < 0) {
            return 0f
        }

        var dot = 0f
        var normA = 0f
        var normB = 0f

        for (offset in 0 until windowSize) {
            val pointA = points[startA + offset]
            val pointB = points[startB + offset]
            val ax = pointA.longitudinalAccel
            val ay = pointA.lateralAccel
            val az = pointA.yawRate * 0.5f
            val bx = pointB.longitudinalAccel
            val by = pointB.lateralAccel
            val bz = pointB.yawRate * 0.5f
            dot += (ax * bx) + (ay * by) + (az * bz)
            normA += (ax * ax) + (ay * ay) + (az * az)
            normB += (bx * bx) + (by * by) + (bz * bz)
        }

        if (normA == 0f || normB == 0f) {
            return 0f
        }

        return dot / (sqrt(normA) * sqrt(normB))
    }
}
