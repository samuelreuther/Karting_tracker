package com.kartingtracker.domain

import com.kartingtracker.data.SensorSample

class PeakDetector {
    private val brakingDropThreshold = 0.7f
    private val corneringYawThreshold = 0.35f
    private val corneringAccelerationThreshold = 1.4f
    private val minimumSampleSpacing = 20

    fun findBrakingPeaks(samples: List<SensorSample>): List<Int> {
        return findBrakingPeaks(samples, samples.map { sample -> sample.totalAcceleration })
    }

    fun findBrakingPeaks(samples: List<SensorSample>, totalAcceleration: List<Float>): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }
        if (totalAcceleration.size != samples.size) {
            return findBrakingPeaks(samples)
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val previous = totalAcceleration[index - 1]
            val current = totalAcceleration[index]
            val next = totalAcceleration[index + 1]
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
        return findCorneringPeaks(
            samples = samples,
            yawRateAbs = samples.map { sample -> sample.yawRateAbs },
            totalAcceleration = samples.map { sample -> sample.totalAcceleration }
        )
    }

    fun findCorneringPeaks(
        samples: List<SensorSample>,
        yawRateAbs: List<Float>,
        totalAcceleration: List<Float>
    ): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }
        if (yawRateAbs.size != samples.size || totalAcceleration.size != samples.size) {
            return findCorneringPeaks(samples)
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -minimumSampleSpacing
        for (index in 1 until samples.lastIndex) {
            val currentYaw = yawRateAbs[index]
            val currentAcceleration = totalAcceleration[index]
            if (
                currentYaw > corneringYawThreshold &&
                currentAcceleration > corneringAccelerationThreshold &&
                currentYaw >= yawRateAbs[index - 1] &&
                currentYaw >= yawRateAbs[index + 1] &&
                index - lastPeakIndex >= minimumSampleSpacing
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }
}
