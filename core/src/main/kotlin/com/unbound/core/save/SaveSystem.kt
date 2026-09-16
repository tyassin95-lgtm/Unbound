package com.unbound.core.save

import com.unbound.core.engine.WorldStore
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Portable saves (§47, §48).
 *
 * The one absolute rule: **a save never contains a credential.** There is no field for one in
 * [SaveBundle], the export reads only from the world store (which has no access to the key store at
 * all), and a test asserts that the serialized bytes contain nothing key-shaped. Making it
 * structurally impossible is better than remembering to strip it.
 */
@Serializable
data class SaveBundle(
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAtEpochMs: Long,
    val game: GameRecord,
    val world: WorldRecord,
    val player: PlayerRecord,
    val npcs: List<NpcRecord>,
    val locations: List<LocationRecord>,
    val factions: List<FactionRecord>,
    val items: List<ItemRecord>,
    val threads: List<ThreadRecord>,
    val relationships: List<RelationshipRecord>,
    val knowledge: List<KnowledgeRecord>,
    val rumors: List<RumorRecord>,
    val memories: List<MemoryRecord>,
    val summaries: List<SummaryRecord>,
    val events: List<GameEvent>,
    val turns: List<TurnRecord>,
    /** Metadata only — image bytes stay on the device that made them. */
    val images: List<ImageRecord>,
    /**
     * Promises, debts and deals. Defaulted so a save written by format 1 still imports: an older
     * campaign simply arrives with no obligations recorded, which is true of it.
     */
    val commitments: List<com.unbound.core.continuity.CommitmentRecord> = emptyList(),
) {
    companion object {
        /** 2 added commitments. Bundles at version 1 still import; the field defaults to empty. */
        const val FORMAT_VERSION = 2
    }
}

class SaveSystem(private val store: WorldStore, private val clock: () -> Long, private val idFactory: () -> String) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun export(gameId: String): String {
        val bundle = buildBundle(gameId)
        return json.encodeToString(SaveBundle.serializer(), bundle)
    }

    suspend fun buildBundle(gameId: String): SaveBundle {
        val game = store.getGame(gameId) ?: error("No such game")
        return SaveBundle(
            exportedAtEpochMs = clock(),
            game = game,
            world = store.getWorld(gameId)!!,
            player = store.getPlayer(gameId)!!,
            npcs = store.allNpcs(gameId),
            locations = store.allLocations(gameId),
            factions = store.factions(gameId),
            items = store.allItems(gameId),
            threads = store.allThreads(gameId, 20_000),
            relationships = store.allRelationships(gameId),
            knowledge = store.allKnowledge(gameId),
            commitments = store.allCommitments(gameId),
            rumors = store.allRumors(gameId),
            memories = store.allMemories(gameId),
            summaries = store.summaries(gameId, 500),
            events = store.eventsPage(gameId, 0, 20_000),
            turns = store.turnsPage(gameId, 0, 20_000),
            images = store.allImages(gameId),
        )
    }

    /**
     * Restores a bundle as a brand-new campaign.
     *
     * Every entity id is regenerated and every reference to it rewritten. This is not cosmetic:
     * ids are unique per device, not per game, so importing a save that shares ids with a campaign
     * already on this device would otherwise overwrite that campaign's NPCs, locations and items.
     * The remap makes an import always additive.
     */
    suspend fun import(text: String): GameRecord = store.transaction {
        val bundle = json.decodeFromString(SaveBundle.serializer(), text)
        require(bundle.formatVersion <= SaveBundle.FORMAT_VERSION) {
            "This save was written by a newer version of UNBOUND (format ${bundle.formatVersion})."
        }

        val newGameId = Ids.game(idFactory())
        val remap = HashMap<String, String>()
        fun fresh(old: String, factory: (String) -> String): String =
            remap.getOrPut(old) { factory(idFactory()) }

        bundle.npcs.forEach { fresh(it.id, Ids::npc) }
        bundle.locations.forEach { fresh(it.id, Ids::location) }
        bundle.factions.forEach { fresh(it.id, Ids::faction) }
        bundle.items.forEach { fresh(it.id, Ids::item) }
        bundle.threads.forEach { fresh(it.id, Ids::thread) }
        bundle.events.forEach { fresh(it.id, Ids::event) }
        bundle.memories.forEach { fresh(it.id, Ids::memory) }
        bundle.turns.forEach { fresh(it.id, Ids::turn) }
        bundle.images.forEach { fresh(it.id, Ids::image) }

        // Unmapped ids pass through unchanged: "player", "world", and any free-text key.
        fun id(old: String?): String? = old?.let { remap[it] ?: it }
        fun ids(old: List<String>): List<String> = old.map { remap[it] ?: it }

        val game = bundle.game.copy(
            id = newGameId,
            title = bundle.game.title + " (imported)",
            updatedAtEpochMs = clock(),
            currentLocationId = id(bundle.game.currentLocationId)!!,
            previewImageId = id(bundle.game.previewImageId),
        )
        store.upsertGame(game)
        store.upsertWorld(bundle.world.copy(gameId = newGameId))
        store.upsertPlayer(
            bundle.player.copy(
                gameId = newGameId,
                currentLocationId = id(bundle.player.currentLocationId)!!,
                canonicalImageId = id(bundle.player.canonicalImageId),
                reputation = bundle.player.reputation.mapKeys { (k, _) -> remap[k] ?: k },
            ),
        )
        store.upsertNpcs(
            bundle.npcs.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    factionId = id(it.factionId),
                    currentLocationId = id(it.currentLocationId)!!,
                    homeLocationId = id(it.homeLocationId),
                    schedule = it.schedule.copy(
                        byHour = it.schedule.byHour.mapValues { (_, v) -> id(v)!! },
                        defaultLocationId = id(it.schedule.defaultLocationId),
                    ),
                    inventory = ids(it.inventory),
                    canonicalImageId = id(it.canonicalImageId),
                )
            },
        )
        store.upsertLocations(
            bundle.locations.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    parentLocationId = id(it.parentLocationId),
                    ownerId = id(it.ownerId),
                    exits = it.exits.mapValues { (_, v) -> id(v)!! },
                    nearbyLocationIds = ids(it.nearbyLocationIds),
                    controllingFactionId = id(it.controllingFactionId),
                    canonicalImageId = id(it.canonicalImageId),
                )
            },
        )
        store.upsertFactions(
            bundle.factions.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    leadershipNpcId = id(it.leadershipNpcId),
                    territoryLocationIds = ids(it.territoryLocationIds),
                    allyFactionIds = ids(it.allyFactionIds),
                    enemyFactionIds = ids(it.enemyFactionIds),
                )
            },
        )
        store.upsertItems(
            bundle.items.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    ownerId = id(it.ownerId), locationId = id(it.locationId),
                    canonicalImageId = id(it.canonicalImageId),
                )
            },
        )
        store.upsertThreads(
            bundle.threads.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    originatingEventId = id(it.originatingEventId),
                    involvedEntityIds = ids(it.involvedEntityIds),
                )
            },
        )
        store.upsertRelationships(
            bundle.relationships.map {
                val from = id(it.fromEntityId)!!
                val to = id(it.toEntityId)!!
                it.copy(
                    id = RelationshipRecord.key(newGameId, from, to), gameId = newGameId,
                    fromEntityId = from, toEntityId = to,
                    history = it.history.map { h -> h.copy(eventId = id(h.eventId)) },
                )
            },
        )
        store.upsertKnowledge(
            bundle.knowledge.map {
                it.copy(
                    id = idFactory(), gameId = newGameId,
                    knowerId = id(it.knowerId)!!,
                    subjectEntityIds = ids(it.subjectEntityIds),
                    learnedFromEventId = id(it.learnedFromEventId),
                    sourceEntityId = id(it.sourceEntityId),
                )
            },
        )
        store.upsertCommitments(
            bundle.commitments.map {
                it.copy(
                    id = idFactory(), gameId = newGameId,
                    fromEntityId = id(it.fromEntityId)!!,
                    toEntityId = id(it.toEntityId)!!,
                    originEventId = id(it.originEventId),
                    resolutionEventId = id(it.resolutionEventId),
                    relatedThreadId = id(it.relatedThreadId),
                )
            },
        )
        store.upsertRumors(
            bundle.rumors.map {
                it.copy(
                    id = idFactory(), gameId = newGameId,
                    originEventId = id(it.originEventId),
                    spreadLocationIds = ids(it.spreadLocationIds),
                    spreadFactionIds = ids(it.spreadFactionIds),
                    knownByEntityIds = ids(it.knownByEntityIds),
                )
            },
        )
        store.upsertMemories(
            bundle.memories.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    ownerId = id(it.ownerId)!!,
                    entityIds = ids(it.entityIds),
                    locationId = id(it.locationId),
                    threadId = id(it.threadId),
                    sourceEventIds = ids(it.sourceEventIds),
                    consolidatedFromIds = ids(it.consolidatedFromIds),
                )
            },
        )
        store.upsertSummaries(
            bundle.summaries.map { it.copy(id = idFactory(), gameId = newGameId, subjectId = id(it.subjectId)) },
        )
        store.appendEvents(
            bundle.events.map {
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    turnId = id(it.turnId),
                    actorId = id(it.actorId), targetId = id(it.targetId), locationId = id(it.locationId),
                    witnessIds = ids(it.witnessIds), relatedEntityIds = ids(it.relatedEntityIds),
                    causedByEventId = id(it.causedByEventId),
                )
            },
        )
        bundle.turns.forEach {
            store.upsertTurn(
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    // A fresh idempotency namespace: the imported turns are already committed and
                    // must never collide with a retry in the new campaign.
                    idempotencyKey = "imported:" + it.idempotencyKey,
                    imageId = id(it.imageId),
                ),
            )
        }
        bundle.images.forEach {
            store.upsertImage(
                it.copy(
                    id = remap.getValue(it.id), gameId = newGameId,
                    entityId = id(it.entityId), turnId = id(it.turnId),
                    // Image bytes are not portable; the record survives so the UI knows to re-fetch.
                    localPath = null,
                ),
            )
        }

        game
    }

    suspend fun duplicate(gameId: String, newTitle: String): GameRecord {
        val text = export(gameId)
        val imported = import(text)
        val renamed = imported.copy(title = newTitle)
        store.upsertGame(renamed)
        return renamed
    }
}
