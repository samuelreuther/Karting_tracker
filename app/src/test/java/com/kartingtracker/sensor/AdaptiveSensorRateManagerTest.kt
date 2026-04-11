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
    fun `shouldDowngrade returns false for stable rate`() {
        val manager = AdaptiveSensorRateManager()

        // Simulate stable 50Hz
        repeat(100) {
            manager.onSampleReceived(it * 20_000_000L, (it - 1) * 20_000_000L)
        }

        assertFalse(manager.shouldDowngrade())
    }

    @Test
    fun `shouldDowngrade returns true when drop rate exceeds 30 percent`() {
        val manager = AdaptiveSensorRateManager()
        // 70 normal samples + 31 drops = 31/101 = ~30.7% drops
        repeat(70) { i -> manager.onSampleReceived((i + 1) * 20_000_000L, i * 20_000_000L) }
        repeat(31) { i -> manager.onSampleReceived((71 + i) * 20_000_000L, (71 + i - 1) * 20_000_000L - 50_000_000L) }  // 10Hz samples (slow), counts as drops
        assertTrue(manager.shouldDowngrade())
    }

    @Test
    fun `shouldDowngrade returns false when sample count below 100`() {
        val manager = AdaptiveSensorRateManager()
        // Only 50 samples, even all drops — not enough data
        repeat(50) { i -> manager.onSampleReceived((i + 1) * 100_000_000L, i * 100_000_000L) }  // 10Hz
        assertFalse(manager.shouldDowngrade())
    }

    @Test
    fun `downgrade transitions GAME to UI to NORMAL`() {
        val manager = AdaptiveSensorRateManager()
        assertEquals(SensorSamplingRate.GAME, manager.currentRate)
        assertTrue(manager.downgrade())
        assertEquals(SensorSamplingRate.UI, manager.currentRate)
        assertTrue(manager.downgrade())
        assertEquals(SensorSamplingRate.NORMAL, manager.currentRate)
        assertFalse(manager.downgrade())  // Already at NORMAL
        assertEquals(SensorSamplingRate.NORMAL, manager.currentRate)
    }

    @Test
    fun `downgrade resets counters`() {
        val manager = AdaptiveSensorRateManager()
        // Feed enough drops to make shouldDowngrade true
        repeat(70) { i -> manager.onSampleReceived((i + 1) * 20_000_000L, i * 20_000_000L) }
        repeat(31) { i -> manager.onSampleReceived((71 + i) * 20_000_000L, (71 + i - 1) * 20_000_000L - 50_000_000L) }
        assertTrue(manager.shouldDowngrade())
        manager.downgrade()
        assertFalse(manager.shouldDowngrade())  // Counters reset
    }

    @Test
    fun `reset restores full initial state`() {
        val manager = AdaptiveSensorRateManager()
        manager.downgrade()
        manager.reset()
        assertEquals(SensorSamplingRate.GAME, manager.currentRate)
        assertFalse(manager.shouldDowngrade())
    }
}
