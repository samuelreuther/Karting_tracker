package com.kartingtracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.domain.DrivingInsightsGenerator
import com.kartingtracker.domain.LapNormalizer
import com.kartingtracker.sensor.RecorderPhase
import com.kartingtracker.sensor.SensorRecorder
import com.kartingtracker.ui.common.formatLapTime
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

class SessionViewModel(
    application: Application,
    private val sessionRepository: SessionRepository,
    val sensorRecorder: SensorRecorder
) : AndroidViewModel(application) {

    private val selectedLapAIndex = MutableStateFlow(0)
    private val selectedLapBIndex = MutableStateFlow(1)

    val laps: StateFlow<List<Lap>> = sessionRepository.latestSession
        .map { session -> session?.laps.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<SessionUiState> = combine(
        sensorRecorder.recorderPhase,
        sessionRepository.isRecording,
        sessionRepository.sampleCount,
        sessionRepository.lastSample,
        sessionRepository.latestSession
    ) { recorderPhase, isRecording, sampleCount, lastSample, session ->
        SessionUiState(
            isRecording = recorderPhase == RecorderPhase.RECORDING || isRecording,
            isCalibrating = recorderPhase == RecorderPhase.CALIBRATING,
            hasRequiredSensors = sensorRecorder.hasRequiredSensors,
            sampleCount = sampleCount,
            liveLongitudinalAccel = lastSample?.longitudinalAccel ?: 0f,
            liveLateralAccel = lastSample?.lateralAccel ?: 0f,
            lapCount = session?.laps?.size ?: 0,
            estimatedLapTimeMs = session?.estimatedLapTimeMs,
            statusLabel = when {
                !sensorRecorder.hasRequiredSensors -> "Missing accelerometer or gyroscope"
                recorderPhase == RecorderPhase.CALIBRATING -> "Calibrating - keep the kart still"
                recorderPhase == RecorderPhase.RECORDING || isRecording -> "Recording"
                session == null -> "Ready"
                session.laps.isEmpty() -> "Stopped"
                else -> "Stopped - ${session.laps.size} laps detected"
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

    val comparisonUiState: StateFlow<ComparisonUiState> = combine(
        laps,
        selectedLapAIndex,
        selectedLapBIndex
    ) { laps, selectedA, selectedB ->
        if (laps.isEmpty()) {
            return@combine ComparisonUiState()
        }

        val safeA = selectedA.coerceIn(0, laps.lastIndex)
        val safeB = selectedB.coerceIn(0, laps.lastIndex)
        val lapA = laps[safeA]
        val lapB = laps[safeB]
        val normalizedA = LapNormalizer.normalize(lapA)
        val normalizedB = LapNormalizer.normalize(lapB)
        val deltaLongitudinal = createDeltaEntries(normalizedA.longitudinalEntries, normalizedB.longitudinalEntries)
        val deltaLateral = createDeltaEntries(normalizedA.lateralEntries, normalizedB.lateralEntries)
        val deltaMs = lapA.lapTimeMs - lapB.lapTimeMs
        val fasterLabel = when {
            deltaMs == 0L -> "Both laps have the same lap time."
            deltaMs > 0L -> "Lap B is ${formatLapTime(abs(deltaMs))} quicker."
            else -> "Lap A is ${formatLapTime(abs(deltaMs))} quicker."
        }

        ComparisonUiState(
            lapLabels = laps.mapIndexed { index, lap -> "Lap ${index + 1} - ${formatLapTime(lap.lapTimeMs)}" },
            selectedLapAIndex = safeA,
            selectedLapBIndex = safeB,
            lapATimeLabel = "Lap A: ${formatLapTime(lapA.lapTimeMs)}",
            lapBTimeLabel = "Lap B: ${formatLapTime(lapB.lapTimeMs)}",
            longitudinalLapA = normalizedA.longitudinalEntries,
            longitudinalLapB = normalizedB.longitudinalEntries,
            longitudinalBrakeMarkersA = normalizedA.brakingMarkerEntries,
            longitudinalBrakeMarkersB = normalizedB.brakingMarkerEntries,
            lateralLapA = normalizedA.lateralEntries,
            lateralLapB = normalizedB.lateralEntries,
            lateralCornerMarkersA = normalizedA.corneringMarkerEntries,
            lateralCornerMarkersB = normalizedB.corneringMarkerEntries,
            deltaLongitudinal = deltaLongitudinal,
            deltaLateral = deltaLateral,
            insights = DrivingInsightsGenerator.generate(normalizedA, normalizedB),
            summaryLabel = buildString {
                append(fasterLabel)
                append(" ")
                append("Braking peaks: ${lapA.brakingPeakIndices.size} vs ${lapB.brakingPeakIndices.size}. ")
                append("Cornering peaks: ${lapA.corneringPeakIndices.size} vs ${lapB.corneringPeakIndices.size}.")
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComparisonUiState())

    fun startRecording() {
        sensorRecorder.startRecording()
    }

    fun stopRecording() {
        sensorRecorder.stopRecording()
    }

    fun selectLapA(index: Int) {
        selectedLapAIndex.value = index
    }

    fun selectLapB(index: Int) {
        selectedLapBIndex.value = index
    }

    private fun createDeltaEntries(entriesA: List<Entry>, entriesB: List<Entry>): List<Entry> {
        val size = minOf(entriesA.size, entriesB.size)
        return List(size) { index ->
            Entry(entriesA[index].x, entriesA[index].y - entriesB[index].y)
        }
    }
}
