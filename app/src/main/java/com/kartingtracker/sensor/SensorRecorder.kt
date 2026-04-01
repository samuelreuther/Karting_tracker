package com.kartingtracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

enum class RecorderPhase {
    IDLE,
    CALIBRATING,
    RECORDING
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

    private val sensorThread = HandlerThread("karting-sensor-thread").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    @Volatile
    private var active = false

    private var listenersRegistered = false
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var lastSensorTimestampNs: Long = 0L

    private val _recorderPhase = MutableStateFlow(RecorderPhase.IDLE)
    val recorderPhase: StateFlow<RecorderPhase> = _recorderPhase.asStateFlow()

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
        calibrationManager.startCalibration()
        lastGyro = floatArrayOf(0f, 0f, 0f)
        lastSensorTimestampNs = 0L
        _recorderPhase.value = RecorderPhase.CALIBRATING
        Log.i(TAG, "$LOG_TAG: sensor recorder start")
        if (!registerListeners()) {
            active = false
            _recorderPhase.value = RecorderPhase.IDLE
            calibrationManager.reset()
            Log.e(TAG, "$LOG_TAG: failed to register sensor listeners")
        }
    }

    fun stopRecording() {
        if (!active) {
            return
        }
        Log.i(TAG, "$LOG_TAG: sensor recorder stop phase=${_recorderPhase.value}")
        try {
            unregisterListeners()
        } catch (exception: Exception) {
            Log.e(TAG, "$LOG_TAG: listener unregistration failed", exception)
        } finally {
            active = false
            val phaseAtStop = _recorderPhase.value
            _recorderPhase.value = RecorderPhase.IDLE
            if (phaseAtStop == RecorderPhase.RECORDING) {
                val lastTimestamp = sessionRepository.lastSample.value?.timestampNs ?: lastSensorTimestampNs
                runCatching { sessionRepository.stopSession(lastTimestamp) }
                    .onFailure { exception ->
                        Log.e(TAG, "$LOG_TAG: stopSession failed", exception)
                    }
            }
            calibrationManager.reset()
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
                    lastSensorTimestampNs = event.timestamp
                    val filteredAccel = accelFilter.apply(event.values.copyOf())
                    if (_recorderPhase.value == RecorderPhase.CALIBRATING) {
                        val calibrationFinished = calibrationManager.addCalibrationSample(filteredAccel, event.timestamp)
                        if (calibrationFinished) {
                            sessionRepository.startSession(event.timestamp)
                            _recorderPhase.value = RecorderPhase.RECORDING
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
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerListeners(): Boolean {
        if (listenersRegistered || !hasRequiredSensors) {
            return listenersRegistered
        }
        val accelRegistered = accelerometer?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        } ?: false
        val gyroRegistered = gyroscope?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        } ?: false
        listenersRegistered = accelRegistered && gyroRegistered
        if (!listenersRegistered) {
            sensorManager.unregisterListener(this)
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
    }
}
