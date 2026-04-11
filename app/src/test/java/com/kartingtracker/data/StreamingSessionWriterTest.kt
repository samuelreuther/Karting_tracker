package com.kartingtracker.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StreamingSessionWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `write single sample to binary file`() = runTest {
        val sessionDir = tempFolder.newFolder()
        val writer = StreamingSessionWriter(sessionId = 123L, sessionDirectory = sessionDir)

        val sample = SensorSample(
            timestampNs = 1000L,
            accelX = 1.0f, accelY = 2.0f, accelZ = 3.0f,
            gyroX = 0.1f, gyroY = 0.2f, gyroZ = 0.3f,
            longitudinalAccel = 0.5f, lateralAccel = 0.6f,
            totalAcceleration = 0.7f, yawRateAbs = 0.8f
        )

        writer.writeSample(sample)
        val file = writer.finalize()

        assertTrue(file.exists())
        assertEquals(48L, file.length())  // 1 sample × 48 bytes
    }

    @Test
    fun `write multiple samples and read back`() = runTest {
        val sessionDir = tempFolder.newFolder()
        val writer = StreamingSessionWriter(sessionId = 456L, sessionDirectory = sessionDir)

        val samples = (1..100).map { i ->
            SensorSample(
                timestampNs = i * 1000L,
                accelX = i.toFloat(), accelY = i * 2f, accelZ = i * 3f,
                gyroX = i * 0.1f, gyroY = i * 0.2f, gyroZ = i * 0.3f,
                longitudinalAccel = i * 0.5f, lateralAccel = i * 0.6f,
                totalAcceleration = i * 0.7f, yawRateAbs = i * 0.8f
            )
        }

        samples.forEach { writer.writeSample(it) }
        val file = writer.finalize()

        assertEquals(4800L, file.length())  // 100 samples × 48 bytes

        // Read back and verify
        val readSamples = StreamingSessionWriter.loadSamplesFromBinaryFile(file)
        assertEquals(100, readSamples.size)
        assertEquals(1000L, readSamples.first().timestampNs)
        assertEquals(100000L, readSamples.last().timestampNs)
    }

    @Test
    fun `finalize with no samples creates empty file`() = runTest {
        val sessionDir = tempFolder.newFolder()
        val writer = StreamingSessionWriter(sessionId = 789L, sessionDirectory = sessionDir)
        val file = writer.finalize()
        assertTrue(file.exists())
        assertEquals(0L, file.length())
        assertEquals(0L, writer.samplesWritten)
    }

    @Test
    fun `samplesWritten matches samples passed in`() = runTest {
        val sessionDir = tempFolder.newFolder()
        val writer = StreamingSessionWriter(sessionId = 111L, sessionDirectory = sessionDir)
        val sample = SensorSample(1L, 1f, 2f, 3f, 0.1f, 0.2f, 0.3f, 0.5f, 0.6f, 0.7f, 0.8f)
        repeat(50) { writer.writeSample(sample) }
        writer.finalize()
        assertEquals(50L, writer.samplesWritten)
    }

    @Test
    fun `loadSamplesFromBinaryFile preserves all field values`() = runTest {
        val sessionDir = tempFolder.newFolder()
        val writer = StreamingSessionWriter(sessionId = 222L, sessionDirectory = sessionDir)
        val sample = SensorSample(
            timestampNs = 9999L,
            accelX = 1.1f, accelY = 2.2f, accelZ = 3.3f,
            gyroX = 4.4f, gyroY = 5.5f, gyroZ = 6.6f,
            longitudinalAccel = 7.7f, lateralAccel = 8.8f,
            totalAcceleration = 9.9f, yawRateAbs = 10.10f
        )
        writer.writeSample(sample)
        val file = writer.finalize()
        val loaded = StreamingSessionWriter.loadSamplesFromBinaryFile(file)
        assertEquals(1, loaded.size)
        val s = loaded[0]
        assertEquals(9999L, s.timestampNs)
        assertEquals(1.1f, s.accelX, 0.001f)
        assertEquals(7.7f, s.longitudinalAccel, 0.001f)
        assertEquals(10.10f, s.yawRateAbs, 0.001f)
    }
}
