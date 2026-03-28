package com.kartingtracker.domain

import com.github.mikephil.charting.data.Entry

object DrivingInsightsGenerator {
    fun generate(seriesA: NormalizedLapSeries, seriesB: NormalizedLapSeries): List<String> {
        val insights = mutableListOf<String>()

        compareBraking(seriesA, seriesB)?.let { insights += it }
        compareCornering(seriesA, seriesB)?.let { insights += it }
        compareAcceleration(seriesA, seriesB)?.let { insights += it }

        if (insights.isEmpty()) {
            insights += "The two laps look very similar in the current telemetry."
        }

        return insights.take(4)
    }

    private fun compareBraking(seriesA: NormalizedLapSeries, seriesB: NormalizedLapSeries): String? {
        val brakingA = seriesA.brakingMarkerEntries.minByOrNull { entry -> entry.y }
        val brakingB = seriesB.brakingMarkerEntries.minByOrNull { entry -> entry.y }
        if (brakingA == null || brakingB == null) {
            return null
        }

        return when {
            brakingA.x - brakingB.x > 3f -> "You brake later in Lap A."
            brakingB.x - brakingA.x > 3f -> "You brake later in Lap B."
            else -> null
        }
    }

    private fun compareCornering(seriesA: NormalizedLapSeries, seriesB: NormalizedLapSeries): String? {
        val lateralA = LapNormalizer.maxAbsoluteLateralAcceleration(seriesA)
        val lateralB = LapNormalizer.maxAbsoluteLateralAcceleration(seriesB)
        return when {
            lateralA - lateralB > 0.3f -> "Higher cornering load appears in Lap A."
            lateralB - lateralA > 0.3f -> "Higher cornering load appears in Lap B."
            else -> null
        }
    }

    private fun compareAcceleration(seriesA: NormalizedLapSeries, seriesB: NormalizedLapSeries): String? {
        val accelA = LapNormalizer.averagePositiveLongitudinalAcceleration(seriesA)
        val accelB = LapNormalizer.averagePositiveLongitudinalAcceleration(seriesB)
        return when {
            accelA - accelB > 0.15f -> "Better positive acceleration in Lap A."
            accelB - accelA > 0.15f -> "Better positive acceleration in Lap B."
            else -> null
        }
    }
}
