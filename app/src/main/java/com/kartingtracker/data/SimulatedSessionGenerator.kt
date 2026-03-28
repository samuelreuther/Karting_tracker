package com.kartingtracker.data

import android.content.Context
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

object SimulatedSessionGenerator {
    private const val sampleIntervalMs = 50L
    private const val sampleIntervalNs = 50_000_000L
    private const val outlapMultiplier = 1.2
    private const val minimumLaps = 8
    private const val additionalLapVariants = 2
    private const val baseSeed = 20_260_328L
    private const val debugPrefsName = "simulated_session_generator"
    private const val debugSeedVersionKey = "debug_seed_version"
    private const val debugSeedVersion = 1
    private const val defaultDebugTrackName = "Test Track"
    private const val twoPi = (2.0 * PI).toFloat()
    private const val fourPi = (4.0 * PI).toFloat()

    fun generateSession(trackName: String): Session {
        val random = Random(baseSeed xor trackName.hashCode().toLong())
        val lapCount = minimumLaps + random.nextInt(additionalLapVariants)
        val lapDurationsMs = buildLapDurations(random, lapCount)
        val totalDurationMs = lapDurationsMs.sum()
        val startTimeEpochMs = System.currentTimeMillis() - totalDurationMs
        val startTimestampNs = 1_000_000_000L

        val allSamples = mutableListOf<SensorSample>()
        val laps = mutableListOf<Lap>()

        var currentTimestampNs = startTimestampNs
        var previousTotalAcceleration = 2.1f

        lapDurationsMs.forEachIndexed { lapIndex, lapDurationMs ->
            val lapStartTimestampNs = currentTimestampNs
            val lapSampleCount = ((lapDurationMs / sampleIntervalMs).toInt()).coerceAtLeast(2) + 1
            val lapSamples = ArrayList<SensorSample>(lapSampleCount)
            var smoothedLongitudinal = 0f

            for (sampleIndex in 0 until lapSampleCount) {
                val phase = sampleIndex.toFloat() / (lapSampleCount - 1).coerceAtLeast(1)
                val lapNoise = random.nextFloat() * 0.2f - 0.1f
                val straightDrive = straightDriveProfile(phase)
                val brakingIntensity = brakingProfile(phase)
                val signedYawRate = signedYawRateProfile(phase, lapIndex, lapCount, lapNoise)
                val yawRateAbs = max(0.02f, abs(signedYawRate) + lapNoise * 0.15f)

                val totalAcceleration = (
                    2.0f +
                        1.1f * sine(phase * twoPi) +
                        0.9f * straightDrive -
                        1.8f * brakingIntensity +
                        0.5f * corneringProfile(phase) +
                        lapNoise
                    ).coerceIn(0.35f, 4.8f)

                val derivative = ((totalAcceleration - previousTotalAcceleration) / (sampleIntervalMs / 1000f))
                val longitudinalTarget = (
                    derivative * 0.17f +
                        straightDrive * 0.45f -
                        brakingIntensity * 0.7f +
                        lapNoise * 0.12f
                    ).coerceIn(-3.8f, 3.8f)
                smoothedLongitudinal = (smoothedLongitudinal * 0.68f) + (longitudinalTarget * 0.32f)

                val lateralAcceleration = (
                    signedYawRate * 0.92f +
                        cornerDirectionProfile(phase) * 0.25f +
                        lapNoise * 0.1f
                    ).coerceIn(-3.5f, 3.5f)

                val gyroX = (0.08f * sine(phase * fourPi)) + (lapNoise * 0.06f)
                val gyroY = (0.06f * sine((phase * fourPi) + 1.2f)) - (lapNoise * 0.04f)
                val gyroZ = signedYawRate
                val accelZ = 9.81f + (0.12f * sine((phase * fourPi) + 0.7f)) + (lapNoise * 0.08f)

                lapSamples += SensorSample(
                    timestampNs = currentTimestampNs,
                    accelX = smoothedLongitudinal + (lapNoise * 0.08f),
                    accelY = lateralAcceleration + (lapNoise * 0.08f),
                    accelZ = accelZ,
                    gyroX = gyroX,
                    gyroY = gyroY,
                    gyroZ = gyroZ,
                    longitudinalAccel = smoothedLongitudinal,
                    lateralAccel = lateralAcceleration,
                    totalAcceleration = totalAcceleration,
                    yawRateAbs = yawRateAbs
                )

                previousTotalAcceleration = totalAcceleration
                currentTimestampNs += sampleIntervalNs
            }

            val brakingPeakIndices = generateBrakingPeaks(lapSamples)
            val corneringPeakIndices = generateCorneringPeaks(lapSamples)
            val lapEndTimestampNs = lapSamples.last().timestampNs
            val isOutlap = lapIndex == 0
            val confidenceScore = if (isOutlap) {
                0.78f + (random.nextFloat() * 0.05f)
            } else {
                0.86f + (random.nextFloat() * 0.09f)
            }.coerceAtMost(0.95f)

            laps += Lap(
                id = lapIndex + 1,
                samples = lapSamples,
                lapTimeMs = ((lapEndTimestampNs - lapStartTimestampNs) / 1_000_000L).coerceAtLeast(sampleIntervalMs),
                startTimestampNs = lapStartTimestampNs,
                endTimestampNs = lapEndTimestampNs,
                brakingPeakIndices = brakingPeakIndices,
                corneringPeakIndices = corneringPeakIndices,
                confidenceScore = confidenceScore,
                isOutlap = isOutlap
            )
            allSamples += lapSamples
        }

        val estimatedLapTimeMs = laps
            .filterNot { lap -> lap.isOutlap }
            .map { lap -> lap.lapTimeMs }
            .average()
            .toLong()
            .takeIf { it > 0L }

        return Session(
            id = generateSessionId(),
            trackName = trackName,
            startTimeEpochMs = startTimeEpochMs,
            endTimeEpochMs = startTimeEpochMs + totalDurationMs,
            startTimestampNs = startTimestampNs,
            endTimestampNs = allSamples.lastOrNull()?.timestampNs ?: startTimestampNs,
            samples = allSamples,
            laps = laps,
            estimatedLapTimeMs = estimatedLapTimeMs
        )
    }

    fun seedDebugSessionIfNeeded(
        context: Context,
        sessionStorageManager: SessionStorageManager,
        trackManager: TrackManager,
        trackName: String = defaultDebugTrackName
    ): Boolean {
        val preferences = context.getSharedPreferences(debugPrefsName, Context.MODE_PRIVATE)
        if (preferences.getInt(debugSeedVersionKey, 0) >= debugSeedVersion) {
            return false
        }

        trackManager.saveTrack(trackName)
        val simulatedSession = generateSession(trackName)
        sessionStorageManager.saveSession(simulatedSession)
        preferences.edit().putInt(debugSeedVersionKey, debugSeedVersion).apply()
        return true
    }

    private fun buildLapDurations(random: Random, lapCount: Int): List<Long> {
        return List(lapCount) { lapIndex ->
            val baseLapTimeMs = 33_000L + random.nextLong(0L, 4_500L)
            if (lapIndex == 0) {
                (baseLapTimeMs * outlapMultiplier).toLong()
            } else {
                (baseLapTimeMs + random.nextLong(-1_200L, 1_200L)).coerceIn(30_500L, 38_500L)
            }
        }
    }

    private fun straightDriveProfile(phase: Float): Float {
        val rampA = ramp(phase, 0.00f, 0.15f)
        val rampB = ramp(phase, 0.34f, 0.49f)
        val rampC = ramp(phase, 0.65f, 0.79f)
        val rampD = ramp(phase, 0.93f, 1.00f)
        return rampA + rampB + rampC + rampD
    }

    private fun brakingProfile(phase: Float): Float {
        return sharpPulse(phase, 0.18f, 0.030f) +
            sharpPulse(phase, 0.52f, 0.032f) +
            sharpPulse(phase, 0.83f, 0.028f)
    }

    private fun corneringProfile(phase: Float): Float {
        return pulse(phase, 0.22f, 0.34f) +
            pulse(phase, 0.57f, 0.68f) +
            pulse(phase, 0.86f, 0.95f)
    }

    private fun cornerDirectionProfile(phase: Float): Float {
        val leftCorner = pulse(phase, 0.22f, 0.34f) + pulse(phase, 0.86f, 0.95f)
        val rightCorner = pulse(phase, 0.57f, 0.68f)
        return leftCorner - rightCorner
    }

    private fun signedYawRateProfile(
        phase: Float,
        lapIndex: Int,
        lapCount: Int,
        noise: Float
    ): Float {
        val lapVariation = 1f + (((lapIndex.toFloat() / lapCount.coerceAtLeast(1)) - 0.5f) * 0.08f)
        val leftCorner = 2.4f * pulse(phase, 0.22f, 0.34f)
        val rightCorner = 2.7f * pulse(phase, 0.57f, 0.68f)
        val finalCorner = 2.2f * pulse(phase, 0.86f, 0.95f)
        return (((leftCorner + finalCorner) - rightCorner) * lapVariation) +
            (0.16f * sine(phase * fourPi)) +
            (noise * 0.2f)
    }

    private fun generateBrakingPeaks(samples: List<SensorSample>): List<Int> {
        return listOf(0.18f, 0.52f, 0.83f).mapNotNull { phase ->
            findLocalMinimumIndex(samples, phase, windowFraction = 0.05f)
        }
    }

    private fun generateCorneringPeaks(samples: List<SensorSample>): List<Int> {
        return listOf(0.28f, 0.62f, 0.90f).mapNotNull { phase ->
            findLocalMaximumIndex(samples, phase, windowFraction = 0.06f)
        }
    }

    private fun findLocalMinimumIndex(
        samples: List<SensorSample>,
        centerPhase: Float,
        windowFraction: Float
    ): Int? {
        if (samples.isEmpty()) {
            return null
        }
        val range = phaseWindow(samples.size, centerPhase, windowFraction)
        return range.minByOrNull { index -> samples[index].totalAcceleration }
    }

    private fun findLocalMaximumIndex(
        samples: List<SensorSample>,
        centerPhase: Float,
        windowFraction: Float
    ): Int? {
        if (samples.isEmpty()) {
            return null
        }
        val range = phaseWindow(samples.size, centerPhase, windowFraction)
        return range.maxByOrNull { index -> samples[index].yawRateAbs }
    }

    private fun phaseWindow(
        sampleCount: Int,
        centerPhase: Float,
        windowFraction: Float
    ): IntRange {
        val centerIndex = (centerPhase * (sampleCount - 1)).toInt()
        val halfWindow = max(2, (sampleCount * windowFraction).toInt())
        val startIndex = (centerIndex - halfWindow).coerceAtLeast(0)
        val endIndex = (centerIndex + halfWindow).coerceAtMost(sampleCount - 1)
        return startIndex..endIndex
    }

    private fun ramp(phase: Float, start: Float, end: Float): Float {
        if (phase <= start) {
            return 0f
        }
        if (phase >= end) {
            return 1f
        }
        return ((phase - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun pulse(phase: Float, start: Float, end: Float): Float {
        if (phase <= start || phase >= end) {
            return 0f
        }
        val localPhase = (phase - start) / (end - start)
        return sin(localPhase * PI).toFloat().coerceAtLeast(0f)
    }

    private fun sine(value: Float): Float {
        return sin(value.toDouble()).toFloat()
    }

    private fun sharpPulse(phase: Float, center: Float, width: Float): Float {
        val distance = abs(phase - center)
        if (distance >= width) {
            return 0f
        }
        val normalized = 1f - (distance / width)
        return normalized * normalized
    }

    private fun generateSessionId(): Long {
        val uuid = UUID.randomUUID()
        return ((uuid.mostSignificantBits xor uuid.leastSignificantBits) and Long.MAX_VALUE)
            .coerceAtLeast(1L)
    }
}
