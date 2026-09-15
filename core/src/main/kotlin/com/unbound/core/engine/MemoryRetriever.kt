package com.unbound.core.engine

import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryScorer
import com.unbound.core.memory.RetrievalBudget
import com.unbound.core.memory.RetrievalQuery
import com.unbound.core.memory.RetrievedContext
import com.unbound.core.memory.SemanticIndex
import com.unbound.core.model.Importance

/**
 * Structured filter first, scoring second, hard budget last (§27).
 *
 * The important property is that none of the three stages is proportional to campaign age. The
 * filter is index-backed and capped; the scorer only sees what the filter returned; the budget
 * truncates regardless. A turn on day 1 and a turn on turn 3,000 both send the same shape of
 * context.
 */
class MemoryRetriever(
    private val store: WorldStore,
    private val scorer: MemoryScorer = MemoryScorer(),
    private val semanticIndex: SemanticIndex? = null,
) {

    suspend fun retrieve(
        query: RetrievalQuery,
        budget: RetrievalBudget = RetrievalBudget(),
    ): RetrievedContext {
        val owners = buildSet {
            add(com.unbound.core.memory.MemoryRecord.WORLD_OWNER)
            add(com.unbound.core.model.Ids.PLAYER)
            addAll(query.presentNpcIds)
            addAll(query.focusEntityIds)
        }

        val entityIds = query.presentNpcIds + query.focusEntityIds + query.factionIds + query.itemIds

        // Oversample by a fixed factor so scoring has something to choose between, then cut hard.
        val candidates = store.memoryCandidates(
            gameId = query.gameId,
            ownerIds = owners,
            entityIds = entityIds,
            locationId = query.currentLocationId,
            threadIds = query.activeThreadIds,
            minImportance = Importance.LOW,
            limit = budget.maxMemories * CANDIDATE_OVERSAMPLE,
        )

        val scored = candidates
            .map { scorer.score(it, query, semanticIndex?.let { _ -> null }) }
            .sortedByDescending { it.score }
            .take(budget.maxMemories)

        val recent = store.recentEvents(query.gameId, budget.maxRecentEvents)

        // "Old but important" is what makes turn 200 able to reference turn 5. It is a separate,
        // separately-budgeted query precisely so that recency can never crowd it out.
        val oldestRecentMinutes = recent.minOfOrNull { it.worldMinutes } ?: query.now.totalMinutes
        val older = store.importantEventsBefore(
            gameId = query.gameId,
            beforeWorldMinutes = oldestRecentMinutes,
            minImportance = Importance.HIGH,
            limit = budget.maxOlderImportantEvents * 2,
        ).let { events -> rankOlderEvents(events, query).take(budget.maxOlderImportantEvents) }

        val summaries = store.summaries(query.gameId, budget.maxSummaries)

        return RetrievedContext(
            memories = scored,
            recentEvents = recent.sortedBy { it.sequence },
            olderImportantEvents = older.sortedBy { it.sequence },
            summaries = summaries,
        )
    }

    private fun rankOlderEvents(events: List<GameEvent>, query: RetrievalQuery): List<GameEvent> {
        val focus = query.focusEntityIds + query.presentNpcIds
        return events.sortedByDescending { e ->
            var s = e.importance.weight * 2
            if (e.actorId in focus || e.targetId in focus) s += 3
            if (e.relatedEntityIds.any { it in focus }) s += 2
            if (e.locationId == query.currentLocationId) s += 1
            s
        }
    }

    /**
     * What each present NPC personally knows about the entities in play. Deliberately per-NPC and
     * capped: this is the section that would otherwise leak world truth into every character's head.
     */
    suspend fun npcKnowledge(
        gameId: String,
        npcIds: Collection<String>,
        subjects: Collection<String>,
        budget: RetrievalBudget = RetrievalBudget(),
    ): Map<String, List<KnowledgeRecord>> = npcIds.associateWith { npcId ->
        val about = if (subjects.isEmpty()) {
            emptyList()
        } else {
            store.knowledgeOfAbout(gameId, npcId, subjects, budget.maxNpcKnowledgeFacts)
        }
        if (about.size >= budget.maxNpcKnowledgeFacts) {
            about
        } else {
            (about + store.knowledgeOf(gameId, npcId, budget.maxNpcKnowledgeFacts))
                .distinctBy { it.factKey }
                .sortedByDescending { it.importance.weight }
                .take(budget.maxNpcKnowledgeFacts)
        }
    }.filterValues { it.isNotEmpty() }

    private companion object {
        const val CANDIDATE_OVERSAMPLE = 6
    }
}
