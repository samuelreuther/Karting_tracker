package com.kartingtracker.domain.corner

import com.kartingtracker.data.CornerCoachingInsight
import com.kartingtracker.data.CornerCoachingSummary
import com.kartingtracker.data.CornerInsightCategory
import com.kartingtracker.data.Lap
import com.kartingtracker.data.LapPhase
import com.kartingtracker.data.Session
import com.kartingtracker.data.TrackLayout
import com.kartingtracker.domain.AutoCornerDetector
import com.kartingtracker.domain.DetectedCorner
import com.kartingtracker.domain.LapNormalizer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CornerCoachingAnalysis(
    val insights: List<CornerCoachingInsight> = emptyList(),
    val summary: CornerCoachingSummary? = null
)

class CornerCoachingAnalyzer {
    private val autoCornerDetector = AutoCornerDetector()

    fun analyze(
        session: Session,
        trackLayout: TrackLayout?
    ): CornerCoachingAnalysis {
        val usableLaps = session.laps.filter { lap ->
            lap.phase == LapPhase.NORMAL && !lap.isDisturbed && lap.confidenceScore >= minimumLapConfidence
        }
        if (usableLaps.size < minimumUsableLaps) {
            return CornerCoachingAnalysis()
        }

        val referenceLap = usableLaps.minByOrNull { lap -> lap.lapTimeMs } ?: return CornerCoachingAnalysis()
        val detectedCorners = autoCornerDetector.detectCorners(referenceLap)
        val windows = buildCornerWindows(detectedCorners, referenceLap)
        if (windows.isEmpty()) {
            return CornerCoachingAnalysis()
        }

        val references = buildCornerReferences(usableLaps, windows, trackLayout)
        val lapPerformances = usableLaps.associateBy(
            keySelector = { lap -> lap.id },
            valueTransform = { lap -> extractMetrics(lap, windows, trackLayout) }
        )

        val generated = windows.mapNotNull { window ->
            val perLap = lapPerformances.values.mapNotNull { lapMap -> lapMap[window.cornerIndex] }
            val ref = references[window.cornerIndex] ?: return@mapNotNull null
            generateInsight(window, perLap, ref, trackLayout)
        }

        if (generated.isEmpty()) {
            return CornerCoachingAnalysis()
        }

        val rankedActions = generated
            .filter { it.category == CornerInsightCategory.ACTION }
            .sortedByDescending { (it.estimatedGainMs ?: 0f) * it.confidence }
            .take(3)

        val strongest = generated
            .filter { it.category == CornerInsightCategory.POSITIVE || it.category == CornerInsightCategory.CONSISTENCY }
            .maxByOrNull { it.confidence }

        val inconsistent = generated
            .filter { it.ruleId == RULE_INCONSISTENT }
            .maxByOrNull { it.confidence }

        val biggestOpportunity = rankedActions.maxByOrNull { it.estimatedGainMs ?: 0f }
        val overall = generated.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)

        return CornerCoachingAnalysis(
            insights = generated.sortedWith(compareByDescending<CornerCoachingInsight> { it.category == CornerInsightCategory.ACTION }
                .thenByDescending { it.estimatedGainMs ?: 0f }
                .thenByDescending { it.confidence }),
            summary = CornerCoachingSummary(
                topActions = rankedActions,
                strongestCorner = strongest,
                mostInconsistentCorner = inconsistent,
                biggestOpportunityCorner = biggestOpportunity,
                overallConfidence = overall
            )
        )
    }

    private fun buildCornerWindows(detectedCorners: List<DetectedCorner>, referenceLap: Lap): List<CornerWindow> {
        val usableCorners = if (detectedCorners.isNotEmpty()) {
            detectedCorners.sortedBy { it.peakPercent }
        } else {
            fallbackCornersFromLap(referenceLap)
        }

        return usableCorners.mapIndexed { index, corner ->
            CornerWindow(
                cornerIndex = index + 1,
                startPercent = corner.startPercent.coerceIn(0f, 100f),
                apexPercent = corner.peakPercent.coerceIn(0f, 100f),
                endPercent = corner.endPercent.coerceIn(0f, 100f),
                mappingConfidence = if (detectedCorners.isNotEmpty()) 0.8f else 0.55f
            )
        }
    }

    private fun fallbackCornersFromLap(lap: Lap): List<DetectedCorner> {
        val peakPercents = lap.corneringPeakIndices
            .mapNotNull { index ->
                if (lap.samples.isEmpty()) null else (index.toFloat() / lap.samples.lastIndex.coerceAtLeast(1)) * 100f
            }
            .sorted()
        if (peakPercents.isEmpty()) {
            val sectors = lap.sectorBoundaries.filter { it in 1..99 }.sorted()
            val anchors = listOf(0f) + sectors.map { it.toFloat() } + listOf(100f)
            return anchors.zipWithNext().mapIndexed { index, (start, end) ->
                val apex = (start + end) / 2f
                DetectedCorner(start, end, apex, 0.4f + index * 0.01f)
            }
        }

        return peakPercents.mapIndexed { index, peak ->
            val previous = peakPercents.getOrNull(index - 1) ?: (peak - 10f)
            val next = peakPercents.getOrNull(index + 1) ?: (peak + 10f)
            DetectedCorner(
                startPercent = ((previous + peak) / 2f).coerceIn(0f, 100f),
                endPercent = ((next + peak) / 2f).coerceIn(0f, 100f),
                peakPercent = peak,
                strength = 0.5f
            )
        }
    }

    private fun buildCornerReferences(
        laps: List<Lap>,
        windows: List<CornerWindow>,
        trackLayout: TrackLayout?
    ): Map<Int, CornerMetrics> {
        val perLap = laps.map { lap -> extractMetrics(lap, windows, trackLayout) }
        return windows.associate { window ->
            val values = perLap.mapNotNull { metrics -> metrics[window.cornerIndex] }
            val sortedByTime = values.sortedBy { it.localTimeMs }
            val trimmed = if (sortedByTime.size >= 4) sortedByTime.drop(1).dropLast(1) else sortedByTime
            window.cornerIndex to CornerMetrics(
                cornerIndex = window.cornerIndex,
                cornerLabel = cornerLabel(window.cornerIndex, trackLayout),
                brakeStartPercent = median(trimmed.map { it.brakeStartPercent }),
                brakeDurationPercent = median(trimmed.map { it.brakeDurationPercent }),
                exitAccelMean = median(trimmed.map { it.exitAccelMean }),
                rotationPeak = median(trimmed.map { it.rotationPeak }),
                localTimeMs = median(trimmed.map { it.localTimeMs }),
                mappingConfidence = median(trimmed.map { it.mappingConfidence }),
                signalQuality = median(trimmed.map { it.signalQuality }),
                lapConfidence = median(trimmed.map { it.lapConfidence })
            )
        }
    }

    private fun extractMetrics(
        lap: Lap,
        windows: List<CornerWindow>,
        trackLayout: TrackLayout?
    ): Map<Int, CornerMetrics> {
        if (lap.samples.size < 3) return emptyMap()
        val longitudinal = LapNormalizer.normalizeSignal(lap) { sample -> sample.longitudinalAccel }
        val lateralAbs = LapNormalizer.normalizeSignal(lap) { sample -> abs(sample.lateralAccel) }
        val yaw = LapNormalizer.normalizeSignal(lap) { sample -> sample.yawRateAbs }

        return windows.associate { window ->
            val start = indexForPercent(window.startPercent)
            val apex = indexForPercent(window.apexPercent)
            val end = indexForPercent(window.endPercent).coerceAtLeast(start + 2)
            val safeEnd = end.coerceAtMost(longitudinal.lastIndex)
            val safeApex = apex.coerceIn(start, safeEnd)
            val entryRange = start..safeApex
            val midStart = (start + safeApex) / 2
            val midEnd = (safeApex + safeEnd) / 2
            val exitRange = safeApex..safeEnd

            val brakeThreshold = -0.45f
            val brakeStart = entryRange.firstOrNull { idx -> longitudinal[idx] <= brakeThreshold } ?: safeApex
            val brakeEnd = (brakeStart..safeApex).lastOrNull { idx -> longitudinal[idx] <= -0.1f } ?: brakeStart

            val localTimeMs = ((lap.lapTimeMs * (safeEnd - start + 1).toFloat()) / LapNormalizer.DEFAULT_POINT_COUNT).roundToInt()

            window.cornerIndex to CornerMetrics(
                cornerIndex = window.cornerIndex,
                cornerLabel = cornerLabel(window.cornerIndex, trackLayout),
                brakeStartPercent = percentForIndex(brakeStart),
                brakeDurationPercent = (percentForIndex(brakeEnd) - percentForIndex(brakeStart)).coerceAtLeast(0f),
                exitAccelMean = average(exitRange.map { idx -> longitudinal[idx] }),
                rotationPeak = maxOf(
                    (start..safeEnd).maxOf { idx -> yaw[idx] },
                    (start..safeEnd).maxOf { idx -> lateralAbs[idx] }
                ),
                localTimeMs = localTimeMs.toFloat(),
                mappingConfidence = window.mappingConfidence,
                signalQuality = signalQuality(longitudinal, start, safeEnd),
                lapConfidence = lap.confidenceScore,
                brakePointStdDev = standardDeviation(listOf(percentForIndex(brakeStart))),
                exitStdDev = standardDeviation(exitRange.map { idx -> longitudinal[idx] }),
                localTimeStdDev = 0f,
                consistencyScore = 0f
            )
        }
    }

    private fun generateInsight(
        window: CornerWindow,
        perLap: List<CornerMetrics>,
        reference: CornerMetrics,
        trackLayout: TrackLayout?
    ): CornerCoachingInsight? {
        if (perLap.size < minimumUsableLaps) return null

        val brakeStartValues = perLap.map { it.brakeStartPercent }
        val exitValues = perLap.map { it.exitAccelMean }
        val localTimes = perLap.map { it.localTimeMs }
        val mapping = perLap.map { it.mappingConfidence }.average().toFloat()
        val signal = perLap.map { it.signalQuality }.average().toFloat()
        val lapQuality = perLap.map { it.lapConfidence }.average().toFloat()
        val brakeStd = standardDeviation(brakeStartValues)
        val exitStd = standardDeviation(exitValues)
        val timeStd = standardDeviation(localTimes)

        val consistencyScore = (1f - ((brakeStd / 6f) + (exitStd / 0.5f) + (timeStd / 220f)) / 3f).coerceIn(0f, 1f)
        val confidence = (lapQuality * 0.25f + mapping * 0.25f + signal * 0.15f + consistencyScore * 0.2f + 0.15f).coerceIn(0f, 1f)

        val avgBrakeStart = brakeStartValues.average().toFloat()
        val avgBrakeDuration = perLap.map { it.brakeDurationPercent }.average().toFloat()
        val avgExit = exitValues.average().toFloat()
        val avgLocalTime = localTimes.average().toFloat()
        val avgRotation = perLap.map { it.rotationPeak }.average().toFloat()

        val brakeDelta = avgBrakeStart - reference.brakeStartPercent
        val exitDelta = avgExit - reference.exitAccelMean
        val timeLoss = (avgLocalTime - reference.localTimeMs).coerceAtLeast(0f)
        val label = cornerLabel(window.cornerIndex, trackLayout)

        if (confidence < 0.45f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.CAUTION,
                headline = "$label: Low confidence, collect more clean laps.",
                confidence = confidence,
                evidence = listOf("Data quality limited"),
                ruleId = RULE_LOW_CONFIDENCE
            )
        }

        if (consistencyScore >= 0.78f && timeLoss < 40f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.POSITIVE,
                headline = "$label is very consistent—keep this approach.",
                confidence = confidence,
                evidence = listOf("Brake point std dev ${"%.1f".format(brakeStd)}%", "Stable local time"),
                ruleId = RULE_CONSISTENT
            )
        }

        if (consistencyScore < 0.5f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.CONSISTENCY,
                headline = "$label is inconsistent; lock a repeatable brake marker.",
                confidence = confidence,
                evidence = listOf("Brake spread ${"%.1f".format(brakeStd)}%", "Time variance ${"%.0f".format(timeStd)}ms"),
                ruleId = RULE_INCONSISTENT
            )
        }

        if (brakeDelta > 1.8f && exitDelta < -0.04f && timeLoss > 30f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.ACTION,
                headline = "$label: Brake earlier into this corner.",
                details = "Later braking is currently hurting exit stability.",
                estimatedGainMs = timeLoss,
                confidence = confidence,
                evidence = listOf("Brake point +${"%.1f".format(brakeDelta)}% later", "Exit acceleration lower"),
                ruleId = RULE_BRAKE_EARLIER
            )
        }

        if (brakeDelta < -1.5f && consistencyScore >= 0.6f && exitDelta <= 0.03f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.ACTION,
                headline = "$label: You can brake slightly later here.",
                details = "Early braking is leaving entry potential unused.",
                estimatedGainMs = timeLoss,
                confidence = confidence,
                evidence = listOf("Brake point ${"%.1f".format(-brakeDelta)}% earlier than reference"),
                ruleId = RULE_BRAKE_LATER
            )
        }

        if (avgBrakeDuration > reference.brakeDurationPercent + 1.2f && exitDelta < -0.03f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.ACTION,
                headline = "$label: Release brakes a touch earlier for better exit.",
                estimatedGainMs = timeLoss,
                confidence = confidence,
                evidence = listOf("Brake phase longer than reference", "Exit acceleration deficit"),
                ruleId = RULE_RELEASE_EARLIER
            )
        }

        if (avgRotation > reference.rotationPeak && avgBrakeDuration >= reference.brakeDurationPercent * 0.85f && timeLoss <= 60f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.ACTION,
                headline = "$label: Trail braking looks promising—keep it smooth.",
                estimatedGainMs = timeLoss,
                confidence = confidence,
                evidence = listOf("Braking overlap aligns with stronger rotation"),
                ruleId = RULE_TRAIL_BRAKE
            )
        }

        if (exitDelta < -0.05f && timeLoss > 25f) {
            return CornerCoachingInsight(
                cornerIndex = window.cornerIndex,
                cornerLabel = label,
                category = CornerInsightCategory.ACTION,
                headline = "$label: Focus on exit speed.",
                details = "Prioritize earlier clean acceleration on corner exit.",
                estimatedGainMs = timeLoss,
                confidence = confidence,
                evidence = listOf("Exit acceleration below reference"),
                ruleId = RULE_EXIT_SPEED
            )
        }

        return CornerCoachingInsight(
            cornerIndex = window.cornerIndex,
            cornerLabel = label,
            category = CornerInsightCategory.CONSISTENCY,
            headline = "$label: No dominant issue detected; keep inputs smooth.",
            confidence = confidence,
            evidence = listOf("Stable but limited gain signal"),
            ruleId = RULE_GENERAL
        )
    }

    private fun cornerLabel(cornerIndex: Int, trackLayout: TrackLayout?): String {
        val layoutCornerName = trackLayout?.corners?.getOrNull(cornerIndex - 1)?.name
        return layoutCornerName ?: "Corner $cornerIndex"
    }

    private fun indexForPercent(percent: Float): Int {
        val normalized = (percent / 100f).coerceIn(0f, 1f)
        return (normalized * (LapNormalizer.DEFAULT_POINT_COUNT - 1)).roundToInt()
    }

    private fun percentForIndex(index: Int): Float {
        return (index.toFloat() / (LapNormalizer.DEFAULT_POINT_COUNT - 1).toFloat()) * 100f
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun average(values: List<Float>): Float {
        return if (values.isEmpty()) 0f else values.average().toFloat()
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = average(values)
        val variance = values.map { value -> (value - mean) * (value - mean) }.average().toFloat()
        return sqrt(variance)
    }

    private fun signalQuality(values: List<Float>, start: Int, end: Int): Float {
        val slice = values.subList(start.coerceIn(0, values.lastIndex), end.coerceIn(start, values.lastIndex) + 1)
        if (slice.size < 3) return 0.3f
        val amplitude = (slice.maxOrNull() ?: 0f) - (slice.minOrNull() ?: 0f)
        return (amplitude / 2.5f).coerceIn(0.3f, 1f)
    }

    private data class CornerWindow(
        val cornerIndex: Int,
        val startPercent: Float,
        val apexPercent: Float,
        val endPercent: Float,
        val mappingConfidence: Float
    )

    private data class CornerMetrics(
        val cornerIndex: Int,
        val cornerLabel: String,
        val brakeStartPercent: Float,
        val brakeDurationPercent: Float,
        val exitAccelMean: Float,
        val rotationPeak: Float,
        val localTimeMs: Float,
        val mappingConfidence: Float,
        val signalQuality: Float,
        val lapConfidence: Float,
        val brakePointStdDev: Float,
        val exitStdDev: Float,
        val localTimeStdDev: Float,
        val consistencyScore: Float
    )

    companion object {
        private const val minimumUsableLaps = 2
        private const val minimumLapConfidence = 0.6f

        private const val RULE_BRAKE_EARLIER = "R1_BRAKE_EARLIER"
        private const val RULE_BRAKE_LATER = "R2_BRAKE_LATER"
        private const val RULE_RELEASE_EARLIER = "R3_RELEASE_EARLIER"
        private const val RULE_TRAIL_BRAKE = "R4_TRAIL_BRAKE"
        private const val RULE_EXIT_SPEED = "R5_EXIT_SPEED"
        private const val RULE_CONSISTENT = "R6_CONSISTENT"
        private const val RULE_INCONSISTENT = "R7_INCONSISTENT"
        private const val RULE_LOW_CONFIDENCE = "R8_LOW_CONFIDENCE"
        private const val RULE_GENERAL = "R9_GENERAL"
    }
}
