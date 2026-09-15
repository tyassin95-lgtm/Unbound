package com.unbound.core.model

import kotlinx.serialization.Serializable

/**
 * NPC persistence tiers (§92). Ambient NPCs cost nothing until they matter; promotion is one-way
 * and driven by actual interaction, so a crowd never becomes a thousand tracked entities.
 */
enum class NpcTier { AMBIENT, SEMI_PERSISTENT, PERSISTENT }

@Serializable
data class DailySchedule(
    /** Hour-of-day (0..23) to location id. Sparse: unlisted hours fall through to [defaultLocationId]. */
    val byHour: Map<Int, String> = emptyMap(),
    val defaultLocationId: String? = null,
) {
    fun locationAt(hour: Int): String? = byHour[hour] ?: defaultLocationId
}

@Serializable
data class EmotionalState(
    val mood: String = "neutral",
    val stress: Int = 0,
    val lastChangedAt: Long = 0,
)

/**
 * A persistent character. What an NPC *knows* is not stored here — it lives in the knowledge table
 * scoped to this NPC, so an NPC can never accidentally read world truth (§24).
 */
@Serializable
data class NpcRecord(
    val id: String,
    val gameId: String,
    val name: String,
    val age: Int,
    val gender: String = "",
    val appearance: Appearance,
    val occupation: String = "",
    val personality: String = "",
    val values: List<String> = emptyList(),
    val fears: List<String> = emptyList(),
    val desires: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val factionId: String? = null,
    val currentLocationId: String,
    val homeLocationId: String? = null,
    val schedule: DailySchedule = DailySchedule(),
    val inventory: List<String> = emptyList(),
    val currency: Long = 0,
    val body: BodyState = BodyState(),
    val emotion: EmotionalState = EmotionalState(),
    val currentPlan: String = "",
    val alive: Boolean = true,
    val tier: NpcTier = NpcTier.PERSISTENT,
    val firstEncounteredTurn: Int? = null,
    val lastSeenTurn: Int? = null,
    val lastSeenWorldMinutes: Long? = null,
    val canonicalImageId: String? = null,
    val introduced: Boolean = false,
)
