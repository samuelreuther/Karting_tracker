package com.kartingtracker.domain

import com.kartingtracker.data.Session
import com.kartingtracker.data.SimulatedSessionGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityWorkflowTest {
    private val lapDetector = LapDetector()
    private val peakDetector = PeakDetector()
    private val drivingCoachAnalyzer = DrivingCoachAnalyzer()

    @Test
    fun threeSessionReliabilityWorkflowProducesAnalysisAndStableVisualizationData() {
        val sessions = listOf(
            SimulatedSessionGenerator.generateSeededSession(trackName = "Reliability Track", seed = 42, durationMinutes = 8),
            SimulatedSessionGenerator.generateSeededSession(trackName = "Reliability Track", seed = 1337, durationMinutes = 12),
            SimulatedSessionGenerator.generateSeededSession(trackName = "Reliability Track", seed = 9001, durationMinutes = 15)
        )

        sessions.forEach { rawSession ->
            val processedSession = processSession(rawSession)
            assertTrue("Expected detected laps for session ${rawSession.id}", processedSession.laps.isNotEmpty())

            val comparisonPair = processedSession.laps.sortedBy { lap -> lap.lapTimeMs }.take(2)
            assertTrue("Expected at least two laps for time-loss visualization", comparisonPair.size == 2)
            val timeLossCurve = TimeLossCalculator.computeTimeLoss(comparisonPair[0], comparisonPair[1])
            assertFalse("Expected non-empty time-loss curve", timeLossCurve.isEmpty())
            assertTrue(
                "Time-loss curve contains invalid values",
                timeLossCurve.none { value -> value.isNaN() || value.isInfinite() }
            )

            val analysis = drivingCoachAnalyzer.analyzeSession(processedSession)
            assertTrue("Expected coaching insights for session ${rawSession.id}", analysis.coachingInsights.isNotEmpty())
            assertTrue("Expected segment markers for session ${rawSession.id}", analysis.segmentMarkers.isNotEmpty())
        }
    }

    private fun processSession(rawSession: Session): Session {
        val detectionResult = lapDetector.detect(rawSession.samples, trackProfile = null)
        val processedLaps = detectionResult.laps.map { lap ->
            val sectorBoundaries = SectorDetector.detectSectors(lap)
            val brakingPeaks = peakDetector.findBrakingPeaks(lap.samples)
            val corneringPeaks = peakDetector.findCorneringPeaks(lap.samples)
            lap.copy(
                brakingPeakIndices = brakingPeaks,
                corneringPeakIndices = corneringPeaks,
                sectorBoundaries = sectorBoundaries,
                sectorTimesMs = SectorDetector.computeSectorTimes(lap, sectorBoundaries)
            )
        }
        return rawSession.copy(
            laps = processedLaps,
            estimatedLapTimeMs = detectionResult.estimatedLapTimeMs
        )
    }
}
