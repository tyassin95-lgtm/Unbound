package com.unbound.core.relationship

import com.unbound.core.ledger.EventType

/**
 * Applies relationship deltas and, where the model supplied none, derives a sensible default from
 * the event type. Deterministic defaults matter for cost: routine social events do not need the
 * model to spell out their consequences (§66).
 */
class RelationshipEngine {

    fun apply(
        record: RelationshipRecord,
        delta: Map<String, Int>,
        reason: String,
        worldMinutes: Long,
        eventId: String? = null,
    ): RelationshipRecord {
        if (delta.isEmpty()) return record
        val history = (record.history + RelationshipChange(worldMinutes, reason, delta, eventId))
            .takeLast(RelationshipRecord.MAX_HISTORY)
        return record.copy(
            vector = record.vector.applyDelta(delta),
            history = history,
            lastInteractionWorldMinutes = worldMinutes,
        )
    }

    /** Baseline consequences for events whose social meaning is unambiguous. */
    fun defaultDeltaFor(type: EventType): Map<String, Int> = when (type) {
        EventType.PLAYER_SPOKE_TO_NPC -> mapOf("familiarity" to 4)
        EventType.PLAYER_HELPED_NPC -> mapOf("trust" to 8, "affection" to 6, "obligation" to 6, "familiarity" to 5)
        EventType.PLAYER_GAVE_ITEM -> mapOf("affection" to 6, "obligation" to 8, "familiarity" to 3)
        EventType.PLAYER_PAID_NPC -> mapOf("obligation" to 4, "familiarity" to 3)
        EventType.PLAYER_LIED -> mapOf("familiarity" to 2)
        EventType.PLAYER_TOLD_TRUTH -> mapOf("trust" to 4, "familiarity" to 3)
        EventType.PLAYER_THREATENED_NPC -> mapOf("fear" to 18, "trust" to -12, "resentment" to 14, "familiarity" to 4)
        EventType.PLAYER_ATTACKED_NPC -> mapOf("fear" to 28, "hostility" to 30, "trust" to -30, "resentment" to 25, "familiarity" to 5)
        EventType.PLAYER_STOLE_ITEM -> mapOf("trust" to -20, "resentment" to 18, "suspicion" to 20)
        EventType.PLAYER_BROKE_PROMISE -> mapOf("trust" to -22, "resentment" to 16, "respect" to -10)
        EventType.PLAYER_MADE_PROMISE -> mapOf("obligation" to -4, "familiarity" to 2)
        EventType.SECRET_DISCOVERED -> mapOf("suspicion" to 10)
        else -> emptyMap()
    }

    /**
     * Relationships drift toward neutral when nothing happens, but only the volatile dimensions.
     * Trust and resentment are deliberately sticky: the point of the product is that people
     * remember, so a betrayal must not quietly evaporate because the player stayed away.
     */
    fun decay(record: RelationshipRecord, nowWorldMinutes: Long): RelationshipRecord {
        val days = (nowWorldMinutes - record.lastInteractionWorldMinutes) / (60.0 * 24)
        if (days < 7) return record
        val steps = (days / 7).toInt().coerceAtMost(8)
        val v = record.vector
        fun toward(value: Int, rate: Int) = when {
            value > 0 -> (value - rate * steps).coerceAtLeast(0)
            value < 0 -> (value + rate * steps).coerceAtMost(0)
            else -> 0
        }
        return record.copy(
            vector = v.copy(
                fear = toward(v.fear, 2),
                hostility = toward(v.hostility, 1),
                suspicion = toward(v.suspicion, 1),
                attraction = toward(v.attraction, 1),
                // trust, respect, affection, resentment, loyalty, obligation and familiarity persist.
            ),
        )
    }
}
