package com.kartingtracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.kartingtracker.data.RecordingState
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.SimulatedSessionGenerator
import com.kartingtracker.data.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class RecorderPhase {
    IDLE,
    PREPARING,
    CALIBRATING,
    RECORDING,
    STOPPING
}

class SensorRecorder(
    context: Context,
    private val sessionRepository: SessionRepository
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelFilter = LowPassFilter()
    private val gyroFilter = LowPassFilter()
    private val calibrationManager = CalibrationManager()
    private val rateManager = AdaptiveSensorRateManager()

    private val sensorThread = HandlerThread("karting-sensor-thread").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var preStartCountdownJob: Job? = null
    private var stopProcessingJob: Job? = null

    @Volatile
    private var active = false

    private var listenersRegistered = false
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastSensorTimestampNs: Long = 0L

    private val _recorderPhase = MutableStateFlow(RecorderPhase.IDLE)
    val recorderPhase: StateFlow<RecorderPhase> = _recorderPhase.asStateFlow()
    private val _preStartSecondsRemaining = MutableStateFlow(0)
    val preStartSecondsRemaining: StateFlow<Int> = _preStartSecondsRemaining.asStateFlow()
    private val _recordingStartedAtEpochMs = MutableStateFlow<Long?>(null)
    val recordingStartedAtEpochMs: StateFlow<Long?> = _recordingStartedAtEpochMs.asStateFlow()

    val hasRequiredSensors: Boolean
        get() = accelerometer != null && gyroscope != null

    val isActive: Boolean
        get() = active

    fun startRecording() {
        if (!hasRequiredSensors || active) {
            Log.w(TAG, "$LOG_TAG: start ignored active=$active hasSensors=$hasRequiredSensors")
            return
        }
        active = true
        accelFilter.reset()
        gyroFilter.reset()
        lastGyro = floatArrayOf(0f, 0f, 0f)
        lastSensorTimestampNs = 0L
        rateManager.reset()  // Reset rate manager for new session
        _recordingStartedAtEpochMs.value = null
        preStartCountdownJob?.cancel()
        _recorderPhase.value = RecorderPhase.PREPARING
        sessionRepository.updateRecordingState(RecordingState.PRESTART_COUNTDOWN)
        Log.i(TAG, "$LOG_TAG: prestart countdown start seconds=$PRE_START_COUNTDOWN_SECONDS")
        preStartCountdownJob = recorderScope.launch {
            for (seconds in PRE_START_COUNTDOWN_SECONDS downTo 1) {
                if (!active) {
                    return@launch
                }
                _preStartSecondsRemaining.value = seconds
                delay(1_000L)
            }
            _preStartSecondsRemaining.value = 0
            if (!active) {
                return@launch
            }
            calibrationManager.startCalibration()
            _recorderPhase.value = RecorderPhase.CALIBRATING
            sessionRepository.updateRecordingState(RecordingState.CALIBRATING)
            Log.i(TAG, "$LOG_TAG: calibration start")
            if (!registerListeners()) {
                active = false
                _recorderPhase.value = RecorderPhase.IDLE
                calibrationManager.reset()
                sessionRepository.markRecordingFailed("Sensor listener registration failed", aborted = true)
                Log.e(TAG, "$LOG_TAG: failed to register sensor listeners")
            }
        }
    }

    fun stopRecording() {
        preStartCountdownJob?.cancel()
        preStartCountdownJob = null
        _preStartSecondsRemaining.value = 0
        if (!active && _recorderPhase.value == RecorderPhase.IDLE) {
            return
        }
        Log.i(TAG, "$LOG_TAG: sensor recorder stop phase=${_recorderPhase.value}")
        try {
            unregisterListeners()
        } catch (exception: Exception) {
            Log.e(TAG, "$LOG_TAG: listener unregistration failed", exception)
        } finally {
            val phaseAtStop = _recorderPhase.value
            active = false
            _recorderPhase.value = RecorderPhase.STOPPING
            sessionRepository.updateRecordingState(RecordingState.STOPPING)
            if (phaseAtStop == RecorderPhase.RECORDING || phaseAtStop == RecorderPhase.STOPPING) {
                val lastTimestamp = sessionRepository.lastSample.value?.timestampNs ?: lastSensorTimestampNs
                stopProcessingJob?.cancel()
                stopProcessingJob = recorderScope.launch {
                    runCatching { sessionRepository.stopSession(lastTimestamp) }
                        .onFailure { exception ->
                            Log.e(TAG, "$LOG_TAG: stopSession failed", exception)
                            sessionRepository.markRecordingFailed("Stop pipeline failed: ${exception.message}")
                        }
                    _recorderPhase.value = RecorderPhase.IDLE
                    _recordingStartedAtEpochMs.value = null
                }
            } else {
                _recorderPhase.value = RecorderPhase.IDLE
                _recordingStartedAtEpochMs.value = null
                sessionRepository.updateRecordingState(RecordingState.ABORTED)
            }
            calibrationManager.reset()
        }
    }

    fun shutdown() {
        stopRecording()
        recorderScope.cancel()
        runCatching {
            sensorThread.quitSafely()
        }.onFailure { exception ->
            Log.w(TAG, "Failed to stop sensor thread", exception)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        try {
            if (!active || event.values.size < 3) {
                return
            }
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    lastSensorTimestampNs = event.timestamp
                    lastGyro = gyroFilter.apply(event.values.copyOf())
                }

                Sensor.TYPE_ACCELEROMETER -> {
                    val previousTimestamp = lastSensorTimestampNs
                    lastSensorTimestampNs = event.timestamp

                    // Track actual sample rate and check for degradation
                    if (previousTimestamp > 0L && _recorderPhase.value == RecorderPhase.RECORDING) {
                        rateManager.onSampleReceived(event.timestamp, previousTimestamp)

                        if (rateManager.shouldDowngrade()) {
                            val downgraded = rateManager.downgrade()
                            if (downgraded) {
                                Log.w(TAG, "$LOG_TAG: sample drop rate high, downgrading to ${rateManager.currentRate.name} (${rateManager.currentRate.targetHz}Hz)")
                                unregisterListeners()
                                registerListeners()
                            }
                        }
                    }

                    val filteredAccel = accelFilter.apply(event.values.copyOf())
                    if (_recorderPhase.value == RecorderPhase.CALIBRATING) {
                        val calibrationFinished = calibrationManager.addCalibrationSample(filteredAccel, event.timestamp)
                        if (calibrationFinished) {
                            sessionRepository.startSession(event.timestamp)
                            _recorderPhase.value = RecorderPhase.RECORDING
                            sessionRepository.updateRecordingState(RecordingState.RECORDING)
                            Log.i(TAG, "$LOG_TAG: entered RECORDING at timestampNs=${event.timestamp}")
                            _recordingStartedAtEpochMs.value = System.currentTimeMillis()
                        }
                        return
                    }

                    if (_recorderPhase.value != RecorderPhase.RECORDING) {
                        return
                    }

                    val calibratedAcceleration = calibrationManager.projectAcceleration(filteredAccel)
                    val sample = SensorSample(
                        timestampNs = event.timestamp,
                        accelX = filteredAccel.getOrElse(0) { 0f },
                        accelY = filteredAccel.getOrElse(1) { 0f },
                        accelZ = filteredAccel.getOrElse(2) { 0f },
                        gyroX = lastGyro.getOrElse(0) { 0f },
                        gyroY = lastGyro.getOrElse(1) { 0f },
                        gyroZ = lastGyro.getOrElse(2) { 0f },
                        longitudinalAccel = calibratedAcceleration.longitudinalAcceleration,
                        lateralAccel = calibratedAcceleration.lateralAcceleration,
                        totalAcceleration = calibratedAcceleration.totalAcceleration,
                        yawRateAbs = calculateGyroMagnitude(lastGyro)
                    )
                    sessionRepository.appendSample(sample)
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "$LOG_TAG: sensor callback failed", exception)
            sessionRepository.markRecordingFailed("Sensor callback failed: ${exception.message}")
        }
    }

    fun simulateRecording(trackName: String, durationMinutes: Int) {
        val synthetic = SimulatedSessionGenerator.generateSeededSession(
            trackName = trackName,
            seed = 42 + durationMinutes,
            durationMinutes = durationMinutes
        )
        sessionRepository.updateRecordingState(RecordingState.PRESTART_COUNTDOWN)
        sessionRepository.startSession(synthetic.startTimestampNs)
        synthetic.samples.forEach { sample -> sessionRepository.appendSample(sample) }
        sessionRepository.stopSession(synthetic.endTimestampNs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerListeners(): Boolean {
        if (listenersRegistered || !hasRequiredSensors) {
            return listenersRegistered
        }
        val accelRegistered = accelerometer?.let { sensor ->
            sensorManager.registerListener(this, sensor, rateManager.currentRate.delay, sensorHandler)
        } ?: false
        val gyroRegistered = gyroscope?.let { sensor ->
            sensorManager.registerListener(this, sensor, rateManager.currentRate.delay, sensorHandler)
        } ?: false
        listenersRegistered = accelRegistered && gyroRegistered
        if (!listenersRegistered) {
            sensorManager.unregisterListener(this)
        }
        if (listenersRegistered) {
            Log.i(TAG, "$LOG_TAG: sensors registered at ${rateManager.currentRate.name} rate (${rateManager.currentRate.targetHz}Hz)")
        }
        return listenersRegistered
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) {
            return
        }
        sensorManager.unregisterListener(this)
        listenersRegistered = false
    }

    private fun calculateGyroMagnitude(gyroValues: FloatArray): Float {
        return sqrt(
            (gyroValues[0] * gyroValues[0]) +
                (gyroValues[1] * gyroValues[1]) +
                (gyroValues[2] * gyroValues[2])
        )
    }

    companion object {
        private const val TAG = "SensorRecorder"
        private const val LOG_TAG = "KartingTracker"
        private const val PRE_START_COUNTDOWN_SECONDS = 10
    }
}
