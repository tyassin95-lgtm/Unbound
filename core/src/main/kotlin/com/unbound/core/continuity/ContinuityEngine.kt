package com.unbound.core.continuity

import com.unbound.core.command.CommandParser
import com.unbound.core.engine.MemoryRetriever
import com.unbound.core.engine.WorldStore
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.RetrievalBudget
import com.unbound.core.memory.RetrievalQuery
import com.unbound.core.model.GameRecord
import com.unbound.core.model.Ids
import com.unbound.core.model.Importance
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.WorldRecord
import com.unbound.core.prompt.TurnContext
import com.unbound.core.validate.ValidationContext

/**
 * The layer between the database and the model.
 *
 * Its single question is: *what does the model need to know right now in order to continue this
 * world correctly?* It is deliberately separate from the turn pipeline, which is about committing
 * state, and from [com.unbound.core.prompt.ContextBuilder], which is about rendering. Assembly is
 * where relevance is decided, and it is the part most worth testing on its own.
 *
 * ## What the audit found
 *
 * Continuity was weak, but not for the reason it looked. The database was already rich — NPCs
 * carried goals, fears, desires and secrets; items carried provenance; events carried a
 * `causedByEventId` field; relationships carried their reasons. Almost none of it reached the
 * model. The context builder sent personality and a mood, and the assembly hard-coded
 * `worldNotes = emptyList()` so the section the system prompt told the model to honour never
 * rendered at all.
 *
 * Worse, the **transcript was never sent**. `recentTurns` was read every turn and used only to
 * detect repetition; no prior narration and no prior player input ever reached the model. Every
 * turn arrived as a fresh dossier with no conversation attached, which is why a player had to keep
 * restating what had just been said.
 *
 * So the fix is not a longer prompt. It is sending the state that was already being kept, plus
 * enough recent dialogue for the scene to read as continuous.
 */
class ContinuityEngine(
    private val store: WorldStore,
    private val retriever: MemoryRetriever,
    private val budget: RetrievalBudget = RetrievalBudget(),
    private val parser: CommandParser = CommandParser(),
) {

    suspend fun assemble(
        game: GameRecord,
        world: WorldRecord,
        player: PlayerRecord,
        playerInput: String,
        directive: String? = null,
    ): Assembled {
        val location = store.getLocation(game.id, player.currentLocationId)
            ?: error("Player is at unknown location ${player.currentLocationId}")

        val present = store.npcsAt(game.id, location.id).filter { it.alive }
        val persistent = store.persistentNpcs(game.id, MAX_CAST)

        val mentioned = parser.resolveMentions(playerInput, persistent.associate { it.id to it.name })

        // Someone who was in the scene a turn or two ago is still relevant even if their stored
        // location says otherwise — that staleness is what used to make a character vanish from
        // her own conversation. Present first, then named, then recently seen, all capped.
        val recentlySeen = persistent.filter { npc ->
            npc.lastSeenTurn?.let { game.turnNumber - it <= RECENTLY_SEEN_TURNS } == true
        }
        val relevantNpcs = (present + persistent.filter { it.id in mentioned } + recentlySeen)
            .distinctBy { it.id }
            .take(budget.maxNpcs)

        val openThreads = store.activeThreads(game.id, budget.maxThreads)
        val allFactions = store.factions(game.id)
        val factions = allFactions.filter { it.discovered }.take(MAX_FACTIONS)
        val inventory = store.itemsOwnedBy(game.id, player.id)
        // Things the people in the room are carrying. Without these a turn could not move an item
        // the player can plainly see someone holding: the validator had no record of it, so the
        // transfer was rejected as a reference to nothing.
        val carriedByOthers = store.itemsOwnedByAny(game.id, present.map { it.id }).take(MAX_CARRIED_ITEMS)
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

        // Ranked, not simply the first rows the index happens to return. What the player knows is
        // the single most-consulted section — it is what stops them being told something they
        // already learned, and what stops a character revealing it twice.
        val playerKnowledge = rankPlayerKnowledge(
            store.knowledgeOf(game.id, Ids.PLAYER, PLAYER_KNOWLEDGE_SCAN),
            focusIds + factions.map { it.id }.toSet() + setOf(location.id),
        )

        val relationships = store.relationshipsFrom(game.id, Ids.PLAYER, MAX_RELATIONSHIPS)
            .associateBy { it.toEntityId }

        val reachable = (location.exits.values + location.nearbyLocationIds + location.id).toSet()
        val referencedLocations = store.locationsByIds(game.id, reachable.toList())
        val nearby = referencedLocations.filter { it.id != location.id }

        // The transcript. Not a substitute for state — a scene is a conversation, and a model that
        // cannot see what was just said has to infer it from a summary of it.
        val recentTurns = store.recentTurns(game.id, budget.maxTranscriptTurns)
            .sortedBy { it.turnNumber }

        // Obligations outlive the scene they were made in, so they are canonical state rather than
        // memory: a creditor turns up whether or not anyone remembered the debt.
        val commitments = store.openCommitments(game.id, MAX_COMMITMENTS)
            .filter { c ->
                c.fromEntityId == Ids.PLAYER || c.toEntityId == Ids.PLAYER ||
                    c.fromEntityId in focusIds || c.toEntityId in focusIds
            }
            .sortedWith(compareByDescending<CommitmentRecord> { it.isOverdue(game.worldTime.totalMinutes) }
                .thenByDescending { it.importance.ordinal })
            .take(budget.maxCommitments)

        // Why the present looks the way it does. Only for events already being sent, so this adds
        // a line each rather than a second history.
        val causes = causeChain(game.id, retrieved.olderImportantEvents + retrieved.recentEvents)

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
            carriedByOthers = carriedByOthers,
            factions = factions,
            threads = openThreads,
            rumors = rumors,
            retrieved = retrieved,
            // What the world did on its own last turn, so "while you were busy" is a real section
            // rather than a heading the assembly always left empty.
            worldNotes = recentTurns.lastOrNull()?.worldNotes.orEmpty(),
            nearbyLocations = nearby,
            repetitionWarning = detectRepetition(recentTurns),
            directive = directive,
            recentTurns = recentTurns,
            commitments = commitments,
            causes = causes,
            locationHistory = location.history.takeLast(MAX_LOCATION_HISTORY),
        )

        val validationContext = ValidationContext(
            game = game,
            player = player,
            world = world,
            npcs = (relevantNpcs + present).distinctBy { it.id }.associateBy { it.id },
            locations = (referencedLocations + location).distinctBy { it.id }.associateBy { it.id },
            factions = allFactions.associateBy { it.id },
            items = (inventory + carriedByOthers + store.itemsAt(game.id, location.id))
                .distinctBy { it.id }.associateBy { it.id },
            reachableLocationIds = reachable,
            specialRules = world.specialRules,
            // Everyone and everywhere this campaign actually has, so a reference to someone real
            // but not retrieved this turn is not mistaken for a reference to nobody.
            knownEntityIds = (persistent.map { it.id } + allFactions.map { it.id } +
                store.discoveredLocations(game.id, MAX_KNOWN_LOCATIONS).map { it.id }).toSet(),
            openCommitments = commitments,
        )

        return Assembled(
            context = context,
            validationContext = validationContext,
            openThreads = openThreads,
            query = query,
            allNpcs = persistent,
            allFactions = allFactions,
        )
    }

    /**
     * What the player knows, ordered by what this turn is about rather than by row order. A flat
     * `LIMIT 10` meant that after a hundred facts the ten the model saw were arbitrary.
     */
    private fun rankPlayerKnowledge(
        facts: List<com.unbound.core.knowledge.KnowledgeRecord>,
        focus: Set<String>,
    ) = facts
        .sortedWith(
            compareByDescending<com.unbound.core.knowledge.KnowledgeRecord> { f ->
                if (f.subjectEntityIds.any { it in focus }) 1 else 0
            }
                .thenByDescending { it.importance.ordinal }
                .thenByDescending { it.learnedAtWorldMinutes },
        )
        .take(budget.maxPlayerKnowledgeFacts)

    /**
     * Resolves the `causedBy` link of each event being sent, one level deep.
     *
     * One level is deliberate. A full ancestry would be unbounded and would mostly restate events
     * already in the context; what the model needs is the immediate "because", so that
     * "Mara will not serve you" arrives attached to the night you brought the watch to her door.
     */
    private suspend fun causeChain(gameId: String, events: List<GameEvent>): Map<String, GameEvent> {
        val wanted = events.mapNotNull { it.causedByEventId }.distinct().take(MAX_CAUSES)
        if (wanted.isEmpty()) return emptyMap()
        val found = store.eventsByIds(gameId, wanted)
        val byId = found.associateBy { it.id }
        return events.mapNotNull { e -> e.causedByEventId?.let { byId[it] }?.let { e.id to it } }.toMap()
    }

    private fun detectRepetition(recentTurns: List<com.unbound.core.model.TurnRecord>): String? {
        if (recentTurns.size < 3) return null
        val verbs = recentTurns.takeLast(3).map { it.playerInput.trim().lowercase().substringBefore(' ') }
        return if (verbs.distinct().size == 1 && verbs.first().length > 2) {
            "The player has done the same kind of thing three turns running. Change the pressure: " +
                "someone arrives or leaves, the weather turns, a deadline moves, or something is " +
                "overheard. Do not force the player to change what they are doing."
        } else {
            null
        }
    }

    /** What assembly produced, including the reads the commit phase reuses rather than repeating. */
    data class Assembled(
        val context: TurnContext,
        val validationContext: ValidationContext,
        val openThreads: List<com.unbound.core.model.ThreadRecord>,
        val query: RetrievalQuery,
        val allNpcs: List<NpcRecord>,
        val allFactions: List<com.unbound.core.model.FactionRecord>,
    )

    private companion object {
        const val RECENTLY_SEEN_TURNS = 3
        const val MAX_CAST = 120
        const val MAX_FACTIONS = 6
        const val MAX_RELATIONSHIPS = 60
        const val MAX_COMMITMENTS = 60
        const val MAX_CAUSES = 12
        const val MAX_LOCATION_HISTORY = 4
        const val MAX_CARRIED_ITEMS = 24
        const val MAX_KNOWN_LOCATIONS = 200

        /**
         * Scanned, then ranked down to the budget. Wider than what is sent, because ranking can
         * only choose between rows it was given.
         */
        const val PLAYER_KNOWLEDGE_SCAN = 120
    }
}

