package com.kartingtracker.data

import android.content.Context
import kotlin.math.acos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
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
    private const val minimumLapSamples = 484
    private const val maximumLapSamples = 520
    private const val minimumLapCount = 23
    private const val maximumLapCount = 25
    private const val imperfectLapChance = 0.19f
    private const val driftLimitMs = 1_500f
    private const val defaultDurationMinutes = 10
    private const val minimumDeterministicId = 10_000L
    private const val twoPi = (2.0 * PI).toFloat()
    private const val fourPi = (4.0 * PI).toFloat()

    fun generateSession(trackName: String): Session {
        return generateSeededSession(trackName, baseSeed.toInt(), defaultDurationMinutes)
    }

    fun generateSeededSession(trackName: String, seed: Int, durationMinutes: Int = defaultDurationMinutes): Session {
        val random = Random(baseSeed xor trackName.hashCode().toLong() xor seed.toLong())
        val totalSampleCount = ((durationMinutes * 60_000L) / sampleIntervalMs).toInt().coerceAtLeast(8_000)
        val targetLapMs = random.nextLong(24_400L, 25_600L)
        val lapCount = ((durationMinutes * 60_000L).toFloat() / targetLapMs.toFloat()).toInt()
            .coerceIn(minimumLapCount, maximumLapCount)
        val lapSampleCounts = buildLapSampleCounts(random, totalSampleCount, lapCount)
        val trackPattern = resolveTrackPattern(trackName)
        val totalDurationMs = lapSampleCounts.sumOf { lapSamples -> lapSamples * sampleIntervalMs }
        val startTimeEpochMs = debugBaseStartTimeEpochMs + (seed.toLong() * totalDurationMs)
        val startTimestampNs = 1_000_000_000L

        val allSamples = mutableListOf<SensorSample>()

        var currentTimestampNs = startTimestampNs
        var previousTotalAcceleration = 2.4f
        var lapDriftMs = random.nextInt(-450, 451).toFloat()

        lapSampleCounts.forEachIndexed { lapIndex, lapSampleCount ->
            var smoothedLongitudinal = 0f
            val lapProfile = buildLapProfile(random, lapIndex, lapCount, lapDriftMs, trackPattern)
            lapDriftMs = ((lapDriftMs * 0.55f) + (random.nextInt(-700, 701) * 0.45f)).coerceIn(-driftLimitMs, driftLimitMs)

            for (sampleIndex in 0 until lapSampleCount) {
                val phase = sampleIndex.toFloat() / (lapSampleCount - 1).coerceAtLeast(1)
                val lapNoise = lapProfile.noiseAmplitude * (random.nextFloat() - 0.5f)
                val straightDrive = straightDriveProfile(phase, lapProfile)
                val brakingDemand = brakingProfile(phase, lapProfile)
                val corneringLoad = corneringProfile(phase, lapProfile)
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
                        (cornerDirectionProfile(phase, lapProfile) * 0.25f) +
                        (disturbance * 0.05f) +
                        (lapNoise * 0.10f)
                    ).coerceIn(-3.5f, 3.5f)

                val gyroX = (0.08f * sine((phase * fourPi) + lapProfile.waveOffset)) + (lapNoise * 0.06f)
                val gyroY = (0.06f * sine((phase * fourPi) + 1.2f + lapProfile.waveOffset)) - (lapNoise * 0.04f)
                val gyroZ = signedYawRate
                val accelZ = 9.81f + (0.12f * sine((phase * fourPi) + 0.7f)) + (lapNoise * 0.08f) - (disturbance * 0.02f)

                allSamples += SensorSample(
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
        }

        val estimatedLapTimeMs = lapSampleCounts
            .map { lapSamples -> lapSamples * sampleIntervalMs }
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
            laps = emptyList(),
            estimatedLapTimeMs = estimatedLapTimeMs,
            insights = emptyList(),
            theoreticalBestLapTimeMs = null,
            topTimeLossSegments = emptyList(),
            segmentMarkers = emptyList(),
            quality = null,
            processingVersion = 5
        )
        return baseSession
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

    private fun buildLapProfile(
        random: Random,
        lapIndex: Int,
        lapCount: Int,
        lapDriftMs: Float,
        trackPattern: TrackPattern
    ): LapProfile {
        val imperfectLap = random.nextFloat() < imperfectLapChance
        val sessionProgress = lapIndex.toFloat() / lapCount.coerceAtLeast(1).toFloat()
        val improvement = if (sessionProgress < 0.62f) sessionProgress * 0.08f else 0.050f
        val fatigue = if (sessionProgress > 0.76f) ((sessionProgress - 0.76f) / 0.24f) * 0.07f else 0f
        val paceFactor = (1f - (lapDriftMs / 5_000f) + improvement - fatigue).coerceIn(0.80f, 1.12f)
        val imperfectionPenalty = if (imperfectLap) 0.10f else 0f
        val disturbanceCenter = trackPattern.cornerPhases[random.nextInt(trackPattern.cornerPhases.size)]
            .coerceIn(0.20f, 0.88f)
        val brakingVariation = random.nextFloat()
        val brakingOffset = when {
            brakingVariation < 0.22f -> -0.010f - (random.nextFloat() * 0.010f) // late braking
            brakingVariation > 0.84f -> 0.008f + (random.nextFloat() * 0.012f) // early braking
            else -> (random.nextFloat() - 0.5f) * 0.012f
        }

        val cornerCenters = trackPattern.cornerPhases.map { center ->
            (center + ((random.nextFloat() - 0.5f) * 0.012f)).coerceIn(0.02f, 0.98f)
        }
        val brakeCenters = cornerCenters.mapIndexed { index, center ->
            val leadTime = (0.024f + (trackPattern.cornerSharpness[index] * 0.022f) + brakingOffset)
                .coerceIn(0.015f, 0.055f)
            (center - leadTime).coerceIn(0.01f, 0.96f)
        }

        return LapProfile(
            brakeCenters = brakeCenters,
            cornerCenters = cornerCenters,
            cornerDirection = trackPattern.cornerDirection,
            cornerWidth = trackPattern.cornerWidth.map { width ->
                (width + ((random.nextFloat() - 0.5f) * 0.009f)).coerceIn(0.045f, 0.095f)
            },
            brakeIntensityScale = trackPattern.cornerSharpness.map { sharpness ->
                (0.80f + (sharpness * 0.42f) + (random.nextFloat() * 0.14f) - imperfectionPenalty).coerceIn(0.65f, 1.24f)
            },
            corneringScale = trackPattern.cornerSharpness.map { sharpness ->
                (paceFactor + 0.06f + (sharpness * 0.35f) + (random.nextFloat() * 0.11f) - imperfectionPenalty).coerceIn(0.76f, 1.26f)
            },
            exitAccelerationScale = trackPattern.cornerSharpness.map { sharpness ->
                (paceFactor + 0.08f - (sharpness * 0.22f) + (random.nextFloat() * 0.14f) - imperfectionPenalty).coerceIn(0.72f, 1.18f)
            },
            imperfectLap = imperfectLap,
            disturbanceCenter = disturbanceCenter,
            noiseAmplitude = if (imperfectLap) 0.20f else 0.12f,
            waveOffset = ((lapIndex % 7) * 0.18f)
        )
    }

    private fun straightDriveProfile(phase: Float, lapProfile: LapProfile): Float {
        val cornerGuard = lapProfile.cornerCenters.indices.sumOf { index ->
            pulse(
                phase,
                lapProfile.cornerCenters[index] - (lapProfile.cornerWidth[index] * 1.10f),
                lapProfile.cornerCenters[index] + (lapProfile.cornerWidth[index] * 0.80f)
            ).toDouble()
        }.toFloat()
        val baseRamp = ramp(phase, 0.0f, 1.0f)
        val exitRamp = lapProfile.cornerCenters.indices.sumOf { index ->
            ramp(
                phase,
                lapProfile.cornerCenters[index] + (lapProfile.cornerWidth[index] * 0.25f),
                min(0.995f, lapProfile.cornerCenters[index] + (lapProfile.cornerWidth[index] * 1.85f))
            ).toDouble() * lapProfile.exitAccelerationScale[index].toDouble()
        }.toFloat()
        return ((0.15f * baseRamp) + exitRamp - (cornerGuard * 0.50f)).coerceAtLeast(0f)
    }

    private fun brakingProfile(phase: Float, lapProfile: LapProfile): Float {
        return lapProfile.brakeCenters.indices.sumOf { index ->
            sharpPulse(phase, lapProfile.brakeCenters[index], lapProfile.cornerWidth[index] * 0.56f).toDouble() *
                lapProfile.brakeIntensityScale[index].toDouble()
        }.toFloat()
    }

    private fun corneringProfile(phase: Float, lapProfile: LapProfile): Float {
        return lapProfile.cornerCenters.indices.sumOf { index ->
            pulse(
                phase,
                lapProfile.cornerCenters[index] - lapProfile.cornerWidth[index],
                lapProfile.cornerCenters[index] + lapProfile.cornerWidth[index]
            ).toDouble() * lapProfile.corneringScale[index].toDouble()
        }.toFloat()
    }

    private fun cornerDirectionProfile(phase: Float, lapProfile: LapProfile): Float {
        return lapProfile.cornerCenters.indices.sumOf { index ->
            pulse(
                phase,
                lapProfile.cornerCenters[index] - lapProfile.cornerWidth[index],
                lapProfile.cornerCenters[index] + lapProfile.cornerWidth[index]
            ).toDouble() * lapProfile.cornerDirection[index].toDouble()
        }.toFloat()
    }

    private fun signedYawRateProfile(
        phase: Float,
        lapProfile: LapProfile,
        lapIndex: Int,
        lapCount: Int,
        noise: Float
    ): Float {
        val lapVariation = 1f + (((lapIndex.toFloat() / lapCount.coerceAtLeast(1)) - 0.5f) * 0.08f)
        val cornerYaw = lapProfile.cornerCenters.indices.sumOf { index ->
            pulse(
                phase,
                lapProfile.cornerCenters[index] - lapProfile.cornerWidth[index],
                lapProfile.cornerCenters[index] + lapProfile.cornerWidth[index]
            ).toDouble() * lapProfile.corneringScale[index].toDouble() * lapProfile.cornerDirection[index].toDouble() *
                (2.15 + (lapProfile.brakeIntensityScale[index] * 0.62)).toDouble()
        }.toFloat()
        return (cornerYaw * lapVariation) +
            (0.14f * sine((phase * fourPi) + lapProfile.waveOffset)) +
            (noise * 0.2f)
    }

    private fun imperfectDisturbance(phase: Float, disturbanceCenter: Float): Float {
        return 0.65f * sharpPulse(phase, disturbanceCenter, 0.045f)
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
        val cornerDirection: List<Float>,
        val cornerWidth: List<Float>,
        val brakeIntensityScale: List<Float>,
        val corneringScale: List<Float>,
        val exitAccelerationScale: List<Float>,
        val imperfectLap: Boolean,
        val disturbanceCenter: Float,
        val noiseAmplitude: Float,
        val waveOffset: Float
    )

    private data class TrackPattern(
        val cornerPhases: List<Float>,
        val cornerSharpness: List<Float>,
        val cornerDirection: List<Float>,
        val cornerWidth: List<Float>
    )

    private fun resolveTrackPattern(trackName: String): TrackPattern {
        val normalized = trackName.trim().lowercase()
        val layoutCorners = if (normalized == "loerrach vm kart racing") {
            loerrachLayoutCorners
        } else {
            defaultLayoutCorners
        }
        return buildTrackPattern(layoutCorners)
    }

    private fun buildTrackPattern(corners: List<TrackPoint>): TrackPattern {
        if (corners.size < 3) {
            return TrackPattern(
                cornerPhases = listOf(0.22f, 0.54f, 0.84f),
                cornerSharpness = listOf(0.80f, 1.00f, 0.88f),
                cornerDirection = listOf(1f, -1f, 1f),
                cornerWidth = listOf(0.070f, 0.060f, 0.065f)
            )
        }

        val phases = buildCornerPhases(corners)
        val sharpness = corners.indices.map { index ->
            val prev = corners[(index - 1 + corners.size) % corners.size]
            val current = corners[index]
            val next = corners[(index + 1) % corners.size]
            val entryX = current.x - prev.x
            val entryY = current.y - prev.y
            val exitX = next.x - current.x
            val exitY = next.y - current.y
            val entryMag = sqrt((entryX * entryX) + (entryY * entryY))
            val exitMag = sqrt((exitX * exitX) + (exitY * exitY))
            if (entryMag <= 0.0001f || exitMag <= 0.0001f) {
                0.85f
            } else {
                val dot = ((entryX * exitX) + (entryY * exitY)) / (entryMag * exitMag)
                val angle = acos(dot.coerceIn(-1f, 1f))
                (0.55f + ((angle / PI.toFloat()).pow(0.85f) * 0.75f)).coerceIn(0.55f, 1.30f)
            }
        }
        val directions = corners.indices.map { index ->
            val prev = corners[(index - 1 + corners.size) % corners.size]
            val current = corners[index]
            val next = corners[(index + 1) % corners.size]
            val entryX = current.x - prev.x
            val entryY = current.y - prev.y
            val exitX = next.x - current.x
            val exitY = next.y - current.y
            val cross = (entryX * exitY) - (entryY * exitX)
            if (cross >= 0f) 1f else -1f
        }
        val widths = sharpness.map { cornerSharpness ->
            (0.095f - ((cornerSharpness - 0.55f) * 0.030f)).coerceIn(0.048f, 0.092f)
        }
        return TrackPattern(
            cornerPhases = phases,
            cornerSharpness = sharpness,
            cornerDirection = directions,
            cornerWidth = widths
        )
    }

    private fun buildCornerPhases(corners: List<TrackPoint>): List<Float> {
        val start = loerrachStartPoint
        val trackPoints = listOf(start) + corners + listOf(start)
        val lengths = trackPoints.zipWithNext { a, b ->
            distance(a, b)
        }
        val totalLength = lengths.sum().takeIf { it > 0f } ?: 1f
        var accum = lengths.firstOrNull() ?: 0f
        return corners.indices.map { index ->
            val phase = (accum / totalLength).coerceIn(0.02f, 0.98f)
            accum += lengths.getOrElse(index + 1) { 0f }
            phase
        }
    }

    private fun distance(a: TrackPoint, b: TrackPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt((dx * dx) + (dy * dy))
    }

    private val loerrachStartPoint = TrackPoint(0.74f, 0.74f)
    private val loerrachLayoutCorners = listOf(
        TrackPoint(0.10f, 0.83f),
        TrackPoint(0.16f, 0.15f),
        TrackPoint(0.60f, 0.09f),
        TrackPoint(0.76f, 0.36f),
        TrackPoint(0.63f, 0.56f),
        TrackPoint(0.17f, 0.64f)
    )
    private val defaultLayoutCorners = listOf(
        TrackPoint(0.12f, 0.78f),
        TrackPoint(0.18f, 0.22f),
        TrackPoint(0.62f, 0.12f),
        TrackPoint(0.82f, 0.45f),
        TrackPoint(0.58f, 0.72f)
    )
}
