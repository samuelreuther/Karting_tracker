package com.kartingtracker.domain

import android.graphics.PointF
import com.kartingtracker.data.CurveDefinition
import com.kartingtracker.data.Lap
import com.kartingtracker.data.Track
import com.kartingtracker.data.TrackCorner
import com.kartingtracker.data.TrackDirection
import com.kartingtracker.data.TrackLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin



data class ProjectedInsight(
    val x: Float,
    val y: Float,
    val severity: Float,
    val label: String,
    val segmentIndex: Int
)

data class ProjectedCurve(
    val index: Int,
    val label: String,
    val position: PointF,
    val intensity: Float,
    val peakPercent: Float
)

class MapOverlayProjector {
    fun projectCurves(
        track: Track?,
        trackLayout: TrackLayout?,
        referenceLap: Lap?,
        curves: List<CurveDefinition>,
        autoDetectedStart: AutoDetectedStart? = null
    ): List<ProjectedCurve> {
        if (curves.isEmpty()) {
            return emptyList()
        }

        val projectedPoints = when {
            !trackLayout?.corners.isNullOrEmpty() -> projectUsingTrackLayoutCorners(trackLayout!!.corners, curves)
            track?.startPoint != null && referenceLap != null -> {
                projectUsingIntegratedPath(
                    startPoint = track.startPoint,
                    startDirectionDeg = track.startDirectionDeg ?: autoDetectedStart.toDerivedHeading(track.startPoint),
                    trackDirection = trackLayout?.direction ?: autoDetectedStart?.trackDirection ?: TrackDirection.CLOCKWISE,
                    lap = referenceLap,
                    curves = curves
                )
            }

            else -> projectAlongPerimeter(curves, autoDetectedStart?.trackDirection ?: trackLayout?.direction)
        }

        return curves.zip(projectedPoints).map { (curve, point) ->
            ProjectedCurve(
                index = curve.index,
                label = "T${curve.index}",
                position = point,
                intensity = curve.intensity,
                peakPercent = curve.peakPercent
            )
        }
    }


    fun projectInsights(
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>,
        segmentMarkers: List<com.kartingtracker.data.SegmentMarker>
    ): List<ProjectedInsight> {
        if (segmentMarkers.isEmpty()) return emptyList()

        val references = TrackLayoutMapper.buildCornerReferences(detectedCorners, trackLayout)
        return segmentMarkers.map { marker ->
            val reference = references.minByOrNull { ref ->
                kotlin.math.abs((ref.mappedPercent * 100f) - marker.positionPercent)
            }
            val point = reference?.corner?.point
            val x = point?.x ?: ((marker.positionPercent / 100f).coerceIn(0.1f, 0.9f))
            val y = point?.y ?: 0.15f
            ProjectedInsight(
                x = x,
                y = y,
                severity = marker.severity,
                label = marker.label,
                segmentIndex = reference?.displayIndex ?: marker.positionPercent.toInt()
            )
        }
    }

    private fun projectUsingTrackLayoutCorners(
        corners: List<TrackCorner>,
        curves: List<CurveDefinition>
    ): List<PointF> {
        if (corners.isEmpty()) {
            return curves.map { PointF(0.5f, 0.5f) }
        }

        return curves.map { curve ->
            val scaledIndex = ((curve.peakPercent / 100f) * corners.lastIndex.toFloat())
                .toInt()
                .coerceIn(0, corners.lastIndex)
            val point = corners[scaledIndex].point
            PointF(point.x, point.y)
        }
    }

    private fun projectUsingIntegratedPath(
        startPoint: PointF,
        startDirectionDeg: Float?,
        trackDirection: TrackDirection,
        lap: Lap,
        curves: List<CurveDefinition>
    ): List<PointF> {
        val normalizedYawSigned = LapNormalizer.normalizeSignal(lap) { sample -> sample.gyroZ }
            .smooth(windowRadius = 2)
            .normalizeSigned()
        val normalizedAcceleration = LapNormalizer.normalizeSignal(lap) { sample -> sample.totalAcceleration }
            .smooth(windowRadius = 2)
            .normalizeUnit()
        if (normalizedYawSigned.isEmpty() || normalizedAcceleration.size != normalizedYawSigned.size) {
            return projectAlongPerimeter(curves, trackDirection)
        }

        val initialHeadingDeg = startDirectionDeg ?: 0f
        var headingRadians = Math.toRadians(initialHeadingDeg.toDouble()).toFloat()
        val rawPath = mutableListOf(PointF(0f, 0f))
        var x = 0f
        var y = 0f
        val yawDirection = if (trackDirection == TrackDirection.COUNTER_CLOCKWISE) 1f else -1f
        for (index in normalizedYawSigned.indices) {
            headingRadians += normalizedYawSigned[index] * yawDirection * headingGainRadians
            val stepSize = baseStepSize + (normalizedAcceleration[index] * stepGain)
            x += cos(headingRadians) * stepSize
            y -= sin(headingRadians) * stepSize
            rawPath += PointF(x, y)
        }

        val scaledPath = scalePathToBounds(rawPath, startPoint)
        return curves.map { curve ->
            val pathIndex = ((curve.peakPercent / 100f) * scaledPath.lastIndex.toFloat())
                .toInt()
                .coerceIn(0, scaledPath.lastIndex)
            scaledPath[pathIndex]
        }
    }

    private fun scalePathToBounds(path: List<PointF>, startPoint: PointF): List<PointF> {
        val minX = path.minOfOrNull(PointF::x) ?: 0f
        val maxX = path.maxOfOrNull(PointF::x) ?: 0f
        val minY = path.minOfOrNull(PointF::y) ?: 0f
        val maxY = path.maxOfOrNull(PointF::y) ?: 0f
        val positiveX = maxX.coerceAtLeast(0f)
        val negativeX = abs(minX.coerceAtMost(0f))
        val positiveY = maxY.coerceAtLeast(0f)
        val negativeY = abs(minY.coerceAtMost(0f))
        val availableRight = (1f - perimeterPadding) - startPoint.x
        val availableLeft = startPoint.x - perimeterPadding
        val availableBottom = (1f - perimeterPadding) - startPoint.y
        val availableTop = startPoint.y - perimeterPadding
        val horizontalScale = listOf(
            positiveX.takeIf { it > minimumExtent }?.let { extent -> availableRight / extent } ?: 1f,
            negativeX.takeIf { it > minimumExtent }?.let { extent -> availableLeft / extent } ?: 1f
        ).minOrNull() ?: 1f
        val verticalScale = listOf(
            positiveY.takeIf { it > minimumExtent }?.let { extent -> availableBottom / extent } ?: 1f,
            negativeY.takeIf { it > minimumExtent }?.let { extent -> availableTop / extent } ?: 1f
        ).minOrNull() ?: 1f
        val scale = minOf(horizontalScale, verticalScale).coerceIn(minimumScale, maximumScale)

        return path.map { point ->
            PointF(
                (startPoint.x + (point.x * scale)).coerceIn(perimeterPadding, 1f - perimeterPadding),
                (startPoint.y + (point.y * scale)).coerceIn(perimeterPadding, 1f - perimeterPadding)
            )
        }
    }

    private fun projectAlongPerimeter(
        curves: List<CurveDefinition>,
        trackDirection: TrackDirection? = null
    ): List<PointF> {
        return curves.map { curve ->
            val progress = curve.peakPercent / 100f
            val directedProgress = if (trackDirection == TrackDirection.COUNTER_CLOCKWISE) {
                (1f - progress + 1f) % 1f
            } else {
                progress
            }
            rectanglePerimeterPoint(directedProgress)
        }
    }

    private fun rectanglePerimeterPoint(progress: Float): PointF {
        val padding = perimeterPadding
        val width = 1f - (padding * 2f)
        val height = 1f - (padding * 2f)
        val perimeter = (width * 2f) + (height * 2f)
        var distance = (progress.coerceIn(0f, 1f) * perimeter)

        return when {
            distance <= width -> PointF(padding + distance, padding)
            run {
                distance -= width
                distance <= height
            } -> PointF(1f - padding, padding + distance)

            run {
                distance -= height
                distance <= width
            } -> PointF((1f - padding) - distance, 1f - padding)

            else -> {
                distance -= width
                PointF(padding, (1f - padding) - distance)
            }
        }
    }

    private fun List<Float>.smooth(windowRadius: Int): List<Float> {
        if (isEmpty() || windowRadius <= 0) {
            return this
        }

        return indices.map { index ->
            val start = (index - windowRadius).coerceAtLeast(0)
            val end = (index + windowRadius).coerceAtMost(lastIndex)
            subList(start, end + 1).average().toFloat()
        }
    }

    private fun List<Float>.normalizeUnit(): List<Float> {
        if (isEmpty()) {
            return emptyList()
        }
        val minValue = minOrNull() ?: 0f
        val maxValue = maxOrNull() ?: 0f
        val range = (maxValue - minValue).coerceAtLeast(minimumExtent)
        return map { value -> ((value - minValue) / range).coerceIn(0f, 1f) }
    }

    private fun List<Float>.normalizeSigned(): List<Float> {
        if (isEmpty()) {
            return emptyList()
        }
        val maxMagnitude = maxOf(abs(minOrNull() ?: 0f), abs(maxOrNull() ?: 0f)).coerceAtLeast(minimumExtent)
        return map { value -> (value / maxMagnitude).coerceIn(-1f, 1f) }
    }

    private fun AutoDetectedStart?.toDerivedHeading(startPoint: PointF): Float {
        val direction = this?.trackDirection ?: TrackDirection.CLOCKWISE
        val radialAngle = Math.toDegrees(
            atan2((0.5f - startPoint.y).toDouble(), (startPoint.x - 0.5f).toDouble())
        ).toFloat()
        val tangentOffset = if (direction == TrackDirection.COUNTER_CLOCKWISE) 90f else -90f
        return ((radialAngle + tangentOffset) + 360f) % 360f
    }

    companion object {
        private const val headingGainRadians = (PI.toFloat() / 72f)
        private const val baseStepSize = 0.004f
        private const val stepGain = 0.008f
        private const val perimeterPadding = 0.12f
        private const val minimumExtent = 1e-4f
        private const val minimumScale = 0.08f
        private const val maximumScale = 0.35f
    }
}
