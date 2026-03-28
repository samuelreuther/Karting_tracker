package com.kartingtracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.SessionRepository

class SensorRecorder(
    context: Context,
    private val sessionRepository: SessionRepository
) : SensorEventListener, DefaultLifecycleObserver {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelFilter = LowPassFilter()
    private val gyroFilter = LowPassFilter()

    private val sensorThread = HandlerThread("karting-sensor-thread").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    @Volatile
    private var active = false

    private var listenersRegistered = false
    private var lastGyro = floatArrayOf(0f, 0f, 0f)

    val hasRequiredSensors: Boolean
        get() = accelerometer != null && gyroscope != null

    fun startRecording() {
        if (!hasRequiredSensors || active) {
            return
        }
        active = true
        lastGyro = floatArrayOf(0f, 0f, 0f)
        sessionRepository.startSession(SystemClock.elapsedRealtimeNanos())
        registerListeners()
    }

    fun stopRecording() {
        if (!active) {
            return
        }
        unregisterListeners()
        active = false
        sessionRepository.stopSession(SystemClock.elapsedRealtimeNanos())
    }

    override fun onStart(owner: LifecycleOwner) {
        if (active) {
            registerListeners()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        unregisterListeners()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterListeners()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro = gyroFilter.apply(event.values.copyOf())
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val filteredAccel = accelFilter.apply(event.values.copyOf())
                val sample = SensorSample(
                    timestampNs = event.timestamp,
                    accelX = filteredAccel[0],
                    accelY = filteredAccel[1],
                    accelZ = filteredAccel[2],
                    gyroX = lastGyro[0],
                    gyroY = lastGyro[1],
                    gyroZ = lastGyro[2],
                    longitudinalAccel = filteredAccel[1],
                    lateralAccel = filteredAccel[0]
                )
                sessionRepository.appendSample(sample)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerListeners() {
        if (listenersRegistered || !hasRequiredSensors) {
            return
        }
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        }
        gyroscope?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        }
        listenersRegistered = true
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) {
            return
        }
        sensorManager.unregisterListener(this)
        listenersRegistered = false
    }
}
