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
import com.unbound.core.memory.MemoryExtractor
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.MemoryVisibility
import com.unbound.core.memory.RetrievalBudget
import com.unbound.core.memory.RetrievalQuery
import com.unbound.core.model.*
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
import com.unbound.core.validate.StateValidator
import com.unbound.core.validate.TurnResponseDto
import com.unbound.core.validate.ValidationContext
import com.unbound.core.validate.ValidationIssue
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
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val memoryExtractor = MemoryExtractor(idFactory)

    /**
     * @param idempotencyKey pass the *same* key when retrying a failed turn. A turn that already
     *   committed under this key is returned as-is rather than applied twice (§45).
     */
    suspend fun execute(
        gameId: String,
        playerInput: String,
        idempotencyKey: String = idFactory(),
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
            assembleContext(game, world, player, playerInput)
        } catch (e: Exception) {
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.FAILED, errorMessage = "Context assembly failed: ${e.message}"))
            return TurnOutcome.Failure("Could not assemble the world state for this turn.", AIErrorKind.UNKNOWN, retryable = true)
        }

        val guardClause = com.unbound.core.safety.ContentGuard.sceneClause(player, assembly.context.presentNpcs)
        val request = AITextRequest(
            modelId = game.textModelId,
            stableSystemPrompt = PromptModules.stableSystemPrompt(),
            dynamicContext = if (guardClause == null) assembly.dynamicContext else assembly.dynamicContext + "\n\n## " + guardClause,
            userInput = playerInput,
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
            store.recordUsage(failureUsage(gameId, turnId, game.textModelId, clock() - started, e.kind))
            return TurnOutcome.Failure(e.message, e.kind, retryable = e.kind.retryable, turnId = turnId, idempotencyKey = idempotencyKey)
        }

        val parsed = try {
            json.decodeFromString(TurnResponseDto.serializer(), extractJson(aiResponse.text))
        } catch (e: Exception) {
            store.upsertTurn(pendingTurn.copy(status = TurnStatus.PENDING, errorMessage = "Malformed response: ${e.message}"))
            store.recordUsage(failureUsage(gameId, turnId, game.textModelId, clock() - started, AIErrorKind.MALFORMED_RESPONSE))
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
                ops = validation.ops,
                issues = validation.issues,
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
    ): Assembly {
        val location = store.getLocation(game.id, player.currentLocationId)
            ?: error("Player is at unknown location ${player.currentLocationId}")

        val present = store.npcsAt(game.id, location.id).filter { it.alive }
        val persistent = store.persistentNpcs(game.id, 120)

        val mentionCandidates = persistent.associate { it.id to it.name }
        val mentioned = CommandParserHolder.parser.resolveMentions(playerInput, mentionCandidates)

        val relevantNpcs = (present + persistent.filter { it.id in mentioned })
            .distinctBy { it.id }
            .take(budget.maxNpcs)

        val openThreads = store.activeThreads(game.id, budget.maxThreads)

        val factions = store.factions(game.id).filter { it.discovered }.take(6)
        val inventory = store.itemsOwnedBy(game.id, player.id)
        val rumors = store.activeRumors(game.id, budget.maxRumors)

        val focusIds = mentioned + relevantNpcs.map { it.id }
        val query = RetrievalQuery(
            gameId = game.id,
            now = game.worldTime,
            currentLocationId = location.id,
            presentNpcIds = present.map { it.id }.toSet(),
            focusEntityIds = focusIds,
            activeThreadIds = openThreads.map { it.id }.toSet(),
            factionIds = factions.map { it.id }.toSet(),
            itemIds = inventory.map { it.id }.toSet(),
            inputText = playerInput,
        )
        val retrieved = retriever.retrieve(query, budget)

        val subjects = (focusIds + setOf(Ids.PLAYER) + inventory.map { it.id }).toSet()
        val npcKnowledge = retriever.npcKnowledge(game.id, relevantNpcs.map { it.id }, subjects, budget)
        val playerKnowledge = store.knowledgeOf(game.id, Ids.PLAYER, 10)

        val relationships = store.relationshipsFrom(game.id, Ids.PLAYER, 60)
            .associateBy { it.toEntityId }

        val nearby = store.locationsByIds(game.id, (location.exits.values + location.nearbyLocationIds).distinct())

        val recentTurns = store.recentTurns(game.id, 3)
        val repetition = detectRepetition(recentTurns)

        val context = TurnContext(
            game = game,
            world = world,
            player = player,
            location = location,
            presentNpcs = present,
            relevantNpcs = relevantNpcs,
            relationships = relationships,
            npcKnowledge = npcKnowledge,
            playerKnowledge = playerKnowledge,
            inventory = inventory,
            factions = factions,
            threads = openThreads,
            rumors = rumors,
            retrieved = retrieved,
            worldNotes = emptyList(),
            nearbyLocations = nearby,
            repetitionWarning = repetition,
        )

        val reachable = (location.exits.values + location.nearbyLocationIds + location.id).toSet()
        val referencedLocations = store.locationsByIds(game.id, reachable)

        val validationContext = ValidationContext(
            game = game,
            player = player,
            world = world,
            npcs = (relevantNpcs + present).distinctBy { it.id }.associateBy { it.id },
            locations = (referencedLocations + location).distinctBy { it.id }.associateBy { it.id },
            factions = store.factions(game.id).associateBy { it.id },
            items = (inventory + store.itemsAt(game.id, location.id)).distinctBy { it.id }.associateBy { it.id },
            reachableLocationIds = reachable,
            specialRules = world.specialRules,
        )

        return Assembly(context, validationContext, openThreads, query, contextBuilder.build(context))
    }

    private fun detectRepetition(recentTurns: List<TurnRecord>): String? {
        if (recentTurns.size < 3) return null
        val verbs = recentTurns.map { it.playerInput.trim().lowercase().substringBefore(' ') }
        return if (verbs.distinct().size == 1 && verbs.first().length > 2) {
            "The player has done the same kind of thing three turns running. Change the pressure: " +
                "someone arrives or leaves, the weather turns, a deadline moves, or something is " +
                "overheard. Do not force the player to change what they are doing."
        } else {
            null
        }
    }

    private suspend fun commit(
        game: GameRecord,
        world: WorldRecord,
        player: PlayerRecord,
        turn: TurnRecord,
        assembly: Assembly,
        response: TurnResponseDto,
        ops: List<StateOp>,
        issues: List<ValidationIssue>,
        usage: com.unbound.core.ai.AITextResponse,
        latencyMs: Long,
    ): TurnOutcome = store.transaction {
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
        ) {
            events += GameEvent(
                id = Ids.event(idFactory()),
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
                    val key = RelationshipRecord.key(game.id, op.fromEntityId, op.toEntityId)
                    val existing = relationshipEdits[key]
                        ?: store.getRelationship(game.id, op.fromEntityId, op.toEntityId)
                        ?: RelationshipRecord(key, game.id, op.fromEntityId, op.toEntityId)
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
        for (dto in response.events) {
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

        // --- knowledge ---------------------------------------------------------------------------
        val presentNpcs = assembly.context.presentNpcs.map { npcEdits[it.id] ?: it }
        val knowledgeRecords = mutableListOf<KnowledgeRecord>()
        val rumorRecords = mutableListOf<com.unbound.core.knowledge.RumorRecord>()

        for (event in events.filter { it.importance.isMemorable }) {
            val result = propagator.propagate(event, presentNpcs)
            knowledgeRecords += result.knowledge
            result.rumor?.let { rumorRecords += it }
        }

        for (dto in response.knowledgeChanges) {
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

        // --- new characters ---------------------------------------------------------------------
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
            emit(EventType.NPC_INTRODUCED, "Met ${created.name}, ${created.occupation.ifBlank { "age ${created.age}" }}.", Importance.LOW, targetId = created.id)
        }

        // --- npc actions --------------------------------------------------------------------------
        for (action in response.npcActions) {
            val n = npcEdits[action.npcId] ?: store.getNpc(game.id, action.npcId) ?: continue
            if (!n.alive) continue
            var updated = n.copy(currentPlan = action.action, lastSeenTurn = turn.turnNumber, lastSeenWorldMinutes = newWorldTime.totalMinutes)
            action.movesToLocationId?.let { updated = updated.copy(currentLocationId = it) }
            npcEdits[n.id] = updated
            if (action.becomesHostile) {
                val key = RelationshipRecord.key(game.id, n.id, Ids.PLAYER)
                val existing = store.getRelationship(game.id, n.id, Ids.PLAYER) ?: RelationshipRecord(key, game.id, n.id, Ids.PLAYER)
                relationshipEdits[key] = relationshipEngine.apply(existing, mapOf("hostility" to 30, "trust" to -20), action.action, newWorldTime.totalMinutes)
                emit(EventType.NPC_BECAME_HOSTILE, "${n.name} turned on the player.", Importance.HIGH, actorId = n.id, targetId = Ids.PLAYER)
            }
        }

        // --- world simulation for elapsed time ---------------------------------------------------
        val simulation = simulator.simulate(
            SimulationInput(
                previous = current.worldTime,
                now = newWorldTime,
                playerLocationId = updatedPlayer.currentLocationId,
                weather = updatedWorld.weather,
                npcs = store.persistentNpcs(game.id, 150).map { npcEdits[it.id] ?: it },
                factions = store.factions(game.id).map { factionEdits[it.id] ?: it },
                threads = assembly.openThreads.map { threadEdits[it.id] ?: it },
                rumors = store.activeRumors(game.id, 40),
                engagedThreadIds = threadEdits.keys,
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

        if (timeAdvance > 0) {
            emit(EventType.TIME_ADVANCED, "$timeAdvance minutes passed.", Importance.TRIVIAL, scope = KnowledgeScope.PUBLIC)
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
        if (rumorRecords.isNotEmpty() || simulation.rumorUpdates.isNotEmpty()) {
            store.upsertRumors(rumorRecords + simulation.rumorUpdates)
        }
        if (newMemories.isNotEmpty() || reinforced.isNotEmpty()) store.upsertMemories(newMemories + reinforced)
        if (threadEdits.isNotEmpty()) store.upsertThreads(threadEdits.values)
        store.appendEvents(events)

        val completedTurn = turn.copy(
            narrative = response.narrative,
            status = TurnStatus.COMPLETE,
            completedAtEpochMs = now,
            worldMinutesAfter = newWorldTime.totalMinutes,
            suggestedActions = if (current.suggestedActionsEnabled) response.suggestedActions.take(5) else emptyList(),
            errorMessage = null,
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
                inputTokens = usage.usage.inputTokens,
                outputTokens = usage.usage.outputTokens,
                cachedTokens = usage.usage.cachedInputTokens,
                latencyMs = latencyMs,
                appliedOps = ops.size,
                rejected = issues.size,
                sceneIsSignificant = response.sceneIsSignificant,
            ),
        )
    }

    private fun failureUsage(gameId: String, turnId: String, modelId: String, latency: Long, kind: AIErrorKind) = UsageRecord(
        id = idFactory(),
        gameId = gameId,
        turnId = turnId,
        timestampMs = clock(),
        requestType = RequestType.NARRATIVE_TURN,
        modelId = modelId,
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
    ) {
        val contextLength: Int get() = dynamicContext.length
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
)

data class TurnDiagnostics(
    val retrievedMemoryIds: List<String>,
    val retrievedEventIds: List<String>,
    val contextCharacters: Int,
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
