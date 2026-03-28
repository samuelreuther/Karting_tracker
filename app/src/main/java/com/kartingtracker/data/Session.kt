package com.kartingtracker.data

data class Session(
    val id: Long,
    val trackName: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val startTimestampNs: Long,
    val endTimestampNs: Long,
    val samples: List<SensorSample>,
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null
)
