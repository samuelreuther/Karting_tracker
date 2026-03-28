package com.kartingtracker.domain

import com.github.mikephil.charting.data.Entry
import com.kartingtracker.data.Lap
import com.kartingtracker.data.SensorSample

data class NormalizedLapSeries(
    val longitudinalEntries: List<Entry>,
    val lateralEntries: List<Entry>
)

object LapNormalizer {
    fun normalize(lap: Lap, pointCount: Int = 101): NormalizedLapSeries {
        if (lap.samples.isEmpty()) {
            return NormalizedLapSeries(emptyList(), emptyList())
        }

        if (lap.samples.size == 1) {
            val onlySample = lap.samples.first()
            return NormalizedLapSeries(
                longitudinalEntries = listOf(Entry(0f, onlySample.longitudinalAccel)),
                lateralEntries = listOf(Entry(0f, onlySample.lateralAccel))
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
            lateralEntries = lateralEntries
        )
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
}
