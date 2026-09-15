package com.unbound.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical physical description. Frozen at character lock and only ever changed through an
 * explicit appearance-change event, so image generation has one stable source of truth.
 */
@Serializable
data class Appearance(
    val summary: String,
    val face: String = "",
    val skin: String = "",
    val hair: String = "",
    val eyes: String = "",
    val build: String = "",
    val notableMarks: List<String> = emptyList(),
    val scars: List<String> = emptyList(),
    val tattoos: List<String> = emptyList(),
    val clothing: String = "",
    val signatureEquipment: List<String> = emptyList(),
    /** Incremented whenever the canonical appearance changes; previous versions are kept in history. */
    val version: Int = 1,
) {
    /** Concrete visual facts only — no mood words. Consumed by the image prompt builder. */
    fun visualFacts(): List<String> = buildList {
        if (face.isNotBlank()) add(face)
        if (skin.isNotBlank()) add("$skin skin")
        if (hair.isNotBlank()) add("$hair hair")
        if (eyes.isNotBlank()) add("$eyes eyes")
        if (build.isNotBlank()) add("$build build")
        addAll(scars.map { "scar: $it" })
        addAll(tattoos.map { "tattoo: $it" })
        addAll(notableMarks)
        if (clothing.isNotBlank()) add("wearing $clothing")
        addAll(signatureEquipment.map { "carries $it" })
        if (isEmpty()) add(summary)
    }
}

@Serializable
data class BodyState(
    val health: Int = 100,
    val maxHealth: Int = 100,
    val fatigue: Int = 0,
    val hunger: Int = 0,
    val injuries: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
) {
    val isAlive: Boolean get() = health > 0
    fun describe(): String = buildString {
        append(
            when {
                health >= 90 -> "unhurt"
                health >= 70 -> "bruised"
                health >= 45 -> "hurt"
                health >= 20 -> "badly hurt"
                health > 0 -> "close to death"
                else -> "dead"
            }
        )
        if (injuries.isNotEmpty()) append(" (${injuries.joinToString(", ")})")
        if (fatigue >= 70) append(", exhausted")
        if (hunger >= 70) append(", starving")
    }
}

/**
 * The protagonist. Age is stored explicitly and separately from any descriptive text, because the
 * adult-content gate reads this field and nothing else.
 */
@Serializable
data class PlayerRecord(
    val id: String = Ids.PLAYER,
    val gameId: String,
    val name: String,
    val age: Int,
    val gender: String,
    val appearance: Appearance,
    val personality: String,
    val desires: String = "",
    val fears: String = "",
    val skills: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val background: String = "",
    val goals: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val currency: Long = 0,
    val currencyName: String = "coins",
    val body: BodyState = BodyState(),
    val currentLocationId: String,
    val reputation: Map<String, Int> = emptyMap(),
    val canonicalImageId: String? = null,
)

/** Player-authored preset characters offered at creation. Examples, never a whitelist. */
@Serializable
data class CharacterTemplate(
    val id: String,
    val name: String,
    val age: Int,
    val gender: String,
    val tagline: String,
    val appearance: Appearance,
    val personality: String,
    val desires: String,
    val fears: String,
    val skills: List<String>,
    val weaknesses: List<String>,
    val background: String,
    val goals: List<String>,
    val secrets: List<String>,
    val startingCurrency: Long,
)
