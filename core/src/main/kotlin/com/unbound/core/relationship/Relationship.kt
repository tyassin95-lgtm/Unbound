package com.unbound.core.relationship

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Relationships are multidimensional (§32). A single "likes you 62%" number cannot express an NPC
 * who respects the player, fears them, and resents being afraid — which is exactly the state that
 * makes a living world read as alive.
 *
 * Every dimension is clamped to -100..100 except [familiarity], which is 0..100 and never falls.
 */
@Serializable
data class RelationshipVector(
    val trust: Int = 0,
    val respect: Int = 0,
    val affection: Int = 0,
    val fear: Int = 0,
    val suspicion: Int = 0,
    val attraction: Int = 0,
    val loyalty: Int = 0,
    val resentment: Int = 0,
    val hostility: Int = 0,
    val obligation: Int = 0,
    val familiarity: Int = 0,
) {
    fun applyDelta(delta: Map<String, Int>): RelationshipVector = copy(
        trust = clamp(trust + (delta["trust"] ?: 0)),
        respect = clamp(respect + (delta["respect"] ?: 0)),
        affection = clamp(affection + (delta["affection"] ?: 0)),
        fear = clamp(fear + (delta["fear"] ?: 0)),
        suspicion = clamp(suspicion + (delta["suspicion"] ?: 0)),
        attraction = clamp(attraction + (delta["attraction"] ?: 0)),
        loyalty = clamp(loyalty + (delta["loyalty"] ?: 0)),
        resentment = clamp(resentment + (delta["resentment"] ?: 0)),
        hostility = clamp(hostility + (delta["hostility"] ?: 0)),
        obligation = clamp(obligation + (delta["obligation"] ?: 0)),
        familiarity = (familiarity + (delta["familiarity"] ?: 0)).coerceIn(familiarity, 100),
    )

    /** Convenience score for UI only. Never used for logic — the dimensions are authoritative. */
    val overall: Int
        get() = (
            (trust * 0.25 + respect * 0.2 + affection * 0.25 + loyalty * 0.1 -
                resentment * 0.2 - hostility * 0.3 - suspicion * 0.15 + obligation * 0.05)
            ).roundToInt().coerceIn(-100, 100)

    /** Short phrase for prompts and the journal. */
    fun describe(): String {
        val parts = mutableListOf<String>()
        if (hostility >= 40) parts += "hostile"
        else if (overall >= 55) parts += "warm"
        else if (overall >= 20) parts += "friendly"
        else if (overall <= -40) parts += "antagonistic"
        else if (overall <= -15) parts += "cold"
        else parts += "neutral"
        if (trust <= -35) parts += "distrustful"
        if (trust >= 50) parts += "trusting"
        if (fear >= 40) parts += "afraid of you"
        if (suspicion >= 40) parts += "suspicious"
        if (resentment >= 40) parts += "resentful"
        if (obligation >= 40) parts += "indebted"
        if (attraction >= 50) parts += "attracted"
        if (familiarity <= 10) parts += "barely knows you"
        return parts.joinToString(", ")
    }

    private fun clamp(v: Int) = v.coerceIn(-100, 100)

    companion object {
        val STRANGER = RelationshipVector()
    }
}

/**
 * The current relationship between two entities, plus a bounded tail of the reasons it changed so
 * the narrator can say *why* an NPC is cold rather than only *that* they are.
 */
@Serializable
data class RelationshipRecord(
    val id: String,
    val gameId: String,
    val fromEntityId: String,
    val toEntityId: String,
    val vector: RelationshipVector = RelationshipVector.STRANGER,
    val history: List<RelationshipChange> = emptyList(),
    val lastInteractionWorldMinutes: Long = 0,
) {
    companion object {
        const val MAX_HISTORY = 24

        fun key(gameId: String, from: String, to: String) = "rel_${gameId}_${from}_to_$to"

        /**
         * The canonical orientation for a player-character relationship.
         *
         * There is exactly one record per pair, stored as (player -> character), and its vector
         * describes **how that character regards the player**. Everything that writes or reads one
         * must agree on that, because an NPC turning hostile once wrote to (character -> player) —
         * an orientation nothing queried — so the hostility never reached the prompt or the
         * journal and the character went on behaving as though nothing had happened.
         */
        fun canonicalKey(gameId: String, playerId: String, a: String, b: String): Pair<String, String> =
            if (a == playerId) playerId to b else if (b == playerId) playerId to a else a to b
    }
}

@Serializable
data class RelationshipChange(
    val worldMinutes: Long,
    val reason: String,
    val delta: Map<String, Int>,
    val eventId: String? = null,
)
