package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.Session
import com.kartingtracker.domain.corner.CornerCoachingAnalyzer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerCoachingAnalyzerTest {

    private val analyzer = CornerCoachingAnalyzer()

    @Test
    fun analyze_returnsEmpty_whenNotEnoughUsableLaps() {
        val session = Session(
            id = 1L,
            trackName = "Test Track",
            startTimeEpochMs = 1L,
            endTimeEpochMs = 2L,
            startTimestampNs = 1L,
            endTimestampNs = 2L,
            samples = emptyList(),
            laps = listOf(
                buildLap(id = 1, confidence = 0.92f),
                buildLap(id = 2, confidence = 0.45f)
            )
        )

        val result = analyzer.analyze(session, trackLayout = null)
        assertTrue(result.insights.isEmpty())
        assertTrue(result.summary == null)
    }

    @Test
    fun analyze_returnsCornerSummary_forUsableSession() {
        val session = Session(
            id = 2L,
            trackName = "Test Track",
            startTimeEpochMs = 1L,
            endTimeEpochMs = 2L,
            startTimestampNs = 1L,
            endTimestampNs = 2L,
            samples = emptyList(),
            laps = listOf(
                buildLap(id = 1, brakeShift = 0f, exitPenalty = 0f),
                buildLap(id = 2, brakeShift = 0.5f, exitPenalty = -0.05f),
                buildLap(id = 3, brakeShift = 3.2f, exitPenalty = -0.22f)
            )
        )

        val result = analyzer.analyze(session, trackLayout = null)

        assertFalse(result.insights.isEmpty())
        assertNotNull(result.summary)
        assertTrue((result.summary?.topActions?.size ?: 0) <= 3)
    }

    private fun buildLap(
        id: Int,
        confidence: Float = 0.9f,
        brakeShift: Float = 0f,
        exitPenalty: Float = 0f
    ): Lap {
        val pointCount = 251
        val samples = (0 until pointCount).map { index ->
            val p = index.toFloat() / (pointCount - 1).toFloat()
            val cornerWave = cornerWave(p)
            val braking = brakingWave(p, brakeShift)
            SensorSample(
                timestampNs = index * 40_000_000L,
                accelX = 0f,
                accelY = 0f,
                accelZ = 0f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = cornerWave,
                longitudinalAccel = braking + if (p > 0.72f) (0.85f + exitPenalty) else 0.1f,
                lateralAccel = cornerWave,
                totalAcceleration = kotlin.math.abs(braking) + kotlin.math.abs(cornerWave),
                yawRateAbs = kotlin.math.abs(cornerWave)
            )
        }
        return Lap(
            id = id,
            samples = samples,
            lapTimeMs = (24_500 + (brakeShift * 120f).toLong()),
            startTimestampNs = samples.first().timestampNs,
            endTimestampNs = samples.last().timestampNs,
            brakingPeakIndices = listOf(48, 120, 195),
            corneringPeakIndices = listOf(55, 128, 202),
            sectorBoundaries = listOf(33, 66),
            sectorTimesMs = listOf(8_200L, 8_100L, 8_200L),
            confidenceScore = confidence,
            lapPhase = LapPhase.NORMAL,
            isDisturbed = false
        )
    }

    private fun cornerWave(progress: Float): Float {
        val peak1 = gaussian(progress, 0.22f, 0.04f) * 1.8f
        val peak2 = gaussian(progress, 0.51f, 0.05f) * 1.6f
        val peak3 = gaussian(progress, 0.80f, 0.04f) * 1.9f
        return peak1 + peak2 + peak3
    }

    private fun brakingWave(progress: Float, brakeShiftPercent: Float): Float {
        val shift = brakeShiftPercent / 100f
        val z1 = -gaussian(progress, 0.18f + shift, 0.03f) * 1.5f
        val z2 = -gaussian(progress, 0.47f + shift, 0.03f) * 1.6f
        val z3 = -gaussian(progress, 0.76f + shift, 0.03f) * 1.55f
        return z1 + z2 + z3
    }

    private fun gaussian(x: Float, center: Float, width: Float): Float {
        val d = (x - center) / width
        return kotlin.math.exp(-(d * d))
    }
}
