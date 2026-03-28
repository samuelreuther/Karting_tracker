package com.kartingtracker.domain

import com.kartingtracker.data.LapPhase

data class ResampledFrame(
    val index: Int,
    val timestampNs: Long,
    val totalAcceleration: Float,
    val yawRateAbs: Float,
    val activity: Float
)

data class LapPrior(
    val expectedLapMs: Long,
    val sigmaMs: Long,
    val minLapMs: Long,
    val maxLapMs: Long,
    val expectedBuckets: Int,
    val minBuckets: Int,
    val maxBuckets: Int,
    val profileWeight: Float,
    val sourceReliability: Float
)

data class BoundaryCandidate(
    val index: Int,
    val timestampNs: Long,
    val score: Float,
    val isPauseEdge: Boolean = false
)

data class BoundaryGenerationResult(
    val candidates: List<BoundaryCandidate>,
    val boundaryScores: FloatArray
)

data class SegmentFeatures(
    val durationMs: Long,
    val durationScore: Float,
    val templateMatchScore: Float?,
    val eventScore: Float,
    val boundarySharpnessScore: Float,
    val activeRatio: Float,
    val lowActivityRatio: Float,
    val brakingPeakCount: Int,
    val corneringPeakCount: Int,
    val normalizedTotalAcceleration: List<Float>,
    val normalizedYawRateAbs: List<Float>
)

data class SegmentCandidate(
    val id: Int,
    val startBoundaryIdx: Int,
    val endBoundaryIdx: Int,
    val startFrameIndex: Int,
    val endFrameIndex: Int,
    val allowedPhases: Set<LapPhase>,
    val features: SegmentFeatures,
    val unaryScores: Map<LapPhase, Float>
)

data class ChosenSegment(
    val segment: SegmentCandidate,
    val phase: LapPhase
)

data class SegmentationPath(
    val segments: List<ChosenSegment>,
    val totalScore: Float
)
