package com.kartingtracker.domain

import android.util.Log
import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.TrackProfile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class LapDetector2(
    private val boundaryGenerator: BoundaryGenerator = BoundaryGenerator(),
    private val globalSegmenter: GlobalSegmenter = GlobalSegmenter()
) {
    fun detect(samples: List<SensorSample>, trackProfile: TrackProfile? = null): LapDetectionResult {
        if (samples.size < minimumSampleCount) {
            return fallbackLap(samples)
        }

        val frames = resample(samples, bucketNs)
        if (frames.size < minimumFrameCount) {
            return fallbackLap(samples)
        }

        val prior = estimateLapPrior(frames, trackProfile) ?: return fallbackLap(samples)
        val boundaryResult = boundaryGenerator.generate(frames, prior, trackProfile)
        val segmentationPath = globalSegmenter.segment(
            frames = frames,
            boundaryResult = boundaryResult,
            prior = prior,
            trackProfile = trackProfile
        ) ?: return fallbackLap(samples)

        val laps = materializeLaps(
            samples = samples,
            frames = frames,
            path = segmentationPath,
            trackProfile = trackProfile,
            prior = prior,
            boundaryScores = boundaryResult.boundaryScores
        )

        if (!isStableSolution(laps, segmentationPath.totalScore, prior)) {
            Log.i(TAG, "LapDetector2 fallback: unstable global solution, segments=${segmentationPath.segments.size}")
            return fallbackLap(samples)
        }

        Log.i(
            TAG,
            "LapDetector2 result: segments=${segmentationPath.segments.size}, laps=${laps.size}, expectedLap=${prior.expectedLapMs}, usingProfile=${trackProfile != null}"
        )

        return LapDetectionResult(
            laps = laps,
            estimatedLapTimeMs = prior.expectedLapMs,
            confidenceScores = laps.map { lap -> lap.confidenceScore }
        )
    }

    private fun materializeLaps(
        samples: List<SensorSample>,
        frames: List<ResampledFrame>,
        path: SegmentationPath,
        trackProfile: TrackProfile?,
        prior: LapPrior,
        boundaryScores: FloatArray
    ): List<Lap> {
        val laps = mutableListOf<Lap>()
        var previousSegment: ChosenSegment? = null

        path.segments.forEachIndexed { index, chosenSegment ->
            val startFrameIndex = chosenSegment.segment.startFrameIndex.coerceIn(0, frames.lastIndex)
            val endFrameIndex = chosenSegment.segment.endFrameIndex.coerceIn(startFrameIndex, frames.lastIndex)
            if (startFrameIndex >= endFrameIndex) {
                return@forEachIndexed
            }

            val startNs = frames[startFrameIndex].timestampNs
            val endNs = frames[endFrameIndex].timestampNs
            val isLastSegment = index == path.segments.lastIndex
            val lapSamples = samples.filter { sample ->
                sample.timestampNs >= startNs && if (isLastSegment) sample.timestampNs <= endNs else sample.timestampNs < endNs
            }
            if (lapSamples.size < minimumLapSampleCount) {
                return@forEachIndexed
            }

            val confidence = computeLapConfidence(
                current = chosenSegment,
                previous = previousSegment,
                prior = prior,
                trackProfile = trackProfile,
                boundaryScores = boundaryScores
            )

            laps += Lap(
                id = index + 1,
                samples = lapSamples,
                lapTimeMs = ((endNs - startNs) / 1_000_000L).coerceAtLeast(0L),
                startTimestampNs = startNs,
                endTimestampNs = endNs,
                confidenceScore = confidence,
                lapPhase = chosenSegment.phase,
                isOutlap = chosenSegment.phase == LapPhase.OUTLAP,
                isDisturbed = chosenSegment.phase == LapPhase.INTERRUPTED
            )

            previousSegment = chosenSegment
        }

        return laps
    }

    private fun computeLapConfidence(
        current: ChosenSegment,
        previous: ChosenSegment?,
        prior: LapPrior,
        trackProfile: TrackProfile?,
        boundaryScores: FloatArray
    ): Float {
        val epsilon = 1e-4f
        val weightedScores = mutableListOf<Pair<Float, Float>>()

        weightedScores += current.segment.features.durationScore to 0.30f

        previous?.takeUnless { it.phase == LapPhase.INTERRUPTED || current.phase == LapPhase.INTERRUPTED }?.let { previousSegment ->
            val similarityScore = similarityScore(previousSegment.segment.features, current.segment.features)
            weightedScores += similarityScore to 0.25f
        }

        current.segment.features.templateMatchScore?.let { templateMatchScore ->
            weightedScores += templateMatchScore to 0.20f
        }

        weightedScores += current.segment.features.eventScore to 0.15f

        val boundarySharpnessScore = (
            boundaryScores.getOrElse(current.segment.startFrameIndex) { current.segment.features.boundarySharpnessScore } +
                boundaryScores.getOrElse(current.segment.endFrameIndex) { current.segment.features.boundarySharpnessScore }
            ) / 2f
        weightedScores += boundarySharpnessScore.coerceIn(0f, 1f) to 0.10f

        val totalWeight = weightedScores.sumOf { (_, weight) -> weight.toDouble() }.toFloat().coerceAtLeast(epsilon)
        val weightedLogSum = weightedScores.sumOf { (score, weight) ->
            (weight * ln(score.coerceIn(epsilon, 1f))).toDouble()
        }.toFloat()
        val rawConfidence = exp(weightedLogSum / totalWeight).coerceIn(0f, 1f)

        val phaseAdjustment = when (current.phase) {
            LapPhase.NORMAL -> 1.0f
            LapPhase.OUTLAP -> 0.95f
            LapPhase.INLAP -> 0.90f
            LapPhase.INTERRUPTED -> 0.35f
        }
        val profileAdjustment = when {
            trackProfile?.confidenceScore ?: 0f >= 0.7f -> 1.00f
            trackProfile != null -> 0.94f
            else -> prior.sourceReliability
        }
        return (rawConfidence * phaseAdjustment * profileAdjustment).coerceIn(0f, 1f)
    }

    private fun similarityScore(previous: SegmentFeatures, current: SegmentFeatures): Float {
        val total = mapCosine(cosineSimilarity(previous.normalizedTotalAcceleration, current.normalizedTotalAcceleration))
        val yaw = mapCosine(cosineSimilarity(previous.normalizedYawRateAbs, current.normalizedYawRateAbs))
        return ((0.6f * total) + (0.4f * yaw)).coerceIn(0f, 1f)
    }

    private fun estimateLapPrior(frames: List<ResampledFrame>, trackProfile: TrackProfile?): LapPrior? {
        val usableTrackProfile = trackProfile?.takeIf { profile ->
            profile.averageLapTimeMs in minimumLapTimeMs..maximumLapTimeMs &&
                profile.averageTotalAcceleration.isNotEmpty() &&
                profile.averageYawRateAbs.isNotEmpty()
        }

        if (usableTrackProfile != null) {
            val expectedLapMs = usableTrackProfile.averageLapTimeMs
            val sigmaMs = maxOf(
                usableTrackProfile.lapTimeStdDevMs,
                (expectedLapMs.toFloat() * 0.08f).toLong(),
                minimumSigmaMs
            )
            return buildPrior(
                expectedLapMs = expectedLapMs,
                sigmaMs = sigmaMs,
                profileWeight = if (usableTrackProfile.confidenceScore >= 0.7f) 0.4f else 0.25f,
                sourceReliability = if (usableTrackProfile.confidenceScore >= 0.7f) 1.0f else 0.94f
            )
        }

        val bestShift = estimateShiftFromSession(frames) ?: return null
        return buildPrior(
            expectedLapMs = bestShift * bucketMs,
            sigmaMs = maxOf(((bestShift * bucketMs).toFloat() * 0.12f).toLong(), minimumSigmaMs),
            profileWeight = 0f,
            sourceReliability = 0.85f
        )
    }

    private fun buildPrior(
        expectedLapMs: Long,
        sigmaMs: Long,
        profileWeight: Float,
        sourceReliability: Float
    ): LapPrior {
        val minLapMs = maxOf(minimumLapTimeMs, (expectedLapMs.toFloat() * 0.55f).toLong())
        val maxLapMs = minOf(maximumLapTimeMs, (expectedLapMs.toFloat() * 1.60f).toLong())
        val expectedBuckets = (expectedLapMs / bucketMs).toInt().coerceAtLeast(1)
        val minBuckets = (minLapMs / bucketMs).toInt().coerceAtLeast(1)
        val maxBuckets = (maxLapMs / bucketMs).toInt().coerceAtLeast(minBuckets)
        return LapPrior(
            expectedLapMs = expectedLapMs,
            sigmaMs = sigmaMs,
            minLapMs = minLapMs,
            maxLapMs = maxLapMs,
            expectedBuckets = expectedBuckets,
            minBuckets = minBuckets,
            maxBuckets = maxBuckets,
            profileWeight = profileWeight,
            sourceReliability = sourceReliability
        )
    }

    private fun estimateShiftFromSession(frames: List<ResampledFrame>): Long? {
        val minShift = 150
        val maxShift = minOf(1200, frames.size / 2)
        if (maxShift <= minShift) {
            return null
        }

        val bestCandidate = (minShift..maxShift step 5)
            .map { shift -> shift to scoreShift(frames, shift) }
            .maxByOrNull { (_, score) -> score }
            ?: return null

        return bestCandidate.first.takeIf { bestCandidate.second >= minimumShiftScore }?.toLong()
    }

    private fun scoreShift(frames: List<ResampledFrame>, shift: Int): Float {
        val windowSize = shift.coerceIn(10, 20)
        val scores = mutableListOf<Float>()
        var endIndex = shift + windowSize
        while (endIndex < frames.size) {
            val startA = endIndex - windowSize
            val startB = endIndex - shift - windowSize
            if (startB < 0) {
                break
            }
            val totalA = frames.subList(startA, endIndex).map { frame -> frame.totalAcceleration }
            val totalB = frames.subList(startB, startB + windowSize).map { frame -> frame.totalAcceleration }
            val yawA = frames.subList(startA, endIndex).map { frame -> frame.yawRateAbs }
            val yawB = frames.subList(startB, startB + windowSize).map { frame -> frame.yawRateAbs }
            val totalSimilarity = mapCosine(cosineSimilarity(totalA, totalB))
            val yawSimilarity = mapCosine(cosineSimilarity(yawA, yawB))
            scores += ((0.6f * totalSimilarity) + (0.4f * yawSimilarity)).coerceIn(0f, 1f)
            endIndex += 10
        }
        if (scores.isEmpty()) {
            return 0f
        }
        return scores.sortedDescending().take(6).average().toFloat()
    }

    private fun isStableSolution(
        laps: List<Lap>,
        totalScore: Float,
        prior: LapPrior
    ): Boolean {
        if (laps.isEmpty()) {
            return false
        }
        val normalOrOutlaps = laps.count { lap ->
            lap.phase == LapPhase.NORMAL || lap.phase == LapPhase.OUTLAP || lap.phase == LapPhase.INLAP
        }
        val averageConfidence = laps.map { lap -> lap.confidenceScore }.average().toFloat()
        val plausibleDurationRatio = laps.count { lap -> lap.lapTimeMs in prior.minLapMs..(prior.maxLapMs * 13L / 10L) }
            .toFloat() / laps.size.toFloat()
        return normalOrOutlaps >= 1 &&
            averageConfidence >= minimumAverageConfidence &&
            plausibleDurationRatio >= minimumPlausibleDurationRatio &&
            totalScore > minimumPathScore
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
            confidenceScore = 0.30f
        )
        return LapDetectionResult(
            laps = listOf(lap),
            estimatedLapTimeMs = lap.lapTimeMs,
            confidenceScores = listOf(lap.confidenceScore)
        )
    }

    private fun resample(samples: List<SensorSample>, bucketNs: Long): List<ResampledFrame> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val points = mutableListOf<Triple<Long, Float, Float>>()
        var bucketStartNs = samples.first().timestampNs
        var count = 0
        var totalAccelerationSum = 0f
        var yawRateAbsSum = 0f

        fun flushBucket() {
            if (count == 0) {
                return
            }
            points += Triple(
                bucketStartNs,
                totalAccelerationSum / count,
                yawRateAbsSum / count
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

        if (points.isEmpty()) {
            return emptyList()
        }

        val totalAccelerationValues = points.map { (_, totalAcceleration, _) -> totalAcceleration }
        val yawRateValues = points.map { (_, _, yawRateAbs) -> yawRateAbs }
        val totalAccelerationStats = robustStats(totalAccelerationValues)
        val yawRateStats = robustStats(yawRateValues)

        return points.mapIndexed { index, (timestampNs, totalAcceleration, yawRateAbs) ->
            val normalizedTotal = ((totalAcceleration - totalAccelerationStats.mean) / totalAccelerationStats.scale).toFloat()
            val normalizedYaw = ((yawRateAbs - yawRateStats.mean) / yawRateStats.scale).toFloat()
            ResampledFrame(
                index = index,
                timestampNs = timestampNs,
                totalAcceleration = totalAcceleration,
                yawRateAbs = yawRateAbs,
                activity = ((0.6f * kotlin.math.abs(normalizedTotal)) + (0.4f * kotlin.math.abs(normalizedYaw))).coerceIn(0f, 4f)
            )
        }
    }

    private fun robustStats(values: List<Float>): RobustStats {
        if (values.isEmpty()) {
            return RobustStats(mean = 0.0, scale = 1.0)
        }
        val mean = values.average()
        val variance = values
            .map { value -> (value - mean) * (value - mean) }
            .average()
            .coerceAtLeast(1e-6)
        return RobustStats(mean = mean, scale = sqrt(variance))
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
        if (firstNorm <= 0f || secondNorm <= 0f) {
            return 0f
        }
        return dot / (sqrt(firstNorm) * sqrt(secondNorm))
    }

    private fun mapCosine(value: Float): Float = ((value + 1f) / 2f).coerceIn(0f, 1f)

    private data class RobustStats(
        val mean: Double,
        val scale: Double
    )

    companion object {
        private const val TAG = "LapDetector2"
        private const val bucketNs = 100_000_000L
        private const val bucketMs = 100L
        private const val minimumSampleCount = 50
        private const val minimumFrameCount = 80
        private const val minimumLapSampleCount = 20
        private const val minimumLapTimeMs = 15_000L
        private const val maximumLapTimeMs = 120_000L
        private const val minimumSigmaMs = 1_500L
        private const val minimumShiftScore = 0.58f
        private const val minimumAverageConfidence = 0.45f
        private const val minimumPlausibleDurationRatio = 0.50f
        private const val minimumPathScore = 0.6f
    }
}
