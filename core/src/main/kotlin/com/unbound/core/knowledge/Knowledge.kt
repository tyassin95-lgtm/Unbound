package com.unbound.core.knowledge

import com.unbound.core.model.Importance
import kotlinx.serialization.Serializable

/**
 * How firmly a knower holds a piece of information. The distinction between [KNOWN] and the weaker
 * grades is what stops the narrator manufacturing certainty: an NPC who only [SUSPECTED] something
 * may accuse, but may not state it as fact.
 */
@Serializable
enum class Certainty {
    /** Directly witnessed or personally verified. */
    KNOWN,

    /** Told by someone trusted, not verified. */
    BELIEVED,

    /** Heard secondhand. May be distorted. */
    RUMORED,

    /** Inferred, no evidence. */
    SUSPECTED,

    /** Held as true but actually false. */
    MISTAKEN,

    /** Explicitly aware of not knowing. */
    UNKNOWN,
}

/**
 * One knower's view of one fact. There is no global "truth" row here — objective truth lives in the
 * canonical entity tables and the event ledger. This table only ever answers "what does X think?".
 *
 * [knowerId] may be the player, an NPC id, or a faction id.
 */
@Serializable
data class KnowledgeRecord(
    val id: String,
    val gameId: String,
    val knowerId: String,
    /** Stable key identifying the proposition, e.g. "npc_mara.ring_stolen_by_player". */
    val factKey: String,
    val statement: String,
    val certainty: Certainty,
    /** True when the held statement diverges from canonical truth (a genuine misunderstanding). */
    val isDistorted: Boolean = false,
    val subjectEntityIds: List<String> = emptyList(),
    val learnedFromEventId: String? = null,
    /** Entity the knower heard it from, when secondhand. */
    val sourceEntityId: String? = null,
    val learnedAtWorldMinutes: Long,
    val importance: Importance = Importance.MEDIUM,
    /** A secret will not propagate as a rumor even if the location is crowded. */
    val secret: Boolean = false,
)

/** A piece of information in circulation, independent of any single knower. */
@Serializable
data class RumorRecord(
    val id: String,
    val gameId: String,
    val factKey: String,
    val statement: String,
    /** How far the distorted version has drifted from truth, 0..100. */
    val distortion: Int = 0,
    val originEventId: String? = null,
    val spreadLocationIds: List<String> = emptyList(),
    val spreadFactionIds: List<String> = emptyList(),
    val knownByEntityIds: List<String> = emptyList(),
    val createdAtWorldMinutes: Long,
    val lastSpreadWorldMinutes: Long,
    /** 0..100. Decays over time; a dead rumor stops spreading. */
    val virality: Int = 50,
)
