package com.unbound.core.ledger

import com.unbound.core.model.Importance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The append-only history of the world. Everything else — memories, summaries, relationship totals,
 * the journal — is derived and can be rebuilt; the ledger itself is never rewritten or pruned (§25B).
 */
@Serializable
data class GameEvent(
    val id: String,
    val gameId: String,
    val turnId: String?,
    val sequence: Long,
    val worldMinutes: Long,
    val realTimestampMs: Long,
    val type: EventType,
    val actorId: String? = null,
    val targetId: String? = null,
    val locationId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    val summary: String,
    val importance: Importance = Importance.LOW,
    /** Who was in a position to learn of this. Drives knowledge propagation. */
    val knowledgeScope: KnowledgeScope = KnowledgeScope.WITNESSED,
    val witnessIds: List<String> = emptyList(),
    val relatedEntityIds: List<String> = emptyList(),
    val causedByEventId: String? = null,
)

/**
 * Who can come to know about an event. This is the mechanism that keeps NPC knowledge local: an
 * event with [SECRET] scope reaches nobody except its explicit witnesses, no matter what the
 * narrative says.
 */
enum class KnowledgeScope {
    /** Only the actor knows. */
    PRIVATE,

    /** Only listed witnesses know. */
    SECRET,

    /** Anyone present at the location knows. */
    WITNESSED,

    /** Spreads locally over time as a rumor. */
    LOCAL_RUMOR,

    /** Known to a faction's membership. */
    FACTION,

    /** Everyone in the region knows. */
    PUBLIC,
}

enum class EventType {
    PLAYER_ENTERED_LOCATION,
    PLAYER_LEFT_LOCATION,
    PLAYER_SPOKE_TO_NPC,
    PLAYER_LIED,
    PLAYER_TOLD_TRUTH,
    PLAYER_THREATENED_NPC,
    PLAYER_HELPED_NPC,
    PLAYER_ATTACKED_NPC,
    PLAYER_STOLE_ITEM,
    PLAYER_GAVE_ITEM,
    PLAYER_TOOK_ITEM,
    PLAYER_PAID_NPC,
    PLAYER_RECEIVED_PAYMENT,
    PLAYER_ASKED_ABOUT_RUMORS,
    PLAYER_MADE_PROMISE,
    PLAYER_BROKE_PROMISE,
    PLAYER_WAITED,
    PLAYER_OBSERVED,
    PLAYER_APPEARANCE_CHANGED,
    PLAYER_JOINED_FACTION,
    PLAYER_LEFT_FACTION,

    NPC_BECAME_HOSTILE,
    NPC_BECAME_FRIENDLY,
    NPC_HEARD_RUMOR,
    NPC_LEARNED_FACT,
    NPC_FORMED_BELIEF,
    NPC_MOVED,
    NPC_DIED,
    NPC_INJURED,
    NPC_ACTED_OFFSCREEN,
    NPC_APPEARANCE_CHANGED,
    NPC_INTRODUCED,

    FACTION_HEARD_RUMOR,
    FACTION_ACTED,
    FACTION_STANDING_CHANGED,

    ITEM_OBTAINED,
    ITEM_LOST,
    ITEM_TRANSFERRED,
    ITEM_DESTROYED,
    ITEM_CREATED,

    RELATIONSHIP_CHANGED,
    SECRET_DISCOVERED,
    KNOWLEDGE_TRANSFERRED,

    THREAD_STARTED,
    THREAD_ADVANCED,
    THREAD_STALLED,
    THREAD_RESOLVED,
    THREAD_FAILED,
    THREAD_TRANSFORMED,

    LOCATION_CHANGED,
    LOCATION_DISCOVERED,
    WEATHER_CHANGED,
    TIME_ADVANCED,
    WORLD_EVENT,
    ECONOMY_CHANGED,

    CURRENCY_CHANGED,
    HEALTH_CHANGED,

    CANON_CORRECTED,
    CANON_REVEALED,
    CANON_RETCONNED,

    GAME_STARTED,
    IMAGE_GENERATED,
    OTHER,
}
