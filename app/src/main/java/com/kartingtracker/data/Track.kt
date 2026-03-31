package com.kartingtracker.data

import android.graphics.PointF

data class Track(
    val name: String,
    val mapImagePath: String? = null,
    val mapWidthMeters: Float? = null,
    val mapHeightMeters: Float? = null,
    val startPoint: PointF? = null,
    val startDirectionDeg: Float? = null,
    val lengthMeters: Float? = null,
    val location: String? = null,
    val isIndoor: Boolean? = null,
    val lastUsedEpochMs: Long? = null
)
