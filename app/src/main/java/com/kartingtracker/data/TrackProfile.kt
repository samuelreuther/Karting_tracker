package com.kartingtracker.data

data class TrackProfile(
    val trackName: String,
    val averageLapTimeMs: Long,
    val lapTimeStdDevMs: Long,
    val averageLapLengthSamples: Int,
    val averageTotalAcceleration: List<Float>,
    val averageYawRateAbs: List<Float>,
    val typicalBrakingZones: List<Int>,
    val typicalCorneringZones: List<Int>,
    val typicalSectorBoundaries: List<Int>,
    val sessionCount: Int,
    val confidenceScore: Float = 0f
)
