package com.kartingtracker.data

enum class RecordingState {
    IDLE,
    PRESTART_COUNTDOWN,
    CALIBRATING,
    RECORDING,
    STOPPING,
    SAVING_RAW,
    PROCESSING,
    COMPLETED,
    FAILED,
    ABORTED
}

data class RecordingHealth(
    val recordingEnteredAtEpochMs: Long = 0L,
    val serviceAliveAtEpochMs: Long = 0L,
    val lastSensorSampleAtEpochMs: Long = 0L,
    val lastAutosaveAtEpochMs: Long = 0L,
    val lastNotificationUpdateAtEpochMs: Long = 0L,
    val samplesReceived: Int = 0,
    val wakeLockHeld: Boolean = false,
    val watchdogActive: Boolean = false,
    val autosaveStatus: String = "",
    val rawFilePath: String = "",
    val rawPersistenceStatus: String = "",
    val lastFailureReason: String = "",
    val watchdogStopReason: String = "",
    val lastTransitionReason: String = ""
)
