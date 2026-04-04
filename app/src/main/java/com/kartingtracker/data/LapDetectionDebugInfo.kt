package com.kartingtracker.data

data class LapDetectionDebugInfo(
    val estimatedLapTimePriorMs: Long? = null,
    val usedTrackProfile: Boolean = false,
    val boundaryCandidateCount: Int = 0,
    val candidateSegmentCount: Int = 0,
    val chosenSegmentCount: Int = 0,
    val confidenceScores: List<Float> = emptyList(),
    val interruptedLapCount: Int = 0,
    val disturbedLapCount: Int = 0,
    val lowActivityRatio: Float = 0f,
    val fallbackToSingleLap: Boolean = false,
    val fallbackReasons: List<String> = emptyList(),
    val peakCountsPerLap: List<LapPeakCount> = emptyList(),
    val sectorDetectionFallbackCount: Int = 0
)

data class LapPeakCount(
    val lapId: Int,
    val brakingPeaks: Int,
    val corneringPeaks: Int
)
