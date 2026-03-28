package com.kartingtracker.data

import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SessionRepository(
    private val lapDetector: LapDetector,
    private val peakDetector: PeakDetector,
    private val sessionStorageManager: SessionStorageManager,
    private val trackManager: TrackManager,
    private val trackProfileManager: TrackProfileManager
) {
    private val lock = Any()
    private var currentSessionId: Long = sessionStorageManager
        .loadAllSessions()
        .maxOfOrNull { session -> session.id }
        ?: 0L
    private var currentStartTimestampNs: Long = 0L
    private var currentStartTimeEpochMs: Long = 0L
    private val currentSamples = mutableListOf<SensorSample>()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autosaveJob: Job? = null

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

    private val _storedSessions = MutableStateFlow(sessionStorageManager.loadAllSessions())
    val storedSessions: StateFlow<List<Session>> = _storedSessions.asStateFlow()

    private val _availableTracks = MutableStateFlow(trackManager.getTracks())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _currentTrackName = MutableStateFlow(trackManager.getSelectedTrackName())
    val currentTrackName: StateFlow<String> = _currentTrackName.asStateFlow()

    private val _currentTrackProfile = MutableStateFlow(loadUsableTrackProfile(_currentTrackName.value))
    val currentTrackProfile: StateFlow<TrackProfile?> = _currentTrackProfile.asStateFlow()

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

            _isRecording.value = false
            stopAutosaveLocked()
            if (currentSamples.isEmpty()) {
                val emptySession = Session(
                    id = currentSessionId,
                    trackName = _currentTrackName.value,
                    startTimeEpochMs = currentStartTimeEpochMs,
                    endTimeEpochMs = System.currentTimeMillis(),
                    startTimestampNs = currentStartTimestampNs,
                    endTimestampNs = endTimestampNs,
                    samples = emptyList(),
                    laps = emptyList()
                )
                _latestSession.value = emptySession
                _currentSession.value = emptySession
                sessionStorageManager.saveSession(emptySession)
                refreshStoredSessions()
                completedSession = emptySession
            } else {
                val session = buildProcessedSession(
                    samples = currentSamples.toList(),
                    endTimestampNs = endTimestampNs,
                    endTimeEpochMs = System.currentTimeMillis()
                )
                _latestSession.value = session
                _currentSession.value = session
                sessionStorageManager.saveSession(session)
                refreshStoredSessions()
                completedSession = session
            }
        }
        completedSession?.trackName?.takeIf { trackName -> trackName.isNotBlank() }?.let { trackName ->
            val sessionsForTrack = sessionStorageManager.loadSessionsForTrack(trackName)
            val updatedProfile = trackProfileManager.updateProfile(trackName, sessionsForTrack)
            if (updatedProfile.averageLapTimeMs > 0L && trackName == _currentTrackName.value) {
                _currentTrackProfile.value = updatedProfile
            }
        }
        return completedSession
    }

    fun createTrack(trackName: String): Track? {
        val track = trackManager.saveTrack(trackName) ?: return null
        refreshTracks()
        selectTrack(track.name)
        return track
    }

    fun selectTrack(trackName: String) {
        trackManager.setSelectedTrack(trackName)
        _currentTrackName.value = trackName
        refreshTracks()
        refreshStoredSessions()
        refreshCurrentTrackProfile(trackName)
    }

    fun loadSessionsForTrack(trackName: String): List<Session> {
        return sessionStorageManager.loadSessionsForTrack(trackName)
    }

    fun loadLastSession(): Session? {
        val session = sessionStorageManager.loadLastSession() ?: return null
        loadSession(session)
        return _currentSession.value
    }

    fun loadSession(session: Session) {
        synchronized(lock) {
            stopAutosaveLocked()
            val preparedSession = prepareSessionForUse(session)
            _isRecording.value = false
            _latestSession.value = preparedSession
            _currentSession.value = preparedSession
            _sampleCount.value = preparedSession.samples.size
            _lastSample.value = preparedSession.samples.lastOrNull()
            if (preparedSession.trackName.isNotBlank()) {
                _currentTrackName.value = preparedSession.trackName
                trackManager.setSelectedTrack(preparedSession.trackName)
                refreshTracks()
                refreshCurrentTrackProfile(preparedSession.trackName)
            }
        }
    }

    fun refreshStoredSessions() {
        _storedSessions.value = sessionStorageManager.loadAllSessions()
    }

    private fun refreshTracks() {
        _availableTracks.value = trackManager.getTracks()
    }

    private fun refreshCurrentTrackProfile(trackName: String = _currentTrackName.value) {
        _currentTrackProfile.value = loadUsableTrackProfile(trackName)
    }

    private fun startAutosaveLocked() {
        autosaveJob?.cancel()
        autosaveJob = repositoryScope.launch {
            while (isActive) {
                delay(AUTOSAVE_INTERVAL_MS)
                val partialSession = buildPartialSessionSnapshot() ?: continue
                sessionStorageManager.saveSession(partialSession)
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
                estimatedLapTimeMs = null
            )
            _currentSession.value = partialSession
            return partialSession
        }
    }

    private fun prepareSessionForUse(session: Session): Session {
        if (session.samples.isEmpty() || session.laps.isNotEmpty()) {
            return session
        }

        val recoveredSession = buildProcessedSession(
            sourceSession = session,
            samples = session.samples,
            endTimestampNs = session.endTimestampNs,
            endTimeEpochMs = session.endTimeEpochMs
        )
        sessionStorageManager.saveSession(recoveredSession)
        refreshStoredSessions()
        return recoveredSession
    }

    private fun buildProcessedSession(
        samples: List<SensorSample>,
        endTimestampNs: Long,
        endTimeEpochMs: Long,
        sourceSession: Session? = null
    ): Session {
        val trackName = sourceSession?.trackName ?: _currentTrackName.value
        val detectionResult = lapDetector.detect(samples, loadUsableTrackProfile(trackName))
        val laps = detectionResult.laps.map { lap ->
            lap.copy(
                brakingPeakIndices = peakDetector.findBrakingPeaks(lap.samples),
                corneringPeakIndices = peakDetector.findCorneringPeaks(lap.samples)
            )
        }

        return Session(
            id = sourceSession?.id ?: currentSessionId,
            trackName = trackName,
            startTimeEpochMs = sourceSession?.startTimeEpochMs ?: currentStartTimeEpochMs,
            endTimeEpochMs = endTimeEpochMs,
            startTimestampNs = sourceSession?.startTimestampNs ?: currentStartTimestampNs,
            endTimestampNs = endTimestampNs,
            samples = samples,
            laps = laps,
            estimatedLapTimeMs = detectionResult.estimatedLapTimeMs
        )
    }

    private fun loadUsableTrackProfile(trackName: String): TrackProfile? {
        val profile = trackProfileManager.loadProfile(trackName) ?: return null
        return profile.takeIf { candidate ->
            candidate.averageLapTimeMs in 15_000L..120_000L &&
                candidate.averageTotalAcceleration.isNotEmpty() &&
                candidate.averageYawRateAbs.isNotEmpty()
        }
    }

    companion object {
        private const val AUTOSAVE_INTERVAL_MS = 5_000L
    }
}
