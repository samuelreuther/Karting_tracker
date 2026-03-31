package com.kartingtracker.data

data class CurveDefinition(
    val index: Int,
    val startPercent: Float,
    val endPercent: Float,
    val peakPercent: Float,
    val intensity: Float
)

data class TrackMapMetadata(
    val trackName: String,
    val curves: List<CurveDefinition>,
    val version: Int = 1
)
