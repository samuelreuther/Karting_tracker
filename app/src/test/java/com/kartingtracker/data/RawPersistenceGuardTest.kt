package com.kartingtracker.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPersistenceGuardTest {
    @Test
    fun autosaveRejectsZeroSampleSnapshots() {
        assertFalse(RawPersistenceGuard.shouldPersistAutosave(0))
        assertTrue(RawPersistenceGuard.shouldPersistAutosave(1))
    }

    @Test
    fun failedProcessingSessionCanStillFinalizeFromRaw() {
        val raw = Session(
            id = 7L,
            trackName = "Test",
            startTimeEpochMs = 1_000L,
            endTimeEpochMs = 2_000L,
            startTimestampNs = 1L,
            endTimestampNs = 2L,
            samples = emptyList(),
            laps = emptyList(),
            processingState = Session.PROCESSING_STATE_FAILED
        )
        assertTrue(RawPersistenceGuard.rawCanFinalize(raw))
    }
}
