package com.kartingtracker.service

import com.kartingtracker.data.RecordingHealth

object RecorderWatchdogEvaluator {
    fun evaluate(
        isRecorderActive: Boolean,
        health: RecordingHealth,
        nowEpochMs: Long,
        stallTimeoutMs: Long
    ): String? {
        if (!isRecorderActive) return null
        if (health.lastSensorSampleAtEpochMs > 0L) {
            val sensorAgeMs = nowEpochMs - health.lastSensorSampleAtEpochMs
            if (sensorAgeMs > stallTimeoutMs) {
                return "Watchdog stop: recorder stalled for ${sensorAgeMs}ms"
            }
            return null
        }
        if (health.recordingEnteredAtEpochMs > 0L) {
            val sinceRecordingMs = nowEpochMs - health.recordingEnteredAtEpochMs
            if (sinceRecordingMs > stallTimeoutMs) {
                return "Watchdog stop: no first sample ${sinceRecordingMs}ms after RECORDING"
            }
        }
        return null
    }
}
