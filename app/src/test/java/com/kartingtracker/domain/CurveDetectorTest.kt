package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveDetectorTest {
    private val detector = CurveDetector()

    @Test
    fun detectCurves_findsStablePeaksInNormalizedLap() {
        val pointCount = 251
        val samples = (0 until pointCount).map { index ->
            val progress = index.toFloat() / (pointCount - 1).toFloat()
            val yawPeakA = gaussian(progress, 0.22f, 0.03f) * 1.9f
            val yawPeakB = gaussian(progress, 0.63f, 0.04f) * 1.7f
            val totalAcceleration = 0.5f + (yawPeakA * 0.6f) + (yawPeakB * 0.55f)
            SensorSample(
                timestampNs = index * 10_000_000L,
                accelX = 0f,
                accelY = 0f,
                accelZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = if (progress < 0.45f) 0.3f else -0.3f,
                longitudinalAccel = totalAcceleration,
                lateralAccel = yawPeakA + yawPeakB,
                totalAcceleration = totalAcceleration,
                yawRateAbs = yawPeakA + yawPeakB
            )
        }
        val lap = Lap(
            id = 1,
            samples = samples,
            lapTimeMs = 42_000L,
            startTimestampNs = samples.first().timestampNs,
            endTimestampNs = samples.last().timestampNs
        )

        val curves = detector.detectCurves(lap)

        assertEquals(2, curves.size)
        assertTrue(curves[0].peakPercent in 15f..30f)
        assertTrue(curves[1].peakPercent in 56f..70f)
        assertTrue(curves.all { curve -> curve.intensity > 0.2f })
    }

    private fun gaussian(progress: Float, center: Float, width: Float): Float {
        val delta = (progress - center) / width
        return kotlin.math.exp((-0.5f * delta * delta).toDouble()).toFloat()
    }
}
