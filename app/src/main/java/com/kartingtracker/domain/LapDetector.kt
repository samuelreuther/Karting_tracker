package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import com.kartingtracker.data.TrackProfile

data class LapDetectionResult(
    val laps: List<Lap>,
    val estimatedLapTimeMs: Long? = null,
    val confidenceScores: List<Float> = emptyList()
)

class LapDetector {
    private val lapDetector2: LapDetector2 = LapDetector2()

    fun detect(samples: List<SensorSample>, trackProfile: TrackProfile? = null): LapDetectionResult {
        return lapDetector2.detect(samples, trackProfile)
    }
}
