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
    val isOutlap: Boolean = false,
    var isDisturbed: Boolean = false
)
