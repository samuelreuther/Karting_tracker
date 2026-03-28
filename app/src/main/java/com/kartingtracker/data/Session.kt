package com.kartingtracker.data

data class Session(
    val id: Long,
    val startTimestampNs: Long,
    val endTimestampNs: Long,
    val samples: List<SensorSample>,
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null
)
