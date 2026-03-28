package com.kartingtracker.domain

import android.util.Log
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.TrackProfile
import kotlin.math.abs
import kotlin.math.roundToInt
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
        val profileSimilarity: Float = 0f,
        val eventPresence: Float,
        val zoneBoost: Float = 1f,
        val durationConsistency: Float = 1f,
        val confidence: Float = 0f
    )

    private data class ResampledPoint(
        val timestampNs: Long,
        val totalAcceleration: Float,
        val yawRateAbs: Float
    )

    private data class ShiftSearchConfig(
        val minShift: Int,
        val maxShift: Int,
        val profileWeight: Float
    )

    fun detect(samples: List<SensorSample>, trackProfile: TrackProfile? = null): LapDetectionResult {
        if (samples.size < 50) {
            return fallbackLap(samples)
        }

        val usableTrackProfile = trackProfile?.takeIf { profile ->
            profile.averageLapTimeMs in 15_000L..120_000L &&
                profile.averageTotalAcceleration.isNotEmpty() &&
                profile.averageYawRateAbs.isNotEmpty()
        }
        val bucketNs = 100_000_000L
        val resampled = resample(samples, bucketNs)
        val searchConfig = buildShiftSearchConfig(resampled.size, usableTrackProfile)
            ?: return fallbackLap(samples)
        if (usableTrackProfile == null && resampled.size < 120) {
            return fallbackLap(samples)
        }

        val windowSize = 60
        val bestShift = (searchConfig.minShift..searchConfig.maxShift step 5)
            .map { shift ->
                shift to scoreShift(
                    points = resampled,
                    shift = shift,
                    windowSize = windowSize,
                    trackProfile = usableTrackProfile,
                    profileWeight = searchConfig.profileWeight
                )
            }
            .maxByOrNull { it.second }
            ?: return fallbackLap(samples)

        val minimumScoreThreshold = if (usableTrackProfile == null) 0.78f else 0.68f
        if (bestShift.second < minimumScoreThreshold) {
            return fallbackLap(samples)
        }

        val boundaryTimestampsNs = findBoundaryCandidates(
            points = resampled,
            shift = bestShift.first,
            windowSize = windowSize,
            threshold = maxOf(if (usableTrackProfile == null) 0.8f else 0.7f, bestShift.second * 0.92f),
            trackProfile = usableTrackProfile,
            profileWeight = searchConfig.profileWeight
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
            "Lap detection result: bestShift=${bestShift.first}, score=${bestShift.second}, candidates=${boundaryTimestampsNs.size}, laps=${laps.size}, avgConfidence=$averageConfidence, usingProfile=${usableTrackProfile != null}, profileSessions=${usableTrackProfile?.sessionCount ?: 0}"
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

    private fun buildShiftSearchConfig(
        pointCount: Int,
        trackProfile: TrackProfile?
    ): ShiftSearchConfig? {
        if (trackProfile == null) {
            val minShift = 150
            val maxShift = minOf(1200, pointCount / 2)
            return if (maxShift > minShift) {
                ShiftSearchConfig(minShift = minShift, maxShift = maxShift, profileWeight = 0f)
            } else {
                null
            }
        }

        val expectedShift = (trackProfile.averageLapTimeMs / 100L).toInt().coerceAtLeast(120)
        val minShift = (expectedShift * 0.7f).toInt().coerceAtLeast(100)
        val maxShift = (expectedShift * 1.3f).toInt().coerceAtMost((pointCount - 61).coerceAtLeast(minShift))
        if (maxShift <= minShift) {
            return null
        }
        val profileWeight = if (trackProfile.sessionCount < 2) 0.2f else 0.4f
        return ShiftSearchConfig(minShift = minShift, maxShift = maxShift, profileWeight = profileWeight)
    }

    private fun scoreShift(
        points: List<ResampledPoint>,
        shift: Int,
        windowSize: Int,
        trackProfile: TrackProfile?,
        profileWeight: Float
    ): Float {
        val scores = mutableListOf<Float>()
        var endIndex = shift + windowSize
        while (endIndex < points.size) {
            scores += combinedWindowScore(
                points = points,
                endIndex = endIndex,
                shift = shift,
                windowSize = windowSize,
                trackProfile = trackProfile,
                profileWeight = profileWeight
            )
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
        threshold: Float,
        trackProfile: TrackProfile?,
        profileWeight: Float
    ): List<BoundaryCandidate> {
        val candidates = mutableListOf<BoundaryCandidate>()
        var endIndex = shift + windowSize
        while (endIndex < points.size) {
            val similarity = combinedWindowScore(points, endIndex, shift, windowSize, trackProfile, profileWeight)
            val profileSimilarity = profileSimilarity(points, endIndex, shift, trackProfile)
            val segmentStartIndex = (endIndex - shift).coerceAtLeast(0)
            val brakingPeaks = detectBrakingEvents(points, segmentStartIndex, endIndex)
            val corneringEvents = detectCorneringEvents(points, segmentStartIndex, endIndex)
            val eventPresence = if (brakingPeaks.isNotEmpty() && corneringEvents.isNotEmpty()) 1f else 0f
            val zoneBoost = zoneAlignmentBoost(segmentStartIndex, endIndex, brakingPeaks, corneringEvents, trackProfile)
            candidates += BoundaryCandidate(
                endIndex = endIndex,
                similarity = similarity,
                profileSimilarity = profileSimilarity,
                eventPresence = eventPresence,
                zoneBoost = zoneBoost
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

    private fun combinedWindowScore(
        points: List<ResampledPoint>,
        endIndex: Int,
        shift: Int,
        windowSize: Int,
        trackProfile: TrackProfile?,
        profileWeight: Float
    ): Float {
        val historicalSimilarity = windowSimilarity(points, endIndex, shift, windowSize)
        if (trackProfile == null || profileWeight <= 0f) {
            return historicalSimilarity
        }
        val trackSimilarity = profileSimilarity(points, endIndex, shift, trackProfile)
        return ((1f - profileWeight) * historicalSimilarity) + (profileWeight * trackSimilarity)
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

    private fun profileSimilarity(
        points: List<ResampledPoint>,
        endIndex: Int,
        shift: Int,
        trackProfile: TrackProfile?
    ): Float {
        if (trackProfile == null) {
            return 0f
        }

        val pointCount = minOf(trackProfile.averageTotalAcceleration.size, trackProfile.averageYawRateAbs.size)
        if (pointCount < 8) {
            return 0f
        }

        val segmentStartIndex = (endIndex - shift).coerceAtLeast(0)
        val normalizedTotalAcceleration = normalizeSegment(points, segmentStartIndex, endIndex, pointCount) { point ->
            point.totalAcceleration
        }
        val normalizedYawRate = normalizeSegment(points, segmentStartIndex, endIndex, pointCount) { point ->
            point.yawRateAbs
        }
        val totalSimilarity = cosineSimilarity(normalizedTotalAcceleration, trackProfile.averageTotalAcceleration)
        val yawSimilarity = cosineSimilarity(normalizedYawRate, trackProfile.averageYawRateAbs)
        return ((0.6f * totalSimilarity) + (0.4f * yawSimilarity)).coerceIn(0f, 1f)
    }

    private fun normalizeSegment(
        points: List<ResampledPoint>,
        startIndex: Int,
        endIndex: Int,
        pointCount: Int,
        selector: (ResampledPoint) -> Float
    ): List<Float> {
        val safeStart = startIndex.coerceAtLeast(0)
        val safeEnd = endIndex.coerceAtMost(points.lastIndex)
        if (safeStart >= safeEnd) {
            return List(pointCount) { selector(points[safeStart]) }
        }

        val segmentLength = (safeEnd - safeStart).toFloat().coerceAtLeast(1f)
        return List(pointCount) { index ->
            val progress = index.toFloat() / (pointCount - 1).coerceAtLeast(1)
            val rawPosition = safeStart + (segmentLength * progress)
            val lowerIndex = rawPosition.toInt().coerceIn(safeStart, safeEnd)
            val upperIndex = (lowerIndex + 1).coerceAtMost(safeEnd)
            if (lowerIndex == upperIndex) {
                selector(points[lowerIndex])
            } else {
                val localProgress = rawPosition - lowerIndex
                val lowerValue = selector(points[lowerIndex])
                val upperValue = selector(points[upperIndex])
                lowerValue + ((upperValue - lowerValue) * localProgress)
            }
        }
    }

    private fun cosineSimilarity(first: List<Float>, second: List<Float>): Float {
        if (first.isEmpty() || second.isEmpty()) {
            return 0f
        }

        val size = minOf(first.size, second.size)
        var dot = 0f
        var firstNorm = 0f
        var secondNorm = 0f
        for (index in 0 until size) {
            val a = first[index]
            val b = second[index]
            dot += a * b
            firstNorm += a * a
            secondNorm += b * b
        }
        if (firstNorm == 0f || secondNorm == 0f) {
            return 0f
        }
        return dot / (sqrt(firstNorm) * sqrt(secondNorm))
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

    private fun zoneAlignmentBoost(
        segmentStartIndex: Int,
        segmentEndIndex: Int,
        brakingEvents: List<Int>,
        corneringEvents: List<Int>,
        trackProfile: TrackProfile?
    ): Float {
        if (trackProfile == null) {
            return 1f
        }

        val segmentLength = (segmentEndIndex - segmentStartIndex).coerceAtLeast(1)
        val brakingAligned = brakingEvents.any { eventIndex ->
            val eventPosition = (((eventIndex - segmentStartIndex).toFloat() / segmentLength.toFloat()) * 100f).roundToInt()
            trackProfile.typicalBrakingZones.any { zone -> abs(zone - eventPosition) <= zoneTolerance }
        }
        val corneringAligned = corneringEvents.any { eventIndex ->
            val eventPosition = (((eventIndex - segmentStartIndex).toFloat() / segmentLength.toFloat()) * 100f).roundToInt()
            trackProfile.typicalCorneringZones.any { zone -> abs(zone - eventPosition) <= zoneTolerance }
        }

        var boost = 1f
        if (brakingAligned) {
            boost += 0.08f
        }
        if (corneringAligned) {
            boost += 0.08f
        }
        return boost
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
            val confidence = (candidate.similarity * candidate.eventPresence * durationConsistency * candidate.zoneBoost)
                .coerceIn(0f, 1.2f)
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
        private const val zoneTolerance = 8
    }
}
