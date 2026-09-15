package com.unbound.core.memory

import com.unbound.core.ledger.GameEvent
import com.unbound.core.model.Importance

/**
 * Turns raw events into durable memories (§90) using only local logic — no model call. An event is
 * only *eligible* for a memory if it is at least MEDIUM importance, which is what stops a long
 * campaign accumulating tens of thousands of memory rows.
 */
class MemoryExtractor(private val idFactory: () -> String) {

    fun candidateFrom(event: GameEvent, ownerId: String, visibility: MemoryVisibility): MemoryRecord? {
        if (!event.importance.isMemorable) return null
        return MemoryRecord(
            id = idFactory(),
            gameId = event.gameId,
            ownerId = ownerId,
            text = event.summary,
            entityIds = (listOfNotNull(event.actorId, event.targetId) + event.relatedEntityIds).distinct(),
            locationId = event.locationId,
            sourceEventIds = listOf(event.id),
            importance = event.importance,
            createdAtWorldMinutes = event.worldMinutes,
            lastReinforcedWorldMinutes = event.worldMinutes,
            visibility = visibility,
        )
    }

    /**
     * When a new memory restates something the owner already remembers, reinforce the existing row
     * instead of adding a near-duplicate. This is what makes [MemoryRecord.reinforcementCount]
     * meaningful, and it bounds growth in repetitive campaigns.
     */
    fun reconcile(candidate: MemoryRecord, existing: List<MemoryRecord>): MemoryReconciliation {
        val match = existing.firstOrNull { it.ownerId == candidate.ownerId && isNearDuplicate(it.text, candidate.text) }
            ?: return MemoryReconciliation(insert = candidate, reinforce = null)
        val merged = match.copy(
            lastReinforcedWorldMinutes = candidate.createdAtWorldMinutes,
            reinforcementCount = match.reinforcementCount + 1,
            importance = maxOf(match.importance, candidate.importance),
            sourceEventIds = (match.sourceEventIds + candidate.sourceEventIds).distinct().takeLast(12),
            entityIds = (match.entityIds + candidate.entityIds).distinct(),
        )
        return MemoryReconciliation(insert = null, reinforce = merged)
    }

    private fun isNearDuplicate(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val overlap = wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size)
        return overlap >= 0.8
    }

    private fun words(s: String) = s.lowercase()
        .split(Regex("[^\\p{L}\\p{N}']+"))
        .filter { it.length > 2 && it !in MemoryScorer.STOP_WORDS }
        .toSet()
}

data class MemoryReconciliation(val insert: MemoryRecord?, val reinforce: MemoryRecord?)

/**
 * Periodic compaction. Groups a single owner's low-importance memories about the same subject into
 * one line, and keeps the originals' event ids so nothing in the ledger is orphaned.
 *
 * Canonical events are never touched — only the derived memory layer is compacted (§90).
 */
class MemoryConsolidator(private val idFactory: () -> String) {

    fun consolidate(memories: List<MemoryRecord>, nowWorldMinutes: Long, config: ConsolidationConfig = ConsolidationConfig()): ConsolidationPlan {
        val eligible = memories.filter {
            it.importance.ordinal <= Importance.LOW.ordinal &&
                nowWorldMinutes - it.lastReinforcedWorldMinutes > config.minAgeMinutes &&
                it.consolidatedFromIds.isEmpty()
        }
        if (eligible.size < config.minGroupSize) return ConsolidationPlan(emptyList(), emptyList())

        val groups = eligible.groupBy { it.ownerId to (it.entityIds.sorted().firstOrNull() ?: it.locationId ?: "misc") }
        val created = mutableListOf<MemoryRecord>()
        val removed = mutableListOf<String>()

        for ((key, group) in groups) {
            if (group.size < config.minGroupSize) continue
            val (owner, _) = key
            val text = "Over time: " + group.sortedBy { it.createdAtWorldMinutes }
                .joinToString("; ") { it.text.trimEnd('.') } + "."
            created += MemoryRecord(
                id = idFactory(),
                gameId = group.first().gameId,
                ownerId = owner,
                text = if (text.length > config.maxTextLength) text.take(config.maxTextLength - 1) + "…" else text,
                entityIds = group.flatMap { it.entityIds }.distinct(),
                locationId = group.mapNotNull { it.locationId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                sourceEventIds = group.flatMap { it.sourceEventIds }.distinct(),
                importance = Importance.LOW,
                createdAtWorldMinutes = group.minOf { it.createdAtWorldMinutes },
                lastReinforcedWorldMinutes = group.maxOf { it.lastReinforcedWorldMinutes },
                reinforcementCount = group.sumOf { it.reinforcementCount },
                visibility = group.first().visibility,
                consolidatedFromIds = group.map { it.id },
            )
            removed += group.map { it.id }
        }
        return ConsolidationPlan(created, removed)
    }
}

data class ConsolidationConfig(
    val minGroupSize: Int = 4,
    val minAgeMinutes: Long = 60L * 24 * 3,
    val maxTextLength: Int = 400,
)

data class ConsolidationPlan(val created: List<MemoryRecord>, val removedIds: List<String>)
