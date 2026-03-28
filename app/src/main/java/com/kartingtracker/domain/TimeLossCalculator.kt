package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import kotlin.math.abs
import kotlin.math.sqrt

data class TimeLossResult(
    val deltaCurve: List<Float>,
    val confidence: Float
)

object TimeLossCalculator {
    private const val integrationDtSeconds = 0.1f
    private const val minimumVelocity = 1.0f
    private const val maximumVelocity = 32.0f
    private const val baseVelocity = 12.0f
    private const val integrationGain = 2.4f
    private const val driftCorrectionFactor = 0.98f
    private const val driftCorrectionInterval = 10
    private const val smoothingRadius = 2
    private const val fallbackShiftRange = 6
    private const val fallbackWindowRadius = 6
    private const val fallbackConfidenceThreshold = 0.55f
    private const val minimumStdDev = 1e-3f

    fun computeTimeLoss(lapA: Lap, lapB: Lap): List<Float> {
        return computeTimeLossResult(lapA, lapB).deltaCurve
    }

    fun calculateTimeLoss(lapA: Lap, lapB: Lap): List<Float> {
        return computeTimeLoss(lapA, lapB)
    }

    fun computeTimeLossResult(lapA: Lap, lapB: Lap): TimeLossResult {
        val pointCount = LapNormalizer.DEFAULT_POINT_COUNT
        val rawAccelerationA = LapNormalizer.normalizeSignal(lapA, pointCount) { sample -> sample.totalAcceleration }
        val rawAccelerationB = LapNormalizer.normalizeSignal(lapB, pointCount) { sample -> sample.totalAcceleration }
        if (rawAccelerationA.isEmpty() || rawAccelerationB.isEmpty()) {
            return TimeLossResult(emptyList(), 0f)
        }

        val preparedAccelerationA = preprocessAcceleration(rawAccelerationA)
        val preparedAccelerationB = preprocessAcceleration(rawAccelerationB)
        val physicsCurve = buildPhysicsBasedDeltaCurve(
            accelerationA = preparedAccelerationA,
            accelerationB = preparedAccelerationB,
            lapTimeAMs = lapA.lapTimeMs,
            lapTimeBMs = lapB.lapTimeMs
        )
        val confidence = estimateConfidence(
            lapA = lapA,
            lapB = lapB,
            accelerationA = preparedAccelerationA,
            accelerationB = preparedAccelerationB
        )

        val blendedCurve = if (confidence < fallbackConfidenceThreshold) {
            val fallbackCurve = buildPatternFallbackCurve(
                accelerationA = preparedAccelerationA,
                accelerationB = preparedAccelerationB,
                lapTimeAMs = lapA.lapTimeMs,
                lapTimeBMs = lapB.lapTimeMs
            )
            val fallbackWeight =
                ((fallbackConfidenceThreshold - confidence) / fallbackConfidenceThreshold).coerceIn(0f, 1f)
            blendCurves(physicsCurve, fallbackCurve, fallbackWeight)
        } else {
            physicsCurve
        }

        val finalDeltaSeconds = (lapA.lapTimeMs - lapB.lapTimeMs) / 1000f
        return TimeLossResult(
            deltaCurve = alignCurveEndpoints(
                curve = smoothSignal(blendedCurve, radius = 1),
                desiredFinalDeltaSeconds = finalDeltaSeconds
            ),
            confidence = confidence
        )
    }

    private fun preprocessAcceleration(acceleration: List<Float>): List<Float> {
        return zScoreNormalize(smoothSignal(acceleration, smoothingRadius))
    }

    private fun buildPhysicsBasedDeltaCurve(
        accelerationA: List<Float>,
        accelerationB: List<Float>,
        lapTimeAMs: Long,
        lapTimeBMs: Long
    ): List<Float> {
        val velocityA = integrateVelocity(accelerationA, integrationDtSeconds)
        val velocityB = integrateVelocity(accelerationB, integrationDtSeconds)
        val distanceA = computeDistanceCurve(velocityA, integrationDtSeconds)
        val distanceB = computeDistanceCurve(velocityB, integrationDtSeconds)
        val timeCurveA = scaleToActualLapTime(
            timeCurve = computeTimeCurveByNormalizedDistance(distanceA, integrationDtSeconds),
            lapTimeMs = lapTimeAMs
        )
        val timeCurveB = scaleToActualLapTime(
            timeCurve = computeTimeCurveByNormalizedDistance(distanceB, integrationDtSeconds),
            lapTimeMs = lapTimeBMs
        )
        return computeDelta(timeCurveA, timeCurveB)
    }

    private fun buildPatternFallbackCurve(
        accelerationA: List<Float>,
        accelerationB: List<Float>,
        lapTimeAMs: Long,
        lapTimeBMs: Long
    ): List<Float> {
        val pointCount = minOf(accelerationA.size, accelerationB.size)
        if (pointCount == 0) {
            return emptyList()
        }

        val averageLapTimeSeconds = ((lapTimeAMs + lapTimeBMs) / 2f) / 1000f
        val localShiftCurve = MutableList(pointCount) { 0f }
        for (index in 0 until pointCount) {
            val bestShift = findBestLocalShift(
                signalA = accelerationA,
                signalB = accelerationB,
                centerIndex = index
            )
            localShiftCurve[index] =
                (bestShift.toFloat() / (pointCount - 1).coerceAtLeast(1).toFloat()) * averageLapTimeSeconds
        }

        return alignCurveEndpoints(
            curve = smoothSignal(localShiftCurve, smoothingRadius),
            desiredFinalDeltaSeconds = (lapTimeAMs - lapTimeBMs) / 1000f
        )
    }

    private fun integrateVelocity(acceleration: List<Float>, dt: Float): List<Float> {
        if (acceleration.isEmpty()) {
            return emptyList()
        }

        val velocity = MutableList(acceleration.size) { baseVelocity }
        for (index in 1 until acceleration.size) {
            var nextVelocity = velocity[index - 1] + (acceleration[index] * integrationGain * dt)
            if (index % driftCorrectionInterval == 0) {
                nextVelocity *= driftCorrectionFactor
            }
            velocity[index] = nextVelocity.coerceIn(minimumVelocity, maximumVelocity)
        }
        return velocity
    }

    private fun computeDistanceCurve(velocity: List<Float>, dt: Float): List<Float> {
        if (velocity.isEmpty()) {
            return emptyList()
        }

        val distance = MutableList(velocity.size) { 0f }
        for (index in 1 until velocity.size) {
            val positiveVelocity = velocity[index].coerceIn(minimumVelocity, maximumVelocity)
            distance[index] = distance[index - 1] + (positiveVelocity * dt)
        }
        return distance
    }

    private fun computeTimeCurveByNormalizedDistance(distanceCurve: List<Float>, dt: Float): List<Float> {
        if (distanceCurve.isEmpty()) {
            return emptyList()
        }

        val totalDistance = distanceCurve.last().coerceAtLeast(minimumVelocity)
        val times = MutableList(distanceCurve.size) { 0f }
        var cursor = 0

        for (index in times.indices) {
            val progress = index.toFloat() / (times.lastIndex).coerceAtLeast(1)
            val targetDistance = totalDistance * progress

            while (cursor < distanceCurve.lastIndex - 1 && distanceCurve[cursor + 1] < targetDistance) {
                cursor += 1
            }

            val beforeDistance = distanceCurve[cursor]
            val afterIndex = (cursor + 1).coerceAtMost(distanceCurve.lastIndex)
            val afterDistance = distanceCurve[afterIndex]
            val beforeTime = cursor * dt
            val afterTime = afterIndex * dt
            times[index] = if (abs(afterDistance - beforeDistance) < minimumStdDev) {
                beforeTime
            } else {
                val interpolation =
                    ((targetDistance - beforeDistance) / (afterDistance - beforeDistance)).coerceIn(0f, 1f)
                beforeTime + ((afterTime - beforeTime) * interpolation)
            }
        }
        return times
    }

    private fun scaleToActualLapTime(timeCurve: List<Float>, lapTimeMs: Long): List<Float> {
        if (timeCurve.isEmpty()) {
            return emptyList()
        }

        val totalTime = timeCurve.last().coerceAtLeast(integrationDtSeconds)
        val scale = ((lapTimeMs / 1000f).coerceAtLeast(integrationDtSeconds)) / totalTime
        return timeCurve.map { value -> value * scale }
    }

    private fun computeDelta(timeA: List<Float>, timeB: List<Float>): List<Float> {
        val size = minOf(timeA.size, timeB.size)
        return List(size) { index ->
            timeA[index] - timeB[index]
        }
    }

    private fun estimateConfidence(
        lapA: Lap,
        lapB: Lap,
        accelerationA: List<Float>,
        accelerationB: List<Float>
    ): Float {
        val signalSimilarity = ((cosineSimilarity(accelerationA, accelerationB) + 1f) / 2f).coerceIn(0f, 1f)
        val lapConfidence = ((lapA.confidenceScore + lapB.confidenceScore) / 2f).coerceIn(0f, 1f)
        return (signalSimilarity * lapConfidence).coerceIn(0f, 1f)
    }

    private fun smoothSignal(values: List<Float>, radius: Int): List<Float> {
        if (values.isEmpty() || radius <= 0) {
            return values
        }

        return List(values.size) { index ->
            val start = (index - radius).coerceAtLeast(0)
            val end = (index + radius).coerceAtMost(values.lastIndex)
            values.subList(start, end + 1).average().toFloat()
        }
    }

    private fun zScoreNormalize(values: List<Float>): List<Float> {
        if (values.isEmpty()) {
            return emptyList()
        }

        val mean = values.average().toFloat()
        val variance = values
            .map { value ->
                val centered = value - mean
                centered * centered
            }
            .average()
            .toFloat()
        val stdDev = sqrt(variance).coerceAtLeast(minimumStdDev)
        return values.map { value -> (value - mean) / stdDev }
    }

    private fun findBestLocalShift(
        signalA: List<Float>,
        signalB: List<Float>,
        centerIndex: Int
    ): Int {
        var bestShift = 0
        var bestScore = Float.NEGATIVE_INFINITY

        for (shift in -fallbackShiftRange..fallbackShiftRange) {
            val score = localSimilarity(signalA, signalB, centerIndex, shift)
            if (score > bestScore) {
                bestScore = score
                bestShift = shift
            }
        }
        return bestShift
    }

    private fun localSimilarity(
        signalA: List<Float>,
        signalB: List<Float>,
        centerIndex: Int,
        shift: Int
    ): Float {
        var dotProduct = 0f
        var magnitudeA = 0f
        var magnitudeB = 0f
        var comparedPoints = 0

        for (offset in -fallbackWindowRadius..fallbackWindowRadius) {
            val indexA = centerIndex + offset
            val indexB = centerIndex + offset + shift
            if (indexA !in signalA.indices || indexB !in signalB.indices) {
                continue
            }
            val valueA = signalA[indexA]
            val valueB = signalB[indexB]
            dotProduct += valueA * valueB
            magnitudeA += valueA * valueA
            magnitudeB += valueB * valueB
            comparedPoints += 1
        }

        if (comparedPoints == 0 || magnitudeA <= minimumStdDev || magnitudeB <= minimumStdDev) {
            return -1f
        }
        return dotProduct / (sqrt(magnitudeA) * sqrt(magnitudeB))
    }

    private fun cosineSimilarity(valuesA: List<Float>, valuesB: List<Float>): Float {
        val size = minOf(valuesA.size, valuesB.size)
        if (size == 0) {
            return 0f
        }

        var dotProduct = 0f
        var magnitudeA = 0f
        var magnitudeB = 0f
        for (index in 0 until size) {
            val valueA = valuesA[index]
            val valueB = valuesB[index]
            dotProduct += valueA * valueB
            magnitudeA += valueA * valueA
            magnitudeB += valueB * valueB
        }

        if (magnitudeA <= minimumStdDev || magnitudeB <= minimumStdDev) {
            return 0f
        }
        return dotProduct / (sqrt(magnitudeA) * sqrt(magnitudeB))
    }

    private fun blendCurves(primary: List<Float>, secondary: List<Float>, secondaryWeight: Float): List<Float> {
        val size = minOf(primary.size, secondary.size)
        val clampedWeight = secondaryWeight.coerceIn(0f, 1f)
        return List(size) { index ->
            val primaryValue = primary[index]
            val secondaryValue = secondary[index]
            (primaryValue * (1f - clampedWeight)) + (secondaryValue * clampedWeight)
        }
    }

    private fun alignCurveEndpoints(curve: List<Float>, desiredFinalDeltaSeconds: Float): List<Float> {
        if (curve.isEmpty()) {
            return emptyList()
        }

        val normalizedStart = curve.map { value -> value - curve.first() }
        val currentFinalDelta = normalizedStart.last()
        return normalizedStart.mapIndexed { index, value ->
            val progress = index.toFloat() / (normalizedStart.lastIndex).coerceAtLeast(1)
            value + ((desiredFinalDeltaSeconds - currentFinalDelta) * progress)
        }
    }
}
