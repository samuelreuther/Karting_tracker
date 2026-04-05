package com.kartingtracker.domain

import com.kartingtracker.data.AnalysisValidity
import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapDetectionDebugInfo
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionValidityEvaluatorTest {
    @Test
    fun marksClearlyNonDrivingRecordingAsInvalid() {
        val session = buildSession(
            samples = List(600) { index ->
                SensorSample(
                    timestampNs = index * 100_000_000L,
                    accelX = 0.01f,
                    accelY = 0.02f,
                    accelZ = 9.8f,
                    gyroX = 0f,
                    gyroY = 0f,
                    gyroZ = 0f,
                    longitudinalAccel = 0.05f,
                    lateralAccel = 0.04f,
                    totalAcceleration = 0.07f,
                    yawRateAbs = 0.02f
                )
            },
            laps = listOf(
                Lap(
                    id = 1,
                    startTimestampNs = 0L,
                    endTimestampNs = 20_000_000_000L,
                    lapTimeMs = 20_000L,
                    samples = emptyList(),
                    lapPhase = LapPhase.INTERRUPTED,
                    confidenceScore = 0.3f,
                    isDisturbed = true
                )
            )
        )
        val debug = LapDetectionDebugInfo(
            boundaryCandidateCount = 0,
            candidateSegmentCount = 0,
            fallbackToSingleLap = true,
            lowActivityRatio = 0.9f
        )

        val result = SessionValidityEvaluator.evaluate(session, session.laps, debug)
        assertEquals(AnalysisValidity.INVALID_NON_DRIVING, result.validity)
        assertTrue(result.diagnostics.isNotEmpty())
    }

    @Test
    fun keepsPlausibleDrivingSessionValid() {
        val session = buildSession(
            samples = List(1000) { index ->
                val accel = if (index % 10 < 3) 2.2f else 0.8f
                SensorSample(
                    timestampNs = index * 100_000_000L,
                    accelX = accel,
                    accelY = accel / 2f,
                    accelZ = 9.2f,
                    gyroX = 0.4f,
                    gyroY = 0.5f,
                    gyroZ = 0.6f,
                    longitudinalAccel = accel,
                    lateralAccel = accel / 2f,
                    totalAcceleration = accel,
                    yawRateAbs = 0.9f
                )
            },
            laps = List(3) { lapIndex ->
                Lap(
                    id = lapIndex + 1,
                    startTimestampNs = lapIndex * 30_000_000_000L,
                    endTimestampNs = (lapIndex + 1) * 30_000_000_000L,
                    lapTimeMs = 30_000L,
                    samples = emptyList(),
                    lapPhase = LapPhase.NORMAL,
                    confidenceScore = 0.82f,
                    isDisturbed = false,
                    brakingPeakIndices = listOf(1, 2, 3),
                    corneringPeakIndices = listOf(4, 5, 6)
                )
            }
        )
        val debug = LapDetectionDebugInfo(
            boundaryCandidateCount = 8,
            candidateSegmentCount = 6,
            lowActivityRatio = 0.2f
        )

        val result = SessionValidityEvaluator.evaluate(session, session.laps, debug)
        assertEquals(AnalysisValidity.VALID, result.validity)
    }

    private fun buildSession(samples: List<SensorSample>, laps: List<Lap>): Session {
        return Session(
            id = 1L,
            trackName = "Test Track",
            startTimeEpochMs = 0L,
            endTimeEpochMs = 100_000L,
            startTimestampNs = samples.firstOrNull()?.timestampNs ?: 0L,
            endTimestampNs = samples.lastOrNull()?.timestampNs ?: 0L,
            samples = samples,
            laps = laps
        )
    }
}
