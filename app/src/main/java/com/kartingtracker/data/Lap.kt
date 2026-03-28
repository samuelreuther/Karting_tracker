package com.kartingtracker.data

data class Lap(
    val id: Int,
    val samples: List<SensorSample>,
    val lapTimeMs: Long,
    val startTimestampNs: Long,
    val endTimestampNs: Long,
    val brakingPeakIndices: List<Int> = emptyList()
)
