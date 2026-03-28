package com.kartingtracker.domain

import com.kartingtracker.data.TrackProfile
import kotlin.math.abs
import kotlin.math.sqrt

class BoundaryGenerator {
    fun generate(
        frames: List<ResampledFrame>,
        prior: LapPrior,
        trackProfile: TrackProfile?
    ): BoundaryGenerationResult {
        if (frames.isEmpty()) {
            return BoundaryGenerationResult(emptyList(), FloatArray(0))
        }

        val boundaryScores = FloatArray(frames.size)
        val repeatWindow = prior.expectedBuckets.coerceIn(10, 20)
        val minSpacing = (prior.expectedBuckets / 4).coerceAtLeast(6)

        for (index in repeatWindow until (frames.size - repeatWindow)) {
            val repeatScore = repeatEvidence(frames, index, prior.expectedBuckets, repeatWindow)
            val boundarySharpness = boundarySharpness(frames, index)
            val pauseEdgeScore = pauseEdgeEvidence(frames, index)
            val anchorScore = anchorEvidence(index, prior.expectedBuckets)
            val profileBias = templateBoundaryBias(index, prior.expectedBuckets, trackProfile)

            boundaryScores[index] = (
                (0.48f * repeatScore) +
                    (0.22f * boundarySharpness) +
                    (0.18f * pauseEdgeScore) +
                    (0.08f * anchorScore) +
                    (0.04f * profileBias)
                ).coerceIn(0f, 1f)
        }

        val maxima = pickLocalMaxima(boundaryScores, minSpacing)
        val anchors = buildAnchorCandidates(frames, prior, boundaryScores)
        val pauseEdges = detectPauseEdges(frames, boundaryScores)

        val mergedCandidates = mergeCandidates(
            listOf(
                BoundaryCandidate(index = 0, timestampNs = frames.first().timestampNs, score = 1f),
                BoundaryCandidate(index = frames.lastIndex, timestampNs = frames.last().timestampNs, score = 1f),
                *maxima.toTypedArray(),
                *anchors.toTypedArray(),
                *pauseEdges.toTypedArray()
            )
        ).map { candidate ->
            candidate.copy(timestampNs = frames[candidate.index].timestampNs)
        }

        return BoundaryGenerationResult(
            candidates = mergedCandidates,
            boundaryScores = boundaryScores
        )
    }

    private fun repeatEvidence(
        frames: List<ResampledFrame>,
        boundaryIndex: Int,
        expectedBuckets: Int,
        windowSize: Int
    ): Float {
        val currentStart = boundaryIndex - windowSize
        val previousEnd = boundaryIndex - expectedBuckets
        val previousStart = previousEnd - windowSize
        if (currentStart < 0 || previousStart < 0 || previousEnd >= frames.size) {
            return 0f
        }

        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (offset in 0 until windowSize) {
            val a = frames[currentStart + offset]
            val b = frames[previousStart + offset]
            val ax = a.totalAcceleration
            val ay = a.yawRateAbs * 0.7f
            val bx = b.totalAcceleration
            val by = b.yawRateAbs * 0.7f
            dot += (ax * bx) + (ay * by)
            normA += (ax * ax) + (ay * ay)
            normB += (bx * bx) + (by * by)
        }
        if (normA <= 0f || normB <= 0f) {
            return 0f
        }
        val cosine = dot / (sqrt(normA) * sqrt(normB))
        return ((cosine + 1f) / 2f).coerceIn(0f, 1f)
    }

    private fun boundarySharpness(frames: List<ResampledFrame>, boundaryIndex: Int): Float {
        val leftRange = (boundaryIndex - 8).coerceAtLeast(0) until boundaryIndex
        val rightRange = boundaryIndex..(boundaryIndex + 7).coerceAtMost(frames.lastIndex)
        if (leftRange.isEmpty() || rightRange.isEmpty()) {
            return 0f
        }

        val leftMean = leftRange.map { index -> frames[index].activity }.average().toFloat()
        val rightMean = rightRange.map { index -> frames[index].activity }.average().toFloat()
        val contrast = abs(rightMean - leftMean)
        return (contrast / 2.5f).coerceIn(0f, 1f)
    }

    private fun pauseEdgeEvidence(frames: List<ResampledFrame>, boundaryIndex: Int): Float {
        val leftRange = (boundaryIndex - 6).coerceAtLeast(0) until boundaryIndex
        val rightRange = boundaryIndex..(boundaryIndex + 5).coerceAtMost(frames.lastIndex)
        if (leftRange.isEmpty() || rightRange.isEmpty()) {
            return 0f
        }

        val leftLowActivity = leftRange.count { index -> frames[index].activity < lowActivityThreshold }.toFloat() / leftRange.count()
        val rightLowActivity = rightRange.count { index -> frames[index].activity < lowActivityThreshold }.toFloat() / rightRange.count()
        return maxOf(leftLowActivity, rightLowActivity)
    }

    private fun anchorEvidence(boundaryIndex: Int, expectedBuckets: Int): Float {
        if (expectedBuckets <= 0) {
            return 0f
        }
        val remainder = boundaryIndex % expectedBuckets
        val distance = minOf(remainder, expectedBuckets - remainder).toFloat()
        val normalized = 1f - (distance / (expectedBuckets / 2f).coerceAtLeast(1f))
        return normalized.coerceIn(0f, 1f)
    }

    private fun templateBoundaryBias(
        boundaryIndex: Int,
        expectedBuckets: Int,
        trackProfile: TrackProfile?
    ): Float {
        if (trackProfile == null || trackProfile.confidenceScore <= 0f || expectedBuckets <= 0) {
            return 0f
        }
        val expectedMultiple = (boundaryIndex.toFloat() / expectedBuckets.toFloat()).toInt()
        if (expectedMultiple <= 0) {
            return 0f
        }
        val anchorIndex = expectedMultiple * expectedBuckets
        val distance = abs(boundaryIndex - anchorIndex).toFloat()
        return (1f - (distance / (expectedBuckets * 0.35f).coerceAtLeast(1f))).coerceIn(0f, 1f) *
            trackProfile.confidenceScore.coerceIn(0f, 1f)
    }

    private fun pickLocalMaxima(boundaryScores: FloatArray, minSpacing: Int): List<BoundaryCandidate> {
        val rawCandidates = mutableListOf<BoundaryCandidate>()
        for (index in 1 until boundaryScores.lastIndex) {
            val previous = boundaryScores[index - 1]
            val current = boundaryScores[index]
            val next = boundaryScores[index + 1]
            if (current >= minimumBoundaryScore && current >= previous && current >= next) {
                rawCandidates += BoundaryCandidate(index = index, timestampNs = 0L, score = current)
            }
        }

        val selected = mutableListOf<BoundaryCandidate>()
        rawCandidates
            .sortedByDescending { candidate -> candidate.score }
            .forEach { candidate ->
                val duplicate = selected.any { selectedCandidate ->
                    abs(selectedCandidate.index - candidate.index) < minSpacing
                }
                if (!duplicate) {
                    selected += candidate
                }
            }

        return selected.sortedBy { candidate -> candidate.index }
    }

    private fun buildAnchorCandidates(
        frames: List<ResampledFrame>,
        prior: LapPrior,
        boundaryScores: FloatArray
    ): List<BoundaryCandidate> {
        if (prior.expectedBuckets <= 0) {
            return emptyList()
        }

        val tolerance = (prior.expectedBuckets / 5).coerceAtLeast(4)
        val candidates = mutableListOf<BoundaryCandidate>()
        var anchor = prior.expectedBuckets
        while (anchor < frames.lastIndex) {
            val start = (anchor - tolerance).coerceAtLeast(1)
            val end = (anchor + tolerance).coerceAtMost(frames.lastIndex - 1)
            val bestIndex = (start..end).maxByOrNull { index -> boundaryScores[index] } ?: anchor
            val score = boundaryScores.getOrElse(bestIndex) { 0f }
            if (score >= 0.25f) {
                candidates += BoundaryCandidate(
                    index = bestIndex,
                    timestampNs = frames[bestIndex].timestampNs,
                    score = score
                )
            }
            anchor += prior.expectedBuckets
        }
        return candidates
    }

    private fun detectPauseEdges(
        frames: List<ResampledFrame>,
        boundaryScores: FloatArray
    ): List<BoundaryCandidate> {
        if (frames.size < minimumPauseLengthBuckets) {
            return emptyList()
        }

        val candidates = mutableListOf<BoundaryCandidate>()
        var runStart: Int? = null

        for (index in frames.indices) {
            if (frames[index].activity < lowActivityThreshold) {
                if (runStart == null) {
                    runStart = index
                }
            } else {
                val start = runStart
                if (start != null && index - start >= minimumPauseLengthBuckets) {
                    val pauseStart = start.coerceAtLeast(1)
                    val pauseEnd = (index - 1).coerceAtMost(frames.lastIndex - 1)
                    candidates += BoundaryCandidate(
                        index = pauseStart,
                        timestampNs = frames[pauseStart].timestampNs,
                        score = boundaryScores.getOrElse(pauseStart) { 0.6f }.coerceAtLeast(0.6f),
                        isPauseEdge = true
                    )
                    candidates += BoundaryCandidate(
                        index = pauseEnd,
                        timestampNs = frames[pauseEnd].timestampNs,
                        score = boundaryScores.getOrElse(pauseEnd) { 0.6f }.coerceAtLeast(0.6f),
                        isPauseEdge = true
                    )
                }
                runStart = null
            }
        }

        val trailingStart = runStart
        if (trailingStart != null && frames.size - trailingStart >= minimumPauseLengthBuckets) {
            val pauseStart = trailingStart.coerceAtLeast(1)
            val pauseEnd = frames.lastIndex
            candidates += BoundaryCandidate(
                index = pauseStart,
                timestampNs = frames[pauseStart].timestampNs,
                score = boundaryScores.getOrElse(pauseStart) { 0.6f }.coerceAtLeast(0.6f),
                isPauseEdge = true
            )
            candidates += BoundaryCandidate(
                index = pauseEnd,
                timestampNs = frames[pauseEnd].timestampNs,
                score = boundaryScores.getOrElse(pauseEnd) { 0.6f }.coerceAtLeast(0.6f),
                isPauseEdge = true
            )
        }

        return candidates
    }

    private fun mergeCandidates(candidates: List<BoundaryCandidate>): List<BoundaryCandidate> {
        val merged = mutableListOf<BoundaryCandidate>()
        candidates
            .filter { candidate -> candidate.index >= 0 }
            .sortedByDescending { candidate -> candidate.score }
            .forEach { candidate ->
                val duplicate = merged.any { existing -> abs(existing.index - candidate.index) <= duplicateSpacing }
                if (!duplicate) {
                    merged += candidate
                }
            }
        return merged.sortedBy { candidate -> candidate.index }
    }

    companion object {
        private const val minimumBoundaryScore = 0.42f
        private const val lowActivityThreshold = 0.22f
        private const val minimumPauseLengthBuckets = 25
        private const val duplicateSpacing = 3
    }
}
