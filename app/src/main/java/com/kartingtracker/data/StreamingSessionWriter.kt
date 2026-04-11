package com.kartingtracker.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    @Volatile private var outputChannel: FileChannel? = null
    private var flushJob: Job? = null

    @Volatile var samplesWritten = 0L
        private set

    @Volatile private var ioFailed = false
    @Volatile private var ioError: Exception? = null

    init {
        val dir = tempFile.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create session directory: $dir")
        }
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
        if (ioFailed) throw IOException("StreamingSessionWriter failed: ${ioError?.message}")

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
        flushJob?.cancelAndJoin()
        flushBuffer()

        outputChannel?.close()
        outputChannel = null

        if (ioFailed) {
            throw IOException("Session $sessionId binary write failed: ${ioError?.message}")
        }

        // Atomic rename: temp → final
        if (!tempFile.renameTo(rawFile)) {
            throw IllegalStateException("Failed to rename $tempFile to $rawFile (session $sessionId)")
        }

        Log.i(TAG, "Finalized raw session $sessionId: $samplesWritten samples, ${rawFile.length()} bytes")

        scope.cancel()
        rawFile
    }

    private fun flushBuffer() {
        synchronized(writeBuffer) {
            if (writeBuffer.position() == 0) return

            writeBuffer.flip()
            try {
                outputChannel?.write(writeBuffer)
            } catch (e: Exception) {
                ioFailed = true
                ioError = e
                Log.e(TAG, "Failed to flush buffer for session $sessionId", e)
            } finally {
                writeBuffer.clear()
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 65536  // 64KB
        private const val SAMPLE_SIZE = 48     // bytes per sample
        private const val FLUSH_INTERVAL_MS = 1000L

        fun loadSamplesFromBinaryFile(file: File): List<SensorSample> {
            val samples = mutableListOf<SensorSample>()
            java.io.FileInputStream(file).channel.use { channel ->
                val buf = ByteBuffer.allocateDirect(BUFFER_SIZE)
                while (channel.read(buf) > 0) {
                    buf.flip()
                    while (buf.remaining() >= SAMPLE_SIZE) {
                        samples.add(
                            SensorSample(
                                timestampNs = buf.getLong(),
                                accelX = buf.getFloat(),
                                accelY = buf.getFloat(),
                                accelZ = buf.getFloat(),
                                gyroX = buf.getFloat(),
                                gyroY = buf.getFloat(),
                                gyroZ = buf.getFloat(),
                                longitudinalAccel = buf.getFloat(),
                                lateralAccel = buf.getFloat(),
                                totalAcceleration = buf.getFloat(),
                                yawRateAbs = buf.getFloat()
                            )
                        )
                    }
                    buf.compact()
                }
            }
            return samples
        }
    }
}
