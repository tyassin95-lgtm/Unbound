package com.unbound.core.memory

import com.unbound.core.model.Importance
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryVisibility {
    /** Retrievable for any prompt in this game. */
    WORLD,

    /** Only retrievable when the owning entity is relevant to the turn. */
    ENTITY,

    /** Retrievable only for the owner, and never surfaced to other NPCs. */
    PRIVATE,
}

/**
 * A compact durable memory distilled from one or more events (§25C). Memories are the unit that
 * actually reaches the model; raw events almost never do, because they are verbose and numerous.
 *
 * [reinforcementCount] rises when the same thing happens again, which is what lets an old but
 * repeatedly-relevant memory outrank a recent trivial one during retrieval.
 */
@Serializable
data class MemoryRecord(
    val id: String,
    val gameId: String,
    /** Whose memory this is: the player, an NPC, a faction, or [WORLD_OWNER] for global memory. */
    val ownerId: String,
    val text: String,
    val entityIds: List<String> = emptyList(),
    val locationId: String? = null,
    val threadId: String? = null,
    val sourceEventIds: List<String> = emptyList(),
    val importance: Importance = Importance.MEDIUM,
    val createdAtWorldMinutes: Long,
    val lastReinforcedWorldMinutes: Long,
    val reinforcementCount: Int = 1,
    /** 0.0..1.0 — how sure the owner is that this memory is accurate. */
    val confidence: Double = 1.0,
    val visibility: MemoryVisibility = MemoryVisibility.ENTITY,
    /** Set when this memory replaced several others during consolidation. */
    val consolidatedFromIds: List<String> = emptyList(),
) {
    companion object {
        const val WORLD_OWNER = "world"
    }
}

/** Narrative summaries (§25D). Convenience only — never authoritative over state or the ledger. */
@Serializable
data class SummaryRecord(
    val id: String,
    val gameId: String,
    val scope: SummaryScope,
    /** Entity the summary is about, or null for whole-campaign summaries. */
    val subjectId: String? = null,
    val text: String,
    val coversFromTurn: Int,
    val coversToTurn: Int,
    val createdAtWorldMinutes: Long,
)

@Serializable
enum class SummaryScope { CHAPTER, SESSION, NPC_HISTORY, LOCATION_HISTORY, FACTION_HISTORY, RELATIONSHIP_HISTORY, MAJOR_EVENTS }
