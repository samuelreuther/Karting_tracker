package com.kartingtracker.data

import android.content.Context
import com.kartingtracker.domain.DrivingCoachAnalyzer
import com.kartingtracker.domain.PeakDetector
import com.kartingtracker.domain.SectorDetector
import com.kartingtracker.domain.SessionQualityEvaluator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

object SimulatedSessionGenerator {
    private const val sampleIntervalMs = 50L
    private const val sampleIntervalNs = 50_000_000L
    private const val baseSeed = 20_260_328L
    private const val debugPrefsName = "simulated_session_generator"
    private const val debugSeedVersionKey = "debug_seed_version"
    private const val debugSeedVersion = 2
    private const val defaultDebugTrackName = "Test Track"
    private const val debugBaseStartTimeEpochMs = 1_775_000_000_000L
    private val debugSeeds = listOf(42, 1337, 9001)
    private const val minimumLapSamples = 476
    private const val maximumLapSamples = 524
    private const val preferredLapSamples = 500
    private const val minimumLapCount = 23
    private const val maximumLapCount = 25
    private const val peakWindowFraction = 0.06f
    private const val brakingWindowFraction = 0.05f
    private const val imperfectLapChance = 0.16f
    private const val driftLimitMs = 1_500f
    private const val defaultDurationMinutes = 10
    private const val minimumDeterministicId = 10_000L
    private const val twoPi = (2.0 * PI).toFloat()
    private const val fourPi = (4.0 * PI).toFloat()

    private val peakDetector = PeakDetector()
    private val drivingCoachAnalyzer = DrivingCoachAnalyzer()

    fun generateSession(trackName: String): Session {
        return generateSeededSession(trackName, baseSeed.toInt(), defaultDurationMinutes)
    }

    fun generateSeededSession(trackName: String, seed: Int, durationMinutes: Int = defaultDurationMinutes): Session {
        val random = Random(baseSeed xor trackName.hashCode().toLong() xor seed.toLong())
        val totalSampleCount = ((durationMinutes * 60_000L) / sampleIntervalMs).toInt().coerceAtLeast(8_000)
        val lapCount = (totalSampleCount / preferredLapSamples.toFloat()).toInt().coerceIn(minimumLapCount, maximumLapCount)
        val lapSampleCounts = buildLapSampleCounts(random, totalSampleCount, lapCount)
        val totalDurationMs = lapSampleCounts.sumOf { lapSamples -> lapSamples * sampleIntervalMs }
        val startTimeEpochMs = debugBaseStartTimeEpochMs + (seed.toLong() * totalDurationMs)
        val startTimestampNs = 1_000_000_000L

        val allSamples = mutableListOf<SensorSample>()
        val laps = mutableListOf<Lap>()

        var currentTimestampNs = startTimestampNs
        var previousTotalAcceleration = 2.4f
        var lapDriftMs = random.nextInt(-400, 401).toFloat()

        lapSampleCounts.forEachIndexed { lapIndex, lapSampleCount ->
            val lapStartTimestampNs = currentTimestampNs
            val lapSamples = ArrayList<SensorSample>(lapSampleCount)
            var smoothedLongitudinal = 0f
            val lapProfile = buildLapProfile(random, lapIndex, lapDriftMs)
            lapDriftMs = ((lapDriftMs * 0.55f) + (random.nextInt(-700, 701) * 0.45f)).coerceIn(-driftLimitMs, driftLimitMs)

            for (sampleIndex in 0 until lapSampleCount) {
                val phase = sampleIndex.toFloat() / (lapSampleCount - 1).coerceAtLeast(1)
                val lapNoise = lapProfile.noiseAmplitude * (random.nextFloat() - 0.5f)
                val straightDrive = straightDriveProfile(phase, lapProfile.exitAccelerationScale)
                val brakingDemand = brakingProfile(phase, lapProfile.brakeCenters, lapProfile.brakeIntensityScale)
                val corneringLoad = corneringProfile(phase, lapProfile.cornerCenters, lapProfile.corneringScale)
                val disturbance = if (lapProfile.imperfectLap) imperfectDisturbance(phase, lapProfile.disturbanceCenter) else 0f
                val signedYawRate = signedYawRateProfile(phase, lapProfile, lapIndex, lapCount, lapNoise)
                val yawRateAbs = max(0.04f, abs(signedYawRate) + (lapNoise * 0.20f) + (corneringLoad * 0.14f))

                val totalAcceleration = (
                    2.2f +
                        (0.65f * sine((phase * twoPi) + lapProfile.waveOffset)) +
                        (0.82f * straightDrive) -
                        (1.62f * brakingDemand) +
                        (0.62f * corneringLoad) -
                        disturbance +
                        lapNoise
                    ).coerceIn(0.35f, 4.9f)

                val derivative = ((totalAcceleration - previousTotalAcceleration) / (sampleIntervalMs / 1000f))
                val longitudinalTarget = (
                    (derivative * 0.17f) +
                        (straightDrive * 0.60f) -
                        (brakingDemand * 0.95f) -
                        (disturbance * 0.16f) +
                        (lapNoise * 0.12f)
                    ).coerceIn(-3.8f, 3.8f)
                smoothedLongitudinal = (smoothedLongitudinal * 0.68f) + (longitudinalTarget * 0.32f)

                val lateralAcceleration = (
                    (signedYawRate * 0.92f) +
                        (cornerDirectionProfile(phase, lapProfile.cornerCenters) * 0.25f) +
                        (disturbance * 0.05f) +
                        (lapNoise * 0.10f)
                    ).coerceIn(-3.5f, 3.5f)

                val gyroX = (0.08f * sine((phase * fourPi) + lapProfile.waveOffset)) + (lapNoise * 0.06f)
                val gyroY = (0.06f * sine((phase * fourPi) + 1.2f + lapProfile.waveOffset)) - (lapNoise * 0.04f)
                val gyroZ = signedYawRate
                val accelZ = 9.81f + (0.12f * sine((phase * fourPi) + 0.7f)) + (lapNoise * 0.08f) - (disturbance * 0.02f)

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

            val detectedBrakingPeaks = peakDetector.findBrakingPeaks(lapSamples)
            val detectedCorneringPeaks = peakDetector.findCorneringPeaks(lapSamples)
            val brakingPeakIndices = if (detectedBrakingPeaks.size >= 3) {
                detectedBrakingPeaks
            } else {
                generateBrakingPeaks(lapSamples, lapProfile.brakeCenters)
            }
            val corneringPeakIndices = if (detectedCorneringPeaks.size >= 3) {
                detectedCorneringPeaks
            } else {
                generateCorneringPeaks(lapSamples, lapProfile.cornerCenters)
            }
            val lapEndTimestampNs = lapSamples.last().timestampNs
            val confidenceScore = if (lapProfile.imperfectLap) {
                (0.72f + (random.nextFloat() * 0.08f)).coerceAtMost(0.82f)
            } else {
                (0.84f + (random.nextFloat() * 0.10f)).coerceAtMost(0.95f)
            }

            val baseLap = Lap(
                id = lapIndex + 1,
                samples = lapSamples,
                lapTimeMs = ((lapEndTimestampNs - lapStartTimestampNs) / 1_000_000L).coerceAtLeast(sampleIntervalMs),
                startTimestampNs = lapStartTimestampNs,
                endTimestampNs = lapEndTimestampNs,
                brakingPeakIndices = brakingPeakIndices,
                corneringPeakIndices = corneringPeakIndices,
                confidenceScore = confidenceScore
            )
            val sectorBoundaries = SectorDetector.detectSectors(baseLap)
            laps += baseLap.copy(
                sectorBoundaries = sectorBoundaries,
                sectorTimesMs = SectorDetector.computeSectorTimes(baseLap, sectorBoundaries)
            )
            allSamples += lapSamples
        }

        val estimatedLapTimeMs = laps
            .map { lap -> lap.lapTimeMs }
            .average()
            .toLong()
            .takeIf { value -> value > 0L }

        val baseSession = Session(
            id = generateSessionId(trackName, seed),
            trackName = trackName,
            startTimeEpochMs = startTimeEpochMs,
            endTimeEpochMs = startTimeEpochMs + totalDurationMs,
            startTimestampNs = startTimestampNs,
            endTimestampNs = allSamples.lastOrNull()?.timestampNs ?: startTimestampNs,
            samples = allSamples,
            laps = laps,
            estimatedLapTimeMs = estimatedLapTimeMs,
            insights = emptyList(),
            theoreticalBestLapTimeMs = null,
            topTimeLossSegments = emptyList(),
            segmentMarkers = emptyList(),
            quality = SessionQualityEvaluator.evaluate(laps),
            processingVersion = 5
        )

        val telemetryAnalysis = drivingCoachAnalyzer.analyzeSession(baseSession)
        return baseSession.copy(
            insights = telemetryAnalysis.insights,
            theoreticalBestLapTimeMs = telemetryAnalysis.theoreticalBestLapTimeMs,
            topTimeLossSegments = telemetryAnalysis.topTimeLossSegments,
            segmentMarkers = telemetryAnalysis.segmentMarkers
        )
    }

    fun seedDebugSessionIfNeeded(
        context: Context,
        sessionStorageManager: SessionStorageManager,
        trackManager: TrackManager,
        trackProfileManager: TrackProfileManager,
        trackName: String = defaultDebugTrackName
    ): Boolean {
        val preferences = context.getSharedPreferences(debugPrefsName, Context.MODE_PRIVATE)
        if (preferences.getInt(debugSeedVersionKey, 0) >= debugSeedVersion) {
            return false
        }

        sessionStorageManager.deleteSessionsForTrack(trackName)
        trackProfileManager.deleteProfile(trackName)
        trackManager.deleteTrack(trackName)
        trackManager.saveTrack(trackName)
        debugSeeds.forEach { seed ->
            sessionStorageManager.saveSession(generateSeededSession(trackName, seed))
        }
        trackProfileManager.updateProfile(trackName, sessionStorageManager.loadSessionsForTrack(trackName))
        preferences.edit().putInt(debugSeedVersionKey, debugSeedVersion).apply()
        return true
    }

    private fun buildLapSampleCounts(random: Random, totalSampleCount: Int, lapCount: Int): List<Int> {
        val counts = MutableList(lapCount) { lapIndex ->
            val baseline = totalSampleCount / lapCount
            val waveAdjustment = (sine((lapIndex.toFloat() / lapCount.toFloat()) * twoPi) * 10f).toInt()
            (baseline + waveAdjustment + random.nextInt(-20, 21)).coerceIn(minimumLapSamples, maximumLapSamples)
        }

        var remaining = totalSampleCount - counts.sum()
        var cursor = 0
        while (remaining != 0) {
            val direction = if (remaining > 0) 1 else -1
            val index = cursor % counts.size
            val candidate = counts[index] + direction
            if (candidate in minimumLapSamples..maximumLapSamples) {
                counts[index] = candidate
                remaining -= direction
            }
            cursor += 1
        }
        return counts
    }

    private fun buildLapProfile(random: Random, lapIndex: Int, lapDriftMs: Float): LapProfile {
        val imperfectLap = random.nextFloat() < imperfectLapChance
        val paceFactor = (1f - (lapDriftMs / 5_000f)).coerceIn(0.82f, 1.10f)
        val brakeCenters = listOf(
            (0.18f + random.nextFloat() * 0.02f - 0.01f).coerceIn(0.14f, 0.22f),
            (0.51f + random.nextFloat() * 0.024f - 0.012f).coerceIn(0.47f, 0.55f),
            (0.83f + random.nextFloat() * 0.02f - 0.01f).coerceIn(0.79f, 0.87f)
        )
        val cornerCenters = listOf(
            (brakeCenters[0] + 0.09f + random.nextFloat() * 0.012f).coerceIn(0.23f, 0.31f),
            (brakeCenters[1] + 0.09f + random.nextFloat() * 0.012f).coerceIn(0.57f, 0.65f),
            (brakeCenters[2] + 0.06f + random.nextFloat() * 0.014f).coerceIn(0.87f, 0.93f)
        )
        val imperfectionPenalty = if (imperfectLap) 0.10f else 0f
        return LapProfile(
            brakeCenters = brakeCenters,
            cornerCenters = cornerCenters,
            brakeIntensityScale = FloatArray(3) { index ->
                (0.92f + random.nextFloat() * 0.22f + (0.02f * index) - imperfectionPenalty).coerceIn(0.74f, 1.16f)
            },
            corneringScale = FloatArray(3) { index ->
                (paceFactor + 0.02f + random.nextFloat() * 0.16f - (0.02f * index) - imperfectionPenalty).coerceIn(0.78f, 1.20f)
            },
            exitAccelerationScale = FloatArray(3) {
                (paceFactor + random.nextFloat() * 0.18f - imperfectionPenalty).coerceIn(0.78f, 1.18f)
            },
            imperfectLap = imperfectLap,
            disturbanceCenter = (0.32f + random.nextFloat() * 0.36f).coerceIn(0.28f, 0.72f),
            noiseAmplitude = if (imperfectLap) 0.20f else 0.12f,
            waveOffset = ((lapIndex % 7) * 0.18f)
        )
    }

    private fun straightDriveProfile(phase: Float, exitAccelerationScale: FloatArray): Float {
        val rampA = ramp(phase, 0.00f, 0.15f)
        val rampB = ramp(phase, 0.34f, 0.49f)
        val rampC = ramp(phase, 0.65f, 0.79f)
        val rampD = ramp(phase, 0.93f, 1.00f)
        return rampA +
            (rampB * exitAccelerationScale[0]) +
            (rampC * exitAccelerationScale[1]) +
            (rampD * exitAccelerationScale[2])
    }

    private fun brakingProfile(phase: Float, brakeCenters: List<Float>, brakeIntensityScale: FloatArray): Float {
        return brakeCenters.indices.sumOf { index ->
            sharpPulse(phase, brakeCenters[index], 0.028f + (index * 0.002f)).toDouble() *
                brakeIntensityScale[index].toDouble()
        }.toFloat()
    }

    private fun corneringProfile(phase: Float, cornerCenters: List<Float>, corneringScale: FloatArray): Float {
        return cornerCenters.indices.sumOf { index ->
            pulse(phase, cornerCenters[index] - 0.055f, cornerCenters[index] + 0.055f).toDouble() *
                corneringScale[index].toDouble()
        }.toFloat()
    }

    private fun cornerDirectionProfile(phase: Float, cornerCenters: List<Float>): Float {
        val leftCorner = pulse(phase, cornerCenters[0] - 0.055f, cornerCenters[0] + 0.055f) +
            pulse(phase, cornerCenters[2] - 0.055f, cornerCenters[2] + 0.055f)
        val rightCorner = pulse(phase, cornerCenters[1] - 0.055f, cornerCenters[1] + 0.055f)
        return leftCorner - rightCorner
    }

    private fun signedYawRateProfile(
        phase: Float,
        lapProfile: LapProfile,
        lapIndex: Int,
        lapCount: Int,
        noise: Float
    ): Float {
        val lapVariation = 1f + (((lapIndex.toFloat() / lapCount.coerceAtLeast(1)) - 0.5f) * 0.08f)
        val leftCorner = 2.3f * lapProfile.corneringScale[0] *
            pulse(phase, lapProfile.cornerCenters[0] - 0.055f, lapProfile.cornerCenters[0] + 0.055f)
        val rightCorner = 2.7f * lapProfile.corneringScale[1] *
            pulse(phase, lapProfile.cornerCenters[1] - 0.055f, lapProfile.cornerCenters[1] + 0.055f)
        val finalCorner = 2.2f * lapProfile.corneringScale[2] *
            pulse(phase, lapProfile.cornerCenters[2] - 0.055f, lapProfile.cornerCenters[2] + 0.055f)
        return (((leftCorner + finalCorner) - rightCorner) * lapVariation) +
            (0.14f * sine((phase * fourPi) + lapProfile.waveOffset)) +
            (noise * 0.2f)
    }

    private fun imperfectDisturbance(phase: Float, disturbanceCenter: Float): Float {
        return 0.65f * sharpPulse(phase, disturbanceCenter, 0.045f)
    }

    private fun generateBrakingPeaks(samples: List<SensorSample>, brakeCenters: List<Float>): List<Int> {
        return brakeCenters.mapNotNull { phase ->
            findLocalMinimumIndex(samples, phase, brakingWindowFraction)
        }
    }

    private fun generateCorneringPeaks(samples: List<SensorSample>, cornerCenters: List<Float>): List<Int> {
        return cornerCenters.mapNotNull { phase ->
            findLocalMaximumIndex(samples, phase, peakWindowFraction)
        }
    }

    private fun findLocalMinimumIndex(samples: List<SensorSample>, centerPhase: Float, windowFraction: Float): Int? {
        if (samples.isEmpty()) {
            return null
        }
        val range = phaseWindow(samples.size, centerPhase, windowFraction)
        return range.minByOrNull { index -> samples[index].totalAcceleration }
    }

    private fun findLocalMaximumIndex(samples: List<SensorSample>, centerPhase: Float, windowFraction: Float): Int? {
        if (samples.isEmpty()) {
            return null
        }
        val range = phaseWindow(samples.size, centerPhase, windowFraction)
        return range.maxByOrNull { index -> samples[index].yawRateAbs }
    }

    private fun phaseWindow(sampleCount: Int, centerPhase: Float, windowFraction: Float): IntRange {
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

    private fun generateSessionId(trackName: String, seed: Int): Long {
        val hash = ((trackName.lowercase().hashCode().toLong() shl 32) xor seed.toLong()) and Long.MAX_VALUE
        return (hash % Long.MAX_VALUE).coerceAtLeast(minimumDeterministicId)
    }

    private data class LapProfile(
        val brakeCenters: List<Float>,
        val cornerCenters: List<Float>,
        val brakeIntensityScale: FloatArray,
        val corneringScale: FloatArray,
        val exitAccelerationScale: FloatArray,
        val imperfectLap: Boolean,
        val disturbanceCenter: Float,
        val noiseAmplitude: Float,
        val waveOffset: Float
    )
}
