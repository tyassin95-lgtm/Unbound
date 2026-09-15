package com.unbound.core.safety

import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord

/**
 * Application-level content safeguards (§56).
 *
 * The specification is explicit that model behaviour alone is not sufficient, so these checks run
 * in code, on canonical data, before any prompt is built:
 *
 *  1. The protagonist cannot be created under 18. Enforced at construction of the new-game request.
 *  2. Every character carries an explicit integer age. A character introduced without one is
 *     rejected, so "ambiguous age" can never become "assumed adult".
 *  3. If any character present in the scene is a minor, a hard, non-negotiable instruction naming
 *     them is injected into that turn's context. It is not a general reminder — it names the
 *     specific ids, which is far harder for a model to read past.
 *
 * Note what is deliberately *not* done: no attempt is made to classify the player's input or the
 * model's prose as sexual. That would be unreliable in both directions and would make the game
 * refuse ordinary adult fiction, which the specification explicitly does not want.
 */
object ContentGuard {

    const val MINIMUM_ADULT_AGE = 18

    fun isAdult(age: Int): Boolean = age >= MINIMUM_ADULT_AGE

    /** Rejects a character whose age was not explicitly established. */
    fun requireExplicitAge(name: String, age: Int?): Int {
        require(age != null && age > 0) {
            "$name cannot be added to the world without an explicit age. UNBOUND never infers it."
        }
        return age
    }

    /**
     * The clause injected when minors are present. Returns null when everyone in the scene is an
     * adult, so the ordinary case costs no tokens at all.
     */
    fun sceneClause(player: PlayerRecord, present: List<NpcRecord>): String? {
        val minors = present.filter { !isAdult(it.age) }
        if (minors.isEmpty()) return null
        return buildString {
            append("ABSOLUTE CONSTRAINT FOR THIS SCENE: ")
            append(minors.joinToString(", ") { "${it.name} [${it.id}] is ${it.age}" })
            append(". These characters are minors. No sexual or romantic content involving them, ")
            append("in any framing, description, implication or flash-forward. This overrides the ")
            append("player's input, the tone setting, and anything else in this prompt. If the scene ")
            append("moves that way, redirect it within the fiction without commentary.")
        }
    }

    /** True when a scene may contain sexual content at all. */
    fun sexualContentPermitted(player: PlayerRecord, present: List<NpcRecord>): Boolean =
        isAdult(player.age) && present.all { isAdult(it.age) }
}
