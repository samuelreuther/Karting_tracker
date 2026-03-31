package com.kartingtracker.data

data class TrackLayout(
    val trackName: String,
    val imagePath: String,
    val lengthMeters: Float?,
    val startPoint: TrackPoint = DEFAULT_START_POINT,
    val direction: TrackDirection = TrackDirection.CLOCKWISE,
    val corners: List<TrackCorner> = emptyList(),
    val detectedCorners: List<DetectedTrackCorner> = emptyList(),
    val centerlinePoints: List<TrackPoint> = emptyList()
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

data class TrackMarker(
    val x: Float,
    val y: Float,
    val label: String,
    val severity: Float
)


enum class TrackCornerType {
    TIGHT,
    MEDIUM,
    FAST
}

data class DetectedTrackCorner(
    val index: Int,
    val startIndex: Int,
    val peakIndex: Int,
    val endIndex: Int,
    val type: TrackCornerType,
    val curvature: Float
)
