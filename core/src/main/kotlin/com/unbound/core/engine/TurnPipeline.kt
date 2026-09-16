package com.unbound.core.engine

import com.unbound.core.ai.AIException
import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AITextProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.knowledge.Certainty
import com.unbound.core.knowledge.KnowledgePropagator
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.ledger.EventType
import com.unbound.core.ledger.GameEvent
import com.unbound.core.ledger.KnowledgeScope
import com.unbound.core.memory.ChapterSummariser
import com.unbound.core.memory.MemoryConsolidator
import com.unbound.core.memory.MemoryExtractor
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.MemoryVisibility
import com.unbound.core.memory.RetrievalBudget
import com.unbound.core.memory.RetrievalQuery
import com.unbound.core.model.*
import com.unbound.core.continuity.ContinuityEngine
import com.unbound.core.prompt.ContextBuilder
import com.unbound.core.prompt.PromptModules
import com.unbound.core.prompt.TurnContext
import com.unbound.core.prompt.TurnSchema
import com.unbound.core.relationship.RelationshipEngine
import com.unbound.core.relationship.RelationshipRecord
import com.unbound.core.simulation.SimulationInput
import com.unbound.core.simulation.WorldSimulator
import com.unbound.core.threads.ThreadEngine
import com.unbound.core.validate.StateOp
import com.unbound.core.validate.ScenePresence
import com.unbound.core.validate.StateValidator
import com.unbound.core.validate.TurnResponseDto
import com.unbound.core.validate.ValidationContext
import com.unbound.core.validate.ValidationIssue
import com.unbound.core.validate.ValidationResult
import kotlinx.serialization.json.Json

/**
 * One player action, start to finish (§40, §44).
 *
 * The shape of this class is dictated by one requirement: a turn either happens completely or not
 * at all. So the model call happens *outside* the transaction (it is slow and can fail), the
 * validation happens after it and before any write, and every write lands inside a single
 * [WorldStore.transaction] guarded by an optimistic version check.
 *
 * Nothing here knows what an OpenAI is. It takes an [AITextProvider] and could be driven by
 * anything, including the deterministic mock the tests use.
 */
class TurnPipeline(
    private val store: WorldStore,
    private val provider: AITextProvider,
    private val clock: () -> Long,
    private val idFactory: () -> String,
    private val retriever: MemoryRetriever = MemoryRetriever(store),
    private val validator: StateValidator = StateValidator(),
    private val relationshipEngine: RelationshipEngine = RelationshipEngine(),
    private val threadEngine: ThreadEngine = ThreadEngine(),
    private val simulator: WorldSimulator = WorldSimulator(idFactory),
    private val propagator: KnowledgePropagator = KnowledgePropagator(idFactory),
    private val contextBuilder: ContextBuilder = ContextBuilder(),
    private val budget: RetrievalBudget = RetrievalBudget(),
    private val config: PipelineConfig = PipelineConfig(),
    /**
     * Assembly lives here rather than in this class. Deciding what the model should know is a
     * different job from committing state, and it is the one most worth being able to test alone.
     */
    private val continuity: ContinuityEngine = ContinuityEngine(store, retriever, budget),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val memoryExtractor = MemoryExtractor(idFactory)
    private val chapterSummariser = ChapterSummariser(idFactory)
    private val consolidator = MemoryConsolidator(idFactory)

    /**
     * @param idempotencyKey pass the *same* key when retrying a failed turn. A turn that already
     *   committed under this key is returned as-is rather than applied twice (§45).
     */
    /**
     * @param directive a staging instruction that is *context*, not something the protagonist did.
     *   The opening scene uses it. Passing it as player input instead — as this once did — puts a
     *   wall of GM instructions in the player's log and makes the situation compete with canonical
     *   state for the model's attention, which canonical state wins.
     */
    suspend fun execute(
        gameId: String,
        playerInput: String,
        idempotencyKey: String = idFactory(),
        directive: String? = null,
    ): TurnOutcome {
        // An idempotency key identifies at most one turn row, ever. A retry *resumes* that row
        // rather than opening a second one — otherwise two rows share a key and the replay check
        // above can match the stale one, which is how a retry double-charges.
        val existingTurn = store.turnByIdempotencyKey(gameId, idempotencyKey)
        if (existingTurn?.status == TurnStatus.COMPLETE) {
            return TurnOutcome.Replayed(existingTurn)
        }

        val game = store.getGame(gameId) ?: return TurnOutcome.Failure("No such game.", AIErrorKind.UNKNOWN, retryable = false)
        val world = store.getWorld(gameId) ?: return TurnOutcome.Failure("World data missing.", AIErrorKind.UNKNOWN, retryable = false)
        val player = store.getPlayer(gameId) ?: return TurnOutcome.Failure("Player data missing.", AIErrorKind.UNKNOWN, retryable = false)

        val baseVersion = game.stateVersion
        val turnId = existingTurn?.id ?: Ids.turn(idFactory())

        // Persisted before the network call: if the process dies mid-turn, this row is what makes
        // the turn resumable instead of lost (§96). A resumed turn is re-based on the game's
        // current version, since nothing of the earlier attempt was committed.
        val pendingTurn = TurnRecord(
            id = turnId,
            gameId = gameId,
            turnNumber = game.turnNumber + 1,
            playerInput = playerInput,
            status = TurnStatus.PENDING,
            idempotencyKey = idempotencyKey,
            baseStateVersion = baseVersion,
            createdAtEpochMs = existingTurn?.createdAtEpochMs ?: clock(),
            worldMinutesBefore = game.worldTime.totalMinutes,
            modelId = game.textModelId,
        )
        store.upsertTurn(pendingTurn)

        val assembly = try {
            assembleContext(game, world, player, playerInput, directive)
        } catch (e: Exception) {
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.FAILED, errorMessage = "Context assembly failed: ${e.message}"))
            return TurnOutcome.Failure("Could not assemble the world state for this turn.", AIErrorKind.UNKNOWN, retryable = true)
        }

        // Deliberately wider than "present": a character who is in play but filed elsewhere can
        // still walk into the scene this turn, and a safety constraint should cover them.
        val guardClause = com.unbound.core.safety.ContentGuard.sceneClause(player, assembly.context.relevantNpcs)
        val request = AITextRequest(
            modelId = game.textModelId,
            // The campaign's own choice, carried through opaquely. The pipeline does not know what
            // an "openai" is; it knows this save says which one it runs on.
            providerId = game.textProviderId,
            stableSystemPrompt = PromptModules.stableSystemPrompt(),
            dynamicContext = if (guardClause == null) assembly.dynamicContext else assembly.dynamicContext + "\n\n## " + guardClause,
            userInput = if (directive != null) DIRECTIVE_INPUT else playerInput,
            jsonSchema = TurnSchema.schema(),
            schemaName = "unbound_turn",
            maxOutputTokens = config.maxOutputTokens,
        )

        val started = clock()
        val aiResponse = try {
            provider.generateText(request)
        } catch (e: AIException) {
            // Nothing was written beyond the pending row, so the world is untouched and the same
            // idempotency key can be replayed safely.
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.PENDING, errorMessage = e.message))
            store.recordUsage(failureUsage(gameId, turnId, game.textModelId, game.textProviderId, clock() - started, e.kind))
            return TurnOutcome.Failure(e.message, e.kind, retryable = e.kind.retryable, turnId = turnId, idempotencyKey = idempotencyKey)
        }

        val parsed = try {
            json.decodeFromString(TurnResponseDto.serializer(), extractJson(aiResponse.text))
        } catch (e: Exception) {
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.PENDING, errorMessage = "Malformed response: ${e.message}"))
            store.recordUsage(failureUsage(gameId, turnId, game.textModelId, game.textProviderId, clock() - started, AIErrorKind.MALFORMED_RESPONSE))
            return TurnOutcome.Failure(
                "The model returned something this game could not read. The turn was not applied — try again.",
                AIErrorKind.MALFORMED_RESPONSE,
                retryable = true,
                turnId = turnId,
                idempotencyKey = idempotencyKey,
            )
        }

        val validation = validator.validate(parsed, assembly.validationContext)
        if (validation.hasFatal) {
            store.upsertTurn(
                pendingTurn.copy(
                    status = TurnStatus.PENDING,
                    errorMessage = validation.issues.filter { it.fatal }.joinToString("; ") { it.detail },
                ),
            )
            return TurnOutcome.Failure(
                "The result was rejected because it contradicted the world's state. Nothing was changed.",
                AIErrorKind.MALFORMED_RESPONSE,
                retryable = true,
                turnId = turnId,
                idempotencyKey = idempotencyKey,
                issues = validation.issues,
            )
        }

        return try {
            commit(
                game = game,
                world = world,
                player = player,
                turn = pendingTurn,
                assembly = assembly,
                response = parsed,
                validation = validation,
                usage = aiResponse,
                latencyMs = clock() - started,
            )
        } catch (e: StaleStateException) {
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.FAILED, errorMessage = e.message))
            TurnOutcome.Failure(
                "Another turn was committed for this game while this one was in flight. Nothing was changed.",
                AIErrorKind.UNKNOWN,
                retryable = false,
                turnId = turnId,
            )
        }
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun assembleContext(
        game: GameRecord,
        world: WorldRecord,
        player: PlayerRecord,
        playerInput: String,
        directive: String? = null,
    ): Assembly {
        val assembled = continuity.assemble(game, world, player, playerInput, directive)
        return Assembly(
            context = assembled.context,
            validationContext = assembled.validationContext,
            openThreads = assembled.openThreads,
            query = assembled.query,
            dynamicContext = contextBuilder.build(assembled.context),
            allNpcs = assembled.allNpcs,
            allFactions = assembled.allFactions,
        )
    }

    /** Section headings and their weight, so a missing section is visible at a glance. */
    private fun sectionsOf(context: String): List<String> {
        val result = mutableListOf<String>()
        var title: String? = null
        var lines = 0
        context.lineSequence().forEach { line ->
            if (line.startsWith("## ")) {
                title?.let { result += "$it ($lines)" }
                title = line.removePrefix("## ")
                lines = 0
            } else if (line.isNotBlank()) {
                lines++
            }
        }
        title?.let { result += "$it ($lines)" }
        return result
    }

    private suspend fun commit(
        game: GameRecord,
        world: WorldRecord,
        player: PlayerRecord,
        turn: TurnRecord,
        assembly: Assembly,
        response: TurnResponseDto,
        validation: ValidationResult,
        usage: com.unbound.core.ai.AITextResponse,
        latencyMs: Long,
    ): TurnOutcome = store.transaction {
        // Only what survived validation is ever applied. Reading `response` for anything the
        // validator filters would silently reinstate rejected content.
        val ops = validation.ops
        val issues = validation.issues
        // Re-read under the transaction and re-check the lock; anything else is a lost update.
        val current = store.getGame(game.id) ?: throw StaleStateException("Game vanished mid-turn.")
        if (current.stateVersion != turn.baseStateVersion) {
            throw StaleStateException("State version moved from ${turn.baseStateVersion} to ${current.stateVersion}.")
        }

        val now = clock()
        val timeAdvance = response.timeAdvanceMinutes.coerceIn(0, config.maxTimeAdvanceMinutes)
        val newWorldTime = current.worldTime.plusMinutes(timeAdvance.toLong())

        var sequence = store.nextEventSequence(game.id)
        val events = mutableListOf<GameEvent>()
        // The first event of a turn is what the player did; everything else the turn emits is a
        // consequence of it. Recording that link is what lets the world explain why a current
        // condition exists — "she will not serve you" traces back to the night it started —
        // instead of the model having to invent a reason on the spot.
        var rootEventId: String? = null

        fun emit(
            type: EventType,
            summary: String,
            importance: Importance = Importance.LOW,
            actorId: String? = null,
            targetId: String? = null,
            locationId: String? = null,
            scope: KnowledgeScope = KnowledgeScope.WITNESSED,
            witnesses: List<String> = emptyList(),
            related: List<String> = emptyList(),
            causedBy: String? = null,
        ): String {
            val id = Ids.event(idFactory())
            events += GameEvent(
                id = id,
                gameId = game.id,
                turnId = turn.id,
                sequence = sequence++,
                worldMinutes = newWorldTime.totalMinutes,
                realTimestampMs = now,
                type = type,
                actorId = actorId,
                targetId = targetId,
                locationId = locationId ?: player.currentLocationId,
                summary = summary,
                importance = importance,
                knowledgeScope = scope,
                witnessIds = witnesses,
                relatedEntityIds = related,
                // Explicit cause when the caller knows one, otherwise this turn's action.
                causedByEventId = causedBy ?: rootEventId,
            )
            if (rootEventId == null) rootEventId = id
            return id
        }

        // The causal anchor for this turn. PRIVATE and TRIVIAL so it never propagates as knowledge
        // and never becomes a memory — it exists so that every consequence below has a real cause
        // to point at, and so the ledger records what the player actually did rather than only
        // what the world did back.
        if (turn.playerInput.isNotBlank()) {
            emit(
                EventType.PLAYER_OBSERVED,
                "Player: ${turn.playerInput.take(MAX_ACTION_SUMMARY)}",
                Importance.TRIVIAL,
                actorId = Ids.PLAYER,
                scope = KnowledgeScope.PRIVATE,
            )
        }

        // --- apply the validated state ops --------------------------------------------------
        var updatedPlayer = player
        var updatedWorld = world
        val npcEdits = mutableMapOf<String, NpcRecord>()
        val itemEdits = mutableMapOf<String, ItemRecord>()
        val locationEdits = mutableMapOf<String, LocationRecord>()
        val factionEdits = mutableMapOf<String, FactionRecord>()
        val relationshipEdits = mutableMapOf<String, RelationshipRecord>()

        suspend fun npc(id: String): NpcRecord? = npcEdits[id] ?: store.getNpc(game.id, id)

        for (op in ops) {
            when (op) {
                is StateOp.CurrencyChange -> {
                    if (op.entityId == player.id || op.entityId == Ids.PLAYER) {
                        updatedPlayer = updatedPlayer.copy(currency = (updatedPlayer.currency + op.amount).coerceAtLeast(0))
                        emit(
                            EventType.CURRENCY_CHANGED,
                            "Player ${if (op.amount < 0) "spent" else "gained"} ${kotlin.math.abs(op.amount)} ${world.currencyName}" +
                                if (op.reason.isNotBlank()) " (${op.reason})" else "",
                            Importance.LOW,
                            actorId = Ids.PLAYER,
                            scope = KnowledgeScope.PRIVATE,
                        )
                    } else {
                        npc(op.entityId)?.let { npcEdits[it.id] = it.copy(currency = (it.currency + op.amount).coerceAtLeast(0)) }
                    }
                }

                is StateOp.HealthChange -> {
                    if (op.entityId == player.id || op.entityId == Ids.PLAYER) {
                        val body = updatedPlayer.body
                        updatedPlayer = updatedPlayer.copy(
                            body = body.copy(health = (body.health + op.amount).coerceIn(0, body.maxHealth)),
                        )
                        emit(
                            EventType.HEALTH_CHANGED,
                            "Player health changed by ${op.amount}" + if (op.reason.isNotBlank()) " (${op.reason})" else "",
                            if (kotlin.math.abs(op.amount) >= 25) Importance.HIGH else Importance.LOW,
                            actorId = Ids.PLAYER,
                        )
                    } else {
                        npc(op.entityId)?.let { n ->
                            val h = (n.body.health + op.amount).coerceIn(0, n.body.maxHealth)
                            npcEdits[n.id] = n.copy(body = n.body.copy(health = h), alive = h > 0)
                            if (h <= 0) {
                                emit(EventType.NPC_DIED, "${n.name} died.", Importance.CRITICAL, targetId = n.id, scope = KnowledgeScope.LOCAL_RUMOR)
                            } else {
                                emit(EventType.NPC_INJURED, "${n.name} was hurt (${op.reason}).", Importance.MEDIUM, targetId = n.id)
                            }
                        }
                    }
                }

                is StateOp.ItemTransfer -> {
                    val item = itemEdits[op.itemId] ?: store.getItem(game.id, op.itemId) ?: continue
                    itemEdits[item.id] = item.copy(
                        ownerId = op.toEntityId ?: if (op.toLocationId != null) null else item.ownerId,
                        locationId = op.toLocationId,
                        provenance = (item.provenance + "moved to ${op.toEntityId ?: op.toLocationId} at turn ${turn.turnNumber}").takeLast(20),
                    )
                    emit(
                        EventType.ITEM_TRANSFERRED,
                        "${item.name} passed from ${op.fromEntityId ?: "somewhere"} to ${op.toEntityId ?: op.toLocationId}.",
                        Importance.MEDIUM,
                        actorId = op.fromEntityId,
                        targetId = op.toEntityId,
                        related = listOf(item.id),
                    )
                }

                is StateOp.ItemCreate -> {
                    val created = ItemRecord(
                        id = Ids.item(idFactory()),
                        gameId = game.id,
                        name = op.itemName,
                        description = op.reason,
                        ownerId = op.ownerId,
                        locationId = op.locationId,
                        provenance = listOf("appeared at turn ${turn.turnNumber}: ${op.reason}"),
                    )
                    itemEdits[created.id] = created
                    emit(EventType.ITEM_CREATED, "${created.name} came into play.", Importance.LOW, related = listOf(created.id))
                }

                is StateOp.ItemDestroy -> {
                    val item = itemEdits[op.itemId] ?: store.getItem(game.id, op.itemId) ?: continue
                    itemEdits[item.id] = item.copy(condition = ItemCondition.DESTROYED, ownerId = null, locationId = null)
                    emit(EventType.ITEM_DESTROYED, "${item.name} was destroyed (${op.reason}).", Importance.MEDIUM, related = listOf(item.id))
                }

                is StateOp.PlayerMove -> {
                    val dest = store.getLocation(game.id, op.locationId) ?: continue
                    emit(EventType.PLAYER_LEFT_LOCATION, "Player left ${assembly.context.location.name}.", Importance.TRIVIAL, actorId = Ids.PLAYER)
                    updatedPlayer = updatedPlayer.copy(currentLocationId = dest.id)
                    if (!dest.discovered) {
                        locationEdits[dest.id] = dest.copy(discovered = true)
                        emit(EventType.LOCATION_DISCOVERED, "Player found ${dest.name}.", Importance.MEDIUM, actorId = Ids.PLAYER, locationId = dest.id)
                    }
                    emit(EventType.PLAYER_ENTERED_LOCATION, "Player arrived at ${dest.name}.", Importance.LOW, actorId = Ids.PLAYER, locationId = dest.id)
                }

                is StateOp.NpcMove -> npc(op.npcId)?.let { npcEdits[it.id] = it.copy(currentLocationId = op.locationId) }

                is StateOp.NpcDeath -> npc(op.npcId)?.let { n ->
                    npcEdits[n.id] = n.copy(alive = false, body = n.body.copy(health = 0))
                    emit(
                        EventType.NPC_DIED,
                        "${n.name} died. ${op.reason}".trim(),
                        Importance.CRITICAL,
                        targetId = n.id,
                        scope = KnowledgeScope.LOCAL_RUMOR,
                    )
                }

                is StateOp.NpcEmotion -> npc(op.npcId)?.let {
                    npcEdits[it.id] = it.copy(emotion = it.emotion.copy(mood = op.mood, stress = op.stress, lastChangedAt = newWorldTime.totalMinutes))
                }

                is StateOp.RelationshipChange -> {
                    // Models reverse the direction freely; normalising here means a reversed pair
                    // updates the same record rather than creating an orphan nothing reads.
                    val (from, to) = RelationshipRecord.canonicalKey(game.id, Ids.PLAYER, op.fromEntityId, op.toEntityId)
                    val key = RelationshipRecord.key(game.id, from, to)
                    val existing = relationshipEdits[key]
                        ?: store.getRelationship(game.id, from, to)
                        ?: RelationshipRecord(key, game.id, from, to)
                    relationshipEdits[key] = relationshipEngine.apply(existing, op.changes, op.reason, newWorldTime.totalMinutes)
                    emit(
                        EventType.RELATIONSHIP_CHANGED,
                        "${op.fromEntityId} -> ${op.toEntityId}: ${op.reason}",
                        Importance.MEDIUM,
                        actorId = op.fromEntityId,
                        targetId = op.toEntityId,
                        scope = KnowledgeScope.PRIVATE,
                    )
                }

                is StateOp.FactionStanding -> {
                    val f = factionEdits[op.factionId] ?: store.getFaction(game.id, op.factionId) ?: continue
                    factionEdits[f.id] = f.copy(playerStanding = (f.playerStanding + op.amount).coerceIn(-100, 100))
                    emit(EventType.FACTION_STANDING_CHANGED, "${f.name}'s view of the player shifted: ${op.reason}", Importance.MEDIUM, targetId = f.id)
                }

                is StateOp.FactionMembership -> {
                    val f = factionEdits[op.factionId] ?: store.getFaction(game.id, op.factionId) ?: continue
                    factionEdits[f.id] = f.copy(playerIsMember = op.joining)
                    emit(
                        if (op.joining) EventType.PLAYER_JOINED_FACTION else EventType.PLAYER_LEFT_FACTION,
                        if (op.joining) "Player joined ${f.name}." else "Player left ${f.name}.",
                        Importance.HIGH,
                        actorId = Ids.PLAYER,
                        targetId = f.id,
                        scope = KnowledgeScope.FACTION,
                    )
                }

                is StateOp.AppearanceChange -> {
                    if (op.entityId == player.id || op.entityId == Ids.PLAYER) {
                        val a = updatedPlayer.appearance
                        updatedPlayer = updatedPlayer.copy(
                            appearance = a.copy(summary = op.description, version = a.version + 1),
                        )
                        emit(
                            EventType.PLAYER_APPEARANCE_CHANGED,
                            "Player's appearance changed: ${op.description} (was: ${a.summary})",
                            Importance.MEDIUM,
                            actorId = Ids.PLAYER,
                        )
                    } else {
                        npc(op.entityId)?.let { n ->
                            npcEdits[n.id] = n.copy(appearance = n.appearance.copy(summary = op.description, version = n.appearance.version + 1))
                            emit(
                                EventType.NPC_APPEARANCE_CHANGED,
                                "${n.name}'s appearance changed: ${op.description} (was: ${n.appearance.summary})",
                                Importance.MEDIUM,
                                targetId = n.id,
                            )
                        }
                    }
                }

                is StateOp.LocationCondition -> {
                    val loc = locationEdits[op.locationId] ?: store.getLocation(game.id, op.locationId) ?: continue
                    locationEdits[loc.id] = loc.copy(
                        condition = op.condition,
                        history = (loc.history + "turn ${turn.turnNumber}: ${op.description.ifBlank { op.condition }}").takeLast(30),
                    )
                    emit(EventType.LOCATION_CHANGED, "${loc.name}: ${op.description.ifBlank { op.condition }}", Importance.MEDIUM, locationId = loc.id, scope = KnowledgeScope.LOCAL_RUMOR)
                }

                is StateOp.WeatherChange -> {
                    updatedWorld = updatedWorld.copy(weather = op.weather)
                    emit(EventType.WEATHER_CHANGED, "The weather turned ${op.weather}.", Importance.TRIVIAL, scope = KnowledgeScope.PUBLIC)
                }

                is StateOp.PlayerGoal -> {
                    updatedPlayer = updatedPlayer.copy(goals = (listOf(op.goal) + updatedPlayer.goals).distinct().take(6))
                }
            }
        }

        // --- model-declared events ------------------------------------------------------------
        for (dto in validation.events) {
            val type = dto.eventType() ?: continue
            events += GameEvent(
                id = Ids.event(idFactory()),
                gameId = game.id,
                turnId = turn.id,
                sequence = sequence++,
                worldMinutes = newWorldTime.totalMinutes,
                realTimestampMs = now,
                type = type,
                actorId = dto.actorId,
                targetId = dto.targetId,
                locationId = dto.locationId ?: updatedPlayer.currentLocationId,
                summary = dto.summary.ifBlank { type.name.lowercase().replace('_', ' ') },
                importance = dto.importanceOrNull() ?: Importance.LOW,
                knowledgeScope = dto.scopeOrNull() ?: KnowledgeScope.WITNESSED,
                witnessIds = dto.witnessIds,
                relatedEntityIds = listOfNotNull(dto.actorId, dto.targetId),
            )
        }

        // What the narrator said this cost. These were accepted into the schema, validated, and
        // then never read — so a model reporting "this cost her trust" changed nothing, and the
        // only relationship movement in the game came from the event-type defaults below.
        for (change in response.relationshipChanges) {
            if (change.changes.isEmpty()) continue
            val other = change.takeIf { it.fromEntityId == Ids.PLAYER }?.toEntityId
                ?: change.takeIf { it.toEntityId == Ids.PLAYER }?.fromEntityId
                ?: change.toEntityId
            if (Ids.kindOf(other) != EntityKind.NPC) continue
            if (!assembly.validationContext.entityExists(other)) continue

            // One record per pair, always oriented (player -> character): writing the other way
            // round put hostility where nothing looked for it.
            val (from, to) = RelationshipRecord.canonicalKey(game.id, Ids.PLAYER, change.fromEntityId, other)
            val key = RelationshipRecord.key(game.id, from, to)
            val existing = relationshipEdits[key]
                ?: store.getRelationship(game.id, from, to)
                ?: RelationshipRecord(key, game.id, from, to)
            relationshipEdits[key] = relationshipEngine.apply(
                existing,
                change.changes,
                change.reason,
                newWorldTime.totalMinutes,
                rootEventId,
            )
        }

        // Relationship consequences the model did not spell out, derived locally from event type.
        for (event in events) {
            if (event.actorId != Ids.PLAYER || event.targetId == null) continue
            if (Ids.kindOf(event.targetId) != EntityKind.NPC) continue
            val key = RelationshipRecord.key(game.id, Ids.PLAYER, event.targetId)
            if (relationshipEdits.containsKey(key)) continue
            val delta = relationshipEngine.defaultDeltaFor(event.type)
            if (delta.isEmpty()) continue
            val existing = store.getRelationship(game.id, Ids.PLAYER, event.targetId)
                ?: RelationshipRecord(key, game.id, Ids.PLAYER, event.targetId)
            relationshipEdits[key] = relationshipEngine.apply(existing, delta, event.summary, newWorldTime.totalMinutes, event.id)
        }

        // The order below is deliberate and was got wrong once: the cast of the scene and
        // where everyone is standing must be settled *before* knowledge and memory are derived
        // from it. With characters created afterwards, anyone introduced in a scene could never
        // witness or remember the scene they were introduced in.

        // --- new characters ---------------------------------------------------------------------
        val introducedIntoScene = mutableSetOf<String>()
        // The world must be able to gain people, but only on the content guard's terms: an
        // explicit age or the character is discarded.
        for (dto in response.newCharacters) {
            if (dto.name.isBlank() || dto.age <= 0) {
                continue
            }
            val existing = store.persistentNpcs(game.id, 300).firstOrNull { it.name.equals(dto.name, ignoreCase = true) }
            if (existing != null) continue
            val created = NpcRecord(
                id = Ids.npc(idFactory()),
                gameId = game.id,
                name = dto.name,
                age = dto.age,
                gender = dto.gender,
                appearance = Appearance(summary = dto.appearance),
                occupation = dto.occupation,
                personality = dto.personality,
                currentPlan = dto.wants,
                // Introduced *into this scene* unless the narrator explicitly places them
                // somewhere else; the scene-presence sync below has the final say either way.
                currentLocationId = dto.locationId?.takeIf { store.getLocation(game.id, it) != null }
                    ?: updatedPlayer.currentLocationId,
                // Semi-persistent: they exist because they were met, and are promoted to fully
                // persistent only if the player keeps dealing with them.
                tier = NpcTier.SEMI_PERSISTENT,
                firstEncounteredTurn = turn.turnNumber,
                lastSeenTurn = turn.turnNumber,
                lastSeenWorldMinutes = newWorldTime.totalMinutes,
                introduced = true,
            )
            npcEdits[created.id] = created
            // A character given an explicit location elsewhere is being *mentioned*, not met, so
            // they do not join the scene. One introduced with no location walked into it.
            if (created.currentLocationId == updatedPlayer.currentLocationId) {
                introducedIntoScene += created.id
            }
            // Meeting someone is exactly the kind of thing both parties remember, and "how did we
            // meet?" is a question that gets asked hundreds of turns later. At LOW this fell below
            // the memorable threshold and no record of the meeting survived at all.
            emit(
                EventType.NPC_INTRODUCED,
                "${updatedPlayer.name} first met ${created.name}" +
                    (if (created.occupation.isNotBlank()) ", ${created.occupation}" else "") +
                    ", at ${assembly.context.location.name} on ${newWorldTime.display()}.",
                Importance.MEDIUM,
                actorId = Ids.PLAYER,
                targetId = created.id,
                related = listOf(created.id),
            )
        }

        // --- npc actions --------------------------------------------------------------------------
        for (action in validation.npcActions) {
            val n = npcEdits[action.npcId] ?: store.getNpc(game.id, action.npcId) ?: continue
            if (!n.alive) continue
            var updated = n.copy(currentPlan = action.action, lastSeenTurn = turn.turnNumber, lastSeenWorldMinutes = newWorldTime.totalMinutes)
            action.movesToLocationId?.let { updated = updated.copy(currentLocationId = it) }
            npcEdits[n.id] = updated
            if (action.becomesHostile) {
                val key = RelationshipRecord.key(game.id, Ids.PLAYER, n.id)
                val existing = relationshipEdits[key]
                    ?: store.getRelationship(game.id, Ids.PLAYER, n.id)
                    ?: RelationshipRecord(key, game.id, Ids.PLAYER, n.id)
                relationshipEdits[key] = relationshipEngine.apply(existing, mapOf("hostility" to 30, "trust" to -20), action.action, newWorldTime.totalMinutes)
                emit(EventType.NPC_BECAME_HOSTILE, "${n.name} turned on the player.", Importance.HIGH, actorId = n.id, targetId = Ids.PLAYER)
            }
        }

        // --- threads ----------------------------------------------------------------------------
        val threadEdits = mutableMapOf<String, ThreadRecord>()
        for (dto in response.threadChanges) {
            val importance = Importance.entries.firstOrNull { it.name == dto.importance.uppercase() } ?: Importance.MEDIUM
            val type = ThreadType.entries.firstOrNull { it.name == dto.type.uppercase() } ?: ThreadType.OTHER
            when (dto.action.uppercase()) {
                "START" -> {
                    if (dto.title.isBlank()) continue
                    val id = Ids.thread(idFactory())
                    threadEdits[id] = ThreadRecord(
                        id = id,
                        gameId = game.id,
                        type = type,
                        title = dto.title,
                        description = dto.description,
                        originatingEventId = events.firstOrNull()?.id,
                        involvedEntityIds = dto.involvedEntityIds,
                        stakes = dto.stakes,
                        lastActivityWorldMinutes = newWorldTime.totalMinutes,
                        importance = importance,
                        deadlineWorldMinutes = dto.deadlineInMinutes?.let { newWorldTime.totalMinutes + it }
                            ?: threadEngine.defaultDeadline(type, importance, newWorldTime),
                    )
                    emit(EventType.THREAD_STARTED, "New situation: ${dto.title}", importance)
                }
                else -> {
                    val id = dto.threadId ?: continue
                    val existing = threadEdits[id] ?: assembly.openThreads.firstOrNull { it.id == id } ?: continue
                    val status = when (dto.action.uppercase()) {
                        "RESOLVE" -> ThreadStatus.RESOLVED
                        "FAIL" -> ThreadStatus.FAILED
                        "STALL" -> ThreadStatus.STALLED
                        "TRANSFORM" -> ThreadStatus.TRANSFORMED
                        else -> ThreadStatus.ADVANCING
                    }
                    threadEdits[id] = existing.copy(
                        status = status,
                        // Advancing a situation on-screen means the player is in it, so it stops
                        // being something happening quietly in the background.
                        visibility = if (existing.visibility == ThreadVisibility.HIDDEN) {
                            ThreadVisibility.KNOWN
                        } else {
                            existing.visibility
                        },
                        description = dto.description.ifBlank { existing.description },
                        lastActivityWorldMinutes = newWorldTime.totalMinutes,
                        momentum = if (status == ThreadStatus.ADVANCING) (existing.momentum + 15).coerceAtMost(100) else existing.momentum,
                    )
                    emit(
                        when (status) {
                            ThreadStatus.RESOLVED -> EventType.THREAD_RESOLVED
                            ThreadStatus.FAILED -> EventType.THREAD_FAILED
                            ThreadStatus.TRANSFORMED -> EventType.THREAD_TRANSFORMED
                            ThreadStatus.STALLED -> EventType.THREAD_STALLED
                            else -> EventType.THREAD_ADVANCED
                        },
                        "${existing.title}: ${dto.description.ifBlank { status.name.lowercase() }}",
                        existing.importance,
                    )
                }
            }
        }

        // --- thread discovery ----------------------------------------------------------------------
        // A world's own running situations start hidden: they drive the simulation before the player
        // knows they exist. Nothing ever revealed them, so they stayed out of the journal forever.
        // Meeting someone caught up in one is how a player learns of it.
        val encounteredIds = assembly.context.presentNpcs.map { it.id }.toSet() +
            npcEdits.keys + events.mapNotNull { it.targetId } + events.mapNotNull { it.actorId }
        for (thread in assembly.openThreads) {
            if (thread.visibility != ThreadVisibility.HIDDEN) continue
            if (threadEdits.containsKey(thread.id)) continue
            if (thread.involvedEntityIds.none { it in encounteredIds }) continue
            threadEdits[thread.id] = thread.copy(visibility = ThreadVisibility.SUSPECTED)
        }

        // --- scene presence -----------------------------------------------------------------------
        // The narrative is the authority on who is in the room; the stored location is a cache of
        // that, and it goes stale the moment someone walks over without an explicit move. Bringing
        // it back in line here is what lets the same people be witnessed, pictured and talked to
        // next turn instead of being treated as absent from their own conversation.
        val knownNpcIds = (assembly.validationContext.npcs.keys + npcEdits.keys).toMutableSet()
        val storedPresent = (assembly.context.presentNpcs.map { it.id } + npcEdits.values
            .filter { it.currentLocationId == updatedPlayer.currentLocationId }
            .map { it.id }).toSet()

        knownNpcIds += npcEdits.keys

        val sceneParticipants = ScenePresence.participants(
            declaredPresentIds = response.presentCharacterIds,
            npcActions = validation.npcActions,
            events = validation.events,
            playerLocationId = updatedPlayer.currentLocationId,
            knownNpcIds = knownNpcIds,
            storedPresentIds = storedPresent + introducedIntoScene,
        )
        val stillHere = ScenePresence.remaining(
            declaredPresentIds = response.presentCharacterIds,
            npcActions = validation.npcActions,
            events = validation.events,
            playerLocationId = updatedPlayer.currentLocationId,
            knownNpcIds = knownNpcIds,
            storedPresentIds = storedPresent + introducedIntoScene,
        )

        for (npcId in stillHere) {
            val npc = npcEdits[npcId] ?: store.getNpc(game.id, npcId) ?: continue
            if (!npc.alive) continue
            npcEdits[npc.id] = npc.copy(
                currentLocationId = updatedPlayer.currentLocationId,
                lastSeenTurn = turn.turnNumber,
                lastSeenWorldMinutes = newWorldTime.totalMinutes,
                // Being in a scene with the player *is* meeting them. Only characters the model
                // invented were stamped as encountered, so anyone the world started with stayed
                // forever unmet: absent from the journal's people, and never described to the
                // model as someone the player already knows.
                firstEncounteredTurn = npc.firstEncounteredTurn ?: turn.turnNumber,
                introduced = true,
                // Someone the player has actually dealt with in a scene stops being disposable.
                tier = if (npc.tier == NpcTier.AMBIENT) NpcTier.SEMI_PERSISTENT else npc.tier,
            )
        }

        // --- knowledge ---------------------------------------------------------------------------
        // Witnesses are everyone who took part in the scene, not everyone who happened to be filed
        // at this location before the turn began.
        val presentNpcs = sceneParticipants.mapNotNull { id -> npcEdits[id] ?: store.getNpc(game.id, id) }
            .filter { it.alive }
        val knowledgeRecords = mutableListOf<KnowledgeRecord>()
        val rumorRecords = mutableListOf<com.unbound.core.knowledge.RumorRecord>()

        for (event in events.filter { it.importance.isMemorable }) {
            val result = propagator.propagate(event, presentNpcs)
            knowledgeRecords += result.knowledge
            result.rumor?.let { rumorRecords += it }
        }

        for (dto in validation.knowledgeChanges) {
            knowledgeRecords += KnowledgeRecord(
                id = idFactory(),
                gameId = game.id,
                knowerId = dto.knowerId,
                factKey = dto.factKey,
                statement = dto.statement,
                certainty = dto.certaintyOrNull() ?: Certainty.KNOWN,
                isDistorted = dto.isDistorted,
                subjectEntityIds = dto.subjectEntityIds,
                sourceEntityId = dto.sourceEntityId,
                learnedAtWorldMinutes = newWorldTime.totalMinutes,
                secret = dto.secret,
            )
        }

        // --- memories -------------------------------------------------------------------------
        val newMemories = mutableListOf<MemoryRecord>()
        val reinforced = mutableListOf<MemoryRecord>()

        suspend fun addMemory(candidate: MemoryRecord) {
            val existing = store.memoriesOf(game.id, candidate.ownerId, 40)
            val outcome = memoryExtractor.reconcile(candidate, existing + newMemories.filter { it.ownerId == candidate.ownerId })
            outcome.insert?.let { newMemories += it }
            outcome.reinforce?.let { reinforced += it }
        }

        for (dto in response.memoryCandidates) {
            val importance = Importance.entries.firstOrNull { it.name == dto.importance.uppercase() } ?: Importance.MEDIUM
            if (!importance.isMemorable) continue
            addMemory(
                MemoryRecord(
                    id = Ids.memory(idFactory()),
                    gameId = game.id,
                    ownerId = dto.ownerId,
                    text = dto.text,
                    entityIds = dto.entityIds,
                    locationId = updatedPlayer.currentLocationId,
                    sourceEventIds = events.map { it.id }.take(3),
                    importance = importance,
                    createdAtWorldMinutes = newWorldTime.totalMinutes,
                    lastReinforcedWorldMinutes = newWorldTime.totalMinutes,
                    confidence = dto.confidence.coerceIn(0.0, 1.0),
                    visibility = if (dto.ownerId == MemoryRecord.WORLD_OWNER) MemoryVisibility.WORLD else MemoryVisibility.ENTITY,
                ),
            )
        }

        // Every NPC who witnessed a memorable event remembers it themselves, whether or not the
        // model thought to say so. This is what makes §31 hold without relying on the model.
        for (event in events.filter { it.importance.isMemorable }) {
            val witnesses = (presentNpcs.map { it.id } + event.witnessIds).distinct()
                .filter { event.knowledgeScope != KnowledgeScope.PRIVATE }
            for (witnessId in witnesses + listOf(Ids.PLAYER)) {
                memoryExtractor.candidateFrom(event, witnessId, MemoryVisibility.ENTITY)?.let { addMemory(it) }
            }
            if (event.importance.ordinal >= Importance.HIGH.ordinal) {
                memoryExtractor.candidateFrom(event, MemoryRecord.WORLD_OWNER, MemoryVisibility.WORLD)?.let { addMemory(it) }
            }
        }

        // --- world simulation for elapsed time ---------------------------------------------------
        val simulation = simulator.simulate(
            SimulationInput(
                previous = current.worldTime,
                now = newWorldTime,
                playerLocationId = updatedPlayer.currentLocationId,
                weather = updatedWorld.weather,
                // The cast as assembly read it, overlaid with this turn's edits and any character
                // this turn brought into being.
                npcs = (assembly.allNpcs.map { npcEdits[it.id] ?: it } +
                    npcEdits.values.filterNot { e -> assembly.allNpcs.any { it.id == e.id } }),
                factions = (assembly.allFactions.map { factionEdits[it.id] ?: it } +
                    factionEdits.values.filterNot { e -> assembly.allFactions.any { it.id == e.id } }),
                threads = assembly.openThreads.map { threadEdits[it.id] ?: it },
                rumors = store.activeRumors(game.id, 40),
                engagedThreadIds = threadEdits.keys,
                narrativelyPlacedNpcIds = npcEdits.keys,
            ),
        )
        simulation.npcUpdates.forEach { npcEdits[it.id] = it }
        simulation.factionUpdates.forEach { factionEdits[it.id] = it }
        simulation.threadUpdates.forEach { threadEdits[it.id] = it }
        updatedWorld = updatedWorld.copy(weather = simulation.weather.ifBlank { updatedWorld.weather })
        knowledgeRecords += simulation.newKnowledge
        for (note in simulation.notes) {
            emit(note.type, note.summary, note.importance, actorId = note.entityId, locationId = note.locationId, scope = KnowledgeScope.LOCAL_RUMOR)
        }

        // Feelings soften when nothing happens. Applied only on genuine time skips, and only to
        // relationships this turn did not touch, so it is bounded and never undoes the scene.
        if (timeAdvance >= DECAY_THRESHOLD_MINUTES) {
            store.relationshipsFrom(game.id, Ids.PLAYER, MAX_DECAY_ROWS)
                .filterNot { relationshipEdits.containsKey(it.id) }
                .forEach { record ->
                    val decayed = relationshipEngine.decay(record, newWorldTime.totalMinutes)
                    if (decayed != record) relationshipEdits[record.id] = decayed
                }
        }

        if (timeAdvance > 0) {
            emit(EventType.TIME_ADVANCED, "$timeAdvance minutes passed.", Importance.TRIVIAL, scope = KnowledgeScope.PUBLIC)
        }

        // --- obligations ----------------------------------------------------------------------
        // Opened and closed here rather than inferred from events: an obligation is state the
        // world consults for as long as it stands, not a line in a history nobody reads back.
        val commitmentWrites = mutableListOf<com.unbound.core.continuity.CommitmentRecord>()
        for (change in validation.commitmentChanges) {
            if (change.isOpening()) {
                val kind = change.kindOrNull() ?: continue
                val from = change.fromEntityId ?: Ids.PLAYER
                val to = change.toEntityId ?: continue
                if (change.terms.isBlank()) continue
                val id = Ids.commitment(idFactory())
                commitmentWrites += com.unbound.core.continuity.CommitmentRecord(
                    id = id,
                    gameId = game.id,
                    kind = kind,
                    fromEntityId = from,
                    toEntityId = to,
                    terms = change.terms.take(MAX_TERMS),
                    importance = change.importanceOrNull() ?: Importance.MEDIUM,
                    amount = change.amount.toLong().coerceAtLeast(0),
                    createdWorldMinutes = newWorldTime.totalMinutes,
                    dueWorldMinutes = change.dueInHours
                        .takeIf { it > 0 }
                        ?.let { newWorldTime.totalMinutes + it * 60L },
                    originEventId = rootEventId,
                )
                emit(
                    EventType.PLAYER_MADE_PROMISE,
                    "${kind.name.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${change.terms.take(MAX_TERMS)}",
                    Importance.HIGH,
                    actorId = from,
                    targetId = to,
                    related = listOf(id),
                )
            } else {
                val status = change.closingStatus() ?: continue
                val existing = assembly.context.commitments.firstOrNull { it.id == change.commitmentId } ?: continue
                commitmentWrites += existing.copy(
                    status = status,
                    resolvedWorldMinutes = newWorldTime.totalMinutes,
                    resolutionEventId = rootEventId,
                )
                emit(
                    if (status == com.unbound.core.continuity.CommitmentStatus.BROKEN) {
                        EventType.PLAYER_BROKE_PROMISE
                    } else {
                        EventType.PLAYER_MADE_PROMISE
                    },
                    "${status.name.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${existing.terms}",
                    if (status == com.unbound.core.continuity.CommitmentStatus.BROKEN) Importance.HIGH else Importance.MEDIUM,
                    actorId = existing.fromEntityId,
                    targetId = existing.toEntityId,
                    related = listOf(existing.id),
                )
            }
        }

        // --- persist ------------------------------------------------------------------------------
        store.upsertPlayer(updatedPlayer)
        store.upsertWorld(updatedWorld)
        if (npcEdits.isNotEmpty()) store.upsertNpcs(npcEdits.values)
        if (itemEdits.isNotEmpty()) store.upsertItems(itemEdits.values)
        if (locationEdits.isNotEmpty()) store.upsertLocations(locationEdits.values)
        if (factionEdits.isNotEmpty()) store.upsertFactions(factionEdits.values)
        if (relationshipEdits.isNotEmpty()) store.upsertRelationships(relationshipEdits.values)
        if (knowledgeRecords.isNotEmpty()) store.upsertKnowledge(knowledgeRecords)
        if (commitmentWrites.isNotEmpty()) store.upsertCommitments(commitmentWrites)
        if (rumorRecords.isNotEmpty() || simulation.rumorUpdates.isNotEmpty()) {
            store.upsertRumors(rumorRecords + simulation.rumorUpdates)
        }
        if (newMemories.isNotEmpty() || reinforced.isNotEmpty()) store.upsertMemories(newMemories + reinforced)

        // Compact the tail periodically, for the people this turn actually involved. Without this
        // an owner's memories grow for the life of the campaign and every one of them stays a
        // retrieval candidate.
        if (turn.turnNumber % config.consolidateEveryTurns == 0) {
            val owners = (presentNpcs.map { it.id } + Ids.PLAYER + MemoryRecord.WORLD_OWNER).distinct()
            for (owner in owners) {
                val held = store.memoriesOf(game.id, owner, MAX_CONSOLIDATION_SCAN)
                val plan = consolidator.consolidate(held, newWorldTime.totalMinutes)
                if (plan.created.isEmpty() && plan.removedIds.isEmpty()) continue
                store.upsertMemories(plan.created)
                store.deleteMemories(plan.removedIds)
            }
        }
        if (threadEdits.isNotEmpty()) store.upsertThreads(threadEdits.values)
        store.appendEvents(events)

        // Close a chapter every so often, from the ledger alone. Free, and it is what lets turn 400
        // still say something about turns 1-20.
        if (ChapterSummariser.closesChapter(turn.turnNumber)) {
            val from = turn.turnNumber - ChapterSummariser.CHAPTER_TURNS + 1
            val window = store.eventsPage(game.id, 0, CHAPTER_EVENT_SCAN)
                .filter { it.turnId != null }
                .sortedBy { it.sequence }
            chapterSummariser.summarise(
                gameId = game.id,
                fromTurn = from,
                toTurn = turn.turnNumber,
                events = window.filter { it.worldMinutes >= turn.worldMinutesBefore - CHAPTER_LOOKBACK_MINUTES },
                nowWorldMinutes = newWorldTime.totalMinutes,
            )?.let { store.upsertSummaries(listOf(it)) }
        }

        val completedTurn = turn.copy(
            narrative = response.narrative,
            status = TurnStatus.COMPLETE,
            completedAtEpochMs = now,
            worldMinutesAfter = newWorldTime.totalMinutes,
            suggestedActions = if (current.suggestedActionsEnabled) response.suggestedActions.take(5) else emptyList(),
            playerDialogue = response.playerDialogue.filter { it.isNotBlank() }.take(12),
            errorMessage = null,
            // Kept so the next turn can tell the model what moved while the player was busy.
            worldNotes = simulation.notes.map { it.summary }.take(MAX_WORLD_NOTES),
            modelId = usage.modelId,
            providerId = usage.providerId ?: game.textProviderId,
        )
        store.upsertTurn(completedTurn)

        // Take the lock before writing the game row: the conditional bump is the commit point.
        if (!store.bumpStateVersion(game.id, turn.baseStateVersion)) {
            throw StaleStateException("Lost the optimistic lock while committing.")
        }
        val updatedGame = current.copy(
            turnNumber = turn.turnNumber,
            worldTime = newWorldTime,
            currentLocationId = updatedPlayer.currentLocationId,
            updatedAtEpochMs = now,
            stateVersion = turn.baseStateVersion + 1,
        )
        store.upsertGame(updatedGame)

        store.recordUsage(
            UsageRecord(
                id = idFactory(),
                gameId = game.id,
                turnId = turn.id,
                timestampMs = now,
                requestType = RequestType.NARRATIVE_TURN,
                modelId = usage.modelId,
                providerId = usage.providerId ?: game.textProviderId,
                inputTokens = usage.usage.inputTokens,
                outputTokens = usage.usage.outputTokens,
                cachedTokens = usage.usage.cachedInputTokens,
                latencyMs = latencyMs,
                success = true,
            ),
        )

        TurnOutcome.Success(
            turn = completedTurn,
            game = updatedGame,
            narrative = response.narrative,
            suggestedActions = completedTurn.suggestedActions,
            appliedEvents = events,
            issues = issues,
            diagnostics = TurnDiagnostics(
                retrievedMemoryIds = assembly.context.retrieved.memoryIds,
                retrievedEventIds = assembly.context.retrieved.eventIds,
                contextCharacters = assembly.contextLength,
                contextSections = sectionsOf(assembly.dynamicContext),
                retrievedMemories = assembly.context.retrieved.memories.map { it.memory.text },
                retrievedEvents = assembly.context.retrieved.recentEvents.map { it.summary },
                npcKnowledgeCounts = assembly.context.npcKnowledge.entries.associate { (id, facts) ->
                    (assembly.context.relevantNpcs.firstOrNull { it.id == id }?.name ?: id) to facts.size
                },
                openCommitments = assembly.context.commitments.size,
                transcriptTurns = assembly.context.recentTurns.size,
                providerId = usage.providerId ?: game.textProviderId,
                modelId = usage.modelId,
                context = assembly.dynamicContext.takeIf { config.captureContext },
                inputTokens = usage.usage.inputTokens,
                outputTokens = usage.usage.outputTokens,
                cachedTokens = usage.usage.cachedInputTokens,
                latencyMs = latencyMs,
                appliedOps = ops.size,
                rejected = issues.size,
                sceneIsSignificant = response.sceneIsSignificant,
            ),
            servedByFallbackFrom = usage.servedByFallbackFrom,
        )
    }

    private fun failureUsage(
        gameId: String,
        turnId: String,
        modelId: String,
        providerId: String,
        latency: Long,
        kind: AIErrorKind,
    ) = UsageRecord(
        id = idFactory(),
        gameId = gameId,
        turnId = turnId,
        timestampMs = clock(),
        requestType = RequestType.NARRATIVE_TURN,
        modelId = modelId,
        providerId = providerId,
        latencyMs = latency,
        success = false,
        errorKind = kind.name,
    )

    /** Models occasionally wrap strict JSON in a fence. Recover rather than failing the turn. */
    private fun extractJson(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{")) return text
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        if (fenced != null && fenced.startsWith("{")) return fenced
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else text
    }

    private data class Assembly(
        val context: TurnContext,
        val validationContext: ValidationContext,
        val openThreads: List<ThreadRecord>,
        val query: RetrievalQuery,
        val dynamicContext: String,
        /** Read during assembly and reused by the commit phase rather than re-queried. */
        val allNpcs: List<NpcRecord>,
        val allFactions: List<FactionRecord>,
    ) {
        val contextLength: Int get() = dynamicContext.length
    }

    private companion object {
        /** How many turns a character stays "in play" after last appearing. */
        const val RECENTLY_SEEN_TURNS = 2

        /** Bounded scan for the chapter digest; a chapter is 20 turns, never thousands of events. */
        /** Shown in place of a GM staging instruction, which is not something the player said. */
        const val DIRECTIVE_INPUT = "Begin."

        /** Below a few hours, nothing has had time to cool off. */
        const val MAX_WORLD_NOTES = 6
        const val MAX_ACTION_SUMMARY = 180
        const val MAX_TERMS = 240
        const val DECAY_THRESHOLD_MINUTES = 240
        const val MAX_DECAY_ROWS = 80

        const val MAX_CONSOLIDATION_SCAN = 400

        const val CHAPTER_EVENT_SCAN = 600
        const val CHAPTER_LOOKBACK_MINUTES = 60L * 24 * 60
    }

    private object CommandParserHolder {
        val parser = com.unbound.core.command.CommandParser()
    }
}

class StaleStateException(message: String) : Exception(message)

data class PipelineConfig(
    val maxOutputTokens: Int = 2400,
    val maxTimeAdvanceMinutes: Int = 60 * 24 * 14,
    val snapshotEveryTurns: Int = 15,
    val consolidateEveryTurns: Int = 25,
    /**
     * Keeps the assembled context on each turn's diagnostics.
     *
     * Off by default because it holds a few kilobytes per turn in memory for no benefit to a
     * player. On, it is the only way to answer "why did the world forget that?" — the question
     * every other diagnostic only circles around.
     */
    val captureContext: Boolean = false,
)

/**
 * Enough to tell why a turn went the way it did (§26).
 *
 * Deliberately includes what was *rejected* and what was *retrieved*, not only what was applied:
 * a continuity failure is almost always something that was not retrieved or something that was
 * thrown out, and neither shows up in a record of what changed.
 *
 * Carries no credential and no key, ever.
 */
data class TurnDiagnostics(
    val retrievedMemoryIds: List<String>,
    val retrievedEventIds: List<String>,
    val contextCharacters: Int,
    /** Section headings actually included this turn, in order, with how many lines each carried. */
    val contextSections: List<String> = emptyList(),
    /** The memories that reached the model, in the order they were ranked. */
    val retrievedMemories: List<String> = emptyList(),
    /** Events sent as history, newest last. */
    val retrievedEvents: List<String> = emptyList(),
    /** How many facts each character in the scene was given. */
    val npcKnowledgeCounts: Map<String, Int> = emptyMap(),
    /** Obligations the turn was told about. */
    val openCommitments: Int = 0,
    /** How many turns of transcript were included. */
    val transcriptTurns: Int = 0,
    val providerId: String? = null,
    val modelId: String? = null,
    /** The whole assembled context, when [PipelineConfig.captureContext] is on. */
    val context: String? = null,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int,
    val latencyMs: Long,
    val appliedOps: Int,
    val rejected: Int,
    val sceneIsSignificant: Boolean,
)

sealed interface TurnOutcome {
    data class Success(
        val turn: TurnRecord,
        val game: GameRecord,
        val narrative: String,
        val suggestedActions: List<String>,
        val appliedEvents: List<GameEvent>,
        val issues: List<ValidationIssue>,
        val diagnostics: TurnDiagnostics,
        /**
         * Set when a second provider served this turn because the chosen one was unreachable.
         *
         * Surfaced rather than swallowed: a different narrator changes how the game reads, and a
         * player who is not told will blame the model they thought they were using.
         */
        val servedByFallbackFrom: String? = null,
    ) : TurnOutcome

    /** The turn was not applied. The world is exactly as it was. */
    data class Failure(
        val message: String,
        val kind: AIErrorKind,
        val retryable: Boolean,
        val turnId: String? = null,
        val idempotencyKey: String? = null,
        val issues: List<ValidationIssue> = emptyList(),
    ) : TurnOutcome

    /** This idempotency key already committed. Returned unchanged rather than applied twice. */
    data class Replayed(val turn: TurnRecord) : TurnOutcome
}
