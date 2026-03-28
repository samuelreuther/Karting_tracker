package com.kartingtracker

import android.content.Context
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.sensor.SensorRecorder

class AppContainer(context: Context) {
    private val lapDetector = LapDetector()
    private val peakDetector = PeakDetector()

    val sessionRepository = SessionRepository(
        lapDetector = lapDetector,
        peakDetector = peakDetector
    )

    val sensorRecorder = SensorRecorder(
        context = context.applicationContext,
        sessionRepository = sessionRepository
    )
}
