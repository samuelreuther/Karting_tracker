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

class DrivingCoachAnalyzer {
    private val autoCornerDetector = AutoCornerDetector()

    fun analyzeSession(
        session: Session,
        trackProfile: TrackProfile? = null,
        trackLayout: TrackLayout? = null
    ): SessionTelemetryAnalysis {
        val referenceLap = selectReferenceLap(session.laps) ?: return SessionTelemetryAnalysis()
        val validLaps = session.laps.filter(::isPrimaryValidLap)
        if (validLaps.size < 2) return SessionTelemetryAnalysis()

        val segmentation = resolveSegmentation(referenceLap)
        val detectedCorners = autoCornerDetector.detectCorners(referenceLap)
        val referenceAnalysis = analyzeLap(referenceLap, segmentation)
        val comparableAnalyses = validLaps.filterNot { it.id == referenceLap.id }.map { analyzeLap(it, segmentation) }
        if (comparableAnalyses.isEmpty()) return SessionTelemetryAnalysis()

        val comparisons = segmentation.segments.map { segment ->
            aggregateSegmentComparison(segment, referenceAnalysis, comparableAnalyses)
        }.filter { it.deltaTime > minimumReportableTimeLossMs }

        val topComparisons = comparisons.sortedByDescending { it.deltaTime }.take(maxInsights)
        val maxLoss = topComparisons.maxOfOrNull { it.deltaTime }?.coerceAtLeast(1L) ?: 1L

        val coachingInsights = topComparisons.map { comparison ->
            val reference = resolveCornerReference(comparison.segment.positionPercent, trackLayout, detectedCorners)
            val causal = classifyCausalInsight(comparison)
            CoachingInsight(
                segmentIndex = reference?.displayIndex ?: comparison.segment.displayIndex,
                cornerName = reference?.insightLabel,
                timeLossMs = comparison.deltaTime.toFloat(),
                cause = causal.first,
                suggestion = causal.second,
                severity = (comparison.deltaTime.toFloat() / maxLoss.toFloat()).coerceIn(0.2f, 1f)
            )
        }

        val textualInsights = coachingInsights.map { insight ->
            val label = insight.cornerName ?: "Sector ${insight.segmentIndex}"
            "$label: ${insight.cause} (+${"%.2f".format(insight.timeLossMs / 1000f)}s). ${insight.suggestion}"
        }

        val topTimeLossSegments = coachingInsights.map { insight ->
            TimeLossSegment(
                segmentIndex = insight.segmentIndex,
                segmentLabel = insight.cornerName ?: "Sector ${insight.segmentIndex}",
                relativePosition = "",
                timeLoss = insight.timeLossMs / 1000f,
                cause = insight.cause
            )
        }

        return SessionTelemetryAnalysis(
            insights = textualInsights,
            coachingInsights = coachingInsights,
            theoreticalBestLapTimeMs = computeTheoreticalBestLapTime(validLaps, segmentation),
            topTimeLossSegments = topTimeLossSegments,
            segmentMarkers = topComparisons.map { comparison ->
                SegmentMarker(
                    positionPercent = comparison.segment.positionPercent,
                    severity = (comparison.deltaTime.toFloat() / maxLoss.toFloat()).coerceIn(0.2f, 1f),
                    label = "S${comparison.segment.displayIndex}"
                )
            }
        )
    }

    private fun aggregateSegmentComparison(
        segment: SegmentDefinition,
        reference: AnalyzedLap,
        others: List<AnalyzedLap>
    ): SegmentComparison {
        val ref = reference.segmentMetrics.getValue(segment)
        val deltas = others.map { lap ->
            val metrics = lap.segmentMetrics.getValue(segment)
            SegmentDelta(
                deltaTime = metrics.segmentTimeMs - ref.segmentTimeMs,
                deltaEntry = metrics.entrySpeedProxy - ref.entrySpeedProxy,
                deltaExit = metrics.exitAcceleration - ref.exitAcceleration,
                deltaBrake = metrics.brakeIntensity - ref.brakeIntensity,
                deltaYaw = metrics.yawPeak - ref.yawPeak,
                deltaStability = metrics.midCornerStability - ref.midCornerStability
            )
        }
        return SegmentComparison(
            segment = segment,
            deltaTime = deltas.map { it.deltaTime.toDouble() }.average().toLong().coerceAtLeast(0L),
            deltaEntry = deltas.map { it.deltaEntry }.average().toFloat(),
            deltaExit = deltas.map { it.deltaExit }.average().toFloat(),
            deltaBrake = deltas.map { it.deltaBrake }.average().toFloat(),
            deltaYaw = deltas.map { it.deltaYaw }.average().toFloat(),
            deltaStability = deltas.map { it.deltaStability }.average().toFloat()
        )
    }

    private fun classifyCausalInsight(comparison: SegmentComparison): Pair<String, String> {
        return when {
            comparison.deltaBrake <= strongBrakeDelta && comparison.deltaExit <= weakExitDelta ->
                "Overbraking" to "Brake earlier and smoother to improve exit speed."

            comparison.deltaEntry <= lowEntryDelta && comparison.deltaBrake > mildBrakeDelta ->
                "Underdriving entry" to "Carry more speed into the corner before braking."

            comparison.deltaYaw >= highYawDelta && comparison.deltaStability >= unstableVarianceDelta ->
                "Unstable cornering" to "Reduce steering aggressiveness and keep the kart balanced."

            comparison.deltaExit <= poorExitDelta ->
                "Poor exit" to "Focus on earlier and cleaner throttle application on exit."

            else ->
                "General inefficiency" to "Focus on smoother line and phase transitions through this segment."
        }
    }

    fun generateSessionInsights(session: Session, trackProfile: TrackProfile?, trackLayout: TrackLayout?): List<String> {
        return analyzeSession(session, trackProfile, trackLayout).insights
    }

    private fun analyzeLap(lap: Lap, segmentation: Segmentation): AnalyzedLap {
        val totalAcceleration = LapNormalizer.normalizeSignal(lap, LapNormalizer.DEFAULT_POINT_COUNT) { it.totalAcceleration }
        val yawRateAbs = LapNormalizer.normalizeSignal(lap, LapNormalizer.DEFAULT_POINT_COUNT) { it.yawRateAbs }
        val segmentMetrics = segmentation.segments.associateWith { segment ->
            val segmentSlice = slice(totalAcceleration, segment.startIndex, segment.endIndex)
            val segmentYaw = slice(yawRateAbs, segment.startIndex, segment.endIndex)
            val entryEnd = (segment.startIndex + ((segment.endIndex - segment.startIndex) * 0.1f)).roundToInt()
            val midStart = (segment.startIndex + ((segment.endIndex - segment.startIndex) * 0.4f)).roundToInt()
            val midEnd = (segment.startIndex + ((segment.endIndex - segment.startIndex) * 0.6f)).roundToInt()
            val exitStart = (segment.startIndex + ((segment.endIndex - segment.startIndex) * 0.8f)).roundToInt()
            SegmentMetrics(
                segmentTimeMs = computeSegmentTimeMs(lap, segmentation, segment),
                entrySpeedProxy = averageOrZero(slice(totalAcceleration, segment.startIndex, entryEnd)),
                brakeIntensity = segmentSlice.minOrNull() ?: 0f,
                midCornerStability = variance(slice(yawRateAbs, midStart, midEnd)),
                exitAcceleration = averageOrZero(slice(totalAcceleration, exitStart, segment.endIndex)),
                yawPeak = segmentYaw.maxOrNull() ?: 0f
            )
        }
        return AnalyzedLap(segmentMetrics)
    }

    private fun resolveSegmentation(referenceLap: Lap): Segmentation {
        val sectorBoundaries = referenceLap.sectorBoundaries.filter { it in 1..99 }.sorted()
        if (sectorBoundaries.isNotEmpty()) {
            val segments = mutableListOf<SegmentDefinition>()
            var startIndex = 0
            sectorBoundaries.forEachIndexed { index, boundary ->
                val endIndex = normalizedIndex(boundary.toFloat())
                segments += SegmentDefinition(index, index + 1, startIndex, endIndex.coerceAtLeast(startIndex + 1))
                startIndex = endIndex.coerceAtLeast(startIndex + 1)
            }
            segments += SegmentDefinition(segments.size, segments.size + 1, startIndex, LapNormalizer.DEFAULT_POINT_COUNT - 1)
            return Segmentation(segments, referenceLap.sectorTimesMs.size == segments.size)
        }
        val fallback = listOf(0, 25, 50, 75, 100)
        return Segmentation(
            fallback.zipWithNext().mapIndexed { index, (start, end) ->
                SegmentDefinition(index, index + 1, normalizedIndex(start.toFloat()), normalizedIndex(end.toFloat()))
            },
            false
        )
    }

    private fun computeSegmentTimeMs(lap: Lap, segmentation: Segmentation, segment: SegmentDefinition): Long {
        return if (segmentation.usesSectorTimes && lap.sectorTimesMs.size == segmentation.segments.size) {
            lap.sectorTimesMs[segment.index]
        } else {
            val fraction = (segment.endIndex - segment.startIndex).toFloat() / (LapNormalizer.DEFAULT_POINT_COUNT - 1)
            (lap.lapTimeMs * fraction).toLong().coerceAtLeast(1L)
        }
    }

    private fun computeTheoreticalBestLapTime(validLaps: List<Lap>, segmentation: Segmentation): Long? {
        val analyzedLaps = validLaps.map { analyzeLap(it, segmentation) }
        return segmentation.segments.sumOf { segment -> analyzedLaps.minOf { it.segmentMetrics.getValue(segment).segmentTimeMs } }
    }

    private fun resolveCornerReference(
        segmentPercent: Float,
        trackLayout: TrackLayout?,
        detectedCorners: List<DetectedCorner>
    ): TrackCornerReference? {
        if (detectedCorners.isEmpty()) return null
        return TrackLayoutMapper.findClosestCornerReference(detectedCorners, trackLayout, segmentPercent)
    }

    private fun isPrimaryValidLap(lap: Lap): Boolean {
        return lap.phase == LapPhase.NORMAL && !lap.isDisturbed && lap.confidenceScore >= minimumReferenceConfidence
    }

    private fun selectReferenceLap(laps: List<Lap>): Lap? {
        val preferred = laps.filter(::isPrimaryValidLap).minByOrNull { it.lapTimeMs }
        return preferred ?: laps.minByOrNull { it.lapTimeMs }
    }

    private fun normalizedIndex(percent: Float): Int {
        return ((percent / 100f) * (LapNormalizer.DEFAULT_POINT_COUNT - 1)).roundToInt().coerceIn(0, LapNormalizer.DEFAULT_POINT_COUNT - 1)
    }

    private fun slice(values: List<Float>, startInclusive: Int, endInclusive: Int): List<Float> {
        if (values.isEmpty()) return emptyList()
        val start = startInclusive.coerceIn(0, values.lastIndex)
        val end = endInclusive.coerceIn(start, values.lastIndex)
        return values.subList(start, end + 1)
    }

    private fun averageOrZero(values: List<Float>): Float = if (values.isEmpty()) 0f else values.average().toFloat()

    private fun variance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private data class Segmentation(val segments: List<SegmentDefinition>, val usesSectorTimes: Boolean)
    private data class SegmentDefinition(val index: Int, val displayIndex: Int, val startIndex: Int, val endIndex: Int) {
        val positionPercent: Float
            get() = ((startIndex + endIndex) / 2f / (LapNormalizer.DEFAULT_POINT_COUNT - 1)) * 100f
    }
    private data class AnalyzedLap(val segmentMetrics: Map<SegmentDefinition, SegmentMetrics>)
    private data class SegmentMetrics(
        val segmentTimeMs: Long,
        val entrySpeedProxy: Float,
        val brakeIntensity: Float,
        val midCornerStability: Float,
        val exitAcceleration: Float,
        val yawPeak: Float
    )
    private data class SegmentDelta(
        val deltaTime: Long,
        val deltaEntry: Float,
        val deltaExit: Float,
        val deltaBrake: Float,
        val deltaYaw: Float,
        val deltaStability: Float
    )
    private data class SegmentComparison(
        val segment: SegmentDefinition,
        val deltaTime: Long,
        val deltaEntry: Float,
        val deltaExit: Float,
        val deltaBrake: Float,
        val deltaYaw: Float,
        val deltaStability: Float
    )

    companion object {
        private const val minimumReferenceConfidence = 0.75f
        private const val minimumReportableTimeLossMs = 60L
        private const val maxInsights = 5
        private const val strongBrakeDelta = -0.30f
        private const val weakExitDelta = -0.15f
        private const val lowEntryDelta = -0.15f
        private const val mildBrakeDelta = -0.1f
        private const val highYawDelta = 0.2f
        private const val unstableVarianceDelta = 0.04f
        private const val poorExitDelta = -0.2f
    }
}
