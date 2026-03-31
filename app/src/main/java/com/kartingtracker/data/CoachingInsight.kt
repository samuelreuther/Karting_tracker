package com.kartingtracker.data

data class CoachingInsight(
    val segmentIndex: Int,
    val cornerName: String?,
    val timeLossMs: Float,
    val cause: String,
    val suggestion: String,
    val severity: Float
)
