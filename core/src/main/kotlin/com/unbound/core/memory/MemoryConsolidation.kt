package com.unbound.core.memory

import com.unbound.core.ledger.GameEvent
import com.unbound.core.model.Importance
import com.unbound.core.model.WorldTime

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
 * Periodic compaction of one owner's memories (§90).
 *
 * Two things were wrong with the first version. It was never called from anywhere, so memories grew
 * for the life of a campaign — two hundred turns of ordinary conversation produced six hundred rows
 * and every one of them stayed a retrieval candidate forever. And it only ever considered `LOW`
 * memories, while almost everything a witness records is `MEDIUM`, so even when called it would
 * have compacted nearly nothing.
 *
 * It now works to a **per-owner cap**: once someone remembers more than [ConsolidationConfig.maxPerOwner]
 * things, the oldest unimportant ones are merged into dated digests. `HIGH` and `CRITICAL` memories
 * are never touched, recent ones are never touched, and the source event ids are carried across, so
 * nothing in the ledger is orphaned and nothing that mattered is lost.
 */
class MemoryConsolidator(private val idFactory: () -> String) {

    fun consolidate(
        memories: List<MemoryRecord>,
        nowWorldMinutes: Long,
        config: ConsolidationConfig = ConsolidationConfig(),
    ): ConsolidationPlan {
        if (memories.size <= config.maxPerOwner) return ConsolidationPlan(emptyList(), emptyList())

        val eligible = memories.filter {
            it.importance.ordinal <= Importance.MEDIUM.ordinal &&
                nowWorldMinutes - it.lastReinforcedWorldMinutes > config.minAgeMinutes &&
                it.consolidatedFromIds.isEmpty()
        }
        // Compact only the excess, oldest first, so recent and important memories survive intact.
        val excess = (memories.size - config.maxPerOwner).coerceAtMost(eligible.size)
        if (excess < config.minGroupSize) return ConsolidationPlan(emptyList(), emptyList())

        val doomed = eligible.sortedBy { it.lastReinforcedWorldMinutes }.take(excess)

        val created = mutableListOf<MemoryRecord>()
        val removed = mutableListOf<String>()

        // Grouped by subject so a digest reads as being about someone, not a list of unrelated days.
        for ((_, group) in doomed.groupBy { it.entityIds.sorted().firstOrNull() ?: it.locationId ?: "misc" }) {
            if (group.size < config.minGroupSize) continue
            val ordered = group.sortedBy { it.createdAtWorldMinutes }
            val from = WorldTime(ordered.first().createdAtWorldMinutes)
            val to = WorldTime(ordered.last().createdAtWorldMinutes)
            val span = if (from.absoluteDay == to.absoluteDay) {
                "on day ${from.absoluteDay + 1}"
            } else {
                "between days ${from.absoluteDay + 1} and ${to.absoluteDay + 1}"
            }

            val body = ordered.joinToString("; ") { it.text.trim().trimEnd('.') }
            val text = "Earlier, $span: $body."
            created += MemoryRecord(
                id = idFactory(),
                gameId = ordered.first().gameId,
                ownerId = ordered.first().ownerId,
                text = if (text.length > config.maxTextLength) text.take(config.maxTextLength - 1) + "\u2026" else text,
                entityIds = ordered.flatMap { it.entityIds }.distinct(),
                locationId = ordered.mapNotNull { it.locationId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                sourceEventIds = ordered.flatMap { it.sourceEventIds }.distinct(),
                importance = ordered.maxOf { it.importance },
                createdAtWorldMinutes = ordered.first().createdAtWorldMinutes,
                lastReinforcedWorldMinutes = ordered.last().lastReinforcedWorldMinutes,
                reinforcementCount = ordered.sumOf { it.reinforcementCount },
                visibility = ordered.first().visibility,
                consolidatedFromIds = ordered.map { it.id },
            )
            removed += ordered.map { it.id }
        }

        return ConsolidationPlan(created, removed)
    }
}

data class ConsolidationConfig(
    /** Above this, an owner's oldest unimportant memories start being merged. */
    val maxPerOwner: Int = 60,
    val minGroupSize: Int = 3,
    val minAgeMinutes: Long = 60L * 24,
    val maxTextLength: Int = 600,
)

data class ConsolidationPlan(val created: List<MemoryRecord>, val removedIds: List<String>)
