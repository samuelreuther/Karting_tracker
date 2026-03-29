package com.kartingtracker

import android.content.Context
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.data.SessionStorageManager
import com.kartingtracker.data.SimulatedSessionGenerator
import com.kartingtracker.data.TrackManager
import com.kartingtracker.data.TrackProfileManager
import com.kartingtracker.domain.DrivingCoachAnalyzer
import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.sensor.SensorRecorder

class AppContainer(context: Context) {
    private val lapDetector = LapDetector()
    private val peakDetector = PeakDetector()
    private val sessionStorageManager = SessionStorageManager(context.applicationContext)
    private val trackProfileManager = TrackProfileManager(context.applicationContext)
    private val trackManager = TrackManager(
        context = context.applicationContext,
        sessionStorageManager = sessionStorageManager,
        trackProfileManager = trackProfileManager
    )
    private val drivingCoachAnalyzer = DrivingCoachAnalyzer()

    init {
        if (BuildConfig.DEBUG) {
            SimulatedSessionGenerator.seedDebugSessionIfNeeded(
                context = context.applicationContext,
                sessionStorageManager = sessionStorageManager,
                trackManager = trackManager,
                trackProfileManager = trackProfileManager
            )
        }
    }

    val sessionRepository = SessionRepository(
        lapDetector = lapDetector,
        peakDetector = peakDetector,
        sessionStorageManager = sessionStorageManager,
        trackManager = trackManager,
        trackProfileManager = trackProfileManager,
        drivingCoachAnalyzer = drivingCoachAnalyzer
    )

    val sensorRecorder = SensorRecorder(
        context = context.applicationContext,
        sessionRepository = sessionRepository
    )
}
