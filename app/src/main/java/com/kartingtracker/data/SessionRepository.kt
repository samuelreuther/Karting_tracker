package com.kartingtracker.data

import android.graphics.PointF
import android.net.Uri
import android.util.Log
import com.kartingtracker.domain.DrivingCoachAnalyzer
import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.domain.SectorDetector
import com.kartingtracker.domain.SessionQualityEvaluator
import com.kartingtracker.domain.TrackLayoutMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.system.measureTimeMillis

class SessionRepository(
    private val lapDetector: LapDetector,
    private val peakDetector: PeakDetector,
    private val sessionStorageManager: SessionStorageManager,
    private val trackManager: TrackManager,
    private val trackProfileManager: TrackProfileManager,
    private val trackLayoutManager: TrackLayoutManager,
    private val trackMapManager: TrackMapManager,
    private val drivingCoachAnalyzer: DrivingCoachAnalyzer,
    private val sessionCsvExporter: SessionCsvExporter
) {
    private val lock = Any()
    private var currentSessionId: Long = System.currentTimeMillis()
    private var currentStartTimestampNs: Long = 0L
    private var currentStartTimeEpochMs: Long = 0L
    private val currentSamples = mutableListOf<SensorSample>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autosaveJob: Job? = null
    private var trackStateRefreshJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _lastSample = MutableStateFlow<SensorSample?>(null)
    val lastSample: StateFlow<SensorSample?> = _lastSample.asStateFlow()

    private val _latestSession = MutableStateFlow<Session?>(null)
    val latestSession: StateFlow<Session?> = _latestSession.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val _storedSessions = MutableStateFlow<List<Session>>(emptyList())
    val storedSessions: StateFlow<List<Session>> = _storedSessions.asStateFlow()

    private val _availableTracks = MutableStateFlow(trackManager.getTracks())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _currentTrackName = MutableStateFlow(trackManager.getSelectedTrackName().orEmpty())
    val currentTrackName: StateFlow<String> = _currentTrackName.asStateFlow()

    private val _currentTrackProfile = MutableStateFlow<TrackProfile?>(null)
    val currentTrackProfile: StateFlow<TrackProfile?> = _currentTrackProfile.asStateFlow()

    private val _currentTrackLayout = MutableStateFlow<TrackLayout?>(null)
    val currentTrackLayout: StateFlow<TrackLayout?> = _currentTrackLayout.asStateFlow()

    init {
        repositoryScope.launch {
            refreshStoredSessions()
            synchronized(lock) {
                currentSessionId = maxOf(
                    currentSessionId,
                    _storedSessions.value.maxOfOrNull { session -> session.id } ?: currentSessionId
                )
            }
            refreshCurrentTrackState(_currentTrackName.value)
        }
    }

    fun startSession(startTimestampNs: Long) {
        synchronized(lock) {
            stopAutosaveLocked()
            currentSessionId += 1L
            currentStartTimestampNs = startTimestampNs
            currentStartTimeEpochMs = System.currentTimeMillis()
            currentSamples.clear()
            _latestSession.value = null
            _currentSession.value = null
            _sampleCount.value = 0
            _lastSample.value = null
            _isRecording.value = true
            Log.i(TAG, "$LOG_TAG: recording start session=$currentSessionId track=${_currentTrackName.value}")
            startAutosaveLocked()
        }
    }

    fun appendSample(sample: SensorSample) {
        synchronized(lock) {
            if (!_isRecording.value) {
                return
            }
            currentSamples += sample
            _sampleCount.value = currentSamples.size
            _lastSample.value = sample
        }
    }

    fun stopSession(endTimestampNs: Long): Session? {
        var completedSession: Session? = null
        synchronized(lock) {
            if (!_isRecording.value) {
                return _latestSession.value
            }

            val flushSnapshot = buildPartialSessionSnapshot()
            if (flushSnapshot != null) {
                persistSessionWithVerification(flushSnapshot, "final-stop-flush")
            }
            _isRecording.value = false
            stopAutosaveLocked()
            completedSession = if (currentSamples.isEmpty()) {
                Session(
                    id = currentSessionId,
                    trackName = _currentTrackName.value,
                    startTimeEpochMs = currentStartTimeEpochMs,
                    endTimeEpochMs = System.currentTimeMillis(),
                    startTimestampNs = currentStartTimestampNs,
                    endTimestampNs = endTimestampNs,
                    samples = emptyList(),
                    laps = emptyList(),
                    estimatedLapTimeMs = null,
                    insights = emptyList(),
                    coachingInsights = emptyList(),
                    theoreticalBestLapTimeMs = null,
                    topTimeLossSegments = emptyList(),
                    segmentMarkers = emptyList(),
                    quality = null
                )
            } else {
                buildProcessedSession(
                    samples = currentSamples.toList(),
                    endTimestampNs = endTimestampNs,
                    endTimeEpochMs = System.currentTimeMillis()
                )
            }

            _latestSession.value = completedSession
            _currentSession.value = completedSession
            completedSession?.let { session ->
                persistSessionWithVerification(session, "final-save")
            }
            refreshStoredSessions()
        }

        completedSession?.trackName?.takeIf { trackName -> trackName.isNotBlank() }?.let(::refreshTrackProfileState)
        return completedSession
    }

    fun createTrack(trackName: String): Track? {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (!trackManager.addTrackSafe(normalizedName)) {
            return null
        }
        val track = trackManager.getTrack(normalizedName) ?: Track(normalizedName)
        refreshTracks()
        selectTrack(track.name)
        return track
    }

    fun selectTrack(trackName: String) {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return
        }
        trackManager.setSelectedTrack(normalizedName)
        _currentTrackName.value = normalizedName
        refreshTracks()
        refreshCurrentTrackStateAsync(normalizedName)
    }

    fun normalizeTrackName(trackName: String): String {
        return trackManager.normalizeTrackName(trackName)
    }

    fun trackExists(trackName: String): Boolean {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return false
        }
        return trackManager.getTracksList().any { existing -> existing.equals(normalizedName, ignoreCase = true) }
    }

    fun loadSessionsForTrack(trackName: String): List<Session> {
        return sessionStorageManager.loadSessionsForTrack(trackName)
    }

    fun loadTrackLayout(trackName: String): TrackLayout? {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }
        return trackLayoutManager.loadLayout(normalizedName)?.let(::normalizeTrackLayout)
    }

    fun loadOrCreateTrackLayout(trackName: String): TrackLayout? {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }
        return normalizeTrackLayout(trackLayoutManager.loadOrCreateLayout(normalizedName))
    }

    fun importTrackLayoutImage(trackName: String, imageUri: Uri): TrackLayout? {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }
        val imagePath = trackLayoutManager.importTrackImage(normalizedName, imageUri) ?: return null
        val updatedLayout = normalizeTrackLayout(
            trackLayoutManager.detectAndClassifyCorners(
                trackLayoutManager.loadOrCreateLayout(normalizedName).copy(imagePath = imagePath)
            )
        )
        trackLayoutManager.saveLayout(updatedLayout)
        upsertTrackFromLayout(updatedLayout)
        if (normalizedName.equals(_currentTrackName.value, ignoreCase = true)) {
            _currentTrackLayout.value = updatedLayout
        }
        refreshTracks()
        return updatedLayout
    }

    fun saveTrackLayout(layout: TrackLayout): TrackLayout {
        val normalizedLayout = normalizeTrackLayout(
            trackLayoutManager.detectAndClassifyCorners(
                layout.copy(trackName = trackManager.normalizeTrackName(layout.trackName))
            )
        )
        trackLayoutManager.saveLayout(normalizedLayout)
        upsertTrackFromLayout(normalizedLayout)
        if (normalizedLayout.trackName.equals(_currentTrackName.value, ignoreCase = true)) {
            _currentTrackLayout.value = normalizedLayout
        }
        refreshTracks()
        return normalizedLayout
    }

    fun loadTrack(trackName: String): Track? {
        return trackManager.getTrack(trackName)
    }

    fun loadTrackMapMetadata(trackName: String): TrackMapMetadata? {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return null
        }
        return trackMapManager.loadMetadata(normalizedName)
    }

    fun loadLastSession(): Session? {
        val session = sessionStorageManager.loadLastSession() ?: return null
        loadSession(session)
        return _currentSession.value
    }

    fun getSessionFileSize(sessionId: Long): Long {
        return sessionStorageManager.getSessionFileSize(sessionId)
    }

    fun deleteSession(sessionId: Long): Boolean {
        val removedSession = _storedSessions.value.firstOrNull { session -> session.id == sessionId }
            ?: _currentSession.value?.takeIf { session -> session.id == sessionId }
            ?: _latestSession.value?.takeIf { session -> session.id == sessionId }
        val deleted = sessionStorageManager.deleteSession(sessionId)
        if (!deleted) {
            return false
        }

        synchronized(lock) {
            if (_currentSession.value?.id == sessionId) {
                _currentSession.value = null
            }
            if (_latestSession.value?.id == sessionId) {
                _latestSession.value = null
            }
        }

        refreshStoredSessions()
        removedSession?.trackName?.takeIf { trackName -> trackName.isNotBlank() }?.let(::refreshTrackProfileState)
        return true
    }

    fun renameTrack(oldName: String, newName: String): Boolean {
        val normalizedOld = trackManager.normalizeTrackName(oldName)
        val normalizedNew = trackManager.normalizeTrackName(newName)
        if (!trackManager.renameTrack(normalizedOld, normalizedNew)) {
            return false
        }

        val oldSessions = sessionStorageManager.loadSessionsForTrack(normalizedOld)
        if (oldSessions.isNotEmpty()) {
            sessionStorageManager.deleteSessionsForTrack(normalizedOld)
            oldSessions.forEach { sessionStorageManager.saveSession(it.copy(trackName = normalizedNew)) }
        }

        val oldProfile = trackProfileManager.loadProfile(normalizedOld)
        if (oldProfile != null) {
            trackProfileManager.saveProfile(oldProfile.copy(trackName = normalizedNew))
            trackProfileManager.deleteProfile(normalizedOld)
        }

        val oldLayout = trackLayoutManager.loadLayout(normalizedOld)
        if (oldLayout != null) {
            trackLayoutManager.saveLayout(oldLayout.copy(trackName = normalizedNew))
            trackLayoutManager.deleteLayout(normalizedOld)
        }

        val oldMap = trackMapManager.loadMetadata(normalizedOld)
        if (oldMap != null) {
            trackMapManager.saveMetadata(normalizedNew, oldMap.copy(trackName = normalizedNew))
            trackMapManager.deleteTrackMap(normalizedOld)
        }

        if (_currentTrackName.value.equals(normalizedOld, ignoreCase = true)) {
            _currentTrackName.value = normalizedNew
        }
        refreshTracks()
        refreshStoredSessions()
        refreshCurrentTrackStateAsync(_currentTrackName.value)
        return true
    }

    fun updateTrack(track: Track): Track? {
        val saved = trackManager.saveTrack(track) ?: return null
        refreshTracks()
        return saved
    }

    fun exportSessionCsv(session: Session): java.io.File {
        return sessionCsvExporter.export(session)
    }

    fun deleteTrack(trackName: String): Boolean {
        val normalizedName = trackManager.normalizeTrackName(trackName)
        if (normalizedName.isBlank()) {
            return false
        }
        if (!trackManager.deleteTrack(normalizedName)) {
            return false
        }
        trackMapManager.deleteTrackMap(normalizedName)

        synchronized(lock) {
            if (_currentSession.value?.trackName.equals(normalizedName, ignoreCase = true)) {
                _currentSession.value = null
            }
            if (_latestSession.value?.trackName.equals(normalizedName, ignoreCase = true)) {
                _latestSession.value = null
            }
        }

        refreshStoredSessions()
        refreshTracks()
        _currentTrackName.value = trackManager.getSelectedTrackName().orEmpty()
        refreshCurrentTrackProfile(_currentTrackName.value)
        refreshCurrentTrackLayout(_currentTrackName.value)
        return true
    }

    fun loadSession(session: Session) {
        synchronized(lock) {
            stopAutosaveLocked()
            if (shouldReprocessSession(session)) {
                reprocessSessionAsync(session)
            }
            _isRecording.value = false
            _latestSession.value = session
            _currentSession.value = session
            _sampleCount.value = session.samples.size
            _lastSample.value = session.samples.lastOrNull()
            if (session.trackName.isNotBlank()) {
                _currentTrackName.value = session.trackName
                trackManager.setSelectedTrack(session.trackName)
                refreshTracks()
                refreshCurrentTrackProfile(session.trackName)
                refreshCurrentTrackLayout(session.trackName)
            }
        }
    }

    fun refreshStoredSessions() {
        _storedSessions.value = sessionStorageManager.loadAllSessions()
    }

    fun reprocessSession(session: Session): Session {
        if (session.samples.isEmpty()) {
            Log.i(TAG, "Skipping reprocess for session ${session.id} because it has no samples")
            return session
        }

        Log.i(TAG, "Reprocessing session: ${session.id}")
        val reprocessed = processSessionInternal(
            session.copy(
                laps = emptyList(),
                insights = emptyList(),
                coachingInsights = emptyList(),
                theoreticalBestLapTimeMs = null,
                topTimeLossSegments = emptyList(),
                segmentMarkers = emptyList(),
                quality = null
            )
        )

        val updated = reprocessed.copy(processingVersion = CURRENT_PROCESSING_VERSION)

        Log.i(TAG, "Old version: ${session.processingVersion} -> New version: ${updated.processingVersion}")
        Log.i(TAG, "Lap count before/after: ${session.laps.size} -> ${updated.laps.size}")

        sessionStorageManager.saveSession(updated)
        refreshStoredSessions()
        updated.trackName.takeIf { trackName -> trackName.isNotBlank() }?.let(::refreshTrackProfileState)

        if (_currentSession.value?.id == updated.id) {
            _currentSession.value = updated
        }
        if (_latestSession.value?.id == updated.id) {
            _latestSession.value = updated
        }

        return updated
    }

    fun reprocessSessionAsync(session: Session) {
        repositoryScope.launch {
            Log.i(TAG, "Async reprocessing start for session ${session.id}")
            try {
                reprocessSession(session)
            } finally {
                Log.i(TAG, "Async reprocessing end for session ${session.id}")
            }
        }
    }

    private fun refreshTracks() {
        _availableTracks.value = trackManager.getTracks()
    }

    private fun refreshCurrentTrackProfile(trackName: String = _currentTrackName.value) {
        val profile = loadUsableTrackProfile(trackName)
        if (trackName.equals(_currentTrackName.value, ignoreCase = true)) {
            _currentTrackProfile.value = profile
        }
    }

    private fun refreshCurrentTrackLayout(trackName: String = _currentTrackName.value) {
        val layout = loadTrackLayout(trackName)
        if (trackName.equals(_currentTrackName.value, ignoreCase = true)) {
            _currentTrackLayout.value = layout
        }
    }

    private fun refreshCurrentTrackState(trackName: String = _currentTrackName.value) {
        refreshCurrentTrackProfile(trackName)
        refreshCurrentTrackLayout(trackName)
    }

    private fun refreshCurrentTrackStateAsync(trackName: String = _currentTrackName.value) {
        trackStateRefreshJob?.cancel()
        trackStateRefreshJob = repositoryScope.launch {
            refreshCurrentTrackState(trackName)
        }
    }

    private fun normalizeTrackLayout(layout: TrackLayout): TrackLayout {
        return layout.copy(corners = TrackLayoutMapper.sortAndRenameCorners(layout))
    }

    private fun upsertTrackFromLayout(layout: TrackLayout) {
        val existingTrack = trackManager.getTrack(layout.trackName) ?: Track(name = layout.trackName)
        trackManager.saveTrack(
            existingTrack.copy(
                name = layout.trackName,
                mapImagePath = layout.imagePath.ifBlank { existingTrack.mapImagePath },
                mapWidthMeters = existingTrack.mapWidthMeters ?: layout.lengthMeters,
                lengthMeters = existingTrack.lengthMeters ?: layout.lengthMeters,
                startPoint = PointF(layout.startPoint.x, layout.startPoint.y),
                startDirectionDeg = deriveStartDirection(layout)
            )
        )
    }

    private fun deriveStartDirection(layout: TrackLayout): Float {
        val centerX = 0.5f
        val centerY = 0.5f
        val radialAngle = Math.toDegrees(
            kotlin.math.atan2(
                (centerY - layout.startPoint.y).toDouble(),
                (layout.startPoint.x - centerX).toDouble()
            )
        ).toFloat()
        val tangentOffset = if (layout.direction == TrackDirection.COUNTER_CLOCKWISE) 90f else -90f
        return ((radialAngle + tangentOffset) + 360f) % 360f
    }

    private fun refreshTrackProfileState(trackName: String) {
        val sessionsForTrack = sessionStorageManager.loadSessionsForTrack(trackName)
        if (sessionsForTrack.isEmpty()) {
            trackProfileManager.deleteProfile(trackName)
        } else {
            val updatedProfile = trackProfileManager.updateProfile(trackName, sessionsForTrack)
            if (trackName.equals(_currentTrackName.value, ignoreCase = true) && updatedProfile.averageLapTimeMs > 0L) {
                _currentTrackProfile.value = updatedProfile
                return
            }
        }
        if (trackName.equals(_currentTrackName.value, ignoreCase = true)) {
            refreshCurrentTrackProfile(trackName)
        }
    }

    private fun startAutosaveLocked() {
        autosaveJob?.cancel()
        autosaveJob = repositoryScope.launch {
            while (isActive) {
                delay(AUTOSAVE_INTERVAL_MS)
                val partialSession = buildPartialSessionSnapshot() ?: continue
                persistSessionWithVerification(partialSession, "autosave")
            }
        }
    }

    private fun stopAutosaveLocked() {
        autosaveJob?.cancel()
        autosaveJob = null
    }

    private fun buildPartialSessionSnapshot(): Session? {
        synchronized(lock) {
            if (!_isRecording.value || currentStartTimeEpochMs == 0L) {
                return null
            }
            val snapshotSamples = currentSamples.toList()
            val lastTimestampNs = snapshotSamples.lastOrNull()?.timestampNs ?: currentStartTimestampNs
            val partialSession = Session(
                id = currentSessionId,
                trackName = _currentTrackName.value,
                startTimeEpochMs = currentStartTimeEpochMs,
                endTimeEpochMs = System.currentTimeMillis(),
                startTimestampNs = currentStartTimestampNs,
                endTimestampNs = lastTimestampNs,
                samples = snapshotSamples,
                laps = emptyList(),
                estimatedLapTimeMs = null,
                insights = emptyList(),
                coachingInsights = emptyList(),
                theoreticalBestLapTimeMs = null,
                topTimeLossSegments = emptyList(),
                segmentMarkers = emptyList(),
                processingVersion = 0,
                isPartial = true
            )
            _currentSession.value = partialSession
            return partialSession
        }
    }

    private fun buildProcessedSession(
        samples: List<SensorSample>,
        endTimestampNs: Long,
        endTimeEpochMs: Long,
        sourceSession: Session? = null
    ): Session {
        val baseSession = Session(
            id = sourceSession?.id ?: currentSessionId,
            trackName = sourceSession?.trackName ?: _currentTrackName.value,
            startTimeEpochMs = sourceSession?.startTimeEpochMs ?: currentStartTimeEpochMs,
            endTimeEpochMs = endTimeEpochMs,
            startTimestampNs = sourceSession?.startTimestampNs ?: currentStartTimestampNs,
            endTimestampNs = endTimestampNs,
            samples = samples,
            laps = emptyList(),
            estimatedLapTimeMs = null,
            insights = emptyList(),
            coachingInsights = emptyList(),
            theoreticalBestLapTimeMs = null,
            topTimeLossSegments = emptyList(),
            segmentMarkers = emptyList(),
            quality = null
        )

        return processSessionInternal(baseSession).copy(
            processingVersion = CURRENT_PROCESSING_VERSION,
            isPartial = false
        )
    }

    private fun classifyLaps(laps: List<Lap>): List<Lap> {
        if (laps.isEmpty()) {
            return emptyList()
        }

        val baselineLaps = laps.filter { lap ->
            lap.isNormalPhase && lap.confidenceScore >= minimumReferenceConfidence
        }
        val averageLapTimeMs = baselineLaps
            .map { lap -> lap.lapTimeMs }
            .average()
            .takeIf { average -> !average.isNaN() && average > 0.0 }

        return laps.map { lap ->
            val exceededReferenceLapTime = averageLapTimeMs?.let { referenceLapTime ->
                lap.lapTimeMs > (referenceLapTime * 1.15)
            } ?: false
            val isPhaseDisturbed = lap.isInlap || lap.isInterrupted
            val hasTooFewPeaks = !lap.isInterrupted &&
                (lap.brakingPeakIndices.size < minimumPeaksPerType || lap.corneringPeakIndices.size < minimumPeaksPerType)

            lap.copy(
                isDisturbed = isPhaseDisturbed ||
                    exceededReferenceLapTime ||
                    lap.confidenceScore < minimumDisturbedConfidence ||
                    hasTooFewPeaks
            )
        }
    }

    private fun resolveSectorBoundaries(trackProfile: TrackProfile?, lap: Lap): List<Int> {
        val profileBoundaries = trackProfile?.typicalSectorBoundaries.orEmpty()
        return if (isConsistentSectorLayout(profileBoundaries)) {
            profileBoundaries
        } else if (lap.sectorBoundaries.isNotEmpty()) {
            lap.sectorBoundaries
        } else {
            SectorDetector.detectSectors(lap)
        }
    }

    private fun loadUsableTrackProfile(trackName: String): TrackProfile? {
        val profile = trackProfileManager.loadProfile(trackName) ?: return null
        return profile.takeIf { candidate ->
            candidate.averageLapTimeMs in 15_000L..120_000L &&
                candidate.averageTotalAcceleration.isNotEmpty() &&
                candidate.averageYawRateAbs.isNotEmpty()
        }
    }

    private fun isConsistentSectorLayout(boundaries: List<Int>): Boolean {
        if (boundaries.size < 2) {
            return false
        }
        val sortedBoundaries = boundaries.sorted()
        if (sortedBoundaries.any { boundary -> boundary !in 1..99 }) {
            return false
        }
        return sortedBoundaries.zipWithNext().all { (previous, next) ->
            next - previous >= minimumSectorSpacingPercent
        }
    }

    private fun Session.withQuality(): Session {
        return copy(quality = SessionQualityEvaluator.evaluate(laps))
    }

    private fun processSessionInternal(session: Session): Session {
        return try {
            val trackProfile = loadUsableTrackProfile(session.trackName)
            val detectionResult = measureOperation("LapDetector.detect") {
                lapDetector.detect(session.samples, trackProfile)
            }
            val laps = measureOperation("Lap/Sector/Peak processing") {
                classifyLaps(
                    detectionResult.laps.map { lap ->
                        val sectorBoundaries = resolveSectorBoundaries(trackProfile, lap)
                        val smoothedAcceleration = smoothSignal(lap.samples.map { sample -> sample.totalAcceleration })
                        val smoothedYawRate = smoothSignal(lap.samples.map { sample -> sample.yawRateAbs })
                        lap.copy(
                            brakingPeakIndices = peakDetector.findBrakingPeaks(lap.samples, smoothedAcceleration),
                            corneringPeakIndices = peakDetector.findCorneringPeaks(
                                samples = lap.samples,
                                yawRateAbs = smoothedYawRate,
                                totalAcceleration = smoothedAcceleration
                            ),
                            sectorBoundaries = sectorBoundaries,
                            sectorTimesMs = SectorDetector.computeSectorTimes(lap, sectorBoundaries)
                        )
                    }
                )
            }

            val processedSession = session.copy(
                laps = laps,
                estimatedLapTimeMs = detectionResult.estimatedLapTimeMs,
                insights = emptyList(),
                coachingInsights = emptyList(),
                theoreticalBestLapTimeMs = null,
                topTimeLossSegments = emptyList(),
                segmentMarkers = emptyList(),
                quality = null,
                isPartial = false
            ).withQuality()

            val telemetryAnalysis = measureOperation("DrivingCoachAnalyzer.analyzeSession") {
                drivingCoachAnalyzer.analyzeSession(
                    session = processedSession,
                    trackProfile = trackProfile,
                    trackLayout = loadTrackLayout(processedSession.trackName)
                )
            }
            processedSession.copy(
                insights = telemetryAnalysis.insights,
                coachingInsights = telemetryAnalysis.coachingInsights,
                theoreticalBestLapTimeMs = telemetryAnalysis.theoreticalBestLapTimeMs,
                topTimeLossSegments = telemetryAnalysis.topTimeLossSegments,
                segmentMarkers = telemetryAnalysis.segmentMarkers
            )
        } catch (exception: Exception) {
            Log.e(TAG, "$LOG_TAG: processing pipeline failed for session=${session.id}", exception)
            session.copy(
                laps = emptyList(),
                estimatedLapTimeMs = null,
                insights = listOf("Partial analysis: session saved with limited insights due to processing error."),
                coachingInsights = emptyList(),
                theoreticalBestLapTimeMs = null,
                topTimeLossSegments = emptyList(),
                segmentMarkers = emptyList(),
                quality = null,
                isPartial = false
            )
        }
    }

    private fun persistSessionWithVerification(session: Session, stage: String) {
        val saved = sessionStorageManager.saveSession(session)
        if (!saved) {
            Log.e(TAG, "$LOG_TAG: failed to persist session=${session.id} stage=$stage")
        }
    }

    private fun <T> measureOperation(label: String, block: () -> T): T {
        var result: Any? = null
        val durationMs = measureTimeMillis {
            result = block()
        }
        if (durationMs > SLOW_OPERATION_THRESHOLD_MS) {
            Log.w(TAG, "$LOG_TAG: slow operation label=$label took=${durationMs}ms")
        } else {
            Log.i(TAG, "$LOG_TAG: operation label=$label took=${durationMs}ms")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun smoothSignal(values: List<Float>): List<Float> {
        if (values.isEmpty()) {
            return emptyList()
        }

        val radius = smoothingWindowSize / 2
        return values.indices.map { index ->
            val start = max(0, index - radius)
            val end = minOf(values.lastIndex, index + radius)
            var sum = 0f
            for (sampleIndex in start..end) {
                sum += values[sampleIndex]
            }
            sum / (end - start + 1)
        }
    }

    private fun shouldReprocessSession(session: Session): Boolean {
        if (session.samples.isEmpty()) {
            return false
        }
        if (session.processingVersion < CURRENT_PROCESSING_VERSION) {
            return true
        }
        if (session.laps.isEmpty()) {
            return true
        }
        if (session.quality == null) {
            return true
        }
        return session.laps.any { lap ->
            lap.sectorBoundaries.isEmpty() || lap.sectorTimesMs.isEmpty()
        }
    }

    companion object {
        private const val TAG = "SessionRepository"
        private const val CURRENT_PROCESSING_VERSION = 7
        private const val AUTOSAVE_INTERVAL_MS = 5_000L
        private const val minimumSectorSpacingPercent = 10
        private const val minimumReferenceConfidence = 0.7f
        private const val minimumDisturbedConfidence = 0.55f
        private const val minimumPeaksPerType = 2
        private const val smoothingWindowSize = 5
        private const val SLOW_OPERATION_THRESHOLD_MS = 100L
        private const val LOG_TAG = "KartingTracker"
    }
}
