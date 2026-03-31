package com.kartingtracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.Session
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.data.Track
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackProfile
import com.kartingtracker.domain.AutoCornerDetector
import com.kartingtracker.domain.IdealLap
import com.kartingtracker.domain.IdealLapCalculator
import com.kartingtracker.domain.LapNormalizer
import com.kartingtracker.domain.TrackLayoutMapper
import com.kartingtracker.domain.TimeLossCalculator
import com.kartingtracker.service.startRecordingService
import com.kartingtracker.service.stopRecordingService
import com.kartingtracker.sensor.RecorderPhase
import com.kartingtracker.sensor.SensorRecorder
import com.kartingtracker.ui.common.formatLapTime
import com.github.mikephil.charting.data.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

class SessionViewModel(
    application: Application,
    private val sessionRepository: SessionRepository,
    val sensorRecorder: SensorRecorder
) : AndroidViewModel(application) {
    private val autoCornerDetector = AutoCornerDetector()

    private val selectedLapAIndex = MutableStateFlow(0)
    private val selectedLapBIndex = MutableStateFlow(1)
    private val selectedSessionFilter = MutableStateFlow(ALL_TRACKS_FILTER)

    val idealLap: StateFlow<IdealLap?> = combine(
        sessionRepository.storedSessions,
        sessionRepository.currentSession,
        sessionRepository.currentTrackName
    ) { storedSessions, currentSession, currentTrackName ->
        val trackSessions = storedSessions.filter { session -> session.trackName == currentTrackName }
        val allTrackLaps = buildList {
            trackSessions.forEach { session -> addAll(session.laps) }
            val currentTrackSession = currentSession?.takeIf { session -> session.trackName == currentTrackName }
            if (currentTrackSession != null && trackSessions.none { session -> session.id == currentTrackSession.id }) {
                addAll(currentTrackSession.laps)
            }
        }
        IdealLapCalculator.calculate(allTrackLaps)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val laps: StateFlow<List<Lap>> = sessionRepository.currentSession
        .map { session -> session?.laps.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trackMapUiState: StateFlow<TrackMapUiState> = combine(
        sessionRepository.currentSession,
        sessionRepository.currentTrackLayout
    ) { session, trackLayout ->
        val referenceLap = session?.laps
            ?.filter { lap -> lap.isNormalPhase && !lap.isDisturbed && lap.confidenceScore >= 0.75f }
            ?.minByOrNull { lap -> lap.lapTimeMs }
            ?: session?.laps?.minByOrNull { lap -> lap.lapTimeMs }
        val detectedCorners = referenceLap?.let(autoCornerDetector::detectCorners).orEmpty()
        val highlightLabels = session?.topTimeLossSegments
            .orEmpty()
            .mapNotNull { segment ->
                Regex("(\\d+)").find(segment.segmentLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .take(3)
            .map { index -> "K$index" }
            .toSet()
        val fallbackCornerLines = if (trackLayout?.imagePath.isNullOrBlank() || trackLayout?.corners.isNullOrEmpty()) {
            TrackLayoutMapper.buildCornerReferences(detectedCorners, null).map { reference ->
                if (reference.relativePosition.isBlank()) {
                    reference.insightLabel
                } else {
                    "${reference.insightLabel} (${reference.relativePosition})"
                }
            }
        } else {
            emptyList()
        }

        TrackMapUiState(
            detectedCorners = detectedCorners,
            highlightedMarkerLabels = highlightLabels,
            fallbackCornerLines = fallbackCornerLines
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackMapUiState())

    val uiState: StateFlow<SessionUiState> = combine(
        sensorRecorder.recorderPhase,
        sessionRepository.isRecording,
        sessionRepository.sampleCount,
        sessionRepository.lastSample,
        sessionRepository.latestSession,
        sessionRepository.availableTracks,
        sessionRepository.currentTrackName,
        sessionRepository.storedSessions,
        sessionRepository.currentTrackProfile
    ) { args: Array<Any?> ->
        val recorderPhase = args[0] as RecorderPhase
        val isRecording = args[1] as Boolean
        val sampleCount = args[2] as Int
        val lastSample = args[3] as SensorSample?
        val session = args[4] as Session?
        @Suppress("UNCHECKED_CAST")
        val tracks = args[5] as List<Track>
        val currentTrackName = args[6] as String
        @Suppress("UNCHECKED_CAST")
        val storedSessions = args[7] as List<Session>
        val trackProfile = args[8] as TrackProfile?

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
            hasValidSelectedTrack = currentTrackName.isNotBlank(),
            usingTrackProfile = trackProfile != null,
            trackProfileSummary = formatTrackProfileSummary(trackProfile),
            canLoadLastSession = storedSessions.isNotEmpty(),
            statusLabel = when {
                !sensorRecorder.hasRequiredSensors -> "Missing accelerometer or gyroscope"
                recorderPhase == RecorderPhase.CALIBRATING -> "Calibrating - keep the kart still"
                recorderPhase == RecorderPhase.RECORDING || isRecording -> "Recording"
                currentTrackName.isBlank() -> "Select a track to start recording"
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
        val activeFilter = selectedFilter.takeIf { filterOptions.contains(it) } ?: ALL_TRACKS_FILTER
        val filteredSessions = if (activeFilter == ALL_TRACKS_FILTER) {
            storedSessions
        } else {
            storedSessions.filter { session -> session.trackName == activeFilter }
        }
        SessionListUiState(
            filterOptions = filterOptions,
            selectedFilter = activeFilter,
            sessions = filteredSessions.map { session ->
                SessionListItemUiState(
                    session = session,
                    sampleCount = session.samples.size,
                    fileSizeBytes = sessionRepository.getSessionFileSize(session.id)
                )
            }
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListUiState())

    val comparisonUiState: StateFlow<ComparisonUiState> = combine(
        laps,
        idealLap,
        sessionRepository.currentSession,
        selectedLapAIndex,
        selectedLapBIndex
    ) { laps, idealLap, currentSession, selectedA, selectedB ->
        if (laps.isEmpty()) {
            return@combine ComparisonUiState(
                insights = currentSession?.insights.orEmpty(),
                theoreticalBestLabel = createTheoreticalBestLabel(currentSession),
                topTimeLossLines = createTopTimeLossLines(currentSession)
            )
        }

        val safeA = selectedA.coerceIn(0, laps.lastIndex)
        val safeB = selectedB.coerceIn(0, laps.lastIndex)
        val lapA = laps[safeA]
        val lapB = laps[safeB]
        val normalizedA = LapNormalizer.normalize(lapA)
        val normalizedB = LapNormalizer.normalize(lapB)
        val timeLossEntries = createTimeLossEntries(lapA, lapB)
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
                    if (lap.isInlap) {
                        append(" (Inlap)")
                    }
                    if (lap.isInterrupted) {
                        append(" (Interrupted)")
                    }
                    if (lap.isDisturbed) {
                        append(" (Disturbed)")
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
            timeLossEntries = timeLossEntries,
            segmentMarkerEntries = createSegmentMarkerEntries(currentSession),
            sectorComparisonLines = createSectorComparisonLines(lapA, lapB),
            idealLapLabel = idealLap?.let { ideal -> "Ideal Lap: ${formatLapTime(ideal.totalTimeMs)}" }.orEmpty(),
            theoreticalBestLabel = createTheoreticalBestLabel(currentSession),
            idealLapSectorLines = createIdealLapSectorLines(idealLap),
            insights = currentSession?.insights.orEmpty(),
            topTimeLossLines = createTopTimeLossLines(currentSession),
            summaryLabel = buildString {
                if (lapA.isOutlap || lapB.isOutlap) {
                    append("Outlap selected. Comparison may be less stable. ")
                }
                if (lapA.isInlap || lapB.isInlap) {
                    append("Inlap selected. Comparison may be less stable. ")
                }
                if (lapA.isInterrupted || lapB.isInterrupted) {
                    append("Interrupted segment selected. Comparison is likely not meaningful. ")
                }
                if (lapA.isDisturbed || lapB.isDisturbed) {
                    append("Disturbed lap selected. Time loss and insights may be less reliable. ")
                }
                append(fasterLabel)
                append(" ")
                append("Braking peaks: ${lapA.brakingPeakIndices.size} vs ${lapB.brakingPeakIndices.size}. ")
                append("Cornering peaks: ${lapA.corneringPeakIndices.size} vs ${lapB.corneringPeakIndices.size}. ")
                idealLap?.let { ideal ->
                    append("Ideal lap reference: ${formatLapTime(ideal.totalTimeMs)}. ")
                }
                currentSession?.theoreticalBestLapTimeMs?.let { theoreticalBestLapTimeMs ->
                    val bestLapTimeMs = currentSession.laps.minOfOrNull { lap -> lap.lapTimeMs } ?: return@let
                    val gapMs = bestLapTimeMs - theoreticalBestLapTimeMs
                    if (gapMs > 0L) {
                        append("Theoretical best is ${formatLapTime(gapMs)} quicker than the current best lap. ")
                    }
                }
                timeLossEntries.lastOrNull()?.let { finalDelta ->
                    val absoluteDelta = abs(finalDelta.y)
                    val deltaLeader = when {
                        absoluteDelta < 0.01f -> "Estimated time loss is effectively even."
                        finalDelta.y > 0f -> "Estimated final time loss: Lap A +${"%.2f".format(absoluteDelta)} s."
                        else -> "Estimated final time loss: Lap A ${"%.2f".format(finalDelta.y)} s."
                    }
                    append(deltaLeader)
                }
            }
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComparisonUiState())

    fun startRecording() {
        if (sessionRepository.currentTrackName.value.isBlank()) {
            return
        }
        getApplication<Application>().startRecordingService(sessionRepository.currentTrackName.value)
    }

    fun stopRecording() {
        getApplication<Application>().stopRecordingService()
    }

    fun selectLapA(index: Int) {
        selectedLapAIndex.value = index
    }

    fun selectLapB(index: Int) {
        selectedLapBIndex.value = index
    }

    fun selectTrack(trackName: String) {
        if (trackName.isBlank()) {
            return
        }
        sessionRepository.selectTrack(trackName)
        selectedSessionFilter.value = trackName
    }

    fun createTrack(trackName: String): String? {
        val track = sessionRepository.createTrack(trackName) ?: return null
        selectedSessionFilter.value = track.name
        return track.name
    }

    fun normalizeTrackName(trackName: String): String {
        return sessionRepository.normalizeTrackName(trackName)
    }

    fun trackExists(trackName: String): Boolean {
        return sessionRepository.trackExists(trackName)
    }

    fun loadOrCreateTrackLayout(trackName: String): TrackLayout? {
        return sessionRepository.loadOrCreateTrackLayout(trackName)
    }

    fun importTrackLayoutImage(trackName: String, imageUri: Uri): TrackLayout? {
        return sessionRepository.importTrackLayoutImage(trackName, imageUri)
    }

    fun saveTrackLayout(layout: TrackLayout): TrackLayout {
        return sessionRepository.saveTrackLayout(layout)
    }

    fun loadLastSession(): Boolean {
        val session = sessionRepository.loadLastSession() ?: return false
        resetLapSelection(session)
        selectedSessionFilter.value = session.trackName
        return true
    }

    fun loadSession(session: Session) {
        sessionRepository.loadSession(session)
        val preparedSession = sessionRepository.currentSession.value ?: session
        resetLapSelection(preparedSession)
        selectedSessionFilter.value = preparedSession.trackName
    }

    fun reprocessSession(session: Session) {
        sessionRepository.reprocessSessionAsync(session)
    }

    fun deleteSession(session: Session): Boolean {
        return sessionRepository.deleteSession(session.id)
    }

    fun deleteTrack(trackName: String): Boolean {
        val deleted = sessionRepository.deleteTrack(trackName)
        if (deleted && selectedSessionFilter.value == trackName) {
            selectedSessionFilter.value = ALL_TRACKS_FILTER
        }
        return deleted
    }

    fun selectSessionFilter(filter: String) {
        selectedSessionFilter.value = filter
    }

    private fun createTimeLossEntries(lapA: Lap, lapB: Lap): List<Entry> {
        val timeLoss = TimeLossCalculator.computeTimeLoss(lapA, lapB)
        val pointCount = timeLoss.size
        if (pointCount == 0) {
            return emptyList()
        }
        return timeLoss.mapIndexed { index, value ->
            val x = (index.toFloat() / (pointCount - 1).coerceAtLeast(1)) * 100f
            Entry(x, value)
        }
    }

    private fun createSectorComparisonLines(lapA: Lap, lapB: Lap): List<String> {
        val sectorCount = minOf(lapA.sectorTimesMs.size, lapB.sectorTimesMs.size)
        if (sectorCount == 0) {
            return emptyList()
        }

        return List(sectorCount) { index ->
            val sectorNumber = index + 1
            val deltaMs = lapA.sectorTimesMs[index] - lapB.sectorTimesMs[index]
            val prefix = "S$sectorNumber: "
            when {
                deltaMs == 0L -> prefix + "even"
                deltaMs > 0L -> prefix + "+${formatLapTime(deltaMs)}"
                else -> prefix + "-${formatLapTime(abs(deltaMs))}"
            }
        }
    }

    private fun createIdealLapSectorLines(idealLap: IdealLap?): List<String> {
        return idealLap?.sectorBestTimes?.mapIndexed { index, sectorTimeMs ->
            "Best S${index + 1}: ${formatLapTime(sectorTimeMs)}"
        }.orEmpty()
    }

    private fun createTheoreticalBestLabel(session: Session?): String {
        val theoreticalBestLapTimeMs = session?.theoreticalBestLapTimeMs ?: return ""
        val currentBestLapTimeMs = session.laps.minOfOrNull { lap -> lap.lapTimeMs } ?: return ""
        return buildString {
            append("Theoretical best: ")
            append(formatLapTime(theoreticalBestLapTimeMs))
            append(" (current best: ")
            append(formatLapTime(currentBestLapTimeMs))
            append(")")
        }
    }

    private fun createTopTimeLossLines(session: Session?): List<String> {
        return session?.topTimeLossSegments.orEmpty().map { segment ->
            buildString {
                append(segment.segmentLabel.ifBlank { "Sector ${segment.segmentIndex}" })
                if (segment.relativePosition.isNotBlank()) {
                    append(" (")
                    append(segment.relativePosition)
                    append(")")
                }
                append(": ")
                append(segment.cause)
                append(" -> +")
                append("%.2f".format(segment.timeLoss))
                append("s")
            }
        }
    }

    private fun createSegmentMarkerEntries(session: Session?): List<Entry> {
        return session?.segmentMarkers.orEmpty().map { marker ->
            Entry(marker.positionPercent, marker.severity)
        }
    }

    private fun resetLapSelection(session: Session) {
        val stableIndices = session.laps
            .mapIndexedNotNull { index, lap ->
                if (!lap.isOutlap && !lap.isDisturbed) {
                    index
                } else {
                    null
                }
            }
        val fallbackNormalPhaseIndices = session.laps
            .mapIndexedNotNull { index, lap -> if (lap.isNormalPhase) index else null }
        val fallbackNonOutlapIndices = session.laps
            .mapIndexedNotNull { index, lap -> if (!lap.isOutlap) index else null }
        val fallbackNonDisturbedIndices = session.laps
            .mapIndexedNotNull { index, lap -> if (!lap.isDisturbed) index else null }

        when {
            stableIndices.size >= 2 -> {
                selectedLapAIndex.value = stableIndices[0]
                selectedLapBIndex.value = stableIndices[1]
            }

            stableIndices.size == 1 -> {
                selectedLapAIndex.value = stableIndices[0]
                selectedLapBIndex.value = fallbackNormalPhaseIndices.firstOrNull { index -> index != stableIndices[0] }
                    ?: fallbackNonOutlapIndices.firstOrNull { index -> index != stableIndices[0] }
                    ?: fallbackNonDisturbedIndices.firstOrNull { index -> index != stableIndices[0] }
                    ?: stableIndices[0]
            }

            fallbackNormalPhaseIndices.size >= 2 -> {
                selectedLapAIndex.value = fallbackNormalPhaseIndices[0]
                selectedLapBIndex.value = fallbackNormalPhaseIndices[1]
            }

            fallbackNonOutlapIndices.size >= 2 -> {
                selectedLapAIndex.value = fallbackNonOutlapIndices[0]
                selectedLapBIndex.value = fallbackNonOutlapIndices[1]
            }

            fallbackNonDisturbedIndices.size >= 2 -> {
                selectedLapAIndex.value = fallbackNonDisturbedIndices[0]
                selectedLapBIndex.value = fallbackNonDisturbedIndices[1]
            }

            else -> {
                selectedLapAIndex.value = 0
                selectedLapBIndex.value = if (session.laps.size > 1) 1 else 0
            }
        }
    }

    private fun formatTrackProfileSummary(trackProfile: TrackProfile?): String {
        if (trackProfile == null) {
            return "No learned track profile yet."
        }

        return if (trackProfile.sessionCount < 2) {
            "Learning track profile from ${trackProfile.sessionCount} session."
        } else {
            "Using track profile from ${trackProfile.sessionCount} sessions."
        }
    }

    companion object {
        const val ALL_TRACKS_FILTER = "All tracks"
    }
}
