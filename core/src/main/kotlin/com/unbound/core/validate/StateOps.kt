package com.unbound.core.validate

/** The complete set of mutations the model is allowed to request. Anything else is rejected. */
sealed interface StateOp {
    data class CurrencyChange(val entityId: String, val amount: Int, val reason: String) : StateOp
    data class HealthChange(val entityId: String, val amount: Int, val reason: String) : StateOp
    data class ItemTransfer(val itemId: String, val fromEntityId: String?, val toEntityId: String?, val toLocationId: String?, val reason: String) : StateOp
    data class ItemCreate(val itemName: String, val ownerId: String?, val locationId: String?, val reason: String) : StateOp
    data class ItemDestroy(val itemId: String, val reason: String) : StateOp
    data class PlayerMove(val locationId: String) : StateOp
    data class NpcMove(val npcId: String, val locationId: String) : StateOp
    data class NpcDeath(val npcId: String, val reason: String) : StateOp
    data class NpcEmotion(val npcId: String, val mood: String, val stress: Int) : StateOp
    data class RelationshipChange(val fromEntityId: String, val toEntityId: String, val changes: Map<String, Int>, val reason: String) : StateOp
    data class FactionStanding(val factionId: String, val amount: Int, val reason: String) : StateOp
    data class FactionMembership(val factionId: String, val joining: Boolean) : StateOp
    data class AppearanceChange(val entityId: String, val description: String, val reason: String) : StateOp
    data class LocationCondition(val locationId: String, val condition: String, val description: String) : StateOp
    data class WeatherChange(val weather: String) : StateOp
    data class PlayerGoal(val goal: String) : StateOp
}

/** A single reason a patch element was refused, kept for the diagnostics screen. */
data class ValidationIssue(
    val code: String,
    val detail: String,
    val fatal: Boolean,
)

/**
 * Result of validating a model response.
 *
 * Every list here is what *survived* validation, and the pipeline must apply these rather than the
 * raw response. That distinction was once only half-honoured: state ops were filtered, but events
 * and knowledge changes were validated and then applied unfiltered anyway, so an NPC flagged as
 * unable to know something learned it regardless. A rejection that does not remove anything is not
 * a rejection.
 *
 * Non-fatal issues drop the offending element and keep the turn; fatal issues fail the whole turn
 * so nothing is partially committed.
 */
data class ValidationResult(
    val ops: List<StateOp>,
    val issues: List<ValidationIssue>,
    /** Knowledge assignments that may be written. */
    val knowledgeChanges: List<KnowledgeChangeDto> = emptyList(),
    /** Events that may be appended, with unresolvable references stripped. */
    val events: List<EventDto> = emptyList(),
    /** NPC actions that may be applied. */
    val npcActions: List<NpcActionDto> = emptyList(),
) {
    val hasFatal: Boolean get() = issues.any { it.fatal }
    val rejectedCount: Int get() = issues.size
}
