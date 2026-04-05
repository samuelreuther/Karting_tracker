package com.kartingtracker.ui

import com.github.mikephil.charting.data.Entry
import com.kartingtracker.data.CoachingInsight
import com.kartingtracker.data.CurveDefinition
import com.kartingtracker.data.Session
import com.kartingtracker.data.Track
import com.kartingtracker.domain.DetectedCorner

enum class AnalysisMode {
    COMPARISON,
    TIME_LOSS,
    COACHING
}

data class SessionUiState(
    val isRecording: Boolean = false,
    val isPreparing: Boolean = false,
    val isCalibrating: Boolean = false,
    val isStopping: Boolean = false,
    val hasRequiredSensors: Boolean = true,
    val sampleCount: Int = 0,
    val liveLongitudinalAccel: Float = 0f,
    val liveLateralAccel: Float = 0f,
    val lapCount: Int = 0,
    val estimatedLapTimeMs: Long? = null,
    val trackOptions: List<String> = emptyList(),
    val availableTracks: List<Track> = emptyList(),
    val selectedTrackName: String = "",
    val hasValidSelectedTrack: Boolean = false,
    val usingTrackProfile: Boolean = false,
    val trackProfileSummary: String = "No learned track profile yet.",
    val canLoadLastSession: Boolean = false,
    val recordingTimerLabel: String = "00:00",
    val statusLabel: String = "Idle",
    val stateHeadline: String = "Ready to record",
    val stateDetail: String = "",
    val preStartCountdownLabel: String = "",
    val showCountdown: Boolean = false,
    val canOpenAnalysis: Boolean = false,
    val invalidSessionMessage: String = "",
    val lastSessionSummary: LastSessionSummaryUiState = LastSessionSummaryUiState(),
    val compareSelection: CompareSelectionUiState = CompareSelectionUiState()
)

data class CompareSelectionUiState(
    val sessionOptions: List<SessionOptionUiState> = emptyList(),
    val selectedSessionAId: Long? = null,
    val selectedSessionBId: Long? = null,
    val lapOptionsA: List<LapOptionUiState> = emptyList(),
    val lapOptionsB: List<LapOptionUiState> = emptyList(),
    val selectedLapAIndex: Int = 0,
    val selectedLapBIndex: Int = 0,
    val emptyStateMessage: String = "",
    val canOpenComparison: Boolean = false
)

data class SessionOptionUiState(
    val id: Long,
    val label: String
)

data class LapOptionUiState(
    val index: Int,
    val label: String
)

data class LastSessionSummaryUiState(
    val title: String = "Last-session coaching summary",
    val headline: String = "Select a track to see your most useful takeaways instantly.",
    val quality: String = "No session loaded yet",
    val biggestLoss: String = "No time-loss segment available",
    val coachingHint: String = "Record a run to unlock coaching recommendations",
    val topCornerActions: List<String> = emptyList(),
    val strongestCorner: String = "Strongest corner unavailable",
    val biggestCornerOpportunity: String = "Biggest corner opportunity unavailable",
    val actionLabel: String = "Open deep analysis",
    val canOpenComparison: Boolean = false
)

data class ComparisonUiState(
    val lapLabelsA: List<String> = emptyList(),
    val lapLabelsB: List<String> = emptyList(),
    val selectedLapAIndex: Int = 0,
    val selectedLapBIndex: Int = 0,
    val lapATimeLabel: String = "",
    val lapBTimeLabel: String = "",
    val longitudinalLapA: List<Entry> = emptyList(),
    val longitudinalLapB: List<Entry> = emptyList(),
    val longitudinalBrakeMarkersA: List<Entry> = emptyList(),
    val longitudinalBrakeMarkersB: List<Entry> = emptyList(),
    val lateralLapA: List<Entry> = emptyList(),
    val lateralLapB: List<Entry> = emptyList(),
    val lateralCornerMarkersA: List<Entry> = emptyList(),
    val lateralCornerMarkersB: List<Entry> = emptyList(),
    val timeLossEntries: List<Entry> = emptyList(),
    val segmentMarkerEntries: List<Entry> = emptyList(),
    val sectorComparisonLines: List<String> = emptyList(),
    val idealLapLabel: String = "",
    val theoreticalBestLabel: String = "",
    val idealLapSectorLines: List<String> = emptyList(),
    val insights: List<String> = emptyList(),
    val topTimeLossLines: List<String> = emptyList(),
    val cornerCoachingLines: List<String> = emptyList(),
    val mapImagePath: String? = null,
    val projectedCurves: List<ProjectedCurveUiState> = emptyList(),
    val trackInsightMarkers: List<TrackInsightMarker> = emptyList(),
    val fallbackCurveLines: List<String> = emptyList(),
    val summaryLabel: String = "Record a session to compare laps.",
    val isReliableForAnalysis: Boolean = true,
    val reliabilityMessage: String = "",
    val recommendedNextStep: String = ""
)

data class SessionListUiState(
    val filterOptions: List<String> = emptyList(),
    val selectedFilter: String = SessionViewModel.ALL_TRACKS_FILTER,
    val sessions: List<SessionListItemUiState> = emptyList()
)

data class SessionListItemUiState(
    val session: Session,
    val sampleCount: Int,
    val fileSizeBytes: Long
)

data class TrackMapUiState(
    val detectedCorners: List<DetectedCorner> = emptyList(),
    val detectedCurves: List<CurveDefinition> = emptyList(),
    val highlightedMarkerLabels: Set<String> = emptySet(),
    val fallbackCornerLines: List<String> = emptyList()
)

data class ProjectedCurveUiState(
    val label: String,
    val x: Float,
    val y: Float,
    val intensity: Float,
    val deltaSeconds: Float = 0f
)

data class TrackInsightMarker(
    val x: Float,
    val y: Float,
    val severity: Float,
    val label: String,
    val insight: CoachingInsight
)
