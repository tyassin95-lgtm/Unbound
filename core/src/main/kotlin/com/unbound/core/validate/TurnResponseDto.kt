package com.unbound.core.validate

import com.unbound.core.knowledge.Certainty
import com.unbound.core.ledger.EventType
import com.unbound.core.ledger.KnowledgeScope
import com.unbound.core.model.Importance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exactly what the model is permitted to return (§41). Everything is optional with a safe default
 * so that a model that omits a field produces a smaller turn rather than a failed one; what is not
 * permitted is *extra* freedom — unknown enum values are rejected by the validator, not coerced.
 *
 * Note what is absent: there is no field by which the model can set an absolute value for currency,
 * health or ownership. It may only describe *deltas and transfers*, which the validator then checks
 * against canonical state. This is the structural reason the model cannot invent 500 gold.
 */
@Serializable
data class TurnResponseDto(
    val narrative: String = "",
    @SerialName("time_advance_minutes") val timeAdvanceMinutes: Int = 0,
    val events: List<EventDto> = emptyList(),
    @SerialName("state_changes") val stateChanges: List<StateChangeDto> = emptyList(),
    @SerialName("knowledge_changes") val knowledgeChanges: List<KnowledgeChangeDto> = emptyList(),
    @SerialName("relationship_changes") val relationshipChanges: List<RelationshipChangeDto> = emptyList(),
    @SerialName("memory_candidates") val memoryCandidates: List<MemoryCandidateDto> = emptyList(),
    @SerialName("thread_changes") val threadChanges: List<ThreadChangeDto> = emptyList(),
    @SerialName("npc_actions") val npcActions: List<NpcActionDto> = emptyList(),
    @SerialName("world_changes") val worldChanges: List<WorldChangeDto> = emptyList(),
    @SerialName("new_characters") val newCharacters: List<NewCharacterDto> = emptyList(),
    @SerialName("suggested_actions") val suggestedActions: List<String> = emptyList(),
    @SerialName("scene_is_significant") val sceneIsSignificant: Boolean = false,
    /**
     * What the protagonist said aloud this turn, copied verbatim from [narrative].
     *
     * This exists so the app can colour the player's own words without having to guess which of
     * several quoted lines were theirs. Because the model produces the same string twice, the
     * renderer can match it exactly rather than parsing attribution, which is why this is a field
     * rather than an inline marker that could be mangled.
     */
    @SerialName("player_dialogue") val playerDialogue: List<String> = emptyList(),
)

@Serializable
data class EventDto(
    val type: String,
    @SerialName("actor_id") val actorId: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    val summary: String = "",
    val importance: String = Importance.LOW.name,
    @SerialName("knowledge_scope") val knowledgeScope: String = KnowledgeScope.WITNESSED.name,
    @SerialName("witness_ids") val witnessIds: List<String> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
) {
    fun eventType(): EventType? = EventType.entries.firstOrNull { it.name == type.uppercase() }
    fun importanceOrNull(): Importance? = Importance.entries.firstOrNull { it.name == importance.uppercase() }
    fun scopeOrNull(): KnowledgeScope? = KnowledgeScope.entries.firstOrNull { it.name == knowledgeScope.uppercase() }
}

@Serializable
data class StateChangeDto(
    val type: String,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    val amount: Int = 0,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("location_id") val locationId: String? = null,
    val value: String? = null,
    val changes: Map<String, Int> = emptyMap(),
    val reason: String = "",
)

@Serializable
data class KnowledgeChangeDto(
    @SerialName("knower_id") val knowerId: String,
    @SerialName("fact_key") val factKey: String,
    val statement: String,
    val certainty: String = Certainty.KNOWN.name,
    @SerialName("source_entity_id") val sourceEntityId: String? = null,
    @SerialName("subject_entity_ids") val subjectEntityIds: List<String> = emptyList(),
    val secret: Boolean = false,
    @SerialName("is_distorted") val isDistorted: Boolean = false,
) {
    fun certaintyOrNull(): Certainty? = Certainty.entries.firstOrNull { it.name == certainty.uppercase() }
}

@Serializable
data class RelationshipChangeDto(
    @SerialName("from_entity_id") val fromEntityId: String = "player",
    @SerialName("to_entity_id") val toEntityId: String,
    val changes: Map<String, Int> = emptyMap(),
    val reason: String = "",
)

@Serializable
data class MemoryCandidateDto(
    @SerialName("owner_id") val ownerId: String,
    val text: String,
    @SerialName("entity_ids") val entityIds: List<String> = emptyList(),
    val importance: String = Importance.MEDIUM.name,
    val confidence: Double = 1.0,
)

@Serializable
data class ThreadChangeDto(
    @SerialName("thread_id") val threadId: String? = null,
    val action: String,
    val title: String = "",
    val description: String = "",
    val type: String = "OTHER",
    val stakes: String = "",
    @SerialName("involved_entity_ids") val involvedEntityIds: List<String> = emptyList(),
    val importance: String = Importance.MEDIUM.name,
    @SerialName("deadline_in_minutes") val deadlineInMinutes: Int? = null,
)

@Serializable
data class NpcActionDto(
    @SerialName("npc_id") val npcId: String,
    val action: String,
    @SerialName("moves_to_location_id") val movesToLocationId: String? = null,
    @SerialName("becomes_hostile") val becomesHostile: Boolean = false,
)

/**
 * A person who has just become worth remembering. Age is required and must be an explicit integer;
 * the content guard rejects anything else rather than inferring adulthood (§56).
 */
@Serializable
data class NewCharacterDto(
    val name: String,
    val age: Int,
    val gender: String = "",
    val appearance: String,
    val occupation: String = "",
    val personality: String = "",
    val wants: String = "",
    @SerialName("location_id") val locationId: String? = null,
)

@Serializable
data class WorldChangeDto(
    val type: String,
    @SerialName("location_id") val locationId: String? = null,
    @SerialName("faction_id") val factionId: String? = null,
    val value: String = "",
    val description: String = "",
)
