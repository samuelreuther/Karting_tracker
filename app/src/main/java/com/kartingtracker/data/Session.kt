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
    val isPartial: Boolean = false
) {
    companion object {
        const val DEFAULT_PROCESSING_VERSION = 1
    }
}
