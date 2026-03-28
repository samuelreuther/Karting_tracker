package com.kartingtracker.domain

import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.TrackProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class GlobalSegmenter {
    fun segment(
        frames: List<ResampledFrame>,
        boundaryResult: BoundaryGenerationResult,
        prior: LapPrior,
        trackProfile: TrackProfile?
    ): SegmentationPath? {
        val boundaries = boundaryResult.candidates
        if (frames.isEmpty() || boundaries.size < 2) {
            return null
        }

        val segmentCandidates = buildSegmentCandidates(
            frames = frames,
            boundaries = boundaries,
            boundaryScores = boundaryResult.boundaryScores,
            prior = prior,
            trackProfile = trackProfile
        )
        if (segmentCandidates.isEmpty()) {
            return null
        }

        val candidatesByEnd = segmentCandidates.groupBy { candidate -> candidate.endBoundaryIdx }
        val states = mutableMapOf<Pair<Int, LapPhase>, DPState>()

        segmentCandidates
            .sortedBy { candidate -> candidate.endBoundaryIdx }
            .forEach { candidate ->
                candidate.allowedPhases.forEach { phase ->
                    val unary = candidate.unaryScores.getValue(phase)
                    var bestState = DPState(
                        score = Float.NEGATIVE_INFINITY,
                        previousSegmentId = null,
                        previousPhase = null
                    )

                    if (candidate.startBoundaryIdx == 0) {
                        bestState = DPState(score = unary, previousSegmentId = null, previousPhase = null)
                    } else {
                        val previousSegments = candidatesByEnd[candidate.startBoundaryIdx].orEmpty()
                        for (previous in previousSegments) {
                            for (previousPhase in previous.allowedPhases) {
                                val previousState = states[previous.id to previousPhase] ?: continue
                                if (!isAllowedTransition(previousPhase, phase)) {
                                    continue
                                }
                                val transitionScore = computeTransitionScore(
                                    previous = previous,
                                    current = candidate,
                                    previousPhase = previousPhase,
                                    currentPhase = phase,
                                    prior = prior
                                )
                                val totalScore = previousState.score + unary + transitionScore
                                if (totalScore > bestState.score) {
                                    bestState = DPState(
                                        score = totalScore,
                                        previousSegmentId = previous.id,
                                        previousPhase = previousPhase
                                    )
                                }
                            }
                        }
                    }

                    states[candidate.id to phase] = bestState
                }
            }

        val terminal = segmentCandidates
            .filter { candidate -> candidate.endBoundaryIdx == boundaries.lastIndex }
            .flatMap { candidate -> candidate.allowedPhases.map { phase -> candidate to phase } }
            .mapNotNull { (candidate, phase) ->
                val state = states[candidate.id to phase] ?: return@mapNotNull null
                if (state.score.isFinite()) {
                    TerminalState(candidate.id, phase, state.score)
                } else {
                    null
                }
            }
            .maxByOrNull { terminalState -> terminalState.score }
            ?: return null

        val segmentsById = segmentCandidates.associateBy { candidate -> candidate.id }
        val chosenSegments = mutableListOf<ChosenSegment>()
        var currentSegmentId: Int? = terminal.segmentId
        var currentPhase: LapPhase? = terminal.phase

        while (currentSegmentId != null && currentPhase != null) {
            val segment = segmentsById[currentSegmentId] ?: break
            chosenSegments += ChosenSegment(segment = segment, phase = currentPhase)
            val state = states[currentSegmentId to currentPhase] ?: break
            currentSegmentId = state.previousSegmentId
            currentPhase = state.previousPhase
        }

        val orderedSegments = chosenSegments.asReversed()
        if (orderedSegments.isEmpty()) {
            return null
        }

        return SegmentationPath(
            segments = orderedSegments,
            totalScore = terminal.score
        )
    }

    private fun buildSegmentCandidates(
        frames: List<ResampledFrame>,
        boundaries: List<BoundaryCandidate>,
        boundaryScores: FloatArray,
        prior: LapPrior,
        trackProfile: TrackProfile?
    ): List<SegmentCandidate> {
        val candidates = mutableListOf<SegmentCandidate>()
        var candidateId = 0

        for (startBoundaryIdx in 0 until boundaries.lastIndex) {
            val startBoundary = boundaries[startBoundaryIdx]
            for (endBoundaryIdx in (startBoundaryIdx + 1)..boundaries.lastIndex) {
                val endBoundary = boundaries[endBoundaryIdx]
                val durationMs = frameDurationMs(frames, startBoundary.index, endBoundary.index)
                if (durationMs > prior.maxLapMs * maximumSpanMultiplier && !endBoundary.isPauseEdge) {
                    break
                }

                val features = computeSegmentFeatures(
                    frames = frames,
                    startFrameIndex = startBoundary.index,
                    endFrameIndex = endBoundary.index,
                    boundaryScores = boundaryScores,
                    prior = prior,
                    trackProfile = trackProfile
                )

                val allowedPhases = determineAllowedPhases(
                    startBoundaryIdx = startBoundaryIdx,
                    endBoundaryIdx = endBoundaryIdx,
                    boundaries = boundaries,
                    features = features,
                    prior = prior
                )
                if (allowedPhases.isEmpty()) {
                    continue
                }

                val unaryScores = allowedPhases.associateWith { phase ->
                    computeUnaryScore(
                        features = features,
                        phase = phase,
                        atSessionStart = startBoundaryIdx == 0,
                        atSessionEnd = endBoundaryIdx == boundaries.lastIndex,
                        followsPause = startBoundary.isPauseEdge,
                        precedesPause = endBoundary.isPauseEdge
                    )
                }

                candidates += SegmentCandidate(
                    id = candidateId++,
                    startBoundaryIdx = startBoundaryIdx,
                    endBoundaryIdx = endBoundaryIdx,
                    startFrameIndex = startBoundary.index,
                    endFrameIndex = endBoundary.index,
                    allowedPhases = allowedPhases,
                    features = features,
                    unaryScores = unaryScores
                )
            }
        }

        return candidates
    }

    private fun computeSegmentFeatures(
        frames: List<ResampledFrame>,
        startFrameIndex: Int,
        endFrameIndex: Int,
        boundaryScores: FloatArray,
        prior: LapPrior,
        trackProfile: TrackProfile?
    ): SegmentFeatures {
        val safeStart = startFrameIndex.coerceAtLeast(0)
        val safeEnd = endFrameIndex.coerceAtMost(frames.lastIndex)
        val segmentFrames = frames.subList(safeStart, safeEnd + 1)
        val durationMs = frameDurationMs(frames, safeStart, safeEnd)
        val durationScore = gaussianDurationScore(durationMs, prior.expectedLapMs, prior.sigmaMs)
        val brakingPeakCount = detectBrakingPeaks(segmentFrames).size
        val corneringPeakCount = detectCorneringPeaks(segmentFrames).size
        val expectedBrakingCount = trackProfile?.typicalBrakingZones?.size?.coerceAtLeast(2) ?: defaultPeakCount
        val expectedCorneringCount = trackProfile?.typicalCorneringZones?.size?.coerceAtLeast(2) ?: defaultPeakCount
        val brakingAgreement = countAgreement(brakingPeakCount, expectedBrakingCount)
        val corneringAgreement = countAgreement(corneringPeakCount, expectedCorneringCount)
        val eventScore = ((0.5f * brakingAgreement) + (0.5f * corneringAgreement)).coerceIn(0f, 1f)
        val boundarySharpnessScore = (
            boundaryScores.getOrElse(safeStart) { 0f } +
                boundaryScores.getOrElse(safeEnd) { 0f }
            ) / 2f
        val activeRatio = segmentFrames.count { frame -> frame.activity >= activityThreshold }.toFloat() / segmentFrames.size.coerceAtLeast(1)
        val lowActivityRatio = segmentFrames.count { frame -> frame.activity < lowActivityThreshold }.toFloat() / segmentFrames.size.coerceAtLeast(1)
        val normalizedTotal = normalizeSegment(segmentFrames, confidencePointCount) { frame -> frame.totalAcceleration }
        val normalizedYaw = normalizeSegment(segmentFrames, confidencePointCount) { frame -> frame.yawRateAbs }
        val templateMatchScore = computeTemplateMatchScore(normalizedTotal, normalizedYaw, trackProfile)

        return SegmentFeatures(
            durationMs = durationMs,
            durationScore = durationScore,
            templateMatchScore = templateMatchScore,
            eventScore = eventScore,
            boundarySharpnessScore = boundarySharpnessScore.coerceIn(0f, 1f),
            activeRatio = activeRatio.coerceIn(0f, 1f),
            lowActivityRatio = lowActivityRatio.coerceIn(0f, 1f),
            brakingPeakCount = brakingPeakCount,
            corneringPeakCount = corneringPeakCount,
            normalizedTotalAcceleration = normalizedTotal,
            normalizedYawRateAbs = normalizedYaw
        )
    }

    private fun determineAllowedPhases(
        startBoundaryIdx: Int,
        endBoundaryIdx: Int,
        boundaries: List<BoundaryCandidate>,
        features: SegmentFeatures,
        prior: LapPrior
    ): Set<LapPhase> {
        val phases = linkedSetOf<LapPhase>()
        val durationMs = features.durationMs
        val nearStart = startBoundaryIdx == 0 || boundaries[startBoundaryIdx].isPauseEdge
        val nearEnd = endBoundaryIdx == boundaries.lastIndex || boundaries[endBoundaryIdx].isPauseEdge
        val withinNormalDuration = durationMs in prior.minLapMs..prior.maxLapMs
        val withinExtendedDuration = durationMs in
            (prior.minLapMs.toFloat() * 0.8f).toLong()..(prior.maxLapMs.toFloat() * 1.3f).toLong()

        if (features.lowActivityRatio >= 0.55f || (features.activeRatio <= 0.18f && durationMs >= minimumInterruptedDurationMs)) {
            phases += LapPhase.INTERRUPTED
        }

        if (withinNormalDuration && features.activeRatio >= 0.22f) {
            phases += LapPhase.NORMAL
        }

        if (nearStart && withinExtendedDuration && features.activeRatio >= 0.16f) {
            phases += LapPhase.OUTLAP
        }

        if (nearEnd && withinExtendedDuration && features.activeRatio >= 0.12f) {
            phases += LapPhase.INLAP
        }

        if (features.activeRatio < 0.10f && phases.isEmpty()) {
            phases += LapPhase.INTERRUPTED
        }

        return phases
    }

    private fun computeUnaryScore(
        features: SegmentFeatures,
        phase: LapPhase,
        atSessionStart: Boolean,
        atSessionEnd: Boolean,
        followsPause: Boolean,
        precedesPause: Boolean
    ): Float {
        return when (phase) {
            LapPhase.NORMAL -> (
                (0.30f * features.durationScore) +
                    (0.25f * (features.templateMatchScore ?: 0.55f)) +
                    (0.20f * features.eventScore) +
                    (0.15f * features.boundarySharpnessScore) +
                    (0.10f * features.activeRatio)
                ).coerceIn(0f, 1f)

            LapPhase.OUTLAP -> (
                (0.24f * features.durationScore) +
                    (0.18f * (features.templateMatchScore ?: 0.50f)) +
                    (0.18f * features.eventScore) +
                    (0.15f * features.boundarySharpnessScore) +
                    (0.10f * features.activeRatio) +
                    if (atSessionStart || followsPause) 0.15f else -0.15f
                ).coerceIn(0f, 1f)

            LapPhase.INLAP -> (
                (0.22f * features.durationScore) +
                    (0.18f * (features.templateMatchScore ?: 0.50f)) +
                    (0.16f * features.eventScore) +
                    (0.14f * features.boundarySharpnessScore) +
                    (0.10f * features.activeRatio) +
                    if (atSessionEnd || precedesPause) 0.20f else -0.12f
                ).coerceIn(0f, 1f)

            LapPhase.INTERRUPTED -> (
                (0.45f * features.lowActivityRatio) +
                    (0.20f * (1f - features.eventScore)) +
                    (0.20f * (1f - features.activeRatio)) +
                    (0.15f * features.boundarySharpnessScore)
                ).coerceIn(0f, 1f)
        }
    }

    private fun computeTransitionScore(
        previous: SegmentCandidate,
        current: SegmentCandidate,
        previousPhase: LapPhase,
        currentPhase: LapPhase,
        prior: LapPrior
    ): Float {
        val transitionPrior = labelTransitionPrior(previousPhase, currentPhase)
        val durationConsistency = gaussianDurationScore(
            valueMs = current.features.durationMs,
            expectedMs = previous.features.durationMs,
            sigmaMs = maxOf(prior.sigmaMs, (previous.features.durationMs.toFloat() * 0.12f).toLong())
        )
        val similarityScore = if (
            previousPhase == LapPhase.INTERRUPTED || currentPhase == LapPhase.INTERRUPTED
        ) {
            0.35f
        } else {
            computeSimilarityScore(previous.features, current.features)
        }

        return (
            (0.45f * similarityScore) +
                (0.30f * durationConsistency) +
                (0.25f * transitionPrior)
            ) - transitionPenalty(previousPhase, currentPhase)
    }

    private fun isAllowedTransition(previousPhase: LapPhase, currentPhase: LapPhase): Boolean {
        return when (previousPhase) {
            LapPhase.NORMAL -> currentPhase != LapPhase.OUTLAP
            LapPhase.OUTLAP -> currentPhase != LapPhase.OUTLAP
            LapPhase.INLAP -> currentPhase == LapPhase.INTERRUPTED
            LapPhase.INTERRUPTED -> currentPhase != LapPhase.INLAP
        }
    }

    private fun labelTransitionPrior(previousPhase: LapPhase, currentPhase: LapPhase): Float {
        return when {
            previousPhase == LapPhase.OUTLAP && currentPhase == LapPhase.NORMAL -> 0.95f
            previousPhase == LapPhase.NORMAL && currentPhase == LapPhase.NORMAL -> 0.90f
            previousPhase == LapPhase.NORMAL && currentPhase == LapPhase.INLAP -> 0.85f
            previousPhase == LapPhase.INTERRUPTED && currentPhase == LapPhase.OUTLAP -> 0.85f
            currentPhase == LapPhase.INTERRUPTED -> 0.65f
            else -> 0.45f
        }
    }

    private fun transitionPenalty(previousPhase: LapPhase, currentPhase: LapPhase): Float {
        return when {
            previousPhase == LapPhase.INTERRUPTED && currentPhase == LapPhase.INLAP -> 0.25f
            previousPhase == LapPhase.INLAP && currentPhase != LapPhase.INTERRUPTED -> 0.25f
            else -> 0f
        }
    }

    private fun computeSimilarityScore(previous: SegmentFeatures, current: SegmentFeatures): Float {
        val totalSimilarity = mapCosine(
            cosineSimilarity(previous.normalizedTotalAcceleration, current.normalizedTotalAcceleration)
        )
        val yawSimilarity = mapCosine(
            cosineSimilarity(previous.normalizedYawRateAbs, current.normalizedYawRateAbs)
        )
        return ((0.6f * totalSimilarity) + (0.4f * yawSimilarity)).coerceIn(0f, 1f)
    }

    private fun computeTemplateMatchScore(
        normalizedTotalAcceleration: List<Float>,
        normalizedYawRateAbs: List<Float>,
        trackProfile: TrackProfile?
    ): Float? {
        if (trackProfile == null ||
            trackProfile.averageTotalAcceleration.isEmpty() ||
            trackProfile.averageYawRateAbs.isEmpty()
        ) {
            return null
        }

        val totalTemplate = resampleList(trackProfile.averageTotalAcceleration, normalizedTotalAcceleration.size)
        val yawTemplate = resampleList(trackProfile.averageYawRateAbs, normalizedYawRateAbs.size)
        val totalSimilarity = mapCosine(cosineSimilarity(normalizedTotalAcceleration, totalTemplate))
        val yawSimilarity = mapCosine(cosineSimilarity(normalizedYawRateAbs, yawTemplate))
        return ((0.6f * totalSimilarity) + (0.4f * yawSimilarity)).coerceIn(0f, 1f)
    }

    private fun normalizeSegment(
        frames: List<ResampledFrame>,
        pointCount: Int,
        selector: (ResampledFrame) -> Float
    ): List<Float> {
        if (frames.isEmpty()) {
            return emptyList()
        }
        if (frames.size == 1) {
            return List(pointCount) { selector(frames.first()) }
        }

        val result = ArrayList<Float>(pointCount)
        val scale = (frames.size - 1).toFloat() / (pointCount - 1).coerceAtLeast(1)
        for (pointIndex in 0 until pointCount) {
            val rawIndex = pointIndex * scale
            val lowerIndex = rawIndex.toInt().coerceIn(0, frames.lastIndex)
            val upperIndex = (lowerIndex + 1).coerceAtMost(frames.lastIndex)
            if (lowerIndex == upperIndex) {
                result += selector(frames[lowerIndex])
            } else {
                val progress = rawIndex - lowerIndex
                val lowerValue = selector(frames[lowerIndex])
                val upperValue = selector(frames[upperIndex])
                result += lowerValue + ((upperValue - lowerValue) * progress)
            }
        }
        return result
    }

    private fun resampleList(values: List<Float>, pointCount: Int): List<Float> {
        if (values.isEmpty()) {
            return emptyList()
        }
        if (values.size == pointCount) {
            return values
        }
        if (values.size == 1) {
            return List(pointCount) { values.first() }
        }

        val result = ArrayList<Float>(pointCount)
        val scale = (values.size - 1).toFloat() / (pointCount - 1).coerceAtLeast(1)
        for (pointIndex in 0 until pointCount) {
            val rawIndex = pointIndex * scale
            val lowerIndex = rawIndex.toInt().coerceIn(0, values.lastIndex)
            val upperIndex = (lowerIndex + 1).coerceAtMost(values.lastIndex)
            if (lowerIndex == upperIndex) {
                result += values[lowerIndex]
            } else {
                val progress = rawIndex - lowerIndex
                val lowerValue = values[lowerIndex]
                val upperValue = values[upperIndex]
                result += lowerValue + ((upperValue - lowerValue) * progress)
            }
        }
        return result
    }

    private fun detectBrakingPeaks(frames: List<ResampledFrame>): List<Int> {
        if (frames.size < 3) {
            return emptyList()
        }
        val indices = mutableListOf<Int>()
        for (index in 1 until frames.lastIndex) {
            val previous = frames[index - 1].totalAcceleration
            val current = frames[index].totalAcceleration
            val next = frames[index + 1].totalAcceleration
            if ((previous - current) > 0.45f && current < previous && current <= next) {
                indices += index
            }
        }
        return enforcePeakSpacing(indices, minimumPeakSpacing)
    }

    private fun detectCorneringPeaks(frames: List<ResampledFrame>): List<Int> {
        if (frames.size < 3) {
            return emptyList()
        }
        val indices = mutableListOf<Int>()
        for (index in 1 until frames.lastIndex) {
            val previous = frames[index - 1].yawRateAbs
            val current = frames[index].yawRateAbs
            val next = frames[index + 1].yawRateAbs
            if (current > 0.35f && current >= previous && current >= next && frames[index].totalAcceleration > 1.1f) {
                indices += index
            }
        }
        return enforcePeakSpacing(indices, minimumPeakSpacing)
    }

    private fun enforcePeakSpacing(indices: List<Int>, minimumSpacing: Int): List<Int> {
        if (indices.isEmpty()) {
            return emptyList()
        }
        val filtered = mutableListOf<Int>()
        indices.forEach { index ->
            if (filtered.none { selected -> abs(selected - index) < minimumSpacing }) {
                filtered += index
            }
        }
        return filtered
    }

    private fun frameDurationMs(frames: List<ResampledFrame>, startIndex: Int, endIndex: Int): Long {
        if (startIndex >= endIndex) {
            return 0L
        }
        return ((frames[endIndex].timestampNs - frames[startIndex].timestampNs) / 1_000_000L).coerceAtLeast(0L)
    }

    private fun gaussianDurationScore(valueMs: Long, expectedMs: Long, sigmaMs: Long): Float {
        val safeSigma = sigmaMs.coerceAtLeast(1L).toFloat()
        val z = (valueMs - expectedMs).toFloat() / safeSigma
        return exp(-0.5f * z * z).coerceIn(0f, 1f)
    }

    private fun countAgreement(actual: Int, expected: Int): Float {
        val safeExpected = expected.coerceAtLeast(1)
        return (minOf(actual, safeExpected).toFloat() / maxOf(actual, safeExpected).toFloat()).coerceIn(0f, 1f)
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

    private data class DPState(
        val score: Float,
        val previousSegmentId: Int?,
        val previousPhase: LapPhase?
    )

    private data class TerminalState(
        val segmentId: Int,
        val phase: LapPhase,
        val score: Float
    )

    companion object {
        private const val confidencePointCount = 101
        private const val defaultPeakCount = 3
        private const val minimumPeakSpacing = 4
        private const val activityThreshold = 0.35f
        private const val lowActivityThreshold = 0.22f
        private const val minimumInterruptedDurationMs = 3_000L
        private const val maximumSpanMultiplier = 2L
    }
}
