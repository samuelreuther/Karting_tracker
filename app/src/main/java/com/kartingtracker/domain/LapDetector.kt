package com.kartingtracker.domain

import android.util.Log
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import kotlin.math.abs
import kotlin.math.sqrt

data class LapDetectionResult(
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null,
    val confidenceScores: List<Float> = emptyList()
)

class LapDetector {
    private data class BoundaryCandidate(
        val endIndex: Int,
        val similarity: Float,
        val eventPresence: Float,
        val durationConsistency: Float = 1f,
        val confidence: Float = 0f
    )

    private data class ResampledPoint(
        val timestampNs: Long,
        val totalAcceleration: Float,
        val yawRateAbs: Float
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
        val laps = filterLapTimeOutliers(
            markFirstLapOutlap(
                buildLaps(samples, boundaryTimestampsNs, resampled)
            )
        )
        if (laps.isEmpty()) {
            Log.i(TAG, "Lap detection fallback: no stable laps for session with ${samples.size} samples")
            return fallbackLap(samples)
        }

        val averageConfidence = if (boundaryTimestampsNs.isEmpty()) 0f else boundaryTimestampsNs.map { it.confidence }.average().toFloat()
        Log.i(
            TAG,
            "Lap detection result: bestShift=${bestShift.first}, correlation=${bestShift.second}, candidates=${boundaryTimestampsNs.size}, laps=${laps.size}, avgConfidence=$averageConfidence"
        )

        return LapDetectionResult(
            laps = laps,
            estimatedLapTimeMs = bestShift.first * 100L,
            confidenceScores = boundaryTimestampsNs.map { candidate -> candidate.confidence }
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
            endTimestampNs = samples.last().timestampNs,
            confidenceScore = 0.3f
        )
        return LapDetectionResult(
            laps = listOf(lap),
            estimatedLapTimeMs = lap.lapTimeMs,
            confidenceScores = listOf(0.3f)
        )
    }

    private fun resample(samples: List<SensorSample>, bucketNs: Long): List<ResampledPoint> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val result = mutableListOf<ResampledPoint>()
        var bucketStartNs = samples.first().timestampNs
        var count = 0
        var totalAccelerationSum = 0f
        var yawRateAbsSum = 0f

        fun flushBucket() {
            if (count == 0) {
                return
            }
            result += ResampledPoint(
                timestampNs = bucketStartNs,
                totalAcceleration = totalAccelerationSum / count,
                yawRateAbs = yawRateAbsSum / count
            )
            count = 0
            totalAccelerationSum = 0f
            yawRateAbsSum = 0f
        }

        samples.forEach { sample ->
            while (sample.timestampNs - bucketStartNs >= bucketNs) {
                flushBucket()
                bucketStartNs += bucketNs
            }
            count += 1
            totalAccelerationSum += sample.totalAcceleration
            yawRateAbsSum += sample.yawRateAbs
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
    ): List<BoundaryCandidate> {
        val candidates = mutableListOf<BoundaryCandidate>()
        var endIndex = shift + windowSize
        while (endIndex < points.size) {
            val similarity = windowSimilarity(points, endIndex, shift, windowSize)
            val startIndex = (endIndex - windowSize).coerceAtLeast(0)
            val brakingPeaks = detectBrakingEvents(points, startIndex, endIndex)
            val corneringEvents = detectCorneringEvents(points, startIndex, endIndex)
            val eventPresence = if (brakingPeaks.isNotEmpty() && corneringEvents.isNotEmpty()) 1f else 0f
            candidates += BoundaryCandidate(
                endIndex = endIndex,
                similarity = similarity,
                eventPresence = eventPresence
            )
            endIndex += 5
        }

        val localMaxima = mutableListOf<BoundaryCandidate>()
        for (index in 1 until candidates.lastIndex) {
            val previous = candidates[index - 1]
            val current = candidates[index]
            val next = candidates[index + 1]
            if (
                current.eventPresence > 0f &&
                current.similarity >= threshold &&
                current.similarity >= previous.similarity &&
                current.similarity >= next.similarity
            ) {
                localMaxima += current
            }
        }

        val uniqueCandidates = filterDuplicateCandidates(localMaxima, (shift * 0.7f).toInt())
        return applyConfidence(uniqueCandidates, shift)
            .filter { candidate -> candidate.confidence >= 0.55f }
    }

    private fun buildLaps(
        samples: List<SensorSample>,
        boundaryCandidates: List<BoundaryCandidate>,
        points: List<ResampledPoint>
    ): List<Lap> {
        if (boundaryCandidates.size < 2) {
            return emptyList()
        }

        val laps = mutableListOf<Lap>()
        for (index in 0 until boundaryCandidates.lastIndex) {
            val startNs = points[boundaryCandidates[index].endIndex].timestampNs
            val endNs = points[boundaryCandidates[index + 1].endIndex].timestampNs
            val lapTimeMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L)
            if (lapTimeMs !in 15_000L..120_000L) {
                continue
            }
            val lapSamples = samples.filter { sample ->
                sample.timestampNs >= startNs && sample.timestampNs < endNs
            }
            if (lapSamples.size < 20) {
                continue
            }
            laps += Lap(
                id = laps.size + 1,
                samples = lapSamples,
                lapTimeMs = lapTimeMs,
                startTimestampNs = startNs,
                endTimestampNs = endNs,
                confidenceScore = boundaryCandidates[index + 1].confidence
            )
        }
        return laps
    }

    private fun markFirstLapOutlap(laps: List<Lap>): List<Lap> {
        if (laps.size < 2) {
            return laps
        }

        val firstLap = laps.first()
        val remainingLaps = laps.drop(1)
        if (remainingLaps.isEmpty()) {
            return laps
        }

        val averageRemainingLapTimeMs = remainingLaps.map { lap -> lap.lapTimeMs }.average()
        val durationDeviation = if (averageRemainingLapTimeMs == 0.0) {
            0.0
        } else {
            abs(firstLap.lapTimeMs - averageRemainingLapTimeMs) / averageRemainingLapTimeMs
        }
        val isOutlap = durationDeviation > 0.2 || firstLap.confidenceScore < 0.6f
        if (!isOutlap) {
            return laps
        }

        Log.i(
            TAG,
            "Marked first lap as outlap: lapTime=${firstLap.lapTimeMs}, confidence=${firstLap.confidenceScore}, deviation=$durationDeviation"
        )

        return listOf(firstLap.copy(isOutlap = true)) + remainingLaps
    }

    private fun filterLapTimeOutliers(laps: List<Lap>): List<Lap> {
        if (laps.isEmpty()) {
            return emptyList()
        }

        val referenceLaps = laps.filterNot { lap -> lap.isOutlap }
        if (referenceLaps.isEmpty()) {
            return laps
        }

        val averageLapTimeMs = referenceLaps.map { lap -> lap.lapTimeMs }.average()
        if (averageLapTimeMs == 0.0) {
            return laps
        }

        val filteredLaps = laps.filter { lap ->
            lap.isOutlap || abs(lap.lapTimeMs - averageLapTimeMs) / averageLapTimeMs <= 0.3
        }

        if (filteredLaps.size != laps.size) {
            Log.i(
                TAG,
                "Filtered ${laps.size - filteredLaps.size} unstable laps after outlap classification"
            )
        }

        return filteredLaps.mapIndexed { index, lap ->
            lap.copy(id = index + 1)
        }
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
            val ax = pointA.totalAcceleration
            val ay = pointA.yawRateAbs * 0.7f
            val bx = pointB.totalAcceleration
            val by = pointB.yawRateAbs * 0.7f
            dot += (ax * bx) + (ay * by)
            normA += (ax * ax) + (ay * ay)
            normB += (bx * bx) + (by * by)
        }

        if (normA == 0f || normB == 0f) {
            return 0f
        }

        return dot / (sqrt(normA) * sqrt(normB))
    }

    private fun detectBrakingEvents(
        points: List<ResampledPoint>,
        startIndex: Int,
        endIndex: Int
    ): List<Int> {
        val peaks = mutableListOf<Int>()
        val safeStart = maxOf(1, startIndex + 1)
        val safeEnd = minOf(points.lastIndex - 1, endIndex - 1)
        if (safeStart > safeEnd) {
            return emptyList()
        }
        for (index in safeStart..safeEnd) {
            val previous = points[index - 1].totalAcceleration
            val current = points[index].totalAcceleration
            val next = points[index + 1].totalAcceleration
            if (
                (previous - current) > 0.6f &&
                current < previous &&
                current <= next
            ) {
                peaks += index
            }
        }
        return peaks
    }

    private fun detectCorneringEvents(
        points: List<ResampledPoint>,
        startIndex: Int,
        endIndex: Int
    ): List<Int> {
        val events = mutableListOf<Int>()
        val safeStart = startIndex.coerceAtLeast(0)
        val safeEnd = endIndex.coerceAtMost(points.lastIndex)
        if (safeStart > safeEnd) {
            return emptyList()
        }
        for (index in safeStart..safeEnd) {
            val sustainedAcceleration = averageTotalAcceleration(points, index)
            if (points[index].yawRateAbs > 0.35f && sustainedAcceleration > 1.4f) {
                events += index
            }
        }
        return events
    }

    private fun filterDuplicateCandidates(
        candidates: List<BoundaryCandidate>,
        minimumSpacing: Int
    ): List<BoundaryCandidate> {
        val selected = mutableListOf<BoundaryCandidate>()
        candidates.sortedByDescending { candidate -> candidate.similarity }.forEach { candidate ->
            val duplicate = selected.any { selectedCandidate ->
                abs(selectedCandidate.endIndex - candidate.endIndex) < minimumSpacing
            }
            if (!duplicate) {
                selected += candidate
            }
        }
        return selected.sortedBy { candidate -> candidate.endIndex }
    }

    private fun applyConfidence(
        candidates: List<BoundaryCandidate>,
        expectedShift: Int
    ): List<BoundaryCandidate> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        val sortedCandidates = candidates.sortedBy { candidate -> candidate.endIndex }
        return sortedCandidates.mapIndexed { index, candidate ->
            val durationConsistency = if (index == 0) {
                1f
            } else {
                val gap = candidate.endIndex - sortedCandidates[index - 1].endIndex
                (1f - (abs(gap - expectedShift).toFloat() / expectedShift.toFloat())).coerceIn(0f, 1f)
            }
            val confidence = candidate.similarity * candidate.eventPresence * durationConsistency
            candidate.copy(durationConsistency = durationConsistency, confidence = confidence)
        }
    }

    private fun averageTotalAcceleration(points: List<ResampledPoint>, index: Int): Float {
        val from = maxOf(0, index - 1)
        val to = minOf(points.lastIndex, index + 1)
        var sum = 0f
        var count = 0
        for (sampleIndex in from..to) {
            sum += points[sampleIndex].totalAcceleration
            count += 1
        }
        return if (count == 0) 0f else sum / count
    }

    companion object {
        private const val TAG = "LapDetector"
    }
}
