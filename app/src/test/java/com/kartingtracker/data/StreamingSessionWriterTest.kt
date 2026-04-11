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
}
