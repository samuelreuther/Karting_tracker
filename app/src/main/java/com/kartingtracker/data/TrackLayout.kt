package com.kartingtracker.data

data class TrackLayout(
    val trackName: String,
    val imagePath: String,
    val lengthMeters: Float?,
    val startPoint: TrackPoint = DEFAULT_START_POINT,
    val direction: TrackDirection = TrackDirection.CLOCKWISE,
    val corners: List<TrackCorner> = emptyList()
) {
    companion object {
        val DEFAULT_START_POINT = TrackPoint(0.5f, 0.5f)
    }
}

data class TrackPoint(
    val x: Float,
    val y: Float
)

enum class TrackDirection {
    CLOCKWISE,
    COUNTER_CLOCKWISE
}

data class TrackCorner(
    val name: String,
    val point: TrackPoint
)
