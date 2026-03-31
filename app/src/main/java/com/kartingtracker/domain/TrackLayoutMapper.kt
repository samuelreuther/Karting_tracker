package com.kartingtracker.domain

import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackMarker
import com.kartingtracker.data.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

object TrackLayoutMapper {
    fun sortAndRenameCorners(layout: TrackLayout): List<TrackCorner> {
        val orderedCorners = sortCorners(
            corners = layout.corners,
            startPoint = layout.startPoint,
            direction = layout.direction
        )
        return orderedCorners.mapIndexed { index, corner ->
            corner.copy(name = "Kurve ${index + 1}")
        }
    }

    fun buildCornerReferences(
        detectedCorners: List<DetectedCorner>,
        trackLayout: TrackLayout?
    ): List<TrackCornerReference> {
        if (detectedCorners.isEmpty()) {
            return emptyList()
        }

        val usableLayout = trackLayout?.takeIf { layout ->
            layout.imagePath.isNotBlank() && layout.corners.isNotEmpty()
        }
        val orderedLayoutCorners = usableLayout?.let(::sortAndRenameCorners).orEmpty()

        return detectedCorners.mapIndexed { index, detectedCorner ->
            val displayIndex = index + 1
            val mappedCorner = orderedLayoutCorners.mapDetectedCornerToLayout(displayIndex, detectedCorners.size)
            TrackCornerReference(
                displayIndex = displayIndex,
                mappedPercent = (detectedCorner.peakPercent / 100f).coerceIn(0f, 1f),
                markerLabel = "K$displayIndex",
                insightLabel = mappedCorner?.name ?: "Corner $displayIndex",
                relativePosition = when {
                    mappedCorner == null -> "~${detectedCorner.peakPercent.roundToInt()}% lap"
                    index == 0 -> "after start/finish"
                    index == detectedCorners.lastIndex -> "before main straight"
                    else -> ""
                },
                corner = mappedCorner,
                detectedCorner = detectedCorner
            )
        }
    }

    fun findClosestCornerReference(
        detectedCorners: List<DetectedCorner>,
        trackLayout: TrackLayout?,
        segmentPercent: Float
    ): TrackCornerReference? {
        val references = buildCornerReferences(detectedCorners, trackLayout)
        if (references.isEmpty()) {
            return null
        }

        val normalizedPercent = (segmentPercent / 100f).coerceIn(0f, 1f)
        return references.minByOrNull { reference ->
            circularDistance(reference.mappedPercent, normalizedPercent)
        }
    }

    fun createTrackMarkers(
        layout: TrackLayout,
        detectedCorners: List<DetectedCorner>
    ): List<TrackMarker> {
        val references = buildCornerReferences(detectedCorners, layout)
            .filter { reference -> reference.corner != null }
        if (references.isEmpty()) {
            return emptyList()
        }

        val maximumStrength = detectedCorners.maxOfOrNull(DetectedCorner::strength)?.coerceAtLeast(1e-3f) ?: 1f
        return references.map { reference ->
            val mappedCorner = reference.corner ?: return@map null
            TrackMarker(
                x = mappedCorner.point.x,
                y = mappedCorner.point.y,
                label = reference.markerLabel,
                severity = (reference.detectedCorner.strength / maximumStrength).coerceIn(0.2f, 1f)
            )
        }.filterNotNull()
    }

    private fun sortCorners(
        corners: List<TrackCorner>,
        startPoint: TrackPoint,
        direction: TrackDirection
    ): List<TrackCorner> {
        if (corners.size < 2) {
            return corners
        }

        val centerPoint = centerPoint(corners.map(TrackCorner::point) + startPoint)
        return corners.sortedBy { corner ->
            progressFromStart(corner.point, startPoint, centerPoint, direction)
        }
    }

    private fun progressFromStart(
        point: TrackPoint,
        startPoint: TrackPoint,
        centerPoint: TrackPoint,
        direction: TrackDirection
    ): Float {
        val clockwiseProgress = clockwiseProgress(point, startPoint, centerPoint)
        val directedProgress = when (direction) {
            TrackDirection.CLOCKWISE -> clockwiseProgress
            TrackDirection.COUNTER_CLOCKWISE -> ((1f - clockwiseProgress) + 1f) % 1f
        }
        return if (abs(directedProgress) < samePointEpsilon) 1f else directedProgress
    }

    private fun clockwiseProgress(
        point: TrackPoint,
        startPoint: TrackPoint,
        centerPoint: TrackPoint
    ): Float {
        val startAngle = angleDegrees(startPoint, centerPoint)
        val pointAngle = angleDegrees(point, centerPoint)
        val clockwiseDelta = ((startAngle - pointAngle) + 360f) % 360f
        return clockwiseDelta / 360f
    }

    private fun angleDegrees(point: TrackPoint, centerPoint: TrackPoint): Float {
        val angleRadians = atan2(point.y - centerPoint.y, point.x - centerPoint.x)
        return (((angleRadians * 180f) / PI.toFloat()) + 360f) % 360f
    }

    private fun centerPoint(points: List<TrackPoint>): TrackPoint {
        if (points.isEmpty()) {
            return TrackLayout.DEFAULT_START_POINT
        }
        return TrackPoint(
            x = points.map { point -> point.x }.average().toFloat(),
            y = points.map { point -> point.y }.average().toFloat()
        )
    }

    private fun List<TrackCorner>.mapDetectedCornerToLayout(displayIndex: Int, totalDetectedCorners: Int): TrackCorner? {
        if (isEmpty()) {
            return null
        }
        if (size == 1 || totalDetectedCorners <= 1) {
            return first()
        }

        val scaledIndex = (((displayIndex - 1).toFloat() / (totalDetectedCorners - 1).toFloat()) * lastIndex.toFloat())
            .roundToInt()
            .coerceIn(0, lastIndex)
        return this[scaledIndex]
    }

    private fun circularDistance(first: Float, second: Float): Float {
        val delta = abs(first - second)
        return minOf(delta, 1f - delta)
    }

    private const val samePointEpsilon = 0.01f
}

data class TrackCornerReference(
    val displayIndex: Int,
    val mappedPercent: Float,
    val markerLabel: String,
    val insightLabel: String,
    val relativePosition: String,
    val corner: TrackCorner?,
    val detectedCorner: DetectedCorner
)
