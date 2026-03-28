package com.kartingtracker

import android.content.Context
import com.kartingtracker.data.SimulatedSessionGenerator
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.data.SessionStorageManager
import com.kartingtracker.data.TrackManager
import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.sensor.SensorRecorder

class AppContainer(context: Context) {
    private val lapDetector = LapDetector()
    private val peakDetector = PeakDetector()
    private val sessionStorageManager = SessionStorageManager(context.applicationContext)
    private val trackManager = TrackManager(context.applicationContext)

    init {
        if (BuildConfig.DEBUG) {
            SimulatedSessionGenerator.seedDebugSessionIfNeeded(
                context = context.applicationContext,
                sessionStorageManager = sessionStorageManager,
                trackManager = trackManager
            )
        }
    }

    val sessionRepository = SessionRepository(
        lapDetector = lapDetector,
        peakDetector = peakDetector,
        sessionStorageManager = sessionStorageManager,
        trackManager = trackManager
    )

    val sensorRecorder = SensorRecorder(
        context = context.applicationContext,
        sessionRepository = sessionRepository
    )
}
