package com.kartingtracker.data

import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRepository(
    private val lapDetector: LapDetector,
    private val peakDetector: PeakDetector
) {
    private val lock = Any()
    private var currentSessionId: Long = 0L
    private var currentStartTimestampNs: Long = 0L
    private val currentSamples = mutableListOf<SensorSample>()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _sampleCount = MutableStateFlow(0)
    val sampleCount: StateFlow<Int> = _sampleCount.asStateFlow()

    private val _lastSample = MutableStateFlow<SensorSample?>(null)
    val lastSample: StateFlow<SensorSample?> = _lastSample.asStateFlow()

    private val _latestSession = MutableStateFlow<Session?>(null)
    val latestSession: StateFlow<Session?> = _latestSession.asStateFlow()

    fun startSession(startTimestampNs: Long) {
        synchronized(lock) {
            currentSessionId += 1L
            currentStartTimestampNs = startTimestampNs
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
                    startTimestampNs = currentStartTimestampNs,
                    endTimestampNs = endTimestampNs,
                    samples = emptyList(),
                    laps = emptyList()
                )
                _latestSession.value = emptySession
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
                startTimestampNs = currentStartTimestampNs,
                endTimestampNs = endTimestampNs,
                samples = rawSamples,
                laps = laps,
                estimatedLapTimeMs = detectionResult.estimatedLapTimeMs
            )
            _latestSession.value = session
            return session
        }
    }
}
