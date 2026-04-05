package com.kartingtracker.data

import com.kartingtracker.domain.DrivingCoachAnalyzer
import com.kartingtracker.domain.LapDetector2
import com.kartingtracker.domain.SectorDetector
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedSessionGeneratorTrackAwareTest {
    private val lapDetector2 = LapDetector2()
    private val drivingCoachAnalyzer = DrivingCoachAnalyzer()

    @Test
    fun loerrachSeededSessionProducesStableLapAndCoachingSignals() {
        val session = SimulatedSessionGenerator.generateSeededSession(
            trackName = "Loerrach VM Kart Racing",
            seed = 42,
            durationMinutes = 10
        )

        val detection = lapDetector2.detect(session.samples)
        assertTrue("Expected around 24 laps, got ${detection.laps.size}", detection.laps.size in 22..26)

        val lapsWithSectors = detection.laps.map { lap ->
            val boundaries = SectorDetector.detectSectors(lap)
            lap.copy(
                sectorBoundaries = boundaries,
                sectorTimesMs = SectorDetector.computeSectorTimes(lap, boundaries)
            )
        }
        assertTrue("Expected sector boundaries for at least one lap", lapsWithSectors.any { it.sectorBoundaries.isNotEmpty() })

        val processed = session.copy(laps = lapsWithSectors, estimatedLapTimeMs = detection.estimatedLapTimeMs)
        val analysis = drivingCoachAnalyzer.analyzeSession(processed)

        assertTrue("Expected coaching insights", analysis.coachingInsights.isNotEmpty())
        assertTrue("Expected segment markers", analysis.segmentMarkers.isNotEmpty())
    }

    @Test
    fun seededGenerationIsDeterministicAcrossRuns() {
        val first = SimulatedSessionGenerator.generateSeededSession(
            trackName = "Loerrach VM Kart Racing",
            seed = 1337,
            durationMinutes = 10
        )
        val second = SimulatedSessionGenerator.generateSeededSession(
            trackName = "Loerrach VM Kart Racing",
            seed = 1337,
            durationMinutes = 10
        )

        assertTrue("Expected deterministic sample count", first.samples.size == second.samples.size)
        assertTrue("Expected deterministic timestamps", first.samples.first().timestampNs == second.samples.first().timestampNs)
        assertTrue("Expected deterministic yaw profile", first.samples.map { it.yawRateAbs } == second.samples.map { it.yawRateAbs })
    }
}
