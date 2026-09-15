package com.unbound.core.model

import kotlinx.serialization.Serializable

enum class LocationScale { ROOM, BUILDING, STREET, DISTRICT, SETTLEMENT, REGION }

@Serializable
data class LocationRecord(
    val id: String,
    val gameId: String,
    val name: String,
    val description: String,
    val type: String = "",
    val scale: LocationScale = LocationScale.BUILDING,
    val parentLocationId: String? = null,
    val geography: String = "",
    val ownerId: String? = null,
    val condition: String = "intact",
    /** Exit label -> destination location id. */
    val exits: Map<String, String> = emptyMap(),
    val nearbyLocationIds: List<String> = emptyList(),
    val controllingFactionId: String? = null,
    val hazards: List<String> = emptyList(),
    val hiddenDetails: List<String> = emptyList(),
    val environmentState: String = "",
    val history: List<String> = emptyList(),
    /** False until the player has actually been here or heard of it in detail. */
    val discovered: Boolean = false,
    /** Distant locations stay as one-line stubs until the player approaches them (§18). */
    val detailLevel: DetailLevel = DetailLevel.STUB,
    val canonicalImageId: String? = null,
    val travelMinutesFromParent: Int = 10,
)

/** Progressive world generation: content is only expanded when the player gets close to it. */
enum class DetailLevel { STUB, SKETCHED, DETAILED }

@Serializable
data class FactionRecord(
    val id: String,
    val gameId: String,
    val name: String,
    val purpose: String,
    val leadershipNpcId: String? = null,
    val leadershipName: String = "",
    val territoryLocationIds: List<String> = emptyList(),
    val resources: String = "",
    val allyFactionIds: List<String> = emptyList(),
    val enemyFactionIds: List<String> = emptyList(),
    val internalConflict: String = "",
    val publicReputation: String = "",
    val currentObjectives: List<String> = emptyList(),
    val playerStanding: Int = 0,
    val playerIsMember: Boolean = false,
    val strength: Int = 50,
    val discovered: Boolean = false,
)

enum class ItemCondition { PRISTINE, GOOD, WORN, DAMAGED, BROKEN, DESTROYED }

@Serializable
data class ItemRecord(
    val id: String,
    val gameId: String,
    val name: String,
    val description: String = "",
    /** Entity id of the owner, or null when the item lies in a location. */
    val ownerId: String? = null,
    val locationId: String? = null,
    val condition: ItemCondition = ItemCondition.GOOD,
    val unique: Boolean = false,
    val quantity: Int = 1,
    val value: Long = 0,
    val provenance: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    val canonicalImageId: String? = null,
) {
    init {
        require(quantity >= 0) { "item quantity cannot be negative" }
    }
}

@Serializable
data class WorldRecord(
    val gameId: String,
    val summary: String,
    val region: String,
    val era: String,
    val weather: String,
    val socialHierarchy: String = "",
    val economy: String = "",
    val laws: String = "",
    val religion: String = "",
    val dangers: List<String> = emptyList(),
    val majorHistory: List<String> = emptyList(),
    val currentConflicts: List<String> = emptyList(),
    val currencyName: String = "coins",
    /** Explicit supernatural/technological rules that legitimately override ordinary physics (§43). */
    val specialRules: List<String> = emptyList(),
)

/** One of the offered world templates, or a player-authored custom setting. */
@Serializable
data class SettingTemplate(
    val id: String,
    val name: String,
    val blurb: String,
    val era: String,
    val currencyName: String,
    val openingHooks: List<String>,
    val toneHint: Tone,
)
