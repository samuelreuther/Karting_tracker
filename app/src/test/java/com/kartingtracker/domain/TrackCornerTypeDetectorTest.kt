package com.kartingtracker.domain

import com.kartingtracker.data.TrackCornerType
import com.kartingtracker.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class TrackCornerTypeDetectorTest {

    private val detector = TrackCornerTypeDetector()

    @Test
    fun `classifies tight, medium and fast corners from curvature peaks`() {
        val centerline = buildOvalCenterline(
            radiusX = 1.0,
            radiusY = 0.65,
            spikes = listOf(
                Spike(angleRad = PI * 0.15, strength = 0.60),
                Spike(angleRad = PI * 0.75, strength = 0.32),
                Spike(angleRad = PI * 1.55, strength = 0.16)
            )
        )

        val detected = detector.detectFromCenterline(centerline)

        assertTrue(detected.any { corner -> corner.type == TrackCornerType.TIGHT })
        assertTrue(detected.any { corner -> corner.type == TrackCornerType.MEDIUM })
        assertTrue(detected.any { corner -> corner.type == TrackCornerType.FAST })
    }

    @Test
    fun `returns deterministic corner list for equal input`() {
        val centerline = buildOvalCenterline(radiusX = 0.9, radiusY = 0.7, spikes = listOf(Spike(PI * 0.45, 0.4)))

        val first = detector.detectFromCenterline(centerline)
        val second = detector.detectFromCenterline(centerline)

        assertEquals(first, second)
    }

    private fun buildOvalCenterline(
        radiusX: Double,
        radiusY: Double,
        spikes: List<Spike>,
        samples: Int = 360
    ): List<TrackPoint> {
        return (0 until samples).map { index ->
            val angle = (2.0 * PI * index.toDouble()) / samples.toDouble()
            val radialFactor = 1.0 + spikes.sumOf { spike ->
                val distance = wrappedAngularDistance(angle, spike.angleRad)
                spike.strength * kotlin.math.exp(-distance * distance / 0.012)
            }
            TrackPoint(
                x = (0.5 + cos(angle) * radiusX * radialFactor * 0.35).toFloat(),
                y = (0.5 + sin(angle) * radiusY * radialFactor * 0.35).toFloat()
            )
        }
    }

    private fun wrappedAngularDistance(first: Double, second: Double): Double {
        val delta = kotlin.math.abs(first - second)
        return kotlin.math.min(delta, (2.0 * PI) - delta)
    }

    private data class Spike(
        val angleRad: Double,
        val strength: Double
    )
}
