package com.kartingtracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kartingtracker.data.Lap
import com.kartingtracker.data.Session
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
    private val selectedSessionFilter = MutableStateFlow(ALL_TRACKS_FILTER)

    val laps: StateFlow<List<Lap>> = sessionRepository.currentSession
        .map { session -> session?.laps.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<SessionUiState> = combine(
        sensorRecorder.recorderPhase,
        sessionRepository.isRecording,
        sessionRepository.sampleCount,
        sessionRepository.lastSample,
        sessionRepository.latestSession,
        sessionRepository.availableTracks,
        sessionRepository.currentTrackName,
        sessionRepository.storedSessions
    ) { recorderPhase, isRecording, sampleCount, lastSample, session, tracks, currentTrackName, storedSessions ->
        SessionUiState(
            isRecording = recorderPhase == RecorderPhase.RECORDING || isRecording,
            isCalibrating = recorderPhase == RecorderPhase.CALIBRATING,
            hasRequiredSensors = sensorRecorder.hasRequiredSensors,
            sampleCount = sampleCount,
            liveLongitudinalAccel = lastSample?.longitudinalAccel ?: 0f,
            liveLateralAccel = lastSample?.lateralAccel ?: 0f,
            lapCount = session?.laps?.size ?: 0,
            estimatedLapTimeMs = session?.estimatedLapTimeMs,
            trackOptions = tracks.map { track -> track.name },
            selectedTrackName = currentTrackName,
            canLoadLastSession = storedSessions.isNotEmpty(),
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

    val sessionListUiState: StateFlow<SessionListUiState> = combine(
        sessionRepository.storedSessions,
        sessionRepository.availableTracks,
        selectedSessionFilter
    ) { storedSessions, tracks, selectedFilter ->
        val filterOptions = listOf(ALL_TRACKS_FILTER) + tracks.map { track -> track.name }
        val filteredSessions = if (selectedFilter == ALL_TRACKS_FILTER) {
            storedSessions
        } else {
            storedSessions.filter { session -> session.trackName == selectedFilter }
        }
        SessionListUiState(
            filterOptions = filterOptions,
            selectedFilter = selectedFilter,
            sessions = filteredSessions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListUiState())

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
            lapLabels = laps.mapIndexed { index, lap ->
                buildString {
                    append("Lap ${index + 1} - ${formatLapTime(lap.lapTimeMs)}")
                    if (lap.isOutlap) {
                        append(" (Outlap)")
                    }
                }
            },
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
                if (lapA.isOutlap || lapB.isOutlap) {
                    append("Outlap selected. Comparison may be less stable. ")
                }
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

    fun selectTrack(trackName: String) {
        sessionRepository.selectTrack(trackName)
        selectedSessionFilter.value = trackName
    }

    fun createTrack(trackName: String): String? {
        val track = sessionRepository.createTrack(trackName) ?: return null
        selectedSessionFilter.value = track.name
        return track.name
    }

    fun loadLastSession(): Boolean {
        val session = sessionRepository.loadLastSession() ?: return false
        resetLapSelection(session)
        selectedSessionFilter.value = session.trackName
        return true
    }

    fun loadSession(session: Session) {
        sessionRepository.loadSession(session)
        resetLapSelection(session)
        selectedSessionFilter.value = session.trackName
    }

    fun selectSessionFilter(filter: String) {
        selectedSessionFilter.value = filter
    }

    private fun createDeltaEntries(entriesA: List<Entry>, entriesB: List<Entry>): List<Entry> {
        val size = minOf(entriesA.size, entriesB.size)
        return List(size) { index ->
            Entry(entriesA[index].x, entriesA[index].y - entriesB[index].y)
        }
    }

    private fun resetLapSelection(session: Session) {
        val stableIndices = session.laps
            .mapIndexedNotNull { index, lap -> if (!lap.isOutlap) index else null }

        when {
            stableIndices.size >= 2 -> {
                selectedLapAIndex.value = stableIndices[0]
                selectedLapBIndex.value = stableIndices[1]
            }

            stableIndices.size == 1 -> {
                selectedLapAIndex.value = stableIndices[0]
                selectedLapBIndex.value = stableIndices[0]
            }

            else -> {
                selectedLapAIndex.value = 0
                selectedLapBIndex.value = if (session.laps.size > 1) 1 else 0
            }
        }
    }

    companion object {
        const val ALL_TRACKS_FILTER = "All tracks"
        const val CREATE_TRACK_OPTION = "Create new track..."
    }
}
