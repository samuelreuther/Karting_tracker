package com.kartingtracker.domain

import com.kartingtracker.data.SensorSample

class PeakDetector {
    fun findBrakingPeaks(samples: List<SensorSample>): List<Int> {
        if (samples.size < 3) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        var lastPeakIndex = -20
        for (index in 1 until samples.lastIndex) {
            val current = samples[index].longitudinalAccel
            if (
                current < -2.2f &&
                current < samples[index - 1].longitudinalAccel &&
                current <= samples[index + 1].longitudinalAccel &&
                index - lastPeakIndex >= 20
            ) {
                peaks += index
                lastPeakIndex = index
            }
        }
        return peaks
    }
}
