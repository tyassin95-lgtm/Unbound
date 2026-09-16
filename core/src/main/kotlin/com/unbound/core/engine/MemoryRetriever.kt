package com.unbound.core.engine

import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryScorer
import com.unbound.core.memory.RetrievalBudget
import com.unbound.core.memory.RetrievalQuery
import com.unbound.core.memory.RetrievedContext
import com.unbound.core.memory.SemanticIndex
import com.unbound.core.memory.SummaryRecord
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

        // The semantic seam is optional but real: when an index is supplied its similarity genuinely
        // feeds the score. (This once read `semanticIndex?.let { _ -> null }`, which always
        // evaluated to null, so supplying an index changed nothing at all.)
        val scored = candidates
            .map { memory ->
                val semantic = semanticIndex?.similarity(memory.id, query.inputText)
                scorer.score(memory, query, semantic)
            }
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

        // The history the player shares with whoever this turn is about. Any importance: a first
        // meeting is not a dramatic event, and it is exactly what gets asked about later.
        val recentIds = recent.map { it.id }.toSet() + older.map { it.id }.toSet()
        val sharedHistory = if (query.focusEntityIds.isEmpty()) {
            emptyList()
        } else {
            store.eventsInvolving(query.gameId, query.focusEntityIds, budget.maxSharedHistoryEvents * 3)
                .filterNot { it.id in recentIds }
                .let { events -> spreadOverTime(events, budget.maxSharedHistoryEvents) }
        }

        return RetrievedContext(
            memories = scored,
            recentEvents = recent.sortedBy { it.sequence },
            olderImportantEvents = older.sortedBy { it.sequence },
            sharedHistory = sharedHistory.sortedBy { it.sequence },
            summaries = chapters(query.gameId, budget),
        )
    }

    /**
     * Keeps the beginning of a relationship, not just the end of it.
     *
     * Taking the most recent N would mean that after a hundred turns with someone, nothing about
     * how the two of you met survives. So the oldest few are kept deliberately, and the remainder
     * of the budget goes to what happened lately.
     */
    private fun spreadOverTime(events: List<GameEvent>, limit: Int): List<GameEvent> {
        if (events.size <= limit) return events
        val bySequence = events.sortedBy { it.sequence }
        val earliest = (limit / 3).coerceAtLeast(1)
        return (bySequence.take(earliest) + bySequence.takeLast(limit - earliest)).distinctBy { it.id }
    }

    /**
     * The first chapter plus the most recent ones: where the story started, and where it is now.
     * Taking only the newest would lose the opening of a long campaign, which is the half a player
     * is most likely to ask about.
     */
    private suspend fun chapters(gameId: String, budget: RetrievalBudget): List<SummaryRecord> {
        if (budget.maxSummaries <= 0) return emptyList()
        val all = store.summaries(gameId, MAX_SUMMARY_SCAN)
        if (all.size <= budget.maxSummaries) return all.sortedBy { it.coversToTurn }
        val oldest = all.minByOrNull { it.coversToTurn }
        val newest = all.sortedByDescending { it.coversToTurn }.take(budget.maxSummaries - 1)
        return (listOfNotNull(oldest) + newest).distinctBy { it.id }.sortedBy { it.coversToTurn }
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
     * What each character in the scene knows, in one read rather than two per character.
     *
     * Facts about what this turn is *about* come first — that is the difference between a
     * character recalling the thing being discussed and a character reciting the first rows in
     * their file. Everything else fills the remaining budget by importance, so a character with
     * nothing relevant still arrives with the things that define them.
     */
    suspend fun npcKnowledge(
        gameId: String,
        npcIds: Collection<String>,
        subjects: Collection<String>,
        budget: RetrievalBudget = RetrievalBudget(),
    ): Map<String, List<KnowledgeRecord>> {
        if (npcIds.isEmpty()) return emptyMap()
        val subjectSet = subjects.toSet()
        // Oversampled so the ranking below has something to choose between.
        val rows = store.knowledgeForKnowers(gameId, npcIds, budget.maxNpcKnowledgeFacts * KNOWLEDGE_OVERSAMPLE)

        return rows.groupBy { it.knowerId }
            .mapValues { (_, facts) ->
                facts.sortedWith(
                    compareByDescending<KnowledgeRecord> { f ->
                        if (f.subjectEntityIds.any { it in subjectSet }) 1 else 0
                    }
                        .thenByDescending { it.importance.weight }
                        .thenByDescending { it.learnedAtWorldMinutes },
                )
                    .distinctBy { it.factKey }
                    .take(budget.maxNpcKnowledgeFacts)
            }
            .filterValues { it.isNotEmpty() }
    }

    private companion object {
        const val CANDIDATE_OVERSAMPLE = 6
        const val KNOWLEDGE_OVERSAMPLE = 4
        const val MAX_SUMMARY_SCAN = 60
    }
}
