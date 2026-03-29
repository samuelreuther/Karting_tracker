package com.kartingtracker.ui

import com.github.mikephil.charting.data.Entry
import com.kartingtracker.data.Session

data class SessionUiState(
    val isRecording: Boolean = false,
    val isCalibrating: Boolean = false,
    val hasRequiredSensors: Boolean = true,
    val sampleCount: Int = 0,
    val liveLongitudinalAccel: Float = 0f,
    val liveLateralAccel: Float = 0f,
    val lapCount: Int = 0,
    val estimatedLapTimeMs: Long? = null,
    val trackOptions: List<String> = emptyList(),
    val selectedTrackName: String = "",
    val hasValidSelectedTrack: Boolean = false,
    val usingTrackProfile: Boolean = false,
    val trackProfileSummary: String = "No learned track profile yet.",
    val canLoadLastSession: Boolean = false,
    val statusLabel: String = "Idle"
)

data class ComparisonUiState(
    val lapLabels: List<String> = emptyList(),
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
    val sectorComparisonLines: List<String> = emptyList(),
    val idealLapLabel: String = "",
    val idealLapSectorLines: List<String> = emptyList(),
    val insights: List<String> = emptyList(),
    val sessionInsights: List<String> = emptyList(),
    val summaryLabel: String = "Record a session to compare laps."
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
