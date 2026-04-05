package com.kartingtracker.domain

import com.kartingtracker.data.AnalysisValidity
import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapDetectionDebugInfo
import com.kartingtracker.data.Session
import kotlin.math.abs

data class SessionValidityResult(
    val validity: AnalysisValidity,
    val reason: String?,
    val diagnostics: List<String>
)

object SessionValidityEvaluator {
    fun evaluate(session: Session, laps: List<Lap>, debugInfo: LapDetectionDebugInfo): SessionValidityResult {
        if (session.samples.isEmpty()) {
            return invalid(
                reason = "This recording is empty and cannot be analyzed.",
                diagnostics = listOf("No sensor samples were captured.")
            )
        }

        val durationMs = (session.endTimestampNs - session.startTimestampNs) / 1_000_000L
        val avgAbsLong = session.samples.map { abs(it.longitudinalAccel) }.average().toFloat()
        val avgAbsLat = session.samples.map { abs(it.lateralAccel) }.average().toFloat()
        val activeSamples = session.samples.count { sample ->
            abs(sample.longitudinalAccel) > activeAccelThreshold || abs(sample.lateralAccel) > activeAccelThreshold
        }
        val activeRatio = if (session.samples.isNotEmpty()) {
            activeSamples.toFloat() / session.samples.size.toFloat()
        } else {
            0f
        }
        val totalBrakingPeaks = debugInfo.peakCountsPerLap.sumOf { it.brakingPeaks }
        val totalCorneringPeaks = debugInfo.peakCountsPerLap.sumOf { it.corneringPeaks }
        val disturbedRatio = if (laps.isNotEmpty()) {
            laps.count { it.isDisturbed }.toFloat() / laps.size.toFloat()
        } else {
            1f
        }

        val failures = mutableListOf<String>()
        if (durationMs < minSessionDurationMs) failures += "Session too short (${durationMs / 1000}s)."
        if (activeRatio < minActiveRatio) failures += "Too much stationary/low-motion time (${(activeRatio * 100).toInt()}% active)."
        if (avgAbsLong < minAverageAcceleration && avgAbsLat < minAverageAcceleration) {
            failures += "Movement signature is too weak for kart driving."
        }
        if (debugInfo.boundaryCandidateCount < minBoundaryCandidates) failures += "No reliable lap boundaries detected."
        if (debugInfo.candidateSegmentCount < minCandidateSegments) failures += "Too little repeat lap structure."
        if (totalBrakingPeaks < minTotalBrakingPeaks || totalCorneringPeaks < minTotalCorneringPeaks) {
            failures += "Insufficient braking/cornering pattern for a real session."
        }
        if (debugInfo.fallbackToSingleLap) failures += "Segmentation fell back to single-lap fallback."
        if (disturbedRatio > maxDisturbedRatio) failures += "Most detected laps are disturbed/invalid."
        if (debugInfo.lowActivityRatio > maxLowActivityRatio) failures += "Low-activity ratio is too high."

        if (failures.size >= minFailureSignalsForInvalid) {
            return invalid(
                reason = "This recording does not look like a valid karting session.",
                diagnostics = failures
            )
        }

        return SessionValidityResult(
            validity = AnalysisValidity.VALID,
            reason = null,
            diagnostics = emptyList()
        )
    }

    private fun invalid(reason: String, diagnostics: List<String>): SessionValidityResult {
        return SessionValidityResult(
            validity = AnalysisValidity.INVALID_NON_DRIVING,
            reason = reason,
            diagnostics = diagnostics
        )
    }

    private const val minSessionDurationMs = 45_000L
    private const val activeAccelThreshold = 1.1f
    private const val minActiveRatio = 0.2f
    private const val minAverageAcceleration = 0.75f
    private const val minBoundaryCandidates = 2
    private const val minCandidateSegments = 2
    private const val minTotalBrakingPeaks = 4
    private const val minTotalCorneringPeaks = 4
    private const val maxLowActivityRatio = 0.65f
    private const val maxDisturbedRatio = 0.8f
    private const val minFailureSignalsForInvalid = 3
}
