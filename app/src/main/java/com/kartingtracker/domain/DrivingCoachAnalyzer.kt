package com.kartingtracker.domain

import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.SegmentMarker
import com.kartingtracker.data.Session
import com.kartingtracker.data.TimeLossSegment
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackProfile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SessionTelemetryAnalysis(
    val insights: List<String> = emptyList(),
    val theoreticalBestLapTimeMs: Long? = null,
    val topTimeLossSegments: List<TimeLossSegment> = emptyList(),
    val segmentMarkers: List<SegmentMarker> = emptyList()
)

class DrivingCoachAnalyzer {
    private val autoCornerDetector = AutoCornerDetector()

    fun analyzeSession(
        session: Session,
        trackProfile: TrackProfile? = null,
        trackLayout: TrackLayout? = null
    ): SessionTelemetryAnalysis {
        val referenceLap = selectReferenceLap(session.laps) ?: return SessionTelemetryAnalysis()
        val validLaps = session.laps.filter(::isPrimaryValidLap)
        if (validLaps.size < minimumComparableLaps) {
            return SessionTelemetryAnalysis()
        }

        val segmentation = resolveSegmentation(referenceLap)
        val detectedCorners = autoCornerDetector.detectCorners(referenceLap)
        val referenceAnalysis = analyzeLap(referenceLap, segmentation)
        val comparableAnalyses = validLaps
            .filterNot { lap -> lap.id == referenceLap.id }
            .map { lap -> analyzeLap(lap, segmentation) }

        if (comparableAnalyses.isEmpty()) {
            return SessionTelemetryAnalysis()
        }

        val segmentComparisons = segmentation.segments.map { segment ->
            aggregateSegmentComparison(segment, referenceAnalysis, comparableAnalyses)
        }
        val topSegments = segmentComparisons
            .filter { comparison -> comparison.averageTimeLossMs > minimumReportableTimeLossMs }
            .sortedByDescending { comparison -> abs(comparison.averageTimeLossMs) }
            .take(maximumTrackedSegments)

        val theoreticalBestLapTimeMs = computeTheoreticalBestLapTime(validLaps, segmentation)
        val consistencyInsight = buildConsistencyInsight(validLaps, segmentation)
        val generalImprovementInsight = buildGeneralImprovementInsight(theoreticalBestLapTimeMs, referenceLap.lapTimeMs)

        val insights = buildList {
            topSegments.forEach { comparison ->
                add(buildSegmentInsight(comparison, resolveCornerReference(comparison, trackLayout, detectedCorners)))
            }
            consistencyInsight?.let(::add)
            generalImprovementInsight?.let(::add)
        }.take(maximumInsightCount)

        return SessionTelemetryAnalysis(
            insights = insights,
            theoreticalBestLapTimeMs = theoreticalBestLapTimeMs,
            topTimeLossSegments = topSegments.map { comparison ->
                val reference = resolveCornerReference(comparison, trackLayout, detectedCorners)
                TimeLossSegment(
                    segmentIndex = reference?.displayIndex ?: comparison.segment.displayIndex,
                    segmentLabel = reference?.insightLabel ?: "Sector ${comparison.segment.displayIndex}",
                    relativePosition = reference?.relativePosition.orEmpty(),
                    timeLoss = comparison.averageTimeLossMs / 1000f,
                    cause = comparison.cause.title
                )
            },
            segmentMarkers = buildSegmentMarkers(topSegments, trackLayout, detectedCorners)
        )
    }

    fun generateSessionInsights(
        session: Session,
        trackProfile: TrackProfile? = null,
        trackLayout: TrackLayout? = null
    ): List<String> {
        return analyzeSession(session, trackProfile, trackLayout).insights
    }

    private fun selectReferenceLap(laps: List<Lap>): Lap? {
        val preferredLap = laps
            .filter(::isPrimaryValidLap)
            .minByOrNull { lap -> lap.lapTimeMs }
        return preferredLap ?: laps.minByOrNull { lap -> lap.lapTimeMs }
    }

    private fun isPrimaryValidLap(lap: Lap): Boolean {
        return lap.phase == LapPhase.NORMAL &&
            !lap.isDisturbed &&
            lap.confidenceScore >= minimumReferenceConfidence
    }

    private fun resolveSegmentation(referenceLap: Lap): Segmentation {
        val sectorBoundaries = referenceLap.sectorBoundaries
            .filter { boundary -> boundary in 1..99 }
            .sorted()

        return if (sectorBoundaries.isNotEmpty()) {
            val segments = mutableListOf<SegmentDefinition>()
            var startIndex = 0
            sectorBoundaries.forEachIndexed { index, boundary ->
                val endIndex = normalizedIndex(boundary.toFloat())
                segments += SegmentDefinition(
                    index = index,
                    displayIndex = index + 1,
                    startIndex = startIndex,
                    endIndex = endIndex.coerceAtLeast(startIndex + 1)
                )
                startIndex = endIndex.coerceAtLeast(startIndex + 1)
            }
            segments += SegmentDefinition(
                index = segments.size,
                displayIndex = segments.size + 1,
                startIndex = startIndex,
                endIndex = LapNormalizer.DEFAULT_POINT_COUNT - 1
            )
            Segmentation(segments = segments, usesSectorTimes = referenceLap.sectorTimesMs.size == segments.size)
        } else {
            val fallbackSegments = listOf(0, 25, 50, 75, 100)
            val segments = fallbackSegments.zipWithNext().mapIndexed { index, (startPercent, endPercent) ->
                SegmentDefinition(
                    index = index,
                    displayIndex = index + 1,
                    startIndex = normalizedIndex(startPercent.toFloat()),
                    endIndex = normalizedIndex(endPercent.toFloat()).coerceAtLeast(normalizedIndex(startPercent.toFloat()) + 1)
                )
            }
            Segmentation(segments = segments, usesSectorTimes = false)
        }
    }

    private fun analyzeLap(lap: Lap, segmentation: Segmentation): AnalyzedLap {
        val totalAcceleration = LapNormalizer.normalizeSignal(lap, LapNormalizer.DEFAULT_POINT_COUNT) { sample ->
            sample.totalAcceleration
        }
        val yawRateAbs = LapNormalizer.normalizeSignal(lap, LapNormalizer.DEFAULT_POINT_COUNT) { sample ->
            sample.yawRateAbs
        }
        val velocityProxy = deriveVelocityProxy(totalAcceleration)
        val normalizedBrakingPeaks = normalizePeakIndices(lap.brakingPeakIndices, lap.samples.size)

        val segmentMetrics = segmentation.segments.associateWith { segment ->
            val totalSlice = slice(totalAcceleration, segment.startIndex, segment.endIndex)
            val yawSlice = slice(yawRateAbs, segment.startIndex, segment.endIndex)
            val velocitySlice = slice(velocityProxy, segment.startIndex, segment.endIndex)
            val entryWindow = slice(velocityProxy, (segment.startIndex - contextWindow).coerceAtLeast(0), segment.startIndex)
            val exitWindow = slice(
                velocityProxy,
                segment.endIndex,
                (segment.endIndex + contextWindow).coerceAtMost(velocityProxy.lastIndex)
            )

            val brakingPointPercent = detectBrakingPointPercent(
                totalAcceleration = totalAcceleration,
                normalizedBrakingPeaks = normalizedBrakingPeaks,
                segment = segment
            )
            val segmentTimeMs = computeSegmentTimeMs(lap, segmentation, segment)

            SegmentMetrics(
                segmentTimeMs = segmentTimeMs,
                entrySpeed = averageOrZero(entryWindow),
                minSpeed = velocitySlice.minOrNull() ?: 0f,
                exitSpeed = averageOrZero(exitWindow),
                brakingPointPercent = brakingPointPercent,
                brakingIntensity = totalSlice.minOrNull() ?: 0f,
                yawMean = averageOrZero(yawSlice),
                yawStdDev = standardDeviation(yawSlice),
                yawPeak = yawSlice.maxOrNull() ?: 0f
            )
        }

        return AnalyzedLap(
            lap = lap,
            totalAcceleration = totalAcceleration,
            yawRateAbs = yawRateAbs,
            velocityProxy = velocityProxy,
            segmentMetrics = segmentMetrics
        )
    }

    private fun deriveVelocityProxy(totalAcceleration: List<Float>): List<Float> {
        if (totalAcceleration.isEmpty()) {
            return emptyList()
        }
        val velocity = MutableList(totalAcceleration.size) { minimumVelocityProxy }
        velocity[0] = baseVelocityProxy
        for (index in 1 until totalAcceleration.size) {
            val positiveContribution = maxOf(0f, totalAcceleration[index] - totalAcceleration[index - 1]) * positiveAccelerationGain
            val brakingPenalty = maxOf(0f, totalAcceleration[index - 1] - totalAcceleration[index]) * brakingPenaltyGain
            velocity[index] = (velocity[index - 1] + positiveContribution - brakingPenalty)
                .coerceIn(minimumVelocityProxy, maximumVelocityProxy)
        }
        return velocity
    }

    private fun normalizePeakIndices(peakIndices: List<Int>, sampleCount: Int): List<Int> {
        if (sampleCount <= 1) {
            return emptyList()
        }
        return peakIndices.map { peakIndex ->
            ((peakIndex.toFloat() / (sampleCount - 1).toFloat()) * (LapNormalizer.DEFAULT_POINT_COUNT - 1))
                .roundToInt()
                .coerceIn(0, LapNormalizer.DEFAULT_POINT_COUNT - 1)
        }
    }

    private fun computeSegmentTimeMs(lap: Lap, segmentation: Segmentation, segment: SegmentDefinition): Long {
        return if (segmentation.usesSectorTimes && lap.sectorTimesMs.size == segmentation.segments.size) {
            lap.sectorTimesMs[segment.index]
        } else {
            val fraction = (segment.endIndex - segment.startIndex).toFloat() / (LapNormalizer.DEFAULT_POINT_COUNT - 1).toFloat()
            (lap.lapTimeMs * fraction).toLong().coerceAtLeast(1L)
        }
    }

    private fun detectBrakingPointPercent(
        totalAcceleration: List<Float>,
        normalizedBrakingPeaks: List<Int>,
        segment: SegmentDefinition
    ): Float {
        for (index in (segment.startIndex + 1)..segment.endIndex) {
            val drop = totalAcceleration[index] - totalAcceleration[index - 1]
            if (drop <= brakingDropThreshold) {
                return indexToPercent(index)
            }
        }

        val brakingPeak = normalizedBrakingPeaks.firstOrNull { peakIndex ->
            peakIndex in segment.startIndex..segment.endIndex
        }
        return brakingPeak?.let(::indexToPercent) ?: indexToPercent(segment.startIndex)
    }

    private fun aggregateSegmentComparison(
        segment: SegmentDefinition,
        referenceAnalysis: AnalyzedLap,
        comparableAnalyses: List<AnalyzedLap>
    ): SegmentComparison {
        val referenceMetrics = referenceAnalysis.segmentMetrics.getValue(segment)
        val deltas = comparableAnalyses.map { analyzedLap ->
            val metrics = analyzedLap.segmentMetrics.getValue(segment)
            SegmentDelta(
                entrySpeed = metrics.entrySpeed - referenceMetrics.entrySpeed,
                minSpeed = metrics.minSpeed - referenceMetrics.minSpeed,
                exitSpeed = metrics.exitSpeed - referenceMetrics.exitSpeed,
                brakingPointPercent = metrics.brakingPointPercent - referenceMetrics.brakingPointPercent,
                brakingIntensity = metrics.brakingIntensity - referenceMetrics.brakingIntensity,
                yawStdDev = metrics.yawStdDev - referenceMetrics.yawStdDev,
                segmentTimeMs = metrics.segmentTimeMs - referenceMetrics.segmentTimeMs
            )
        }

        val averageDelta = SegmentDelta(
            entrySpeed = deltas.map { delta -> delta.entrySpeed }.average().toFloat(),
            minSpeed = deltas.map { delta -> delta.minSpeed }.average().toFloat(),
            exitSpeed = deltas.map { delta -> delta.exitSpeed }.average().toFloat(),
            brakingPointPercent = deltas.map { delta -> delta.brakingPointPercent }.average().toFloat(),
            brakingIntensity = deltas.map { delta -> delta.brakingIntensity }.average().toFloat(),
            yawStdDev = deltas.map { delta -> delta.yawStdDev }.average().toFloat(),
            segmentTimeMs = deltas.map { delta -> delta.segmentTimeMs.toDouble() }.average().toLong()
        )

        return SegmentComparison(
            segment = segment,
            averageTimeLossMs = averageDelta.segmentTimeMs.coerceAtLeast(0L),
            averageDelta = averageDelta,
            cause = classifyCause(averageDelta)
        )
    }

    private fun classifyCause(averageDelta: SegmentDelta): TimeLossCause {
        return when {
            averageDelta.brakingPointPercent <= -minimumEarlyBrakingPercent &&
                averageDelta.entrySpeed <= -minimumEntrySpeedDelta -> TimeLossCause.BRAKING_TOO_EARLY

            averageDelta.minSpeed <= -minimumMidCornerSpeedDelta -> TimeLossCause.OVER_SLOWING

            averageDelta.exitSpeed <= -minimumExitSpeedDelta -> TimeLossCause.POOR_EXIT_SPEED

            averageDelta.yawStdDev >= minimumYawStdDevDelta -> TimeLossCause.UNSTABLE_CORNERING

            averageDelta.brakingIntensity >= minimumBrakingIntensityDelta -> TimeLossCause.INSUFFICIENT_BRAKING_FORCE

            else -> TimeLossCause.GENERAL_TIME_LOSS
        }
    }

    private fun buildSegmentInsight(
        comparison: SegmentComparison,
        cornerReference: TrackCornerReference?
    ): String {
        val timeLoss = comparison.averageTimeLossMs / 1000f
        val detail = when (comparison.cause) {
            TimeLossCause.BRAKING_TOO_EARLY -> "braking too early"
            TimeLossCause.OVER_SLOWING -> "mid-corner speed too low"
            TimeLossCause.POOR_EXIT_SPEED -> {
                if (cornerReference?.relativePosition == "before main straight") {
                    "poor exit onto main straight"
                } else {
                    "poor exit speed"
                }
            }

            TimeLossCause.UNSTABLE_CORNERING -> "unstable cornering"
            TimeLossCause.INSUFFICIENT_BRAKING_FORCE -> "insufficient braking force"
            TimeLossCause.GENERAL_TIME_LOSS -> "general time loss"
        }
        val locationLabel = buildLocationLabel(cornerReference, comparison)
        return "$locationLabel: $detail (+${"%.2f".format(timeLoss)}s). Recommendation: ${comparison.cause.recommendation}"
    }

    private fun buildConsistencyInsight(validLaps: List<Lap>, segmentation: Segmentation): String? {
        val analyzedLaps = validLaps.map { lap -> analyzeLap(lap, segmentation) }
        val averageBrakingPointByLap = analyzedLaps.map { analyzedLap ->
            analyzedLap.segmentMetrics.values.map { metrics -> metrics.brakingPointPercent }.average().toFloat()
        }
        val averageMinSpeedByLap = analyzedLaps.map { analyzedLap ->
            analyzedLap.segmentMetrics.values.map { metrics -> metrics.minSpeed }.average().toFloat()
        }
        val lapTimeStdDev = standardDeviation(validLaps.map { lap -> lap.lapTimeMs.toFloat() })
        val brakingPointStdDev = standardDeviation(averageBrakingPointByLap)
        val minSpeedStdDev = standardDeviation(averageMinSpeedByLap)

        return if (
            lapTimeStdDev >= highLapTimeVarianceMs &&
            (brakingPointStdDev >= highBrakingPointVariancePercent || minSpeedStdDev >= highMinSpeedVariance)
        ) {
            "Inconsistent braking points are causing unstable lap times."
        } else {
            null
        }
    }

    private fun buildGeneralImprovementInsight(theoreticalBestLapTimeMs: Long?, currentBestLapTimeMs: Long): String? {
        val theoretical = theoreticalBestLapTimeMs ?: return null
        val gainMs = currentBestLapTimeMs - theoretical
        if (gainMs < minimumGeneralImprovementGainMs) {
            return null
        }
        return "Theoretical best lap is ${"%.2f".format(gainMs / 1000f)}s quicker if you combine the strongest segments."
    }

    private fun computeTheoreticalBestLapTime(validLaps: List<Lap>, segmentation: Segmentation): Long? {
        if (validLaps.size < minimumComparableLaps) {
            return null
        }
        val analyzedLaps = validLaps.map { lap -> analyzeLap(lap, segmentation) }
        return segmentation.segments.sumOf { segment ->
            analyzedLaps.minOf { analyzedLap ->
                analyzedLap.segmentMetrics.getValue(segment).segmentTimeMs
            }
        }.takeIf { total -> total > 0L }
    }

    private fun buildSegmentMarkers(
        topSegments: List<SegmentComparison>,
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>
    ): List<SegmentMarker> {
        if (topSegments.isEmpty()) {
            return emptyList()
        }
        val maxLoss = topSegments.maxOf { comparison -> comparison.averageTimeLossMs }.coerceAtLeast(1L)
        return topSegments.map { comparison ->
            val reference = resolveCornerReference(comparison, trackLayout, detectedCorners)
            SegmentMarker(
                positionPercent = comparison.segment.positionPercent,
                severity = (comparison.averageTimeLossMs.toFloat() / maxLoss.toFloat()).coerceIn(0.2f, 1f),
                label = reference?.markerLabel ?: comparison.cause.shortLabel
            )
        }
    }

    private fun resolveCornerReference(
        comparison: SegmentComparison,
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>
    ): TrackCornerReference? {
        if (detectedCorners.isEmpty()) {
            return null
        }

        return TrackLayoutMapper.findClosestCornerReference(
            detectedCorners = detectedCorners,
            trackLayout = trackLayout,
            segmentPercent = comparison.segment.positionPercent
        )
    }

    private fun buildLocationLabel(
        cornerReference: TrackCornerReference?,
        comparison: SegmentComparison
    ): String {
        if (cornerReference == null) {
            return "Sector ${comparison.segment.displayIndex}"
        }

        return if (cornerReference.relativePosition.isBlank()) {
            cornerReference.insightLabel
        } else {
            "${cornerReference.insightLabel} (${cornerReference.relativePosition})"
        }
    }

    private fun slice(values: List<Float>, startInclusive: Int, endInclusive: Int): List<Float> {
        if (values.isEmpty()) {
            return emptyList()
        }
        val start = startInclusive.coerceIn(0, values.lastIndex)
        val end = endInclusive.coerceIn(start, values.lastIndex)
        return values.subList(start, end + 1)
    }

    private fun averageOrZero(values: List<Float>): Float {
        return if (values.isEmpty()) 0f else values.average().toFloat()
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val mean = values.average().toFloat()
        val variance = values
            .map { value -> (value - mean) * (value - mean) }
            .average()
        return sqrt(variance).toFloat()
    }

    private fun normalizedIndex(percent: Float): Int {
        return ((percent / 100f) * (LapNormalizer.DEFAULT_POINT_COUNT - 1))
            .roundToInt()
            .coerceIn(0, LapNormalizer.DEFAULT_POINT_COUNT - 1)
    }

    private fun indexToPercent(index: Int): Float {
        return (index.toFloat() / (LapNormalizer.DEFAULT_POINT_COUNT - 1).toFloat()) * 100f
    }

    private data class Segmentation(
        val segments: List<SegmentDefinition>,
        val usesSectorTimes: Boolean
    )

    private data class SegmentDefinition(
        val index: Int,
        val displayIndex: Int,
        val startIndex: Int,
        val endIndex: Int
    ) {
        val positionPercent: Float
            get() = ((startIndex + endIndex) / 2f / (LapNormalizer.DEFAULT_POINT_COUNT - 1).toFloat()) * 100f
    }

    private data class AnalyzedLap(
        val lap: Lap,
        val totalAcceleration: List<Float>,
        val yawRateAbs: List<Float>,
        val velocityProxy: List<Float>,
        val segmentMetrics: Map<SegmentDefinition, SegmentMetrics>
    )

    private data class SegmentMetrics(
        val segmentTimeMs: Long,
        val entrySpeed: Float,
        val minSpeed: Float,
        val exitSpeed: Float,
        val brakingPointPercent: Float,
        val brakingIntensity: Float,
        val yawMean: Float,
        val yawStdDev: Float,
        val yawPeak: Float
    )

    private data class SegmentDelta(
        val entrySpeed: Float,
        val minSpeed: Float,
        val exitSpeed: Float,
        val brakingPointPercent: Float,
        val brakingIntensity: Float,
        val yawStdDev: Float,
        val segmentTimeMs: Long
    )

    private data class SegmentComparison(
        val segment: SegmentDefinition,
        val averageTimeLossMs: Long,
        val averageDelta: SegmentDelta,
        val cause: TimeLossCause
    )

    private enum class TimeLossCause(val title: String, val shortLabel: String, val recommendation: String) {
        BRAKING_TOO_EARLY(
            title = "Braking too early",
            shortLabel = "Brake early",
            recommendation = "Brake later and commit to the release closer to turn-in."
        ),
        OVER_SLOWING(
            title = "Over-slowing in corner",
            shortLabel = "Over-slowing",
            recommendation = "Carry a little more minimum speed and trust the kart mid-corner."
        ),
        POOR_EXIT_SPEED(
            title = "Poor exit speed",
            shortLabel = "Poor exit",
            recommendation = "Get back to throttle earlier and straighten the exit sooner."
        ),
        UNSTABLE_CORNERING(
            title = "Unstable cornering",
            shortLabel = "Unstable",
            recommendation = "Aim for a cleaner steering trace and reduce corrections through the apex."
        ),
        INSUFFICIENT_BRAKING_FORCE(
            title = "Insufficient braking force",
            shortLabel = "Brake weak",
            recommendation = "Use firmer initial brake pressure to finish deceleration before rotation."
        ),
        GENERAL_TIME_LOSS(
            title = "General time loss",
            shortLabel = "Time loss",
            recommendation = "Focus on cleaner line discipline and smoother phase transitions."
        )
    }

    companion object {
        private const val minimumReferenceConfidence = 0.75f
        private const val minimumComparableLaps = 2
        private const val contextWindow = 8
        private const val baseVelocityProxy = 12f
        private const val minimumVelocityProxy = 1f
        private const val maximumVelocityProxy = 32f
        private const val positiveAccelerationGain = 2.4f
        private const val brakingPenaltyGain = 2.0f
        private const val brakingDropThreshold = -0.35f
        private const val minimumReportableTimeLossMs = 60L
        private const val minimumGeneralImprovementGainMs = 120L
        private const val maximumTrackedSegments = 3
        private const val maximumInsightCount = 5
        private const val minimumEarlyBrakingPercent = 1.5f
        private const val minimumEntrySpeedDelta = 0.8f
        private const val minimumMidCornerSpeedDelta = 0.8f
        private const val minimumExitSpeedDelta = 0.8f
        private const val minimumYawStdDevDelta = 0.18f
        private const val minimumBrakingIntensityDelta = 0.18f
        private const val highLapTimeVarianceMs = 220f
        private const val highBrakingPointVariancePercent = 2.5f
        private const val highMinSpeedVariance = 0.9f
    }
}
