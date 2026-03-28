package com.kartingtracker.sensor

import kotlin.math.abs
import kotlin.math.sqrt

data class CalibratedAcceleration(
    val longitudinalAcceleration: Float,
    val lateralAcceleration: Float,
    val totalAcceleration: Float
)

class CalibrationManager(
    private val calibrationDurationNs: Long = 2_000_000_000L,
    private val minimumSampleCount: Int = 30
) {
    private data class CalibrationData(
        val gravityVector: FloatArray,
        val gravityUnitVector: FloatArray,
        val forwardAxis: FloatArray,
        val lateralAxis: FloatArray
    )

    private var calibrationData: CalibrationData? = null
    private var firstCalibrationTimestampNs: Long = -1L
    private var sampleCount = 0
    private val gravityAccumulator = floatArrayOf(0f, 0f, 0f)

    val isCalibrated: Boolean
        get() = calibrationData != null

    fun startCalibration() {
        calibrationData = null
        firstCalibrationTimestampNs = -1L
        sampleCount = 0
        gravityAccumulator.fill(0f)
    }

    fun reset() {
        startCalibration()
    }

    fun addCalibrationSample(accelValues: FloatArray, timestampNs: Long): Boolean {
        if (firstCalibrationTimestampNs < 0L) {
            firstCalibrationTimestampNs = timestampNs
        }

        gravityAccumulator[0] += accelValues[0]
        gravityAccumulator[1] += accelValues[1]
        gravityAccumulator[2] += accelValues[2]
        sampleCount += 1

        val elapsedNs = timestampNs - firstCalibrationTimestampNs
        if (sampleCount >= minimumSampleCount && elapsedNs >= calibrationDurationNs) {
            finalizeCalibration()
            return true
        }
        return false
    }

    fun getGravityVector(): FloatArray? = calibrationData?.gravityVector?.copyOf()

    fun projectAcceleration(accelValues: FloatArray): CalibratedAcceleration {
        val calibration = calibrationData ?: return CalibratedAcceleration(0f, 0f, 0f)
        val gravityComponentMagnitude = dot(accelValues, calibration.gravityUnitVector)
        val gravityComponent = scale(calibration.gravityUnitVector, gravityComponentMagnitude)
        val gravityRemovedAcceleration = subtract(accelValues, gravityComponent)
        return CalibratedAcceleration(
            longitudinalAcceleration = dot(gravityRemovedAcceleration, calibration.forwardAxis),
            lateralAcceleration = dot(gravityRemovedAcceleration, calibration.lateralAxis),
            totalAcceleration = norm(gravityRemovedAcceleration)
        )
    }

    private fun finalizeCalibration() {
        if (sampleCount == 0) {
            return
        }

        val gravityVector = floatArrayOf(
            gravityAccumulator[0] / sampleCount,
            gravityAccumulator[1] / sampleCount,
            gravityAccumulator[2] / sampleCount
        )
        val gravityUnitVector = normalize(gravityVector)
        var forwardAxis = projectOntoPlane(floatArrayOf(0f, 1f, 0f), gravityUnitVector)
        if (norm(forwardAxis) < 1e-4f) {
            forwardAxis = projectOntoPlane(floatArrayOf(1f, 0f, 0f), gravityUnitVector)
        }
        if (norm(forwardAxis) < 1e-4f) {
            forwardAxis = normalize(cross(floatArrayOf(0f, 0f, 1f), gravityUnitVector))
        } else {
            forwardAxis = normalize(forwardAxis)
        }

        var lateralAxis = normalize(cross(forwardAxis, gravityUnitVector))
        if (norm(lateralAxis) < 1e-4f) {
            lateralAxis = normalize(cross(gravityUnitVector, forwardAxis))
        }
        if (abs(dot(forwardAxis, lateralAxis)) > 0.1f) {
            lateralAxis = normalize(projectOntoPlane(lateralAxis, forwardAxis))
        }

        calibrationData = CalibrationData(
            gravityVector = gravityVector,
            gravityUnitVector = gravityUnitVector,
            forwardAxis = forwardAxis,
            lateralAxis = lateralAxis
        )
    }

    private fun projectOntoPlane(vector: FloatArray, planeNormal: FloatArray): FloatArray {
        val normalComponent = scale(planeNormal, dot(vector, planeNormal))
        return subtract(vector, normalComponent)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val length = norm(vector)
        if (length < 1e-6f) {
            return floatArrayOf(0f, 0f, 0f)
        }
        return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
    }

    private fun norm(vector: FloatArray): Float {
        return sqrt((vector[0] * vector[0]) + (vector[1] * vector[1]) + (vector[2] * vector[2]))
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        return (a[0] * b[0]) + (a[1] * b[1]) + (a[2] * b[2])
    }

    private fun scale(vector: FloatArray, scalar: Float): FloatArray {
        return floatArrayOf(vector[0] * scalar, vector[1] * scalar, vector[2] * scalar)
    }

    private fun subtract(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            (a[1] * b[2]) - (a[2] * b[1]),
            (a[2] * b[0]) - (a[0] * b[2]),
            (a[0] * b[1]) - (a[1] * b[0])
        )
    }
}
