package com.kartingtracker.data

data class SensorSample(
    val timestampNs: Long,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val longitudinalAccel: Float,
    val lateralAccel: Float
)
