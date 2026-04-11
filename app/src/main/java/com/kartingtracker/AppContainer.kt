package com.kartingtracker

import android.content.Context
import com.kartingtracker.data.SessionCsvExporter
import com.kartingtracker.data.SessionRepository
import com.kartingtracker.data.SessionStorageManager
import com.kartingtracker.data.AppBackupManager
import com.kartingtracker.data.TrackLayoutManager
import com.kartingtracker.data.TrackMapManager
import com.kartingtracker.data.TrackManager
import com.kartingtracker.data.TrackProfileManager
import com.kartingtracker.domain.DrivingCoachAnalyzer
import com.kartingtracker.domain.LapDetector
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.domain.corner.CornerCoachingAnalyzer
import com.kartingtracker.sensor.SensorRecorder

class AppContainer(context: Context) {
    private val lapDetector = LapDetector()
    private val peakDetector = PeakDetector()
    private val sessionStorageManager = SessionStorageManager(context.applicationContext)
    private val trackProfileManager = TrackProfileManager(context.applicationContext)
    private val trackLayoutManager = TrackLayoutManager(context.applicationContext)
    private val trackMapManager = TrackMapManager(context.applicationContext)
    private val trackManager = TrackManager(
        context = context.applicationContext,
        sessionStorageManager = sessionStorageManager,
        trackProfileManager = trackProfileManager,
        trackLayoutManager = trackLayoutManager
    )
    private val drivingCoachAnalyzer = DrivingCoachAnalyzer()
    private val cornerCoachingAnalyzer = CornerCoachingAnalyzer()
    private val sessionCsvExporter = SessionCsvExporter(context.applicationContext)
    private val appBackupManager = AppBackupManager(context.applicationContext)

    init {
        trackMapManager.seedBundledMaps(trackManager)
        trackLayoutManager.seedBundledTracks(trackManager)
        listOf("Test Track", "Demo Indoor Track", "sr test").forEach(trackManager::deleteTrack)
    }

    val sessionRepository = SessionRepository(
        context = context.applicationContext,
        lapDetector = lapDetector,
        peakDetector = peakDetector,
        sessionStorageManager = sessionStorageManager,
        trackManager = trackManager,
        trackProfileManager = trackProfileManager,
        trackLayoutManager = trackLayoutManager,
        trackMapManager = trackMapManager,
        drivingCoachAnalyzer = drivingCoachAnalyzer,
        cornerCoachingAnalyzer = cornerCoachingAnalyzer,
        sessionCsvExporter = sessionCsvExporter,
        appBackupManager = appBackupManager
    )

    val sensorRecorder = SensorRecorder(
        context = context.applicationContext,
        sessionRepository = sessionRepository
    )
}
