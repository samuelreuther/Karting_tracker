package com.kartingtracker.domain

import com.kartingtracker.data.Lap

data class IdealLap(
    val sectorBestTimes: List<Long>,
    val totalTimeMs: Long
)

object IdealLapCalculator {
    private const val minimumConfidence = 0.6f

    fun calculate(laps: List<Lap>): IdealLap? {
        val validLaps = laps.filter { lap ->
            !lap.isOutlap &&
                !lap.isDisturbed &&
                lap.confidenceScore >= minimumConfidence &&
                lap.sectorTimesMs.isNotEmpty()
        }
        if (validLaps.isEmpty()) {
            return null
        }

        val targetSectorCount = validLaps
            .groupingBy { lap -> lap.sectorTimesMs.size }
            .eachCount()
            .maxByOrNull { entry -> entry.value }
            ?.key
            ?: return null

        val comparableLaps = validLaps.filter { lap -> lap.sectorTimesMs.size == targetSectorCount }
        if (comparableLaps.isEmpty()) {
            return null
        }

        val sectorBestTimes = List(targetSectorCount) { sectorIndex ->
            comparableLaps.minOf { lap -> lap.sectorTimesMs[sectorIndex] }
        }

        return IdealLap(
            sectorBestTimes = sectorBestTimes,
            totalTimeMs = sectorBestTimes.sum()
        )
    }
}
