package com.kartingtracker.sensor

import android.hardware.SensorManager

enum class SensorSamplingRate(val delay: Int, val targetHz: Int) {
    GAME(SensorManager.SENSOR_DELAY_GAME, 50),
    UI(SensorManager.SENSOR_DELAY_UI, 20),
    NORMAL(SensorManager.SENSOR_DELAY_NORMAL, 10)
}

/**
 * Monitors sensor sample rates and downgrades the sampling tier if too many drops are detected.
 *
 * Sampling tiers (in order): GAME (50Hz) → UI (20Hz) → NORMAL (10Hz)
 *
 * A "drop" is counted when the measured rate is below [DROP_THRESHOLD_RATIO] × target.
 * Downgrade is triggered when more than [DROP_RATE_THRESHOLD] of samples are drops,
 * with a minimum of [MIN_SAMPLES_BEFORE_DOWNGRADE] samples observed.
 *
 * Thread safety: [onSampleReceived] is called from the sensor HandlerThread;
 * [shouldDowngrade] and [downgrade] are called from the recording coroutine.
 * Fields use @Volatile for visibility; callers must ensure [shouldDowngrade]/[downgrade]
 * are not called concurrently from multiple threads.
 */
class AdaptiveSensorRateManager {

    companion object {
        private const val DROP_THRESHOLD_RATIO = 0.5f      // Rate below 50% of target counts as drop
        private const val DROP_RATE_THRESHOLD = 0.3f        // Downgrade when >30% of samples are drops
        private const val MIN_SAMPLES_BEFORE_DOWNGRADE = 100 // Need enough samples before deciding
    }

    var currentRate = SensorSamplingRate.GAME
        private set

    @Volatile private var sampleDropCount = 0
    @Volatile private var totalSamples = 0

    fun onSampleReceived(timestampNs: Long, previousNs: Long) {
        if (previousNs <= 0L) {
            return
        }

        val actualRate = calculateActualRate(timestampNs, previousNs)
        val targetRate = currentRate.targetHz

        // If actual rate is less than 50% of target, count as drop
        if (actualRate > 0 && actualRate < targetRate * DROP_THRESHOLD_RATIO) {
            sampleDropCount++
        }

        totalSamples++
    }

    internal fun calculateActualRate(currentTimestampNs: Long, previousTimestampNs: Long): Int {
        val deltaNs = currentTimestampNs - previousTimestampNs
        if (deltaNs <= 0) return 0

        // Hz = 1 / seconds = 1,000,000,000 / nanoseconds
        return (1_000_000_000L / deltaNs).toInt()
    }

    fun shouldDowngrade(): Boolean {
        if (totalSamples < MIN_SAMPLES_BEFORE_DOWNGRADE) return false

        val dropRate = sampleDropCount.toFloat() / totalSamples
        return dropRate > DROP_RATE_THRESHOLD
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
    }
}
