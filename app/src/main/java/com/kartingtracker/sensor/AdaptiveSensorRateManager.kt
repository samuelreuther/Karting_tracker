package com.kartingtracker.sensor

import android.hardware.SensorManager

enum class SensorSamplingRate(val delay: Int, val targetHz: Int) {
    GAME(SensorManager.SENSOR_DELAY_GAME, 50),
    UI(SensorManager.SENSOR_DELAY_UI, 20),
    NORMAL(SensorManager.SENSOR_DELAY_NORMAL, 10)
}

class AdaptiveSensorRateManager {

    var currentRate = SensorSamplingRate.GAME
        private set

    private var sampleDropCount = 0
    private var totalSamples = 0
    private var previousTimestampNs = 0L

    fun onSampleReceived(timestampNs: Long, previousNs: Long) {
        if (previousNs == 0L) {
            previousTimestampNs = timestampNs
            return
        }

        val actualRate = calculateActualRate(timestampNs, previousNs)
        val targetRate = currentRate.targetHz

        // If actual rate is less than 50% of target, count as drop
        if (actualRate < targetRate * 0.5f) {
            sampleDropCount++
        }

        totalSamples++
        previousTimestampNs = timestampNs
    }

    fun calculateActualRate(currentTimestampNs: Long, previousTimestampNs: Long): Int {
        val deltaNs = currentTimestampNs - previousTimestampNs
        if (deltaNs <= 0) return 0

        // Hz = 1 / seconds = 1,000,000,000 / nanoseconds
        return (1_000_000_000L / deltaNs).toInt()
    }

    fun shouldDowngrade(): Boolean {
        if (totalSamples < 100) return false  // Need enough samples

        val dropRate = sampleDropCount.toFloat() / totalSamples
        return dropRate > 0.3f  // More than 30% drops
    }

    fun downgrade(): Boolean {
        currentRate = when (currentRate) {
            SensorSamplingRate.GAME -> SensorSamplingRate.UI
            SensorSamplingRate.UI -> SensorSamplingRate.NORMAL
            SensorSamplingRate.NORMAL -> return false  // Already at lowest
        }

        // Reset counters after downgrade
        sampleDropCount = 0
        totalSamples = 0

        return true
    }

    fun reset() {
        currentRate = SensorSamplingRate.GAME
        sampleDropCount = 0
        totalSamples = 0
        previousTimestampNs = 0L
    }
}
