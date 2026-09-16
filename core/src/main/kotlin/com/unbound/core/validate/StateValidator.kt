package com.unbound.core.validate

import com.unbound.core.model.EntityKind
import com.unbound.core.model.FactionRecord
import com.unbound.core.model.GameRecord
import com.unbound.core.model.Ids
import com.unbound.core.model.ItemRecord
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.WorldRecord

/**
 * Everything the validator is allowed to consult. The pipeline pre-loads exactly the entities the
 * response referenced, so validation never needs to page the whole world into memory.
 */
data class ValidationContext(
    val game: GameRecord,
    val player: PlayerRecord,
    val world: WorldRecord,
    val npcs: Map<String, NpcRecord>,
    val locations: Map<String, LocationRecord>,
    val factions: Map<String, FactionRecord>,
    val items: Map<String, ItemRecord>,
    /** Location ids reachable from the player's current location in one move. */
    val reachableLocationIds: Set<String>,
    /** True when the world's own rules permit something ordinary physics would not. */
    val specialRules: List<String> = emptyList(),
    /**
     * Ids of entities that exist in this campaign but were not retrieved for this turn.
     *
     * Existence and relevance are different questions. The maps above are a *retrieval budget* —
     * what was worth sending to the model — and treating absence from them as non-existence made
     * the validator reject perfectly legitimate references, such as telling a guard across town
     * something with a named source.
     */
    val knownEntityIds: Set<String> = emptySet(),
    /** Obligations still outstanding, so a turn cannot quietly settle one that was never made. */
    val openCommitments: List<com.unbound.core.continuity.CommitmentRecord> = emptyList(),
) {
    fun entityExists(id: String): Boolean = when {
        id == Ids.PLAYER || id == player.id -> true
        npcs.containsKey(id) -> true
        locations.containsKey(id) -> true
        factions.containsKey(id) -> true
        items.containsKey(id) -> true
        else -> id in knownEntityIds
    }

    fun allowsSupernaturalMovement(): Boolean =
        specialRules.any { it.contains("teleport", true) || it.contains("fast travel", true) || it.contains("portal", true) }
}

/**
 * Deterministic gatekeeper between the model and the database (§43).
 *
 * Design rule: **prefer dropping one element over failing the turn.** A model that invents a single
 * bad relationship target should not cost the player their action. Only a response that is
 * structurally unusable — no narrative at all, or time running backwards — fails outright, because
 * committing that would corrupt canonical state.
 */
class StateValidator(
    private val config: ValidatorConfig = ValidatorConfig(),
) {

    fun validate(response: TurnResponseDto, ctx: ValidationContext): ValidationResult {
        val ops = mutableListOf<StateOp>()
        val issues = mutableListOf<ValidationIssue>()

        if (response.narrative.isBlank()) {
            issues += ValidationIssue("EMPTY_NARRATIVE", "The model returned no narrative.", fatal = true)
        }
        if (response.timeAdvanceMinutes < 0) {
            issues += ValidationIssue(
                "TIME_REVERSAL",
                "Requested time advance of ${response.timeAdvanceMinutes} minutes; world time only moves forward.",
                fatal = true,
            )
        }
        if (response.timeAdvanceMinutes > config.maxTimeAdvanceMinutes) {
            issues += ValidationIssue(
                "TIME_JUMP_TOO_LARGE",
                "Requested ${response.timeAdvanceMinutes} minutes; clamped to ${config.maxTimeAdvanceMinutes}.",
                fatal = false,
            )
        }

        // Track currency and item movement across the whole patch so that two changes that are
        // individually legal but jointly impossible (spending the same coin twice, moving the same
        // unique item to two owners) are still caught.
        var projectedCurrency = ctx.player.currency
        val itemMovedTo = mutableMapOf<String, String>()
        val deadNpcs = ctx.npcs.filterValues { !it.alive }.keys.toMutableSet()

        for (change in response.stateChanges) {
            val op = parseAndCheck(change, ctx, issues, deadNpcs) ?: continue

            when (op) {
                is StateOp.CurrencyChange -> {
                    if (op.entityId == ctx.player.id || op.entityId == Ids.PLAYER) {
                        val next = projectedCurrency + op.amount
                        if (next < 0 && !config.allowNegativeCurrency) {
                            issues += ValidationIssue(
                                "INSUFFICIENT_FUNDS",
                                "Change of ${op.amount} would take the player to $next ${ctx.world.currencyName}; " +
                                    "they hold $projectedCurrency. Rejected.",
                                fatal = false,
                            )
                            continue
                        }
                        projectedCurrency = next
                    }
                }

                is StateOp.ItemTransfer -> {
                    val item = ctx.items[op.itemId]
                    if (item == null) {
                        issues += ValidationIssue("UNKNOWN_ITEM", "No item ${op.itemId} exists.", fatal = false)
                        continue
                    }
                    if (item.condition == com.unbound.core.model.ItemCondition.DESTROYED) {
                        issues += ValidationIssue("DESTROYED_ITEM", "${item.name} has been destroyed and cannot change hands.", fatal = false)
                        continue
                    }
                    if (op.fromEntityId != null && item.ownerId != null && item.ownerId != op.fromEntityId) {
                        issues += ValidationIssue(
                            "WRONG_OWNER",
                            "${item.name} belongs to ${item.ownerId}, not ${op.fromEntityId}.",
                            fatal = false,
                        )
                        continue
                    }
                    val destination = op.toEntityId ?: op.toLocationId
                    val already = itemMovedTo[op.itemId]
                    if (item.unique && already != null && already != destination) {
                        issues += ValidationIssue(
                            "ITEM_DUPLICATION",
                            "Unique item ${item.name} cannot move to both $already and $destination in one turn.",
                            fatal = false,
                        )
                        continue
                    }
                    if (destination != null) itemMovedTo[op.itemId] = destination
                }

                is StateOp.ItemCreate -> {
                    val duplicate = ctx.items.values.firstOrNull {
                        it.unique && it.name.equals(op.itemName, ignoreCase = true)
                    }
                    if (duplicate != null) {
                        issues += ValidationIssue(
                            "DUPLICATE_UNIQUE_ITEM",
                            "A unique item named ${op.itemName} already exists (${duplicate.id}).",
                            fatal = false,
                        )
                        continue
                    }
                }

                is StateOp.PlayerMove -> {
                    if (!ctx.locations.containsKey(op.locationId)) {
                        issues += ValidationIssue("UNKNOWN_LOCATION", "No location ${op.locationId} exists.", fatal = false)
                        continue
                    }
                    val adjacent = op.locationId in ctx.reachableLocationIds
                    if (!adjacent && !ctx.allowsSupernaturalMovement() && config.enforceAdjacentTravel) {
                        // Not fatal: long journeys are legitimate, they simply must cost time.
                        if (response.timeAdvanceMinutes < config.minMinutesForDistantTravel) {
                            issues += ValidationIssue(
                                "TELEPORTATION",
                                "Move to ${op.locationId} is not adjacent to ${ctx.player.currentLocationId} " +
                                    "and only ${response.timeAdvanceMinutes} minutes were spent.",
                                fatal = false,
                            )
                            continue
                        }
                    }
                }

                is StateOp.NpcMove -> {
                    if (!ctx.locations.containsKey(op.locationId)) {
                        issues += ValidationIssue("UNKNOWN_LOCATION", "No location ${op.locationId} exists.", fatal = false)
                        continue
                    }
                }

                is StateOp.NpcDeath -> deadNpcs += op.npcId

                is StateOp.HealthChange -> {
                    val target = if (op.entityId == Ids.PLAYER || op.entityId == ctx.player.id) null else ctx.npcs[op.entityId]
                    if (target != null && !target.alive) {
                        issues += ValidationIssue("DEAD_ENTITY", "${target.name} is dead and cannot change health.", fatal = false)
                        continue
                    }
                    if (kotlin.math.abs(op.amount) > config.maxHealthSwing) {
                        issues += ValidationIssue(
                            "IMPLAUSIBLE_HEALTH_SWING",
                            "Health change of ${op.amount} exceeds the per-turn limit of ${config.maxHealthSwing}.",
                            fatal = false,
                        )
                        continue
                    }
                }

                is StateOp.RelationshipChange -> {
                    if (op.reason.isBlank() && config.requireRelationshipReason) {
                        issues += ValidationIssue(
                            "UNEXPLAINED_RELATIONSHIP_CHANGE",
                            "Relationship ${op.fromEntityId} -> ${op.toEntityId} changed with no stated reason.",
                            fatal = false,
                        )
                        continue
                    }
                    val bad = op.changes.keys.filterNot { it in RELATIONSHIP_DIMENSIONS }
                    if (bad.isNotEmpty()) {
                        issues += ValidationIssue("UNKNOWN_DIMENSION", "Unknown relationship dimensions: $bad.", fatal = false)
                        continue
                    }
                    val excessive = op.changes.values.any { kotlin.math.abs(it) > config.maxRelationshipSwing }
                    if (excessive) {
                        issues += ValidationIssue(
                            "IMPLAUSIBLE_RELATIONSHIP_SWING",
                            "A single turn cannot move a relationship dimension by more than ${config.maxRelationshipSwing}.",
                            fatal = false,
                        )
                        continue
                    }
                }

                else -> Unit
            }
            ops += op
        }

        // Dead NPCs must not also be given actions.
        val acceptedActions = mutableListOf<NpcActionDto>()
        for (action in response.npcActions) {
            val npc = ctx.npcs[action.npcId]
            when {
                npc == null ->
                    issues += ValidationIssue("UNKNOWN_NPC", "Action for unknown NPC ${action.npcId}.", fatal = false)
                !npc.alive || action.npcId in deadNpcs ->
                    issues += ValidationIssue("DEAD_NPC_ACTING", "${npc.name} is dead but was given an action.", fatal = false)
                else -> acceptedActions += action
            }
        }

        val acceptedEvents = mutableListOf<EventDto>()
        for (event in response.events) {
            if (event.eventType() == null) {
                issues += ValidationIssue("UNKNOWN_EVENT_TYPE", "Unrecognised event type '${event.type}'.", fatal = false)
                continue
            }
            // A dangling reference is stripped rather than costing the whole event: the summary is
            // still true history, it just cannot be attributed to somebody who does not exist.
            var cleaned = event
            listOfNotNull(event.actorId, event.targetId).forEach { id ->
                if (!ctx.entityExists(id)) {
                    issues += ValidationIssue("DANGLING_REFERENCE", "Event references unknown entity $id.", fatal = false)
                    if (cleaned.actorId == id) cleaned = cleaned.copy(actorId = null)
                    if (cleaned.targetId == id) cleaned = cleaned.copy(targetId = null)
                }
            }
            acceptedEvents += cleaned
        }

        val sceneParticipants = ScenePresence.participants(
            declaredPresentIds = response.presentCharacterIds,
            npcActions = acceptedActions,
            events = acceptedEvents,
            playerLocationId = ctx.player.currentLocationId,
            knownNpcIds = ctx.npcs.keys,
            storedPresentIds = ctx.npcs.values
                .filter { it.currentLocationId == ctx.player.currentLocationId }
                .map { it.id }
                .toSet(),
        )

        val acceptedKnowledge = mutableListOf<KnowledgeChangeDto>()
        for (k in response.knowledgeChanges) {
            if (!ctx.entityExists(k.knowerId)) {
                issues += ValidationIssue("UNKNOWN_KNOWER", "Knowledge assigned to unknown entity ${k.knowerId}.", fatal = false)
                continue
            }
            // A character cannot learn something first-hand unless they were in the room. With a
            // source named, they were told, and where they stand is irrelevant.
            //
            // Presence is judged against the scene the narrator just described, not against a
            // stored location that may predate the character walking over — rejecting knowledge for
            // someone demonstrably standing in the room was the bug this check once caused.
            val isCharacter = Ids.kindOf(k.knowerId) == EntityKind.NPC
            val firstHand = k.sourceEntityId == null &&
                k.certaintyOrNull() == com.unbound.core.knowledge.Certainty.KNOWN
            if (isCharacter && firstHand && k.knowerId !in sceneParticipants) {
                val who = ctx.npcs[k.knowerId]?.name ?: k.knowerId
                issues += ValidationIssue(
                    "IMPOSSIBLE_KNOWLEDGE",
                    "$who is not in this scene and no source was named, so they cannot come to " +
                        "know '${k.factKey}' first-hand.",
                    fatal = false,
                )
                continue
            }
            acceptedKnowledge += k
        }

        // Obligations. The world may only settle one that actually stands, and may only bind
        // parties it can name — otherwise a turn can quietly discharge a debt by asserting it did.
        val acceptedCommitments = mutableListOf<CommitmentChangeDto>()
        val openById = ctx.openCommitments.associateBy { it.id }
        for (change in response.commitmentChanges) {
            if (change.isOpening()) {
                if (change.kindOrNull() == null) {
                    issues += ValidationIssue("UNKNOWN_COMMITMENT_KIND", "No such kind of obligation: ${change.kind}", fatal = false)
                    continue
                }
                if (change.terms.isBlank()) {
                    issues += ValidationIssue("EMPTY_COMMITMENT", "An obligation with no terms cannot be held to.", fatal = false)
                    continue
                }
                val from = change.fromEntityId ?: Ids.PLAYER
                val to = change.toEntityId
                if (to == null) {
                    issues += ValidationIssue("DANGLING_COMMITMENT", "An obligation must be owed to someone.", fatal = false)
                    continue
                }
                if (!ctx.entityExists(from) || !ctx.entityExists(to)) {
                    issues += ValidationIssue(
                        "DANGLING_COMMITMENT",
                        "Obligation between unknown parties: $from -> $to",
                        fatal = false,
                    )
                    continue
                }
                acceptedCommitments += change
            } else {
                val id = change.commitmentId
                val existing = id?.let { openById[it] }
                if (existing == null) {
                    // The most damaging version of this is a turn declaring a debt settled that
                    // the world never recorded, which would erase an obligation by inventing it.
                    issues += ValidationIssue(
                        "NO_SUCH_COMMITMENT",
                        "Cannot ${change.action.lowercase()} an obligation that is not open: $id",
                        fatal = false,
                    )
                    continue
                }
                if (change.closingStatus() == null) {
                    issues += ValidationIssue("UNKNOWN_COMMITMENT_ACTION", "No such action: ${change.action}", fatal = false)
                    continue
                }
                acceptedCommitments += change
            }
        }

        return ValidationResult(ops, issues, acceptedKnowledge, acceptedEvents, acceptedActions, acceptedCommitments)
    }

    private fun parseAndCheck(
        change: StateChangeDto,
        ctx: ValidationContext,
        issues: MutableList<ValidationIssue>,
        deadNpcs: Set<String>,
    ): StateOp? {
        fun requireEntity(id: String?, label: String): String? {
            if (id == null) {
                issues += ValidationIssue("MISSING_FIELD", "${change.type} requires $label.", fatal = false)
                return null
            }
            if (!ctx.entityExists(id)) {
                issues += ValidationIssue("DANGLING_REFERENCE", "${change.type} references unknown entity $id.", fatal = false)
                return null
            }
            return id
        }

        return when (change.type.uppercase()) {
            "CURRENCY_CHANGE" -> StateOp.CurrencyChange(requireEntity(change.entityId, "entity_id") ?: return null, change.amount, change.reason)
            "HEALTH_CHANGE" -> StateOp.HealthChange(requireEntity(change.entityId, "entity_id") ?: return null, change.amount, change.reason)
            "ITEM_TRANSFER" -> StateOp.ItemTransfer(
                itemId = change.itemId ?: run {
                    issues += ValidationIssue("MISSING_FIELD", "ITEM_TRANSFER requires item_id.", fatal = false); return null
                },
                fromEntityId = change.entityId,
                toEntityId = change.targetId,
                toLocationId = change.locationId,
                reason = change.reason,
            )
            "ITEM_CREATE" -> StateOp.ItemCreate(
                itemName = change.itemName ?: run {
                    issues += ValidationIssue("MISSING_FIELD", "ITEM_CREATE requires item_name.", fatal = false); return null
                },
                ownerId = change.entityId,
                locationId = change.locationId,
                reason = change.reason,
            )
            "ITEM_DESTROY" -> StateOp.ItemDestroy(change.itemId ?: return null, change.reason)
            "PLAYER_MOVE" -> StateOp.PlayerMove(change.locationId ?: return null)
            "NPC_MOVE" -> StateOp.NpcMove(
                npcId = requireEntity(change.entityId, "entity_id") ?: return null,
                locationId = change.locationId ?: return null,
            )
            "NPC_DEATH" -> {
                val id = requireEntity(change.entityId, "entity_id") ?: return null
                if (id in deadNpcs) {
                    issues += ValidationIssue("ALREADY_DEAD", "${ctx.npcs[id]?.name ?: id} is already dead.", fatal = false)
                    return null
                }
                StateOp.NpcDeath(id, change.reason)
            }
            "NPC_EMOTION" -> StateOp.NpcEmotion(
                requireEntity(change.entityId, "entity_id") ?: return null,
                change.value ?: "neutral",
                change.amount.coerceIn(0, 100),
            )
            "NPC_RELATIONSHIP_CHANGE", "RELATIONSHIP_CHANGE" -> StateOp.RelationshipChange(
                fromEntityId = change.entityId ?: Ids.PLAYER,
                toEntityId = requireEntity(change.targetId ?: change.entityId, "target_id") ?: return null,
                changes = change.changes,
                reason = change.reason,
            )
            "FACTION_STANDING_CHANGE" -> StateOp.FactionStanding(requireEntity(change.entityId, "entity_id") ?: return null, change.amount, change.reason)
            "FACTION_JOIN" -> StateOp.FactionMembership(requireEntity(change.entityId, "entity_id") ?: return null, true)
            "FACTION_LEAVE" -> StateOp.FactionMembership(requireEntity(change.entityId, "entity_id") ?: return null, false)
            "APPEARANCE_CHANGE" -> StateOp.AppearanceChange(
                requireEntity(change.entityId, "entity_id") ?: return null,
                change.value ?: return null,
                change.reason,
            )
            "LOCATION_CONDITION_CHANGE" -> StateOp.LocationCondition(
                change.locationId ?: return null,
                change.value ?: "changed",
                change.reason,
            )
            "WEATHER_CHANGE" -> StateOp.WeatherChange(change.value ?: return null)
            "PLAYER_GOAL_CHANGE" -> StateOp.PlayerGoal(change.value ?: return null)
            else -> {
                issues += ValidationIssue("UNKNOWN_CHANGE_TYPE", "Unrecognised state change type '${change.type}'.", fatal = false)
                null
            }
        }
    }

    companion object {
        val RELATIONSHIP_DIMENSIONS = setOf(
            "trust", "respect", "affection", "fear", "suspicion",
            "attraction", "loyalty", "resentment", "hostility", "obligation", "familiarity",
        )
    }
}

data class ValidatorConfig(
    val allowNegativeCurrency: Boolean = false,
    val maxTimeAdvanceMinutes: Int = 60 * 24 * 14,
    val maxRelationshipSwing: Int = 45,
    val maxHealthSwing: Int = 100,
    val requireRelationshipReason: Boolean = true,
    val enforceAdjacentTravel: Boolean = true,
    val minMinutesForDistantTravel: Int = 15,
)
