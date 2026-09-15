package com.unbound.core.engine

import com.unbound.core.content.SeedWorld
import com.unbound.core.knowledge.Certainty
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.ledger.EventType
import com.unbound.core.ledger.GameEvent
import com.unbound.core.ledger.KnowledgeScope
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.MemoryVisibility
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord

/**
 * Builds a complete, internally consistent campaign from a seed world and a character (§12, §17).
 *
 * Everything is written in one transaction, and the world starts *small*: only the starting
 * location is DETAILED, its neighbours are SKETCHED, and everything else is a STUB to be expanded
 * when the player actually goes there (§18). Nothing is generated for places the player may never
 * visit.
 */
class GameFactory(
    private val store: WorldStore,
    private val clock: () -> Long,
    private val idFactory: () -> String,
) {

    suspend fun createGame(request: NewGameRequest): GameRecord = store.transaction {
        val seed = request.seed
        val gameId = Ids.game(idFactory())
        val now = clock()

        // Seed keys are namespaced per game so two campaigns in the same setting never collide.
        val locIds = seed.locations.associate { it.key to Ids.location("${gameId}_${it.key}") }
        val npcIds = seed.npcs.associate { it.key to Ids.npc("${gameId}_${it.key}") }
        val facIds = seed.factions.associate { it.key to Ids.faction("${gameId}_${it.key}") }

        val startLocationId = locIds.getValue(seed.startLocationKey)
        val startLocation = seed.locations.first { it.key == seed.startLocationKey }
        val neighbourKeys = startLocation.exits.values.toSet()

        val locations = seed.locations.map { l ->
            LocationRecord(
                id = locIds.getValue(l.key),
                gameId = gameId,
                name = l.name,
                description = l.description,
                type = l.type,
                exits = l.exits.mapValues { (_, dest) -> locIds[dest] ?: dest },
                nearbyLocationIds = l.exits.values.mapNotNull { locIds[it] },
                hazards = l.hazards,
                hiddenDetails = l.hidden,
                discovered = l.key == seed.startLocationKey,
                detailLevel = when {
                    l.key == seed.startLocationKey -> DetailLevel.DETAILED
                    l.key in neighbourKeys -> DetailLevel.SKETCHED
                    else -> DetailLevel.STUB
                },
            )
        }

        val factions = seed.factions.map { f ->
            FactionRecord(
                id = facIds.getValue(f.key),
                gameId = gameId,
                name = f.name,
                purpose = f.purpose,
                currentObjectives = f.objectives,
                publicReputation = f.reputation,
                discovered = true,
            )
        }

        val npcs = seed.npcs.map { n ->
            NpcRecord(
                id = npcIds.getValue(n.key),
                gameId = gameId,
                name = n.name,
                age = n.age,
                gender = n.gender,
                appearance = Appearance(summary = n.appearance),
                occupation = n.occupation,
                personality = n.personality,
                currentPlan = n.wants,
                secrets = listOf(n.secret),
                factionId = n.factionKey?.let { facIds[it] },
                currentLocationId = locIds.getValue(n.locationKey),
                homeLocationId = locIds.getValue(n.locationKey),
                schedule = DailySchedule(defaultLocationId = locIds.getValue(n.locationKey)),
                tier = NpcTier.PERSISTENT,
                firstEncounteredTurn = null,
            )
        }

        val player = PlayerRecord(
            gameId = gameId,
            name = request.name,
            age = request.age,
            gender = request.gender,
            appearance = request.appearance,
            personality = request.personality,
            desires = request.desires,
            fears = request.fears,
            skills = request.skills,
            weaknesses = request.weaknesses,
            background = request.background,
            goals = request.goals,
            secrets = request.secrets,
            currency = request.startingCurrency,
            currencyName = seed.currencyName,
            currentLocationId = startLocationId,
        )

        val world = WorldRecord(
            gameId = gameId,
            summary = seed.summary,
            region = seed.region,
            era = seed.era,
            weather = seed.weather,
            socialHierarchy = seed.socialHierarchy,
            economy = seed.economy,
            laws = seed.laws,
            religion = seed.religion,
            dangers = seed.dangers,
            majorHistory = seed.history,
            currentConflicts = seed.conflicts,
            currencyName = seed.currencyName,
            specialRules = seed.specialRules,
        )

        val game = GameRecord(
            id = gameId,
            title = request.title.ifBlank { "${request.name} — ${seed.name}" },
            settingId = seed.id,
            settingName = seed.name,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            currentLocationId = startLocationId,
            worldTime = WorldTime.DEFAULT,
            tone = request.tone ?: seed.toneHint,
            narrationLength = request.narrationLength,
            limits = request.limits,
            textModelId = request.textModelId,
            imageModelId = request.imageModelId,
            imageMode = request.imageMode,
        )

        val threads = seed.threads.map { t ->
            ThreadRecord(
                id = Ids.thread(idFactory()),
                gameId = gameId,
                type = t.type,
                title = t.title,
                description = t.description,
                involvedEntityIds = t.involvedKeys.mapNotNull { npcIds[it] ?: facIds[it] },
                stakes = t.stakes,
                lastActivityWorldMinutes = WorldTime.DEFAULT.totalMinutes,
                importance = t.importance,
                // Hidden at the start: the player has not learned about these yet, but the
                // simulator is already moving them.
                visibility = ThreadVisibility.HIDDEN,
            )
        }

        // Each NPC starts knowing their own secret and nothing of anybody else's. This is the
        // ground state of the knowledge system — world truth is never pre-shared.
        val knowledge = seed.npcs.map { n ->
            KnowledgeRecord(
                id = idFactory(),
                gameId = gameId,
                knowerId = npcIds.getValue(n.key),
                factKey = "${n.key}.own_secret",
                statement = n.secret,
                certainty = Certainty.KNOWN,
                subjectEntityIds = listOf(npcIds.getValue(n.key)),
                learnedAtWorldMinutes = WorldTime.DEFAULT.totalMinutes,
                importance = Importance.HIGH,
                secret = true,
            )
        }

        // The player starts as a stranger to everyone: no relationship row implies STRANGER, but
        // creating them explicitly means the journal has something honest to show from turn one.
        val relationships = npcs.map { n ->
            RelationshipRecord(
                id = RelationshipRecord.key(gameId, Ids.PLAYER, n.id),
                gameId = gameId,
                fromEntityId = Ids.PLAYER,
                toEntityId = n.id,
                lastInteractionWorldMinutes = WorldTime.DEFAULT.totalMinutes,
            )
        }

        // Possessions are real entities from the first turn, so they can be given away, stolen,
        // broken and tracked like anything else rather than existing only as prose.
        val startingItems = request.startingPossessions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(8)
            .map { name ->
                ItemRecord(
                    id = Ids.item(idFactory()),
                    gameId = gameId,
                    name = name,
                    description = "Carried since before the story began.",
                    ownerId = Ids.PLAYER,
                    provenance = listOf("brought into the story by ${request.name}"),
                )
            }

        val openingEvent = GameEvent(
            id = Ids.event(idFactory()),
            gameId = gameId,
            turnId = null,
            sequence = 1,
            worldMinutes = WorldTime.DEFAULT.totalMinutes,
            realTimestampMs = now,
            type = EventType.GAME_STARTED,
            actorId = Ids.PLAYER,
            locationId = startLocationId,
            summary = "${request.name} arrived at ${startLocation.name}.",
            importance = Importance.HIGH,
            knowledgeScope = KnowledgeScope.WITNESSED,
        )

        store.upsertGame(game)
        store.upsertWorld(world)
        store.upsertPlayer(player)
        store.upsertLocations(locations)
        store.upsertFactions(factions)
        store.upsertNpcs(npcs)
        if (startingItems.isNotEmpty()) store.upsertItems(startingItems)
        store.upsertThreads(threads)
        store.upsertKnowledge(knowledge)
        store.upsertRelationships(relationships)
        store.appendEvents(listOf(openingEvent))
        store.upsertMemories(
            listOf(
                MemoryRecord(
                    id = Ids.memory(idFactory()),
                    gameId = gameId,
                    ownerId = MemoryRecord.WORLD_OWNER,
                    text = "${request.name} arrived at ${startLocation.name} in ${seed.region}.",
                    entityIds = listOf(Ids.PLAYER),
                    locationId = startLocationId,
                    sourceEventIds = listOf(openingEvent.id),
                    importance = Importance.HIGH,
                    createdAtWorldMinutes = WorldTime.DEFAULT.totalMinutes,
                    lastReinforcedWorldMinutes = WorldTime.DEFAULT.totalMinutes,
                    visibility = MemoryVisibility.WORLD,
                ),
            ),
        )

        game
    }

    /**
     * The opening prompt. Kept separate from [createGame] so that world creation always succeeds
     * offline and only the prose depends on the network (§63).
     */
    fun openingInstruction(seed: SeedWorld, hook: String?): String = buildString {
        append("Open the campaign. Establish the place, the hour, the weather, the sounds and smells, ")
        append("who holds power here and who is suffering for it, what ordinary life looks like, and ")
        append("who is physically nearby. Put one concrete pressure in front of the player. ")
        if (!hook.isNullOrBlank()) append("Begin from this situation: $hook ")
        append("End at a natural point where the player can act. Do not decide what they do, think or feel.")
    }
}

data class NewGameRequest(
    val seed: SeedWorld,
    val title: String = "",
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
    /** Zero unless something decided otherwise. There is no default "starting money" in UNBOUND. */
    val startingCurrency: Long = 0,
    val textModelId: String,
    val imageModelId: String? = null,
    val imageMode: ImageMode = ImageMode.ON_DEMAND,
    val tone: Tone? = null,
    val narrationLength: NarrationLength = NarrationLength.NORMAL,
    val limits: List<String> = emptyList(),
    /** Things the character begins with, beyond money. Named items become real entities. */
    val startingPossessions: List<String> = emptyList(),
) {
    init {
        require(age >= 18) { "The protagonist must be an adult." }
        require(name.isNotBlank()) { "The protagonist needs a name." }
    }
}
