package com.kartingtracker.domain

import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackPoint
import com.kartingtracker.data.TrackProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

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
        layout: TrackLayout,
        trackProfile: TrackProfile?
    ): List<TrackCornerReference> {
        val orderedCorners = sortAndRenameCorners(layout)
        if (orderedCorners.isEmpty()) {
            return emptyList()
        }

        val mappedPercents = resolveCornerPercents(orderedCorners.size, trackProfile)
        return orderedCorners.mapIndexed { index, corner ->
            TrackCornerReference(
                corner = corner,
                displayIndex = index + 1,
                mappedPercent = mappedPercents.getOrElse(index) { evenlySpacedPercent(index, orderedCorners.size) },
                relativePosition = when (index) {
                    0 -> "after start/finish"
                    orderedCorners.lastIndex -> "before main straight"
                    else -> ""
                }
            )
        }
    }

    fun findClosestCornerReference(
        layout: TrackLayout,
        trackProfile: TrackProfile?,
        segmentPercent: Float
    ): TrackCornerReference? {
        val references = buildCornerReferences(layout, trackProfile)
        if (references.isEmpty()) {
            return null
        }

        val normalizedPercent = (segmentPercent / 100f).coerceIn(0f, 1f)
        return references.minByOrNull { reference ->
            circularDistance(reference.mappedPercent, normalizedPercent)
        }
    }

    fun mapProgress(point: TrackPoint, startPoint: TrackPoint, direction: TrackDirection): Float {
        val centerPoint = centerPoint(listOf(point, startPoint))
        val clockwiseProgress = clockwiseProgress(point, startPoint, centerPoint)
        return when (direction) {
            TrackDirection.CLOCKWISE -> clockwiseProgress
            TrackDirection.COUNTER_CLOCKWISE -> ((1f - clockwiseProgress) + 1f) % 1f
        }
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

    private fun resolveCornerPercents(
        cornerCount: Int,
        trackProfile: TrackProfile?
    ): List<Float> {
        val profilePercents = trackProfile?.typicalCorneringZones
            .orEmpty()
            .filter { percent -> percent in 1..99 }
            .sorted()

        return if (profilePercents.size == cornerCount) {
            profilePercents.map { percent -> percent / 100f }
        } else {
            List(cornerCount) { index -> evenlySpacedPercent(index, cornerCount) }
        }
    }

    private fun evenlySpacedPercent(index: Int, total: Int): Float {
        if (total <= 0) {
            return 0f
        }
        return (index + 1).toFloat() / (total + 1).toFloat()
    }

    private fun circularDistance(first: Float, second: Float): Float {
        val delta = abs(first - second)
        return minOf(delta, 1f - delta)
    }

    private const val samePointEpsilon = 0.01f
}

data class TrackCornerReference(
    val corner: TrackCorner,
    val displayIndex: Int,
    val mappedPercent: Float,
    val relativePosition: String
)
