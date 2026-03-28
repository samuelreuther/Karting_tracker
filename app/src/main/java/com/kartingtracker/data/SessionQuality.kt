package com.kartingtracker.data

data class SessionQuality(
    val overallScore: Float,
    val validLapRatio: Float,
    val avgConfidence: Float,
    val disturbedLapRatio: Float,
    val lapTimeVariance: Float
)
