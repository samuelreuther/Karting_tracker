package com.kartingtracker.data

class RecordingStateMachine(initialState: RecordingState = RecordingState.IDLE) {
    private var _state: RecordingState = initialState
    val state: RecordingState get() = _state

    fun transitionTo(next: RecordingState): Boolean {
        if (next == _state) return true
        val allowed = allowedTransitions[_state].orEmpty()
        if (next !in allowed) {
            return false
        }
        _state = next
        return true
    }

    fun forceSet(next: RecordingState) {
        _state = next
    }

    companion object {
        private val allowedTransitions = mapOf(
            RecordingState.IDLE to setOf(RecordingState.PRESTART_COUNTDOWN, RecordingState.RECORDING, RecordingState.ABORTED),
            RecordingState.PRESTART_COUNTDOWN to setOf(RecordingState.CALIBRATING, RecordingState.ABORTED, RecordingState.FAILED),
            RecordingState.CALIBRATING to setOf(RecordingState.RECORDING, RecordingState.ABORTED, RecordingState.FAILED),
            RecordingState.RECORDING to setOf(RecordingState.STOPPING, RecordingState.FAILED),
            RecordingState.STOPPING to setOf(RecordingState.SAVING_RAW, RecordingState.FAILED),
            RecordingState.SAVING_RAW to setOf(RecordingState.RAW_SAVED, RecordingState.FAILED),
            RecordingState.RAW_SAVED to setOf(RecordingState.PROCESSING, RecordingState.FAILED),
            RecordingState.PROCESSING to setOf(RecordingState.COMPLETED, RecordingState.FAILED),
            RecordingState.COMPLETED to setOf(RecordingState.IDLE, RecordingState.PRESTART_COUNTDOWN, RecordingState.RECORDING),
            RecordingState.FAILED to setOf(RecordingState.IDLE, RecordingState.PRESTART_COUNTDOWN),
            RecordingState.ABORTED to setOf(RecordingState.IDLE, RecordingState.PRESTART_COUNTDOWN)
        )
    }
}
