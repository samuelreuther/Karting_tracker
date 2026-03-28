package com.kartingtracker.domain

import com.kartingtracker.data.Lap

object TimeLossCalculator {
    private const val integrationDtSeconds = 0.1f
    private const val minimumVelocity = 0.1f

    fun calculateTimeLoss(lapA: Lap, lapB: Lap): List<Float> {
        val pointCount = LapNormalizer.DEFAULT_POINT_COUNT
        val accA = LapNormalizer.normalizeSignal(lapA, pointCount) { sample -> sample.totalAcceleration }
        val accB = LapNormalizer.normalizeSignal(lapB, pointCount) { sample -> sample.totalAcceleration }
        if (accA.isEmpty() || accB.isEmpty()) {
            return emptyList()
        }

        val velocityA = integrateVelocity(accA, integrationDtSeconds)
        val velocityB = integrateVelocity(accB, integrationDtSeconds)
        val timeCurveA = scaleToActualLapTime(computeTimeCurve(velocityA), lapA.lapTimeMs)
        val timeCurveB = scaleToActualLapTime(computeTimeCurve(velocityB), lapB.lapTimeMs)
        return computeDelta(timeCurveA, timeCurveB)
    }

    private fun integrateVelocity(acc: List<Float>, dt: Float): List<Float> {
        val velocity = MutableList(acc.size) { minimumVelocity }
        for (index in 1 until acc.size) {
            velocity[index] = (velocity[index - 1] + (acc[index] * dt)).coerceAtLeast(minimumVelocity)
        }
        return velocity
    }

    private fun computeTimeCurve(velocity: List<Float>): List<Float> {
        val time = MutableList(velocity.size) { 0f }
        for (index in 1 until velocity.size) {
            val v = velocity[index].coerceAtLeast(minimumVelocity)
            val dt = 1f / v
            time[index] = time[index - 1] + dt
        }
        return time
    }

    private fun scaleToActualLapTime(timeCurve: List<Float>, lapTimeMs: Long): List<Float> {
        if (timeCurve.isEmpty()) {
            return emptyList()
        }
        val totalTime = timeCurve.last().coerceAtLeast(minimumVelocity)
        val scale = (lapTimeMs / 1000f).coerceAtLeast(minimumVelocity) / totalTime
        return timeCurve.map { value -> value * scale }
    }

    private fun computeDelta(timeA: List<Float>, timeB: List<Float>): List<Float> {
        val size = minOf(timeA.size, timeB.size)
        return List(size) { index ->
            timeA[index] - timeB[index]
        }
    }
}
