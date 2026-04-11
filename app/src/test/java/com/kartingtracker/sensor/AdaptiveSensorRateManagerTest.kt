package com.kartingtracker.sensor

import org.junit.Assert.*
import org.junit.Test

class AdaptiveSensorRateManagerTest {

    @Test
    fun `starts with GAME rate as default`() {
        val manager = AdaptiveSensorRateManager()

        assertEquals(SensorSamplingRate.GAME, manager.currentRate)
    }

    @Test
    fun `calculateActualRate returns correct Hz`() {
        val manager = AdaptiveSensorRateManager()

        // 50Hz = 20ms per sample = 20,000,000 ns
        val rate = manager.calculateActualRate(
            currentTimestampNs = 100_000_000L,
            previousTimestampNs = 80_000_000L  // 20ms ago
        )

        assertEquals(50, rate)
    }

    @Test
    fun `detectSampleDrops returns false for stable rate`() {
        val manager = AdaptiveSensorRateManager()

        // Simulate stable 50Hz
        repeat(100) {
            manager.onSampleReceived(it * 20_000_000L, (it - 1) * 20_000_000L)
        }

        assertFalse(manager.shouldDowngrade())
    }
}
