package com.kartingtracker.data

object RawPersistenceGuard {
    fun shouldPersistAutosave(sampleCount: Int): Boolean = sampleCount > 0

    fun rawCanFinalize(session: Session): Boolean = session.samples.isNotEmpty() || session.processingState == Session.PROCESSING_STATE_FAILED
}
