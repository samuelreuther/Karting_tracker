package com.kartingtracker.data

data class TimeLossSegment(
    val segmentIndex: Int,
    val timeLoss: Float,
    val cause: String
)
