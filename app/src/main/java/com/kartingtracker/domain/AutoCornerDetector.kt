package com.kartingtracker.domain

import com.kartingtracker.data.Lap

data class DetectedCorner(
    val startPercent: Float,
    val endPercent: Float,
    val peakPercent: Float,
    val strength: Float
)

class AutoCornerDetector {
    private val curveDetector = CurveDetector()

    fun detectCorners(lap: Lap): List<DetectedCorner> {
        return curveDetector.detectCurves(lap, DETECTION_POINT_COUNT)
            .map { curve ->
                DetectedCorner(
                    startPercent = curve.startPercent,
                    endPercent = curve.endPercent,
                    peakPercent = curve.peakPercent,
                    strength = curve.intensity
                )
            }
            .sortedBy(DetectedCorner::peakPercent)
    }
}

private const val DETECTION_POINT_COUNT = 101
