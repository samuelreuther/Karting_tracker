package com.kartingtracker.data

import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRepository(
    private val lapDetector: LapDetector,
    private val peakDetector: PeakDetector,
    private val sessionStorageManager: SessionStorageManager,
    private val trackManager: TrackManager
) {
    private val lock = Any()
    private var currentSessionId: Long = sessionStorageManager
        .loadAllSessions()
        .maxOfOrNull { session -> session.id }
        ?: 0L
    private var currentStartTimestampNs: Long = 0L
    private var currentStartTimeEpochMs: Long = 0L
    private val currentSamples = mutableListOf<SensorSample>()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _lastSample = MutableStateFlow<SensorSample?>(null)
    val lastSample: StateFlow<SensorSample?> = _lastSample.asStateFlow()

    private val _latestSession = MutableStateFlow<Session?>(null)
    val latestSession: StateFlow<Session?> = _latestSession.asStateFlow()

    private val _storedSessions = MutableStateFlow(sessionStorageManager.loadAllSessions())
    val storedSessions: StateFlow<List<Session>> = _storedSessions.asStateFlow()

    private val _availableTracks = MutableStateFlow(trackManager.getTracks())
    val availableTracks: StateFlow<List<Track>> = _availableTracks.asStateFlow()

    private val _currentTrackName = MutableStateFlow(trackManager.getSelectedTrackName())
    val currentTrackName: StateFlow<String> = _currentTrackName.asStateFlow()

    fun startSession(startTimestampNs: Long) {
        synchronized(lock) {
            currentSessionId += 1L
            currentStartTimestampNs = startTimestampNs
            currentStartTimeEpochMs = System.currentTimeMillis()
            currentSamples.clear()
            _latestSession.value = null
            _sampleCount.value = 0
            _lastSample.value = null
            _isRecording.value = true
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
        synchronized(lock) {
            if (!_isRecording.value) {
                return _latestSession.value
            }

            _isRecording.value = false
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
                sessionStorageManager.saveSession(emptySession)
                refreshStoredSessions()
                return emptySession
            }

            val rawSamples = currentSamples.toList()
            val detectionResult = lapDetector.detect(rawSamples)
            val laps = detectionResult.laps.map { lap ->
                lap.copy(
                    brakingPeakIndices = peakDetector.findBrakingPeaks(lap.samples),
                    corneringPeakIndices = peakDetector.findCorneringPeaks(lap.samples)
                )
            }

            val session = Session(
                id = currentSessionId,
                trackName = _currentTrackName.value,
                startTimeEpochMs = currentStartTimeEpochMs,
                endTimeEpochMs = System.currentTimeMillis(),
                startTimestampNs = currentStartTimestampNs,
                endTimestampNs = endTimestampNs,
                samples = rawSamples,
                laps = laps,
                estimatedLapTimeMs = detectionResult.estimatedLapTimeMs
            )
            _latestSession.value = session
            sessionStorageManager.saveSession(session)
            refreshStoredSessions()
            return session
        }
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
    }

    fun loadSessionsForTrack(trackName: String): List<Session> {
        return sessionStorageManager.loadSessionsForTrack(trackName)
    }

    fun loadLastSession(): Session? {
        val session = sessionStorageManager.loadLastSession() ?: return null
        loadSession(session)
        return session
    }

    fun loadSession(session: Session) {
        synchronized(lock) {
            _isRecording.value = false
            _latestSession.value = session
            _sampleCount.value = session.samples.size
            _lastSample.value = session.samples.lastOrNull()
            if (session.trackName.isNotBlank()) {
                _currentTrackName.value = session.trackName
                trackManager.setSelectedTrack(session.trackName)
                refreshTracks()
            }
        }
    }

    fun refreshStoredSessions() {
        _storedSessions.value = sessionStorageManager.loadAllSessions()
    }

    private fun refreshTracks() {
        _availableTracks.value = trackManager.getTracks()
    }
}
