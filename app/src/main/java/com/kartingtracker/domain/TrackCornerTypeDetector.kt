package com.kartingtracker.domain

import android.graphics.Bitmap
import com.kartingtracker.data.DetectedTrackCorner
import com.kartingtracker.data.TrackCornerType
import com.kartingtracker.data.TrackPoint
import kotlin.math.acos
import kotlin.math.hypot

class TrackCornerTypeDetector(
    private val config: Config = Config()
) {

    fun detectFromCenterline(centerline: List<TrackPoint>): List<DetectedTrackCorner> {
        if (centerline.size < config.sampleOffset * 2 + 1) {
            return emptyList()
        }

        val smoothedCenterline = smoothPolyline(centerline, config.centerlineSmoothingWindow)
        val rawCurvature = computeCurvature(smoothedCenterline, config.sampleOffset)
        val curvature = smoothSignal(rawCurvature, config.curvatureSmoothingWindow)
        val peakIndices = findCornerPeaks(curvature)

        if (peakIndices.isEmpty()) {
            return emptyList()
        }

        val mergedPeakIndices = mergeNearbyPeaks(curvature, peakIndices, config.minimumPeakSpacing)
        return mergedPeakIndices.mapIndexedNotNull { index, peakIndex ->
            buildDetectedCorner(
                cornerIndex = index,
                peakIndex = peakIndex,
                curvature = curvature
            )
        }
    }

    /**
     * Image fallback without OpenCV: extract dark-track centerline with radial sampling from centroid.
     */
    fun extractCenterlineFromBitmap(bitmap: Bitmap): List<TrackPoint> {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return emptyList()
        }

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val luminance = IntArray(pixels.size)
        var luminanceSum = 0L
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            val luma = (red * 299 + green * 587 + blue * 114) / 1000
            luminance[index] = luma
            luminanceSum += luma
        }

        val threshold = (luminanceSum / luminance.size).toInt().coerceIn(30, 220)
        val darkMask = BooleanArray(luminance.size)

        var centroidX = 0.0
        var centroidY = 0.0
        var darkCount = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixelIndex = y * bitmap.width + x
                if (luminance[pixelIndex] <= threshold) {
                    darkMask[pixelIndex] = true
                    centroidX += x.toDouble()
                    centroidY += y.toDouble()
                    darkCount += 1
                }
            }
        }

        if (darkCount < bitmap.width) {
            return emptyList()
        }

        centroidX /= darkCount.toDouble()
        centroidY /= darkCount.toDouble()
        val maxRadius = hypot(bitmap.width.toDouble(), bitmap.height.toDouble())
        val points = mutableListOf<TrackPoint>()

        for (angleIndex in 0 until config.radialSamples) {
            val angle = (2.0 * Math.PI * angleIndex.toDouble()) / config.radialSamples.toDouble()
            val dx = kotlin.math.cos(angle)
            val dy = kotlin.math.sin(angle)

            var firstRadius: Double? = null
            var lastRadius: Double? = null
            var radius = 0.0
            while (radius <= maxRadius) {
                val x = (centroidX + (dx * radius)).toInt()
                val y = (centroidY + (dy * radius)).toInt()
                if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
                    break
                }
                val pixelIndex = y * bitmap.width + x
                if (darkMask[pixelIndex]) {
                    if (firstRadius == null) {
                        firstRadius = radius
                    }
                    lastRadius = radius
                }
                radius += config.radialStepPixels
            }

            val first = firstRadius ?: continue
            val last = lastRadius ?: continue
            val centerRadius = (first + last) / 2.0
            val pointX = ((centroidX + (dx * centerRadius)) / bitmap.width.toDouble()).toFloat().coerceIn(0f, 1f)
            val pointY = ((centroidY + (dy * centerRadius)) / bitmap.height.toDouble()).toFloat().coerceIn(0f, 1f)
            points += TrackPoint(pointX, pointY)
        }

        return smoothPolyline(points, config.centerlineSmoothingWindow)
    }

    private fun findCornerPeaks(curvature: List<Float>): List<Int> {
        if (curvature.isEmpty()) {
            return emptyList()
        }

        val peaks = mutableListOf<Int>()
        for (index in curvature.indices) {
            val previous = curvature[circularIndex(index - 1, curvature.size)]
            val current = curvature[index]
            val next = curvature[circularIndex(index + 1, curvature.size)]
            if (current >= config.minimumCornerAngle && current >= previous && current >= next) {
                peaks += index
            }
        }
        return peaks
    }

    private fun mergeNearbyPeaks(curvature: List<Float>, peaks: List<Int>, minimumSpacing: Int): List<Int> {
        if (peaks.size <= 1) {
            return peaks
        }

        val sorted = peaks.sorted()
        val merged = mutableListOf<Int>()

        sorted.forEach { peak ->
            if (merged.isEmpty()) {
                merged += peak
                return@forEach
            }

            val lastPeak = merged.last()
            val distance = circularDistance(lastPeak, peak, curvature.size)
            if (distance < minimumSpacing) {
                if (curvature[peak] > curvature[lastPeak]) {
                    merged[merged.lastIndex] = peak
                }
            } else {
                merged += peak
            }
        }

        if (merged.size > 1) {
            val first = merged.first()
            val last = merged.last()
            val wrapDistance = circularDistance(last, first, curvature.size)
            if (wrapDistance < minimumSpacing) {
                if (curvature[first] >= curvature[last]) {
                    merged.removeLast()
                } else {
                    merged.removeFirst()
                }
            }
        }

        return merged
    }

    private fun buildDetectedCorner(
        cornerIndex: Int,
        peakIndex: Int,
        curvature: List<Float>
    ): DetectedTrackCorner? {
        val threshold = config.minimumCornerAngle * config.segmentEndFactor
        var startIndex = peakIndex
        while (curvature[startIndex] > threshold) {
            val next = circularIndex(startIndex - 1, curvature.size)
            if (next == peakIndex) {
                break
            }
            startIndex = next
        }

        var endIndex = peakIndex
        while (curvature[endIndex] > threshold) {
            val next = circularIndex(endIndex + 1, curvature.size)
            if (next == peakIndex) {
                break
            }
            endIndex = next
        }

        val peak = curvature[peakIndex]
        val type = when {
            peak > config.tightCornerThreshold -> TrackCornerType.TIGHT
            peak > config.mediumCornerThreshold -> TrackCornerType.MEDIUM
            else -> TrackCornerType.FAST
        }

        return DetectedTrackCorner(
            index = cornerIndex,
            startIndex = startIndex,
            peakIndex = peakIndex,
            endIndex = endIndex,
            type = type,
            curvature = peak
        )
    }

    private fun computeCurvature(points: List<TrackPoint>, sampleOffset: Int): List<Float> {
        return points.indices.map { index ->
            val previous = points[circularIndex(index - sampleOffset, points.size)]
            val current = points[index]
            val next = points[circularIndex(index + sampleOffset, points.size)]

            val v1x = current.x - previous.x
            val v1y = current.y - previous.y
            val v2x = next.x - current.x
            val v2y = next.y - current.y

            val magnitude1 = hypot(v1x.toDouble(), v1y.toDouble())
            val magnitude2 = hypot(v2x.toDouble(), v2y.toDouble())
            if (magnitude1 < 1e-6 || magnitude2 < 1e-6) {
                0f
            } else {
                val dot = (v1x * v2x + v1y * v2y).toDouble()
                val normalizedDot = (dot / (magnitude1 * magnitude2)).coerceIn(-1.0, 1.0)
                acos(normalizedDot).toFloat()
            }
        }
    }

    private fun smoothPolyline(points: List<TrackPoint>, windowSize: Int): List<TrackPoint> {
        if (points.isEmpty() || windowSize <= 1) {
            return points
        }
        val radius = windowSize / 2
        return points.indices.map { index ->
            var xSum = 0f
            var ySum = 0f
            var count = 0
            for (delta in -radius..radius) {
                val point = points[circularIndex(index + delta, points.size)]
                xSum += point.x
                ySum += point.y
                count += 1
            }
            TrackPoint(xSum / count.toFloat(), ySum / count.toFloat())
        }
    }

    private fun smoothSignal(values: List<Float>, windowSize: Int): List<Float> {
        if (values.isEmpty() || windowSize <= 1) {
            return values
        }
        val radius = windowSize / 2
        return values.indices.map { index ->
            var sum = 0f
            var count = 0
            for (delta in -radius..radius) {
                sum += values[circularIndex(index + delta, values.size)]
                count += 1
            }
            sum / count.toFloat()
        }
    }

    private fun circularIndex(index: Int, size: Int): Int {
        if (size == 0) {
            return 0
        }
        val mod = index % size
        return if (mod >= 0) mod else mod + size
    }

    private fun circularDistance(first: Int, second: Int, size: Int): Int {
        val delta = kotlin.math.abs(first - second)
        return minOf(delta, size - delta)
    }

    data class Config(
        val sampleOffset: Int = 3,
        val minimumCornerAngle: Float = 0.2f,
        val tightCornerThreshold: Float = 1.0f,
        val mediumCornerThreshold: Float = 0.5f,
        val minimumPeakSpacing: Int = 6,
        val curvatureSmoothingWindow: Int = 7,
        val centerlineSmoothingWindow: Int = 5,
        val segmentEndFactor: Float = 0.75f,
        val radialSamples: Int = 360,
        val radialStepPixels: Double = 1.0
    )
}
