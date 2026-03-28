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

        val longitudinalValues = normalizeSamples(lap.samples, pointCount) { sample -> sample.longitudinalAccel }
        val lateralValues = normalizeSamples(lap.samples, pointCount) { sample -> sample.lateralAccel }
        val longitudinalEntries = longitudinalValues.mapIndexed { index, value ->
            Entry(index.toNormalizedX(pointCount), value)
        }
        val lateralEntries = lateralValues.mapIndexed { index, value ->
            Entry(index.toNormalizedX(pointCount), value)
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

    fun normalizeSignal(
        lap: Lap,
        pointCount: Int = DEFAULT_POINT_COUNT,
        selector: (SensorSample) -> Float
    ): List<Float> {
        return normalizeSamples(lap.samples, pointCount, selector)
    }

    fun normalizeSamples(
        samples: List<SensorSample>,
        pointCount: Int = DEFAULT_POINT_COUNT,
        selector: (SensorSample) -> Float
    ): List<Float> {
        if (samples.isEmpty()) {
            return emptyList()
        }
        if (samples.size == 1) {
            return List(pointCount.coerceAtLeast(1)) { selector(samples.first()) }
        }

        val startNs = samples.first().timestampNs
        val endNs = samples.last().timestampNs
        val durationNs = (endNs - startNs).coerceAtLeast(1L)
        val values = ArrayList<Float>(pointCount)
        var cursor = 0

        for (index in 0 until pointCount) {
            val progress = index.toFloat() / (pointCount - 1).coerceAtLeast(1)
            val targetTimestampNs = startNs + (durationNs * progress).toLong()
            while (cursor < samples.lastIndex - 1 && samples[cursor + 1].timestampNs < targetTimestampNs) {
                cursor += 1
            }

            val interpolated = interpolate(
                before = samples[cursor],
                after = samples[(cursor + 1).coerceAtMost(samples.lastIndex)],
                targetTimestampNs = targetTimestampNs
            )
            values += selector(interpolated)
        }
        return values
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

    private fun Int.toNormalizedX(pointCount: Int): Float {
        return (this.toFloat() / (pointCount - 1).coerceAtLeast(1)) * 100f
    }
}
