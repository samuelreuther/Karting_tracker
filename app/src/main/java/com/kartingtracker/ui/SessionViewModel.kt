package com.kartingtracker.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.mikephil.charting.data.Entry
import com.kartingtracker.data.CurveDefinition
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.Session
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.data.Track
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackProfile
import com.kartingtracker.data.StopPipelineStage
import com.kartingtracker.domain.AutoStartDetector
import com.kartingtracker.domain.AutoCornerDetector
import com.kartingtracker.domain.CurveDetector
import com.kartingtracker.domain.IdealLap
import com.kartingtracker.domain.IdealLapCalculator
import com.kartingtracker.domain.LapNormalizer
import com.kartingtracker.domain.MapOverlayProjector
import com.kartingtracker.domain.TimeLossCalculator
import com.kartingtracker.service.startRecordingService
import com.kartingtracker.service.stopRecordingService
import com.kartingtracker.sensor.RecorderPhase
import com.kartingtracker.sensor.SensorRecorder
import com.kartingtracker.ui.common.formatLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File

class SessionViewModel(
    application: Application,
    private val sessionRepository: SessionRepository,
    val sensorRecorder: SensorRecorder
) : AndroidViewModel(application) {
    private val autoCornerDetector = AutoCornerDetector()
    private val curveDetector = CurveDetector()
    private val autoStartDetector = AutoStartDetector()
    private val mapOverlayProjector = MapOverlayProjector()

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
        sessionRepository.currentTrackLayout,
        sessionRepository.availableTracks,
        sessionRepository.currentTrackName
    ) { session, trackLayout, tracks, currentTrackName ->
        val referenceLap = selectReferenceLap(session?.laps.orEmpty())
        val selectedTrack = tracks.firstOrNull { track -> track.name.equals(currentTrackName, ignoreCase = true) }
        val savedCurves = currentTrackName
            .takeIf { trackName -> trackName.isNotBlank() }
            ?.let(sessionRepository::loadTrackMapMetadata)
            ?.curves
            .orEmpty()
        val detectedCurves = referenceLap?.let(curveDetector::detectCurves).orEmpty().ifEmpty { savedCurves }
        val detectedCorners = detectedCurves.map { curve ->
            com.kartingtracker.domain.DetectedCorner(
                startPercent = curve.startPercent,
                endPercent = curve.endPercent,
                peakPercent = curve.peakPercent,
                strength = curve.intensity
            )
        }.ifEmpty {
            referenceLap?.let(autoCornerDetector::detectCorners).orEmpty()
        }
        val highlightLabels = session?.topTimeLossSegments
            .orEmpty()
            .mapNotNull { segment ->
                Regex("(\\d+)").find(segment.segmentLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            .take(3)
            .map { index -> "K$index" }
            .toSet()
        val mapImagePath = selectedTrack?.mapImagePath ?: trackLayout?.imagePath?.takeIf { path -> path.isNotBlank() }
        val fallbackCornerLines = buildFallbackCurveLines(
            curves = detectedCurves,
            includePosition = mapImagePath.isNullOrBlank()
        )

        TrackMapUiState(
            detectedCorners = detectedCorners,
            detectedCurves = detectedCurves,
            highlightedMarkerLabels = highlightLabels,
            fallbackCornerLines = fallbackCornerLines
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackMapUiState())

    val uiState: StateFlow<SessionUiState> = combine(
        sensorRecorder.recorderPhase,
        sensorRecorder.preStartSecondsRemaining,
        sensorRecorder.recordingStartedAtEpochMs,
        sessionRepository.isRecording,
        sessionRepository.sampleCount,
        sessionRepository.lastSample,
        sessionRepository.latestSession,
        sessionRepository.availableTracks,
        sessionRepository.currentTrackName,
        sessionRepository.storedSessions,
        sessionRepository.currentTrackProfile,
        sessionRepository.stopPipelineStatus,
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(1_000L)
            }
        }
    ) { args: Array<Any?> ->
        val recorderPhase = args[0] as RecorderPhase
        val preStartSecondsRemaining = args[1] as Int
        val recordingStartedAtEpochMs = args[2] as Long?
        val isRecording = args[3] as Boolean
        val sampleCount = args[4] as Int
        val lastSample = args[5] as SensorSample?
        val session = args[6] as Session?
        @Suppress("UNCHECKED_CAST")
        val tracks = args[7] as List<Track>
        val currentTrackName = args[8] as String
        @Suppress("UNCHECKED_CAST")
        val storedSessions = args[9] as List<Session>
        val trackProfile = args[10] as TrackProfile?
        val stopStatus = args[11] as com.kartingtracker.data.StopPipelineStatus
        val nowEpochMs = args[12] as Long
        val elapsedMs = recordingStartedAtEpochMs?.let { nowEpochMs - it }?.coerceAtLeast(0L) ?: 0L

        SessionUiState(
            isRecording = recorderPhase == RecorderPhase.RECORDING || isRecording,
            isPreparing = recorderPhase == RecorderPhase.PREPARING,
            isCalibrating = recorderPhase == RecorderPhase.CALIBRATING,
            isStopping = recorderPhase == RecorderPhase.STOPPING,
            hasRequiredSensors = sensorRecorder.hasRequiredSensors,
            sampleCount = sampleCount,
            liveLongitudinalAccel = lastSample?.longitudinalAccel ?: 0f,
            liveLateralAccel = lastSample?.lateralAccel ?: 0f,
            lapCount = session?.laps?.size ?: 0,
            estimatedLapTimeMs = session?.estimatedLapTimeMs,
            trackOptions = tracks.map { track -> track.name },
            availableTracks = tracks,
            selectedTrackName = currentTrackName,
            hasValidSelectedTrack = currentTrackName.isNotBlank(),
            usingTrackProfile = trackProfile != null,
            trackProfileSummary = formatTrackProfileSummary(trackProfile),
            canLoadLastSession = storedSessions.isNotEmpty(),
            lastSessionSummary = buildLastSessionSummary(currentTrackName, storedSessions),
            compareSelection = buildCompareSelectionUiState(currentTrackName, storedSessions),
            recordingTimerLabel = formatDurationLabel(elapsedMs),
            statusLabel = when {
                !sensorRecorder.hasRequiredSensors -> "Missing accelerometer or gyroscope"
                recorderPhase == RecorderPhase.PREPARING -> "Session starts in $preStartSecondsRemaining… Please stow the phone now"
                recorderPhase == RecorderPhase.CALIBRATING -> "Calibrating - keep the kart still"
                recorderPhase == RecorderPhase.STOPPING || stopStatus.stage == StopPipelineStage.STOPPING_RECORDING -> "Stopping recording…"
                stopStatus.stage == StopPipelineStage.SAVING_RAW_SESSION -> "Saving raw session…"
                stopStatus.stage == StopPipelineStage.PROCESSING_LAPS -> "Processing laps…"
                stopStatus.stage == StopPipelineStage.FINALIZING_SESSION -> "Finalizing session…"
                stopStatus.stage == StopPipelineStage.FAILED -> "Finalization failed; raw session preserved"
                recorderPhase == RecorderPhase.RECORDING || isRecording -> "Recording"
                currentTrackName.isBlank() -> "Select a track to start recording"
                session == null -> "Ready"
                session.analysisWarnings.isNotEmpty() -> "Stopped with warnings"
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

    private val selectedCompareSessionAId = MutableStateFlow<Long?>(null)
    private val selectedCompareSessionBId = MutableStateFlow<Long?>(null)
    private val analysisMode = MutableStateFlow(AnalysisMode.COMPARISON)

    val selectedAnalysisMode: StateFlow<AnalysisMode> = analysisMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisMode.COMPARISON)

    val compareSelectionUiState: StateFlow<CompareSelectionUiState> = combine(
        sessionRepository.storedSessions,
        sessionRepository.currentTrackName,
        selectedCompareSessionAId,
        selectedCompareSessionBId,
        selectedLapAIndex,
        selectedLapBIndex
    ) { storedSessions, trackName, sessionAId, sessionBId, lapAIndex, lapBIndex ->
        val trackSessions = storedSessions
            .filter { it.trackName.equals(trackName, ignoreCase = true) }
            .sortedByDescending { it.endTimeEpochMs }

        if (trackName.isBlank()) {
            return@combine CompareSelectionUiState(emptyStateMessage = "Select a track to prepare lap comparison.")
        }
        if (trackSessions.isEmpty()) {
            return@combine CompareSelectionUiState(emptyStateMessage = "No saved sessions yet for $trackName. Record and stop one session first.")
        }

        val sessionOptions = trackSessions.mapIndexed { index, session ->
            SessionOptionUiState(
                id = session.id,
                label = "Session ${index + 1} · ${formatLapTime(session.laps.minOfOrNull { it.lapTimeMs } ?: 0L)} best"
            )
        }
        val resolvedSessionA = trackSessions.firstOrNull { it.id == sessionAId } ?: trackSessions.first()
        val resolvedSessionB = trackSessions.firstOrNull { it.id == sessionBId } ?: trackSessions.first()

        val validLapsA = resolvedSessionA.laps.mapIndexed { index, lap -> LapOptionUiState(index, formatLapLabel(index, lap)) }
        val validLapsB = resolvedSessionB.laps.mapIndexed { index, lap -> LapOptionUiState(index, formatLapLabel(index, lap)) }
        val safeLapA = lapAIndex.coerceIn(0, (validLapsA.lastIndex).coerceAtLeast(0))
        val safeLapB = lapBIndex.coerceIn(0, (validLapsB.lastIndex).coerceAtLeast(0))

        val message = when {
            validLapsA.isEmpty() && validLapsB.isEmpty() -> "Sessions exist for $trackName but none contain processed laps yet. Reprocess or record a complete run."
            validLapsA.isEmpty() -> "Session A has no comparable laps yet. Choose another session."
            validLapsB.isEmpty() -> "Session B has no comparable laps yet. Choose another session."
            else -> ""
        }

        CompareSelectionUiState(
            sessionOptions = sessionOptions,
            selectedSessionAId = resolvedSessionA.id,
            selectedSessionBId = resolvedSessionB.id,
            lapOptionsA = validLapsA,
            lapOptionsB = validLapsB,
            selectedLapAIndex = safeLapA,
            selectedLapBIndex = safeLapB,
            emptyStateMessage = message,
            canOpenComparison = message.isBlank() && validLapsA.isNotEmpty() && validLapsB.isNotEmpty()
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareSelectionUiState())

    val comparisonUiState: StateFlow<ComparisonUiState> = combine(
        compareSelectionUiState,
        sessionRepository.storedSessions,
        idealLap,
        sessionRepository.availableTracks,
        sessionRepository.currentTrackName,
        sessionRepository.currentTrackLayout
    ) { selection, storedSessions, idealLap, tracks, currentTrackName, currentTrackLayout ->
        val trackSessions = storedSessions.filter { it.trackName.equals(currentTrackName, ignoreCase = true) }
        val sessionA = trackSessions.firstOrNull { it.id == selection.selectedSessionAId }
        val sessionB = trackSessions.firstOrNull { it.id == selection.selectedSessionBId }
        val lapA = sessionA?.laps?.getOrNull(selection.selectedLapAIndex)
        val lapB = sessionB?.laps?.getOrNull(selection.selectedLapBIndex)
        val contextSession = sessionA ?: sessionB
        val currentTrack = tracks.firstOrNull { track -> track.name.equals(currentTrackName, ignoreCase = true) }
        val mapImagePath = currentTrack?.mapImagePath ?: currentTrackLayout?.imagePath?.takeIf { it.isNotBlank() }
        val savedCurves = currentTrackName.takeIf { it.isNotBlank() }?.let(sessionRepository::loadTrackMapMetadata)?.curves.orEmpty()

        if (lapA == null || lapB == null) {
            return@combine ComparisonUiState(
                lapLabelsA = selection.lapOptionsA.map { it.label },
                lapLabelsB = selection.lapOptionsB.map { it.label },
                selectedLapAIndex = selection.selectedLapAIndex,
                selectedLapBIndex = selection.selectedLapBIndex,
                summaryLabel = selection.emptyStateMessage.ifBlank { "Pick sessions and laps on the start page to compare." },
                insights = contextSession?.insights.orEmpty(),
                theoreticalBestLabel = createTheoreticalBestLabel(contextSession),
                topTimeLossLines = createTopTimeLossLines(contextSession),
                cornerCoachingLines = createCornerCoachingLines(contextSession),
                mapImagePath = mapImagePath,
                fallbackCurveLines = buildFallbackCurveLines(savedCurves, includePosition = true)
            )
        }

        val normalizedA = LapNormalizer.normalize(lapA)
        val normalizedB = LapNormalizer.normalize(lapB)
        val timeLossEntries = createTimeLossEntries(lapA, lapB)
        val referenceLap = minOf(lapA, lapB, compareBy<Lap> { it.lapTimeMs })
        val detectedCurves = curveDetector.detectCurves(referenceLap).ifEmpty { savedCurves }
        val autoDetectedStart = autoStartDetector.detectStart(contextSession?.laps.orEmpty())
        val projectedCurves = if (mapImagePath.isNullOrBlank()) emptyList() else {
            mapOverlayProjector.projectCurves(currentTrack, currentTrackLayout, referenceLap, detectedCurves, autoDetectedStart)
                .map { projectedCurve ->
                    ProjectedCurveUiState(
                        label = projectedCurve.label,
                        x = projectedCurve.position.x,
                        y = projectedCurve.position.y,
                        intensity = projectedCurve.intensity,
                        deltaSeconds = sampleTimeLossAtPercent(timeLossEntries, projectedCurve.peakPercent)
                    )
                }
        }

        val deltaMs = lapA.lapTimeMs - lapB.lapTimeMs
        val fasterLabel = when {
            deltaMs == 0L -> "Both laps have the same lap time."
            deltaMs > 0L -> "Lap B is ${formatLapTime(abs(deltaMs))} quicker."
            else -> "Lap A is ${formatLapTime(abs(deltaMs))} quicker."
        }

        ComparisonUiState(
            lapLabelsA = selection.lapOptionsA.map { it.label },
            lapLabelsB = selection.lapOptionsB.map { it.label },
            selectedLapAIndex = selection.selectedLapAIndex,
            selectedLapBIndex = selection.selectedLapBIndex,
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
            segmentMarkerEntries = createSegmentMarkerEntries(contextSession),
            sectorComparisonLines = createSectorComparisonLines(lapA, lapB),
            idealLapLabel = idealLap?.let { "Ideal Lap: ${formatLapTime(it.totalTimeMs)}" }.orEmpty(),
            theoreticalBestLabel = createTheoreticalBestLabel(contextSession),
            idealLapSectorLines = createIdealLapSectorLines(idealLap),
            insights = contextSession?.insights.orEmpty(),
            topTimeLossLines = createTopTimeLossLines(contextSession),
            cornerCoachingLines = createCornerCoachingLines(contextSession),
            mapImagePath = mapImagePath,
            projectedCurves = projectedCurves,
            fallbackCurveLines = buildFallbackCurveLines(detectedCurves, includePosition = mapImagePath.isNullOrBlank()),
            summaryLabel = fasterLabel
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

    fun setAnalysisMode(mode: AnalysisMode) {
        analysisMode.value = mode
    }

    fun selectCompareSessionA(sessionId: Long) {
        selectedCompareSessionAId.value = sessionId
        selectedLapAIndex.value = 0
    }

    fun selectCompareSessionB(sessionId: Long) {
        selectedCompareSessionBId.value = sessionId
        selectedLapBIndex.value = 0
    }

    fun openSelectedComparisonContext(): Boolean {
        return compareSelectionUiState.value.canOpenComparison
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

    fun loadTrack(trackName: String): Track? {
        return sessionRepository.loadTrack(trackName)
    }

    fun deleteTrack(trackName: String): Boolean {
        val deleted = sessionRepository.deleteTrack(trackName)
        if (deleted && selectedSessionFilter.value == trackName) {
            selectedSessionFilter.value = ALL_TRACKS_FILTER
        }
        return deleted
    }

    fun renameTrack(oldName: String, newName: String): Boolean {
        return sessionRepository.renameTrack(oldName, newName)
    }

    fun updateTrack(track: Track): Track? {
        return sessionRepository.updateTrack(track)
    }

    fun exportSessionCsv(session: Session): File {
        return sessionRepository.exportSessionCsv(session)
    }

    suspend fun exportBackup(targetUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            sessionRepository.exportBackup(targetUri)
        }
    }

    suspend fun importBackup(sourceUri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            sessionRepository.importBackup(sourceUri)
        }
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

    private fun sampleTimeLossAtPercent(entries: List<Entry>, percent: Float): Float {
        if (entries.isEmpty()) {
            return 0f
        }
        return entries.minByOrNull { entry -> abs(entry.x - percent) }?.y ?: 0f
    }

    private fun buildFallbackCurveLines(curves: List<CurveDefinition>, includePosition: Boolean): List<String> {
        return curves.map { curve ->
            if (includePosition) {
                "Turn ${curve.index} (${curve.peakPercent.toInt()}%)"
            } else {
                "Turn ${curve.index}"
            }
        }
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

    private fun createCornerCoachingLines(session: Session?): List<String> {
        val summary = session?.cornerCoachingSummary
        val topActions = summary?.topActions.orEmpty()
        if (topActions.isNotEmpty()) {
            return topActions.mapIndexed { index, insight ->
                "${index + 1}) ${insight.headline}"
            }
        }
        return session?.cornerCoachingInsights.orEmpty().take(5).map { insight ->
            "- ${insight.headline}"
        }
    }

    private fun createSegmentMarkerEntries(session: Session?): List<Entry> {
        return session?.segmentMarkers.orEmpty().map { marker ->
            Entry(marker.positionPercent, marker.severity)
        }
    }

    private fun selectReferenceLap(laps: List<Lap>): Lap? {
        val preferredLap = laps
            .filter { lap -> lap.isNormalPhase && !lap.isDisturbed && lap.confidenceScore >= 0.75f }
            .minByOrNull { lap -> lap.lapTimeMs }
        return preferredLap ?: laps.minByOrNull { lap -> lap.lapTimeMs }
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


    private fun formatLapLabel(index: Int, lap: Lap): String {
        return buildString {
            append("Lap ${index + 1} · ${formatLapTime(lap.lapTimeMs)}")
            if (lap.isInterrupted) append(" (Interrupted)")
            if (lap.isDisturbed) append(" (Disturbed)")
        }
    }

    private fun buildCompareSelectionUiState(trackName: String, storedSessions: List<Session>): CompareSelectionUiState {
        val trackSessions = storedSessions.filter { it.trackName.equals(trackName, ignoreCase = true) }
        if (trackName.isBlank()) return CompareSelectionUiState(emptyStateMessage = "Select a track to prepare lap comparison.")
        if (trackSessions.isEmpty()) return CompareSelectionUiState(emptyStateMessage = "No saved sessions yet for $trackName.")
        val sessionA = trackSessions.firstOrNull { it.id == selectedCompareSessionAId.value } ?: trackSessions.first()
        val sessionB = trackSessions.firstOrNull { it.id == selectedCompareSessionBId.value } ?: trackSessions.first()
        val lapsA = sessionA.laps.mapIndexed { idx, lap -> LapOptionUiState(idx, formatLapLabel(idx, lap)) }
        val lapsB = sessionB.laps.mapIndexed { idx, lap -> LapOptionUiState(idx, formatLapLabel(idx, lap)) }
        val message = if (lapsA.isEmpty() || lapsB.isEmpty()) "Saved sessions exist, but no valid processed laps are available yet." else ""
        return CompareSelectionUiState(
            sessionOptions = trackSessions.mapIndexed { index, session -> SessionOptionUiState(session.id, "Session ${index + 1}") },
            selectedSessionAId = sessionA.id,
            selectedSessionBId = sessionB.id,
            lapOptionsA = lapsA,
            lapOptionsB = lapsB,
            selectedLapAIndex = selectedLapAIndex.value.coerceIn(0, (lapsA.lastIndex).coerceAtLeast(0)),
            selectedLapBIndex = selectedLapBIndex.value.coerceIn(0, (lapsB.lastIndex).coerceAtLeast(0)),
            emptyStateMessage = message,
            canOpenComparison = message.isBlank()
        )
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

    private fun buildLastSessionSummary(
        trackName: String,
        storedSessions: List<Session>
    ): LastSessionSummaryUiState {
        if (trackName.isBlank()) {
            return LastSessionSummaryUiState()
        }
        val lastTrackSession = storedSessions
            .filter { session -> session.trackName.equals(trackName, ignoreCase = true) }
            .maxByOrNull { session -> session.endTimeEpochMs }
            ?: return LastSessionSummaryUiState(
                headline = "No saved session for $trackName yet.",
                quality = "Quality score unavailable",
                biggestLoss = "Run one session to identify biggest time-loss areas",
                coachingHint = "After one run, coaching hints and comparison entry become available",
                topCornerActions = emptyList(),
                strongestCorner = "Strongest corner unavailable",
                biggestCornerOpportunity = "Corner opportunity unavailable",
                canOpenComparison = false
            )

        val qualityScore = ((lastTrackSession.quality?.overallScore ?: 0f) * 100f).toInt().coerceIn(0, 100)
        val qualityLabel = when {
            qualityScore >= 80 -> "Session quality: strong ($qualityScore/100)"
            qualityScore >= 60 -> "Session quality: fair ($qualityScore/100)"
            else -> "Session quality: low confidence ($qualityScore/100)"
        }
        val topLoss = lastTrackSession.topTimeLossSegments.firstOrNull()
        val topInsight = lastTrackSession.coachingInsights.maxByOrNull { insight -> insight.severity }
            ?: lastTrackSession.coachingInsights.firstOrNull()
        val bestLapLabel = lastTrackSession.laps.minByOrNull { lap -> lap.lapTimeMs }
            ?.lapTimeMs
            ?.let(::formatLapTime)
            ?: "n/a"
        val cornerSummary = lastTrackSession.cornerCoachingSummary
        val topCornerActions = cornerSummary?.topActions.orEmpty()
            .take(3)
            .mapIndexed { index, insight -> "${index + 1}) ${insight.headline}" }
        val strongestCorner = cornerSummary?.strongestCorner?.headline
            ?: "Strongest corner unavailable"
        val biggestCornerOpportunity = cornerSummary?.biggestOpportunityCorner?.headline
            ?: "Corner opportunity unavailable"
        return LastSessionSummaryUiState(
            headline = "Best lap $bestLapLabel · ${lastTrackSession.laps.size} laps",
            quality = qualityLabel,
            biggestLoss = topLoss?.let { segment ->
                "Biggest time loss: ${segment.segmentLabel.ifBlank { "segment ${segment.segmentIndex}" }} (+${"%.2f".format(segment.timeLoss)}s)"
            } ?: "No clear time-loss hotspot in latest run",
            coachingHint = topInsight?.let { insight ->
                "Top coaching hint: ${insight.suggestion}"
            } ?: lastTrackSession.insights.firstOrNull()?.let { insight -> "Top coaching hint: $insight" }
            ?: "Top coaching hint unavailable for this run",
            topCornerActions = topCornerActions,
            strongestCorner = strongestCorner,
            biggestCornerOpportunity = biggestCornerOpportunity,
            canOpenComparison = lastTrackSession.laps.size >= 2
        )
    }

    companion object {
        const val ALL_TRACKS_FILTER = "All tracks"
    }

    private fun formatDurationLabel(elapsedMs: Long): String {
        val totalSeconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
