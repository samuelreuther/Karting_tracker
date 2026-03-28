package com.kartingtracker.domain

import com.kartingtracker.data.SensorSample

class PeakDetector {
    private val brakingDropThreshold = 0.7f
    private val corneringYawThreshold = 0.35f
    private val corneringAccelerationThreshold = 1.4f
    private val minimumSampleSpacing = 20

    fun findBrakingPeaks(samples: List<SensorSample>): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val previous = samples[index - 1].totalAcceleration
            val current = samples[index].totalAcceleration
            val next = samples[index + 1].totalAcceleration
            if (
                (previous - current) > brakingDropThreshold &&
                current < previous &&
                current <= next &&
                index - lastPeakIndex >= minimumSampleSpacing
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }

    fun findCorneringPeaks(samples: List<SensorSample>): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val currentYaw = samples[index].yawRateAbs
            val currentAcceleration = samples[index].totalAcceleration
            if (
                currentYaw > corneringYawThreshold &&
                currentAcceleration > corneringAccelerationThreshold &&
                currentYaw >= samples[index - 1].yawRateAbs &&
                currentYaw >= samples[index + 1].yawRateAbs &&
                index - lastPeakIndex >= minimumSampleSpacing
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }
}
