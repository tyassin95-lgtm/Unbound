package com.unbound.core.memory

import com.unbound.core.ledger.GameEvent
import com.unbound.core.model.WorldTime
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * What the current turn is *about*. Retrieval is structured-first: this query is turned into
 * relational filters, and only the surviving candidates are scored. That is what keeps retrieval
 * bounded and — crucially — debuggable, since every returned memory can be traced to the clause
 * that admitted it (§27, §28).
 */
data class RetrievalQuery(
    val gameId: String,
    val now: WorldTime,
    val currentLocationId: String,
    val presentNpcIds: Set<String> = emptySet(),
    /** Entities the player's input named or that the intent parser resolved. */
    val focusEntityIds: Set<String> = emptySet(),
    val activeThreadIds: Set<String> = emptySet(),
    val factionIds: Set<String> = emptySet(),
    val itemIds: Set<String> = emptySet(),
    /** Free text of the player's action, used for the lexical-overlap term. */
    val inputText: String = "",
)

/** A scored candidate plus the human-readable reasons it scored, for the diagnostics screen. */
data class ScoredMemory(
    val memory: MemoryRecord,
    val score: Double,
    val reasons: List<String>,
)

/**
 * Deterministic relevance scoring (§28).
 *
 * Every term is bounded and the weights are explicit constants rather than a learned model, because
 * the specification's real requirement is that a designer can look at a retrieved set and explain
 * it. An embedding index can be layered on later behind [SemanticIndex] without changing this
 * contract — the lexical term is simply replaced by a cosine score.
 */
class MemoryScorer(private val weights: RetrievalWeights = RetrievalWeights()) {

    fun score(memory: MemoryRecord, query: RetrievalQuery, semantic: Double? = null): ScoredMemory {
        var total = 0.0
        val reasons = mutableListOf<String>()

        fun add(value: Double, reason: String) {
            if (value != 0.0) {
                total += value
                reasons += reason
            }
        }

        if (memory.entityIds.any { it in query.presentNpcIds }) {
            add(weights.presentEntity, "involves someone present")
        }
        if (memory.entityIds.any { it in query.focusEntityIds } || memory.ownerId in query.focusEntityIds) {
            add(weights.focusEntity, "involves the focus of this action")
        }
        if (memory.locationId != null && memory.locationId == query.currentLocationId) {
            add(weights.sameLocation, "happened here")
        }
        if (memory.threadId != null && memory.threadId in query.activeThreadIds) {
            add(weights.activeThread, "belongs to an active thread")
        }
        if (memory.entityIds.any { it in query.factionIds }) {
            add(weights.faction, "involves a relevant faction")
        }
        if (memory.entityIds.any { it in query.itemIds }) {
            add(weights.item, "involves a relevant item")
        }

        add(weights.importance * memory.importance.weight, "importance ${memory.importance}")

        // Recency decays smoothly with a half-life rather than a cliff, so a memory does not
        // vanish the moment it crosses an arbitrary age boundary.
        val ageDays = (query.now.totalMinutes - memory.lastReinforcedWorldMinutes).toDouble() /
            WorldTime.MINUTES_PER_DAY
        val recency = exp(-ageDays.coerceAtLeast(0.0) / weights.recencyHalfLifeDays)
        add(weights.recency * recency, "recency %.2f".format(recency))

        // Reinforcement grows logarithmically: being reminded twenty times is not twenty times
        // more relevant than being reminded once, but it should still beat a one-off.
        val reinforcement = ln(1.0 + memory.reinforcementCount) / ln(10.0)
        add(weights.reinforcement * min(reinforcement, 1.5), "reinforced ${memory.reinforcementCount}x")

        add(weights.confidence * (memory.confidence - 0.5), "confidence ${memory.confidence}")

        val lexical = semantic ?: lexicalOverlap(memory.text, query.inputText)
        add(weights.semantic * lexical, if (semantic != null) "semantic match" else "wording overlap")

        return ScoredMemory(memory, total, reasons)
    }

    /**
     * Cheap local similarity: proportion of the player's content words that appear in the memory.
     * Deliberately not an API call — retrieval must never itself cost tokens (§66).
     */
    fun lexicalOverlap(memoryText: String, input: String): Double {
        if (input.isBlank()) return 0.0
        val inputWords = tokenize(input)
        if (inputWords.isEmpty()) return 0.0
        val memWords = tokenize(memoryText).toSet()
        val hits = inputWords.count { it in memWords }
        return hits.toDouble() / inputWords.size
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length > 2 && it !in STOP_WORDS }

    companion object {
        val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "you", "your", "his", "her", "its",
            "was", "were", "are", "but", "not", "from", "have", "has", "had", "into", "out",
            "then", "than", "them", "they", "she", "him", "who", "what", "when", "where", "how",
            "will", "would", "could", "should", "about", "there", "their", "been", "being",
        )
    }
}

data class RetrievalWeights(
    val presentEntity: Double = 3.0,
    val focusEntity: Double = 4.0,
    val sameLocation: Double = 1.5,
    val activeThread: Double = 2.0,
    val faction: Double = 1.0,
    val item: Double = 1.2,
    val importance: Double = 2.5,
    val recency: Double = 2.0,
    val recencyHalfLifeDays: Double = 7.0,
    val reinforcement: Double = 1.0,
    val confidence: Double = 0.5,
    val semantic: Double = 2.0,
)

/** Optional seam for a later embedding index. Nothing in the engine requires one. */
interface SemanticIndex {
    suspend fun similarity(memoryId: String, queryText: String): Double?
}

/**
 * Hard ceilings on what may reach the model. These are the numbers that make a 1000-turn campaign
 * cost the same per turn as a 10-turn one (§65).
 */
data class RetrievalBudget(
    val maxMemories: Int = 14,
    val maxRecentEvents: Int = 12,
    val maxOlderImportantEvents: Int = 6,
    val maxNpcs: Int = 8,
    val maxNpcKnowledgeFacts: Int = 6,
    val maxThreads: Int = 6,
    val maxRumors: Int = 4,
    val maxSummaries: Int = 3,
)

/** The bounded slice of history handed to the prompt builder. */
data class RetrievedContext(
    val memories: List<ScoredMemory>,
    val recentEvents: List<GameEvent>,
    val olderImportantEvents: List<GameEvent>,
    val summaries: List<SummaryRecord>,
) {
    val memoryIds: List<String> get() = memories.map { it.memory.id }
    val eventIds: List<String> get() = (recentEvents + olderImportantEvents).map { it.id }
}
