package com.kartingtracker.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

private const val TAG = "StreamingSessionWriter"

/**
 * Writes sensor samples to binary file in real-time.
 *
 * Binary format per sample (48 bytes):
 * - timestampNs: Long (8 bytes)
 * - accelX, accelY, accelZ: Float × 3 (12 bytes)
 * - gyroX, gyroY, gyroZ: Float × 3 (12 bytes)
 * - longitudinalAccel, lateralAccel, totalAcceleration, yawRateAbs: Float × 4 (16 bytes)
 *
 * Total: 48 bytes per sample
 */
class StreamingSessionWriter(
    private val sessionId: Long,
    private val sessionDirectory: File
) {
    private val rawFile = File(sessionDirectory, "session_${sessionId}_raw.bin")
    private val tempFile = File(sessionDirectory, "session_${sessionId}_raw.tmp")

    private val writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var outputChannel: FileChannel? = null
    private var flushJob: Job? = null

    var samplesWritten = 0L
        private set

    init {
        tempFile.parentFile?.mkdirs()
        outputChannel = FileOutputStream(tempFile).channel

        // Background flush loop
        flushJob = scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffer()
            }
        }
    }

    suspend fun writeSample(sample: SensorSample) = withContext(Dispatchers.IO) {
        synchronized(writeBuffer) {
            if (writeBuffer.remaining() < SAMPLE_SIZE) {
                flushBuffer()
            }

            // Write 48-byte binary sample
            writeBuffer.putLong(sample.timestampNs)
            writeBuffer.putFloat(sample.accelX)
            writeBuffer.putFloat(sample.accelY)
            writeBuffer.putFloat(sample.accelZ)
            writeBuffer.putFloat(sample.gyroX)
            writeBuffer.putFloat(sample.gyroY)
            writeBuffer.putFloat(sample.gyroZ)
            writeBuffer.putFloat(sample.longitudinalAccel)
            writeBuffer.putFloat(sample.lateralAccel)
            writeBuffer.putFloat(sample.totalAcceleration)
            writeBuffer.putFloat(sample.yawRateAbs)

            samplesWritten++
        }
    }

    suspend fun finalize(): File = withContext(Dispatchers.IO) {
        flushJob?.cancel()
        flushBuffer()

        outputChannel?.close()
        outputChannel = null

        // Atomic rename: temp → final
        if (!tempFile.renameTo(rawFile)) {
            throw IllegalStateException("Failed to finalize raw session file")
        }

        Log.i(TAG, "Finalized raw session $sessionId: $samplesWritten samples, ${rawFile.length()} bytes")

        scope.cancel()
        rawFile
    }

    private fun flushBuffer() {
        synchronized(writeBuffer) {
            if (writeBuffer.position() == 0) return

            writeBuffer.flip()
            outputChannel?.write(writeBuffer)
            writeBuffer.clear()
        }
    }

    companion object {
        private const val BUFFER_SIZE = 65536  // 64KB
        private const val SAMPLE_SIZE = 48     // bytes per sample
        private const val FLUSH_INTERVAL_MS = 1000L

        fun loadSamplesFromBinaryFile(file: File): List<SensorSample> {
            val samples = mutableListOf<SensorSample>()
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes)

            while (buffer.remaining() >= SAMPLE_SIZE) {
                samples.add(
                    SensorSample(
                        timestampNs = buffer.getLong(),
                        accelX = buffer.getFloat(),
                        accelY = buffer.getFloat(),
                        accelZ = buffer.getFloat(),
                        gyroX = buffer.getFloat(),
                        gyroY = buffer.getFloat(),
                        gyroZ = buffer.getFloat(),
                        longitudinalAccel = buffer.getFloat(),
                        lateralAccel = buffer.getFloat(),
                        totalAcceleration = buffer.getFloat(),
                        yawRateAbs = buffer.getFloat()
                    )
                )
            }

            return samples
        }
    }
}
