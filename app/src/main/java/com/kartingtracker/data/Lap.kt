package com.kartingtracker.data

data class Lap(
    val id: Int,
    val samples: List<SensorSample>,
    val lapTimeMs: Long,
    val startTimestampNs: Long,
    val endTimestampNs: Long,
    val brakingPeakIndices: List<Int> = emptyList(),
    val corneringPeakIndices: List<Int> = emptyList(),
    val sectorBoundaries: List<Int> = emptyList(),
    val sectorTimesMs: List<Long> = emptyList(),
    val confidenceScore: Float = 1f,
    val lapPhase: LapPhase? = null,
    val isOutlap: Boolean = lapPhase == LapPhase.OUTLAP,
    var isDisturbed: Boolean = false
) {
    val phase: LapPhase
        get() = lapPhase ?: if (isOutlap) LapPhase.OUTLAP else LapPhase.NORMAL

    val isInlap: Boolean
        get() = phase == LapPhase.INLAP

    val isInterrupted: Boolean
        get() = phase == LapPhase.INTERRUPTED

    val isNormalPhase: Boolean
        get() = phase == LapPhase.NORMAL
}
