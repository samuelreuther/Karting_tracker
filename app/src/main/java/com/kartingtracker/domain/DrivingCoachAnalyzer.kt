package com.kartingtracker.domain

import com.kartingtracker.data.CoachingInsight
import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.SegmentMarker
import com.kartingtracker.data.Session
import com.kartingtracker.data.TimeLossSegment
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.data.TrackProfile
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SessionTelemetryAnalysis(
    val insights: List<String> = emptyList(),
    val coachingInsights: List<CoachingInsight> = emptyList(),
    val theoreticalBestLapTimeMs: Long? = null,
    val topTimeLossSegments: List<TimeLossSegment> = emptyList(),
    val segmentMarkers: List<SegmentMarker> = emptyList()
)

data class DrivingSegmentFeatures(
    val entrySpeed: Float,
    val brakeIntensity: Float,
    val midStability: Float,
    val exitAcceleration: Float,
    val yawPeak: Float
)

data class SegmentDelta(
    val deltaTimeMs: Float,
    val deltaEntry: Float,
    val deltaBrake: Float,
    val deltaExit: Float,
    val deltaYaw: Float,
    val deltaStability: Float
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

        val aggregateInsights = segmentation.segments.mapNotNull { segment ->
            aggregateCoachingInsight(
                segment = segment,
                referenceAnalysis = referenceAnalysis,
                comparableAnalyses = comparableAnalyses,
                trackLayout = trackLayout,
                detectedCorners = detectedCorners
            )
        }
            .sortedByDescending { insight -> insight.timeLossMs }
            .take(maximumInsightCount)

        val theoreticalBestLapTimeMs = computeTheoreticalBestLapTime(validLaps, segmentation)
        val consistencyInsight = buildConsistencyInsight(validLaps, segmentation)
        val generalImprovementInsight = buildGeneralImprovementInsight(theoreticalBestLapTimeMs, referenceLap.lapTimeMs)

        val insights = buildList {
            aggregateInsights.forEach { insight ->
                add(formatCoachingInsight(insight))
            }
            consistencyInsight?.let(::add)
            generalImprovementInsight?.let(::add)
        }.take(maximumInsightCount)

        return SessionTelemetryAnalysis(
            insights = insights,
            coachingInsights = aggregateInsights,
            theoreticalBestLapTimeMs = theoreticalBestLapTimeMs,
            topTimeLossSegments = aggregateInsights.map { insight ->
                val defaultLabel = "Segment ${insight.segmentIndex}"
                TimeLossSegment(
                    segmentIndex = insight.segmentIndex,
                    segmentLabel = insight.cornerName ?: defaultLabel,
                    relativePosition = "",
                    timeLoss = insight.timeLossMs / 1000f,
                    cause = insight.cause
                )
            },
            segmentMarkers = buildSegmentMarkers(aggregateInsights, segmentation)
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

        val segmentMetrics = segmentation.segments.associateWith { segment ->
            val segmentAcceleration = slice(totalAcceleration, segment.startIndex, segment.endIndex)
            val segmentYaw = slice(yawRateAbs, segment.startIndex, segment.endIndex)
            val features = extractSegmentFeatures(segmentAcceleration, segmentYaw)
            val segmentTimeMs = computeSegmentTimeMs(lap, segmentation, segment)

            SegmentMetrics(
                segmentTimeMs = segmentTimeMs,
                features = features
            )
        }

        return AnalyzedLap(
            lap = lap,
            segmentMetrics = segmentMetrics
        )
    }

    private fun extractSegmentFeatures(
        segmentAcceleration: List<Float>,
        segmentYaw: List<Float>
    ): DrivingSegmentFeatures {
        if (segmentAcceleration.isEmpty() || segmentYaw.isEmpty()) {
            return DrivingSegmentFeatures(0f, 0f, 0f, 0f, 0f)
        }

        val entry = subSliceByPercent(segmentAcceleration, 0f, 10f)
        val midYaw = subSliceByPercent(segmentYaw, 40f, 60f)
        val exit = subSliceByPercent(segmentAcceleration, 80f, 100f)

        return DrivingSegmentFeatures(
            entrySpeed = averageOrZero(entry),
            brakeIntensity = segmentAcceleration.minOrNull() ?: 0f,
            midStability = variance(midYaw),
            exitAcceleration = averageOrZero(exit),
            yawPeak = segmentYaw.maxOrNull() ?: 0f
        )
    }

    private fun aggregateCoachingInsight(
        segment: SegmentDefinition,
        referenceAnalysis: AnalyzedLap,
        comparableAnalyses: List<AnalyzedLap>,
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>
    ): CoachingInsight? {
        val referenceMetrics = referenceAnalysis.segmentMetrics.getValue(segment)
        val deltas = comparableAnalyses.map { analyzedLap ->
            val metrics = analyzedLap.segmentMetrics.getValue(segment)
            SegmentDelta(
                deltaTimeMs = (metrics.segmentTimeMs - referenceMetrics.segmentTimeMs).toFloat(),
                deltaEntry = metrics.features.entrySpeed - referenceMetrics.features.entrySpeed,
                deltaBrake = metrics.features.brakeIntensity - referenceMetrics.features.brakeIntensity,
                deltaExit = metrics.features.exitAcceleration - referenceMetrics.features.exitAcceleration,
                deltaYaw = metrics.features.yawPeak - referenceMetrics.features.yawPeak,
                deltaStability = metrics.features.midStability - referenceMetrics.features.midStability
            )
        }

        val averageDelta = SegmentDelta(
            deltaTimeMs = deltas.map { it.deltaTimeMs }.average().toFloat(),
            deltaEntry = deltas.map { it.deltaEntry }.average().toFloat(),
            deltaBrake = deltas.map { it.deltaBrake }.average().toFloat(),
            deltaExit = deltas.map { it.deltaExit }.average().toFloat(),
            deltaYaw = deltas.map { it.deltaYaw }.average().toFloat(),
            deltaStability = deltas.map { it.deltaStability }.average().toFloat()
        )

        val timeLossMs = averageDelta.deltaTimeMs.coerceAtLeast(0f)
        if (timeLossMs < minimumReportableTimeLossMs) {
            return null
        }

        val classification = classifyCause(averageDelta)
        val cornerReference = resolveCornerReference(segment, trackLayout, detectedCorners)

        return CoachingInsight(
            segmentIndex = cornerReference?.displayIndex ?: segment.displayIndex,
            cornerName = cornerReference?.insightLabel,
            timeLossMs = timeLossMs,
            cause = classification.cause,
            suggestion = classification.suggestion,
            severity = (timeLossMs / severityScaleMs).coerceIn(0f, 1f)
        )
    }

    private fun classifyCause(delta: SegmentDelta): CauseClassification {
        return when {
            delta.deltaBrake < -thresholdMedium &&
                delta.deltaExit < -thresholdSmall -> CauseClassification(
                cause = "Overbraking",
                suggestion = "Brake slightly earlier and smoother to improve exit speed"
            )

            delta.deltaBrake < -thresholdLarge &&
                delta.deltaYaw > thresholdMedium &&
                delta.deltaStability > thresholdSmall -> CauseClassification(
                cause = "Too late braking",
                suggestion = "Brake earlier in a straight line to stabilize the kart"
            )

            delta.deltaEntry < -thresholdMedium &&
                delta.deltaBrake >= -thresholdSmall -> CauseClassification(
                cause = "Slow corner entry",
                suggestion = "Carry more speed into the corner before braking"
            )

            delta.deltaExit < -thresholdMedium -> CauseClassification(
                cause = "Poor exit speed",
                suggestion = "Apply throttle earlier and unwind steering faster"
            )

            delta.deltaYaw > thresholdMedium &&
                delta.deltaStability > thresholdMedium -> CauseClassification(
                cause = "Unstable cornering",
                suggestion = "Reduce steering input and keep smoother line"
            )

            delta.deltaEntry >= -thresholdSmall &&
                delta.deltaExit < -thresholdLarge &&
                delta.deltaYaw in -thresholdSmall..thresholdMedium -> CauseClassification(
                cause = "Early apex",
                suggestion = "Aim for a later apex to improve exit"
            )

            delta.deltaBrake > -thresholdSmall &&
                delta.deltaEntry < -thresholdSmall &&
                delta.deltaExit < -thresholdSmall -> CauseClassification(
                cause = "Coasting into corner",
                suggestion = "Brake harder and commit to acceleration earlier"
            )

            else -> CauseClassification(
                cause = "General time loss",
                suggestion = "Focus on smoother inputs and consistency"
            )
        }
    }

    private fun formatCoachingInsight(insight: CoachingInsight): String {
        val corner = insight.cornerName ?: "Segment ${insight.segmentIndex}"
        return "$corner: ${insight.cause} → ${insight.suggestion} → +${"%.2f".format(insight.timeLossMs / 1000f)}s"
    }

    private fun buildConsistencyInsight(validLaps: List<Lap>, segmentation: Segmentation): String? {
        val analyzedLaps = validLaps.map { lap -> analyzeLap(lap, segmentation) }
        val averageEntryByLap = analyzedLaps.map { analyzedLap ->
            analyzedLap.segmentMetrics.values.map { metrics -> metrics.features.entrySpeed }.average().toFloat()
        }
        val averageExitByLap = analyzedLaps.map { analyzedLap ->
            analyzedLap.segmentMetrics.values.map { metrics -> metrics.features.exitAcceleration }.average().toFloat()
        }
        val lapTimeStdDev = standardDeviation(validLaps.map { lap -> lap.lapTimeMs.toFloat() })
        val entryStdDev = standardDeviation(averageEntryByLap)
        val exitStdDev = standardDeviation(averageExitByLap)

        return if (
            lapTimeStdDev >= highLapTimeVarianceMs &&
            (entryStdDev >= highEntryVariance || exitStdDev >= highExitVariance)
        ) {
            "Inconsistent entry and exit phases are causing unstable lap times."
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
        insights: List<CoachingInsight>,
        segmentation: Segmentation
    ): List<SegmentMarker> {
        if (insights.isEmpty()) {
            return emptyList()
        }
        return insights.map { insight ->
            val segment = segmentation.segments.firstOrNull { it.displayIndex == insight.segmentIndex }
                ?: segmentation.segments.firstOrNull { it.index == insight.segmentIndex }
            SegmentMarker(
                positionPercent = segment?.positionPercent ?: 0f,
                severity = insight.severity.coerceIn(0.2f, 1f),
                label = insight.cause
            )
        }
    }

    private fun resolveCornerReference(
        segment: SegmentDefinition,
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>
    ): TrackCornerReference? {
        if (detectedCorners.isEmpty()) {
            return null
        }

        return TrackLayoutMapper.findClosestCornerReference(
            detectedCorners = detectedCorners,
            trackLayout = trackLayout,
            segmentPercent = segment.positionPercent
        )
    }

    private fun computeSegmentTimeMs(lap: Lap, segmentation: Segmentation, segment: SegmentDefinition): Long {
        return if (segmentation.usesSectorTimes && lap.sectorTimesMs.size == segmentation.segments.size) {
            lap.sectorTimesMs[segment.index]
        } else {
            val fraction = (segment.endIndex - segment.startIndex).toFloat() / (LapNormalizer.DEFAULT_POINT_COUNT - 1).toFloat()
            (lap.lapTimeMs * fraction).toLong().coerceAtLeast(1L)
        }
    }

    private fun subSliceByPercent(values: List<Float>, startPercent: Float, endPercent: Float): List<Float> {
        if (values.isEmpty()) {
            return emptyList()
        }
        val count = values.size
        val start = ((startPercent / 100f) * (count - 1)).roundToInt().coerceIn(0, count - 1)
        val end = ((endPercent / 100f) * (count - 1)).roundToInt().coerceIn(start, count - 1)
        return values.subList(start, end + 1)
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

    private fun variance(values: List<Float>): Float {
        if (values.isEmpty()) {
            return 0f
        }
        val mean = values.average().toFloat()
        return values
            .map { value -> (value - mean) * (value - mean) }
            .average()
            .toFloat()
    }

    private fun standardDeviation(values: List<Float>): Float {
        return sqrt(variance(values))
    }

    private fun normalizedIndex(percent: Float): Int {
        return ((percent / 100f) * (LapNormalizer.DEFAULT_POINT_COUNT - 1))
            .roundToInt()
            .coerceIn(0, LapNormalizer.DEFAULT_POINT_COUNT - 1)
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
        val segmentMetrics: Map<SegmentDefinition, SegmentMetrics>
    )

    private data class SegmentMetrics(
        val segmentTimeMs: Long,
        val features: DrivingSegmentFeatures
    )

    private data class CauseClassification(
        val cause: String,
        val suggestion: String
    )

    companion object {
        private const val minimumReferenceConfidence = 0.75f
        private const val minimumComparableLaps = 2
        private const val minimumReportableTimeLossMs = 20f
        private const val minimumGeneralImprovementGainMs = 120L
        private const val maximumInsightCount = 5
        private const val severityScaleMs = 300f

        private const val thresholdSmall = 0.05f
        private const val thresholdMedium = 0.15f
        private const val thresholdLarge = 0.25f

        private const val highLapTimeVarianceMs = 220f
        private const val highEntryVariance = 0.5f
        private const val highExitVariance = 0.5f
    }
}
