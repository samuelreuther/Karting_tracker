package com.kartingtracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {
    @Test
    fun validReliabilityPathTransitionsToCompleted() {
        val machine = RecordingStateMachine()
        assertTrue(machine.transitionTo(RecordingState.PRESTART_COUNTDOWN))
        assertTrue(machine.transitionTo(RecordingState.CALIBRATING))
        assertTrue(machine.transitionTo(RecordingState.RECORDING))
        assertTrue(machine.transitionTo(RecordingState.STOPPING))
        assertTrue(machine.transitionTo(RecordingState.SAVING_RAW))
        assertTrue(machine.transitionTo(RecordingState.PROCESSING))
        assertTrue(machine.transitionTo(RecordingState.COMPLETED))
        assertEquals(RecordingState.COMPLETED, machine.state)
    }

    @Test
    fun impossibleTransitionIsRejected() {
        val machine = RecordingStateMachine()
        assertFalse(machine.transitionTo(RecordingState.PROCESSING))
        assertEquals(RecordingState.IDLE, machine.state)
    }
}
