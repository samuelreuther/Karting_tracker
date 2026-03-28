package com.kartingtracker.ui

import com.github.mikephil.charting.data.Entry

data class SessionUiState(
    val isRecording: Boolean = false,
    val hasRequiredSensors: Boolean = true,
    val sampleCount: Int = 0,
    val liveLongitudinalAccel: Float = 0f,
    val liveLateralAccel: Float = 0f,
    val lapCount: Int = 0,
    val estimatedLapTimeMs: Long? = null,
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
    val lateralLapA: List<Entry> = emptyList(),
    val lateralLapB: List<Entry> = emptyList(),
    val summaryLabel: String = "Record a session to compare laps."
)
