package com.kartingtracker.data

enum class CornerInsightCategory {
    ACTION,
    POSITIVE,
    CONSISTENCY,
    CAUTION
}

data class CornerCoachingInsight(
    val cornerIndex: Int,
    val cornerLabel: String,
    val category: CornerInsightCategory,
    val headline: String,
    val details: String? = null,
    val estimatedGainMs: Float? = null,
    val confidence: Float = 0f,
    val evidence: List<String> = emptyList(),
    val ruleId: String = ""
)

data class CornerCoachingSummary(
    val topActions: List<CornerCoachingInsight> = emptyList(),
    val strongestCorner: CornerCoachingInsight? = null,
    val mostInconsistentCorner: CornerCoachingInsight? = null,
    val biggestOpportunityCorner: CornerCoachingInsight? = null,
    val overallConfidence: Float = 0f
)
