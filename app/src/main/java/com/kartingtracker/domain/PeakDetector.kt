package com.kartingtracker.domain

import com.kartingtracker.data.SensorSample

class PeakDetector {
    private val brakingThreshold = -2.5f
    private val corneringThreshold = 2.0f
    private val minimumSampleSpacing = 20

    fun findBrakingPeaks(samples: List<SensorSample>): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val current = samples[index].longitudinalAccel
            if (
                current < brakingThreshold &&
                current < samples[index - 1].longitudinalAccel &&
                current <= samples[index + 1].longitudinalAccel &&
                index - lastPeakIndex >= minimumSampleSpacing
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }

    fun findCorneringPeaks(samples: List<SensorSample>): List<Int> {
        return findPeaks(
            samples = samples,
            selector = { sample -> kotlin.math.abs(sample.lateralAccel) },
            thresholdCheck = { value -> value > corneringThreshold }
        )
    }

    private fun findPeaks(
        samples: List<SensorSample>,
        selector: (SensorSample) -> Float,
        thresholdCheck: (Float) -> Boolean
    ): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val current = selector(samples[index])
            if (
                thresholdCheck(current) &&
                current >= selector(samples[index - 1]) &&
                current >= selector(samples[index + 1]) &&
                index - lastPeakIndex >= minimumSampleSpacing
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }
}
