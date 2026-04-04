package com.kartingtracker.data

data class Session(
    val id: Long,
    val trackName: String,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val startTimestampNs: Long,
    val endTimestampNs: Long,
    val samples: List<SensorSample>,
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null,
    val insights: List<String> = emptyList(),
    val coachingInsights: List<CoachingInsight> = emptyList(),
    val theoreticalBestLapTimeMs: Long? = null,
    val topTimeLossSegments: List<TimeLossSegment> = emptyList(),
    val segmentMarkers: List<SegmentMarker> = emptyList(),
    val cornerCoachingInsights: List<CornerCoachingInsight> = emptyList(),
    val cornerCoachingSummary: CornerCoachingSummary? = null,
    val quality: SessionQuality? = null,
    val processingVersion: Int = DEFAULT_PROCESSING_VERSION,
    val isPartial: Boolean = false,
    val processingState: String = PROCESSING_STATE_FINAL,
    val processingFailureReason: String? = null,
    val isReprocessable: Boolean = true,
    val analysisWarnings: List<String> = emptyList(),
    val lapDetectionDebugInfo: LapDetectionDebugInfo? = null
) {
    companion object {
        const val DEFAULT_PROCESSING_VERSION = 1
        const val PROCESSING_STATE_PENDING = "raw_saved_processing_pending"
        const val PROCESSING_STATE_FAILED = "processing_failed_reprocessable"
        const val PROCESSING_STATE_FINAL = "final_processed"
    }
}
