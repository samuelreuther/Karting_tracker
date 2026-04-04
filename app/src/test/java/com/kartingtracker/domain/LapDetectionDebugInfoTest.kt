package com.kartingtracker.domain

import com.kartingtracker.data.SensorSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LapDetectionDebugInfoTest {
    private val detector = LapDetector()

    @Test
    fun detectProvidesFallbackDebugInfoForTinySessions() {
        val samples = listOf(
            SensorSample(1L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            SensorSample(2L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        )

        val result = detector.detect(samples)

        assertTrue(result.debugInfo.fallbackToSingleLap)
        assertFalse(result.debugInfo.fallbackReasons.isEmpty())
        assertTrue(result.laps.size == 1)
    }
}
