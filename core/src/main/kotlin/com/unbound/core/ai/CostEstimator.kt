package com.unbound.core.ai

import com.unbound.core.model.RequestType
import com.unbound.core.model.UsageRecord

/**
 * Local cost estimation (§69, §102).
 *
 * Every number this produces is an *estimate*, and the UI is required to label it as one. The
 * provider's own dashboard is authoritative for billing; pretending otherwise would be a lie the
 * app cannot back up, since it cannot see discounts, tiers or free credits.
 */
class CostEstimator(private val catalog: Map<String, ModelProfile>) {

    fun estimate(record: UsageRecord): Double {
        val profile = catalog[record.modelId] ?: return 0.0
        return if (record.requestType == RequestType.IMAGE) {
            profile.imageCostEach ?: 0.0
        } else {
            profile.estimateCost(record.inputTokens, record.outputTokens, record.cachedTokens) ?: 0.0
        }
    }

    fun summarise(records: List<UsageRecord>): CostSummary {
        if (records.isEmpty()) return CostSummary()
        val turns = records.filter { it.requestType == RequestType.NARRATIVE_TURN }
        val totalCost = records.sumOf { estimate(it) }
        return CostSummary(
            requests = records.size,
            narrativeTurns = turns.size,
            imageRequests = records.count { it.requestType == RequestType.IMAGE },
            inputTokens = records.sumOf { it.inputTokens.toLong() },
            outputTokens = records.sumOf { it.outputTokens.toLong() },
            cachedTokens = records.sumOf { it.cachedTokens.toLong() },
            estimatedCostUsd = totalCost,
            averageCostPerTurnUsd = if (turns.isEmpty()) 0.0 else turns.sumOf { estimate(it) } / turns.size,
            averageInputTokensPerTurn = if (turns.isEmpty()) 0 else turns.sumOf { it.inputTokens } / turns.size,
            averageOutputTokensPerTurn = if (turns.isEmpty()) 0 else turns.sumOf { it.outputTokens } / turns.size,
            averageLatencyMs = records.map { it.latencyMs }.average().toLong(),
            failures = records.count { !it.success },
            /** Share of input tokens the provider reported as cached — the prompt-stability payoff. */
            cacheHitRatio = records.sumOf { it.inputTokens.toLong() }.let { total ->
                if (total == 0L) 0.0 else records.sumOf { it.cachedTokens.toLong() }.toDouble() / total
            },
        )
    }

    /** A rough token count for showing context size before a request is sent. */
    fun approximateTokens(text: String): Int = (text.length / 4.0).toInt()
}

data class CostSummary(
    val requests: Int = 0,
    val narrativeTurns: Int = 0,
    val imageRequests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val estimatedCostUsd: Double = 0.0,
    val averageCostPerTurnUsd: Double = 0.0,
    val averageInputTokensPerTurn: Int = 0,
    val averageOutputTokensPerTurn: Int = 0,
    val averageLatencyMs: Long = 0,
    val failures: Int = 0,
    val cacheHitRatio: Double = 0.0,
)
