package com.kartingtracker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kartingtracker.KartingApplication
import com.kartingtracker.R
import com.kartingtracker.sensor.RecorderPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RecordingForegroundService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val appContainer by lazy {
        (application as KartingApplication).appContainer
    }
    private val sessionRepository by lazy { appContainer.sessionRepository }
    private val sensorRecorder by lazy { appContainer.sensorRecorder }
    private val notificationHelper by lazy { RecordingNotificationHelper(this) }
    private val wakeLock by lazy { createWakeLock() }

    private var notificationJob: Job? = null
    private var serviceStartedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        notificationHelper.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopRecordingAndService()
                ACTION_START -> handleStart(intent)
                null -> handleRestart()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Recording service failed during startup", exception)
            stopServiceInternal("Service startup failed")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        if (sensorRecorder.recorderPhase.value != RecorderPhase.IDLE) {
            safeStopRecording("Service destroyed while recorder was active")
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun getElapsedTimeMs(): Long {
        return if (serviceStartedAtMs == 0L) 0L else (System.currentTimeMillis() - serviceStartedAtMs).coerceAtLeast(0L)
    }

    private fun handleStart(intent: Intent?) {
        val requestedTrackName = intent?.getStringExtra(EXTRA_TRACK_NAME)?.trim().orEmpty()
        if (!promoteToForeground()) {
            stopServiceInternal("Unable to promote recording service to foreground")
            return
        }
        startNotificationUpdates()

        if (!sensorRecorder.hasRequiredSensors) {
            stopRecordingAndService()
            return
        }

        if (sensorRecorder.recorderPhase.value == RecorderPhase.IDLE) {
            if (requestedTrackName.isNotBlank()) {
                sessionRepository.selectTrack(requestedTrackName)
            }
            serviceStartedAtMs = System.currentTimeMillis()
            acquireWakeLock()
            try {
                sensorRecorder.startRecording()
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start sensor recording", exception)
                stopServiceInternal("Sensor recorder startup failed")
            }
        } else if (serviceStartedAtMs == 0L) {
            serviceStartedAtMs = System.currentTimeMillis()
            acquireWakeLock()
        }
    }

    private fun handleRestart() {
        if (sensorRecorder.isActive || sensorRecorder.recorderPhase.value != RecorderPhase.IDLE) {
            if (!promoteToForeground()) {
                stopServiceInternal("Unable to restore recording service in foreground")
                return
            }
            startNotificationUpdates()
            if (serviceStartedAtMs == 0L) {
                serviceStartedAtMs = System.currentTimeMillis()
            }
            acquireWakeLock()
            return
        }
        stopSelf()
    }

    private fun promoteToForeground(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                }
                ServiceCompat.startForeground(
                    this,
                    RecordingNotificationHelper.NOTIFICATION_ID,
                    notification,
                    serviceType
                )
            } else {
                startForeground(RecordingNotificationHelper.NOTIFICATION_ID, notification)
            }
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to enter foreground mode", exception)
            false
        }
    }

    private fun startNotificationUpdates() {
        if (notificationJob != null) {
            return
        }

        notificationJob = serviceScope.launch {
            while (isActive) {
                try {
                    notificationHelper.notify(buildNotification())
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to update recording notification", exception)
                    stopServiceInternal("Notification updates failed")
                    return@launch
                }
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopRecordingAndService() {
        stopServiceInternal("Recording stopped")
    }

    private fun stopServiceInternal(reason: String) {
        notificationJob?.cancel()
        notificationJob = null
        safeStopRecording(reason)
        releaseWakeLock()
        serviceStartedAtMs = 0L
        stopForegroundSafely()
        stopSelf()
    }

    private fun safeStopRecording(reason: String) {
        if (sensorRecorder.recorderPhase.value == RecorderPhase.IDLE) {
            return
        }
        try {
            sensorRecorder.stopRecording()
        } catch (exception: Exception) {
            Log.e(TAG, "$reason: recorder shutdown failed", exception)
        }
    }

    private fun stopForegroundSafely() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to stop foreground state cleanly", exception)
        }
    }

    private fun buildNotification() = notificationHelper.buildNotification(
        trackName = sessionRepository.currentTrackName.value,
        phaseLabel = when (sensorRecorder.recorderPhase.value) {
            RecorderPhase.CALIBRATING -> getString(R.string.recording_phase_calibrating)
            RecorderPhase.RECORDING -> getString(R.string.recording_phase_recording)
            RecorderPhase.IDLE -> getString(R.string.recording_phase_starting)
        },
        elapsedMs = getElapsedTimeMs(),
        sampleCount = sessionRepository.sampleCount.value,
        lapCount = sessionRepository.currentSession.value?.laps?.size ?: 0
    )

    private fun createWakeLock(): PowerManager.WakeLock {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:recording"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingForegroundService = this@RecordingForegroundService
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "com.kartingtracker.action.START_RECORDING"
        const val ACTION_STOP = "com.kartingtracker.action.STOP_RECORDING"
        const val EXTRA_TRACK_NAME = "extra_track_name"

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L
    }
}

fun Context.startRecordingService(trackName: String) {
    val intent = Intent(applicationContext, RecordingForegroundService::class.java).apply {
        action = RecordingForegroundService.ACTION_START
        putExtra(RecordingForegroundService.EXTRA_TRACK_NAME, trackName)
    }
    ContextCompat.startForegroundService(applicationContext, intent)
}

fun Context.stopRecordingService() {
    val intent = Intent(applicationContext, RecordingForegroundService::class.java).apply {
        action = RecordingForegroundService.ACTION_STOP
    }
    applicationContext.startService(intent)
}
