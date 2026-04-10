package com.kartingtracker.service

import com.kartingtracker.data.RecordingHealth
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderWatchdogEvaluatorTest {
    @Test
    fun detectsNoFirstSampleTimeoutAfterRecording() {
        val health = RecordingHealth(recordingEnteredAtEpochMs = 1_000L, lastSensorSampleAtEpochMs = 0L)
        val reason = RecorderWatchdogEvaluator.evaluate(
            isRecorderActive = true,
            health = health,
            nowEpochMs = 20_000L,
            stallTimeoutMs = 15_000L
        )
        assertTrue(reason?.contains("no first sample") == true)
    }

    @Test
    fun detectsStalledSensorStreamAfterSamples() {
        val health = RecordingHealth(recordingEnteredAtEpochMs = 1_000L, lastSensorSampleAtEpochMs = 2_000L)
        val reason = RecorderWatchdogEvaluator.evaluate(
            isRecorderActive = true,
            health = health,
            nowEpochMs = 25_000L,
            stallTimeoutMs = 15_000L
        )
        assertTrue(reason?.contains("stalled") == true)
    }

    @Test
    fun doesNotKillHealthySession() {
        val health = RecordingHealth(recordingEnteredAtEpochMs = 1_000L, lastSensorSampleAtEpochMs = 10_000L)
        val reason = RecorderWatchdogEvaluator.evaluate(
            isRecorderActive = true,
            health = health,
            nowEpochMs = 20_000L,
            stallTimeoutMs = 15_000L
        )
        assertNull(reason)
    }
}
