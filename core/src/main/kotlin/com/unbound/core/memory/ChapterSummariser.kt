package com.unbound.core.memory

import com.unbound.core.ledger.GameEvent
import com.unbound.core.model.Importance
import com.unbound.core.model.WorldTime

/**
 * Builds the narrative-summary layer (§25D) from the event ledger.
 *
 * This layer was specified, modelled and stored, and then never written — nothing in the engine
 * ever created a summary, so a long campaign's early history could only be reached through
 * individual events and the "earlier chapters" section of every prompt was permanently empty.
 *
 * It is deliberately **deterministic and free**. Asking a model to summarise every N turns would be
 * a recurring charge on the player's own key for something the ledger already knows, and it would
 * risk inventing history — the one thing the memory architecture exists to prevent. Every line here
 * is assembled from event summaries that were validated when they were written.
 */
class ChapterSummariser(private val idFactory: () -> String) {

    /**
     * @param events every event in the chapter's turn window, in sequence order.
     * @return null when nothing in the window was worth remembering, so quiet stretches do not
     *   produce filler that crowds out real chapters.
     */
    fun summarise(
        gameId: String,
        fromTurn: Int,
        toTurn: Int,
        events: List<GameEvent>,
        nowWorldMinutes: Long,
    ): SummaryRecord? {
        val notable = events
            .filter { it.importance.ordinal >= Importance.MEDIUM.ordinal }
            .sortedWith(compareByDescending<GameEvent> { it.importance.ordinal }.thenBy { it.sequence })
            .distinctBy { it.summary.lowercase() }
            .take(MAX_LINES)
            .sortedBy { it.sequence }

        if (notable.isEmpty()) return null

        val opened = WorldTime(notable.first().worldMinutes)
        val closed = WorldTime(notable.last().worldMinutes)
        val span = if (opened.absoluteDay == closed.absoluteDay) {
            "day ${opened.absoluteDay + 1}"
        } else {
            "days ${opened.absoluteDay + 1}-${closed.absoluteDay + 1}"
        }

        val text = buildString {
            append("Turns ").append(fromTurn).append('-').append(toTurn)
            append(" (").append(span).append("): ")
            append(notable.joinToString("; ") { it.summary.trim().trimEnd('.') })
            append('.')
        }

        return SummaryRecord(
            id = idFactory(),
            gameId = gameId,
            scope = SummaryScope.CHAPTER,
            subjectId = null,
            text = if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1) + "…" else text,
            coversFromTurn = fromTurn,
            coversToTurn = toTurn,
            createdAtWorldMinutes = nowWorldMinutes,
        )
    }

    companion object {
        /** How many turns a chapter covers. */
        const val CHAPTER_TURNS = 20
        private const val MAX_LINES = 8
        private const val MAX_LENGTH = 700

        /** True on the turn that closes a chapter. */
        fun closesChapter(turnNumber: Int): Boolean = turnNumber > 0 && turnNumber % CHAPTER_TURNS == 0
    }
}
