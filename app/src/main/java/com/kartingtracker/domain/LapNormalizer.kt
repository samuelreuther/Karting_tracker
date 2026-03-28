package com.kartingtracker.domain

import com.github.mikephil.charting.data.Entry
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample
import kotlin.math.abs

data class NormalizedLapSeries(
    val longitudinalEntries: List<Entry>,
    val lateralEntries: List<Entry>,
    val brakingMarkerEntries: List<Entry>,
    val corneringMarkerEntries: List<Entry>
)

object LapNormalizer {
    const val DEFAULT_POINT_COUNT = 251

    fun normalize(lap: Lap, pointCount: Int = DEFAULT_POINT_COUNT): NormalizedLapSeries {
        if (lap.samples.isEmpty()) {
            return NormalizedLapSeries(emptyList(), emptyList(), emptyList(), emptyList())
        }

        if (lap.samples.size == 1) {
            val onlySample = lap.samples.first()
            return NormalizedLapSeries(
                longitudinalEntries = listOf(Entry(0f, onlySample.longitudinalAccel)),
                lateralEntries = listOf(Entry(0f, onlySample.lateralAccel)),
                brakingMarkerEntries = emptyList(),
                corneringMarkerEntries = emptyList()
            )
        }

        val startNs = lap.samples.first().timestampNs
        val endNs = lap.samples.last().timestampNs
        val durationNs = (endNs - startNs).coerceAtLeast(1L)
        val longitudinalEntries = ArrayList<Entry>(pointCount)
        val lateralEntries = ArrayList<Entry>(pointCount)

        var cursor = 0
        for (index in 0 until pointCount) {
            val progress = index.toFloat() / (pointCount - 1).coerceAtLeast(1)
            val targetTimestampNs = startNs + (durationNs * progress).toLong()
            while (cursor < lap.samples.lastIndex - 1 && lap.samples[cursor + 1].timestampNs < targetTimestampNs) {
                cursor += 1
            }

            val interpolated = interpolate(
                before = lap.samples[cursor],
                after = lap.samples[(cursor + 1).coerceAtMost(lap.samples.lastIndex)],
                targetTimestampNs = targetTimestampNs
            )
            val xValue = progress * 100f
            longitudinalEntries += Entry(xValue, interpolated.longitudinalAccel)
            lateralEntries += Entry(xValue, interpolated.lateralAccel)
        }

        return NormalizedLapSeries(
            longitudinalEntries = longitudinalEntries,
            lateralEntries = lateralEntries,
            brakingMarkerEntries = createMarkerEntries(
                lap = lap,
                peakIndices = lap.brakingPeakIndices,
                selector = { sample -> sample.longitudinalAccel }
            ),
            corneringMarkerEntries = createMarkerEntries(
                lap = lap,
                peakIndices = lap.corneringPeakIndices,
                selector = { sample -> sample.lateralAccel }
            )
        )
    }

    fun averagePositiveLongitudinalAcceleration(series: NormalizedLapSeries): Float {
        val positives = series.longitudinalEntries.map { entry -> entry.y }.filter { value -> value > 0f }
        return if (positives.isEmpty()) 0f else positives.average().toFloat()
    }

    fun maxAbsoluteLateralAcceleration(series: NormalizedLapSeries): Float {
        return series.lateralEntries.maxOfOrNull { entry -> abs(entry.y) } ?: 0f
    }

    private fun interpolate(before: SensorSample, after: SensorSample, targetTimestampNs: Long): SensorSample {
        if (before.timestampNs == after.timestampNs) {
            return before
        }
        val progress =
            ((targetTimestampNs - before.timestampNs).toDouble() / (after.timestampNs - before.timestampNs).toDouble())
                .toFloat()
                .coerceIn(0f, 1f)

        fun lerp(start: Float, end: Float): Float = start + ((end - start) * progress)

        return before.copy(
            longitudinalAccel = lerp(before.longitudinalAccel, after.longitudinalAccel),
            lateralAccel = lerp(before.lateralAccel, after.lateralAccel)
        )
    }

    private fun createMarkerEntries(
        lap: Lap,
        peakIndices: List<Int>,
        selector: (SensorSample) -> Float
    ): List<Entry> {
        if (lap.samples.isEmpty()) {
            return emptyList()
        }
        val startNs = lap.samples.first().timestampNs
        val durationNs = (lap.samples.last().timestampNs - startNs).coerceAtLeast(1L)
        return peakIndices.mapNotNull { index ->
            val sample = lap.samples.getOrNull(index) ?: return@mapNotNull null
            val progress = ((sample.timestampNs - startNs).toDouble() / durationNs.toDouble()).toFloat().coerceIn(0f, 1f)
            Entry(progress * 100f, selector(sample))
        }
    }
}
