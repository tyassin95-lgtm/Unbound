package com.unbound.rpg.data.db

import androidx.room.withTransaction
import com.unbound.core.engine.UsageTotals
import com.unbound.core.engine.WorldStore
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord

/**
 * The production [WorldStore]. Behaviourally identical to `InMemoryWorldStore`, which is the
 * reference implementation the engine's test-suite runs against.
 *
 * Queries with an `IN (:ids)` clause are chunked, because SQLite has a hard limit on bound
 * variables (999 on older Android versions) and a large campaign can legitimately ask about more
 * ids than that during an export.
 */
class RoomWorldStore(private val db: UnboundDatabase) : WorldStore {

    private companion object {
        /** Comfortably under SQLite's 999-variable limit, leaving room for the other bindings. */
        const val SQL_CHUNK = 400

        /** An id no entity can have, used to keep an empty IN () clause syntactically valid. */
        const val NO_MATCH = "\u0000none"
    }

    private suspend fun <T, R> chunked(ids: Collection<T>, block: suspend (List<T>) -> List<R>): List<R> =
        if (ids.isEmpty()) emptyList()
        else if (ids.size <= SQL_CHUNK) block(ids.toList())
        else ids.chunked(SQL_CHUNK).flatMap { block(it) }

    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    // --- games --------------------------------------------------------------------------------
    override suspend fun getGame(gameId: String) = db.games().get(gameId)?.let(Mappers::toGame)
    override suspend fun listGames() = db.games().list().map(Mappers::toGame)
    override suspend fun upsertGame(game: GameRecord) = db.games().upsert(Mappers.toEntity(game))
    override suspend fun deleteGame(gameId: String) {
        // Child rows go with it via CASCADE; usage has no FK because it outlives the save.
        db.usage().deleteForGame(gameId)
        db.games().delete(gameId)
    }

    override suspend fun bumpStateVersion(gameId: String, expectedVersion: Long): Boolean =
        db.games().bumpVersion(gameId, expectedVersion) == 1

    // --- world / player ------------------------------------------------------------------------
    override suspend fun getWorld(gameId: String) = db.worlds().get(gameId)?.let(Mappers::toWorld)
    override suspend fun upsertWorld(world: WorldRecord) = db.worlds().upsert(Mappers.toEntity(world))
    override suspend fun getPlayer(gameId: String) = db.players().get(gameId)?.let(Mappers::toPlayer)
    override suspend fun upsertPlayer(player: PlayerRecord) = db.players().upsert(Mappers.toEntity(player))

    // --- npcs ------------------------------------------------------------------------------------
    override suspend fun getNpc(gameId: String, npcId: String) = db.npcs().get(gameId, npcId)?.let(Mappers::toNpc)
    override suspend fun npcsAt(gameId: String, locationId: String) = db.npcs().at(gameId, locationId).map(Mappers::toNpc)
    override suspend fun npcsByIds(gameId: String, ids: Collection<String>) =
        chunked(ids) { db.npcs().byIds(gameId, it) }.map(Mappers::toNpc)
    override suspend fun persistentNpcs(gameId: String, limit: Int) = db.npcs().persistent(gameId, limit).map(Mappers::toNpc)
    override suspend fun allNpcs(gameId: String, limit: Int) = db.npcs().all(gameId, limit).map(Mappers::toNpc)
    override suspend fun countNpcs(gameId: String) = db.npcs().count(gameId)
    override suspend fun upsertNpcs(npcs: Collection<NpcRecord>) {
        if (npcs.isNotEmpty()) db.npcs().upsertAll(npcs.map(Mappers::toEntity))
    }

    // --- locations ---------------------------------------------------------------------------------
    override suspend fun getLocation(gameId: String, locationId: String) = db.locations().get(gameId, locationId)?.let(Mappers::toLocation)
    override suspend fun locationsByIds(gameId: String, ids: Collection<String>) =
        chunked(ids) { db.locations().byIds(gameId, it) }.map(Mappers::toLocation)
    override suspend fun discoveredLocations(gameId: String, limit: Int) = db.locations().discovered(gameId, limit).map(Mappers::toLocation)
    override suspend fun allLocations(gameId: String, limit: Int) = db.locations().all(gameId, limit).map(Mappers::toLocation)
    override suspend fun upsertLocations(locations: Collection<LocationRecord>) {
        if (locations.isNotEmpty()) db.locations().upsertAll(locations.map(Mappers::toEntity))
    }

    // --- factions -----------------------------------------------------------------------------------
    override suspend fun getFaction(gameId: String, factionId: String) = db.factions().get(gameId, factionId)?.let(Mappers::toFaction)
    override suspend fun factions(gameId: String) = db.factions().all(gameId).map(Mappers::toFaction)
    override suspend fun upsertFactions(factions: Collection<FactionRecord>) {
        if (factions.isNotEmpty()) db.factions().upsertAll(factions.map(Mappers::toEntity))
    }

    // --- items ----------------------------------------------------------------------------------------
    override suspend fun getItem(gameId: String, itemId: String) = db.items().get(gameId, itemId)?.let(Mappers::toItem)
    override suspend fun itemsOwnedBy(gameId: String, ownerId: String) = db.items().ownedBy(gameId, ownerId).map(Mappers::toItem)
    override suspend fun itemsByIds(gameId: String, ids: Collection<String>) = chunked(ids) { db.items().byIds(gameId, it) }.map(Mappers::toItem)
    override suspend fun itemsAt(gameId: String, locationId: String) = db.items().at(gameId, locationId).map(Mappers::toItem)
    override suspend fun allItems(gameId: String, limit: Int) = db.items().all(gameId, limit).map(Mappers::toItem)
    override suspend fun upsertItems(items: Collection<ItemRecord>) {
        if (items.isNotEmpty()) db.items().upsertAll(items.map(Mappers::toEntity))
    }

    // --- threads ---------------------------------------------------------------------------------------
    override suspend fun activeThreads(gameId: String, limit: Int) = db.threads().active(gameId, limit).map(Mappers::toThread)
    override suspend fun allThreads(gameId: String, limit: Int) = db.threads().all(gameId, limit).map(Mappers::toThread)
    override suspend fun getThread(gameId: String, threadId: String) = db.threads().get(gameId, threadId)?.let(Mappers::toThread)
    override suspend fun upsertThreads(threads: Collection<ThreadRecord>) {
        if (threads.isNotEmpty()) db.threads().upsertAll(threads.map(Mappers::toEntity))
    }

    // --- relationships ------------------------------------------------------------------------------------
    override suspend fun getRelationship(gameId: String, from: String, to: String) =
        db.relationships().get(gameId, from, to)?.let(Mappers::toRelationship)
    override suspend fun relationshipsFrom(gameId: String, from: String, limit: Int) =
        db.relationships().from(gameId, from, limit).map(Mappers::toRelationship)
    override suspend fun allRelationships(gameId: String, limit: Int) =
        db.relationships().all(gameId, limit).map(Mappers::toRelationship)
    override suspend fun upsertRelationships(records: Collection<RelationshipRecord>) {
        if (records.isNotEmpty()) db.relationships().upsertAll(records.map(Mappers::toEntity))
    }

    // --- knowledge ------------------------------------------------------------------------------------------
    override suspend fun knowledgeOf(gameId: String, knowerId: String, limit: Int) =
        db.knowledge().of(gameId, knowerId, limit).map(Mappers::toKnowledge)

    override suspend fun knowledgeOfAbout(gameId: String, knowerId: String, subjectIds: Collection<String>, limit: Int) =
        chunked(subjectIds) { db.knowledge().about(gameId, knowerId, it, limit) }
            .distinctBy { it.id }.take(limit).map(Mappers::toKnowledge)

    override suspend fun hasFact(gameId: String, knowerId: String, factKey: String) =
        db.knowledge().byFact(gameId, knowerId, factKey) != null

    override suspend fun allKnowledge(gameId: String, limit: Int) = db.knowledge().all(gameId, limit).map(Mappers::toKnowledge)

    /**
     * A knower holds exactly one view of a given fact. A new record replaces the existing one only
     * when it is *more* certain — hearing a rumor about something you witnessed must not downgrade
     * what you know.
     */
    override suspend fun upsertKnowledge(records: Collection<KnowledgeRecord>) {
        for (r in records) {
            val existing = db.knowledge().byFact(r.gameId, r.knowerId, r.factKey)
            if (existing == null) {
                db.knowledge().insert(Mappers.toEntity(r))
                db.knowledge().insertSubjects(Mappers.subjectRefs(r))
            } else if (r.certainty.ordinal < existing.certainty) {
                val replacement = r.copy(id = existing.id)
                db.knowledge().insert(Mappers.toEntity(replacement))
                db.knowledge().insertSubjects(Mappers.subjectRefs(replacement))
            }
        }
    }

    override suspend fun activeRumors(gameId: String, limit: Int) = db.rumors().active(gameId, limit).map(Mappers::toRumor)
    override suspend fun allRumors(gameId: String, limit: Int) = db.rumors().all(gameId, limit).map(Mappers::toRumor)
    override suspend fun upsertRumors(records: Collection<RumorRecord>) {
        if (records.isNotEmpty()) db.rumors().upsertAll(records.map(Mappers::toEntity))
    }

    // --- memory ------------------------------------------------------------------------------------------------
    override suspend fun memoryCandidates(
        gameId: String,
        ownerIds: Collection<String>,
        entityIds: Collection<String>,
        locationId: String?,
        threadIds: Collection<String>,
        minImportance: Importance,
        limit: Int,
    ): List<MemoryRecord> = db.memories().candidates(
        gameId = gameId,
        // SQLite's IN () is a syntax error on an empty list, and Room binds the list verbatim.
        // A single impossible sentinel keeps the clause valid and matches nothing.
        ownerIds = ownerIds.ifEmpty { listOf(NO_MATCH) },
        entityIds = entityIds.ifEmpty { listOf(NO_MATCH) },
        locationId = locationId,
        threadIds = threadIds.ifEmpty { listOf(NO_MATCH) },
        minImportance = minImportance.ordinal,
        limit = limit,
    ).map(Mappers::toMemory)

    override suspend fun memoriesOf(gameId: String, ownerId: String, limit: Int) =
        db.memories().of(gameId, ownerId, limit).map(Mappers::toMemory)

    override suspend fun allMemories(gameId: String, limit: Int) = db.memories().all(gameId, limit).map(Mappers::toMemory)
    override suspend fun countMemories(gameId: String) = db.memories().count(gameId)

    override suspend fun upsertMemories(records: Collection<MemoryRecord>) {
        if (records.isEmpty()) return
        db.memories().upsertAll(records.map(Mappers::toEntity))
        db.memories().deleteRefs(records.map { it.id })
        val refs = records.flatMap(Mappers::memoryRefs)
        if (refs.isNotEmpty()) db.memories().insertRefs(refs)
    }

    override suspend fun deleteMemories(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.chunked(SQL_CHUNK).forEach { db.memories().delete(it) }
    }

    override suspend fun summaries(gameId: String, limit: Int) = db.summaries().recent(gameId, limit).map(Mappers::toSummary)
    override suspend fun upsertSummaries(records: Collection<SummaryRecord>) {
        if (records.isNotEmpty()) db.summaries().upsertAll(records.map(Mappers::toEntity))
    }

    // --- events ---------------------------------------------------------------------------------------------------
    override suspend fun appendEvents(events: Collection<GameEvent>) {
        if (events.isEmpty()) return
        db.events().insertAll(events.map(Mappers::toEntity))
        val refs = events.flatMap(Mappers::eventRefs)
        if (refs.isNotEmpty()) db.events().insertRefs(refs)
    }

    override suspend fun recentEvents(gameId: String, limit: Int) = db.events().recent(gameId, limit).map(Mappers::toEvent)
    override suspend fun importantEventsBefore(gameId: String, beforeWorldMinutes: Long, minImportance: Importance, limit: Int) =
        db.events().importantBefore(gameId, beforeWorldMinutes, minImportance.ordinal, limit).map(Mappers::toEvent)
    override suspend fun eventsInvolving(gameId: String, entityIds: Collection<String>, limit: Int) =
        chunked(entityIds) { db.events().involving(gameId, it, limit) }.distinctBy { it.id }.take(limit).map(Mappers::toEvent)
    override suspend fun eventsPage(gameId: String, offset: Int, limit: Int) = db.events().page(gameId, offset, limit).map(Mappers::toEvent)
    override suspend fun nextEventSequence(gameId: String) = db.events().maxSequence(gameId) + 1
    override suspend fun countEvents(gameId: String) = db.events().count(gameId)
    override suspend fun deleteEventsAfterSequence(gameId: String, sequence: Long) = db.events().deleteAfter(gameId, sequence)

    // --- turns -----------------------------------------------------------------------------------------------------
    override suspend fun upsertTurn(turn: TurnRecord) = db.turns().upsert(Mappers.toEntity(turn))
    override suspend fun getTurn(turnId: String) = db.turns().get(turnId)?.let(Mappers::toTurn)
    override suspend fun turnByIdempotencyKey(gameId: String, key: String) =
        db.turns().byIdempotencyKey(gameId, key)?.let(Mappers::toTurn)
    override suspend fun recentTurns(gameId: String, limit: Int) = db.turns().recent(gameId, limit).map(Mappers::toTurn)
    override suspend fun turnsPage(gameId: String, offset: Int, limit: Int) = db.turns().page(gameId, offset, limit).map(Mappers::toTurn)
    override suspend fun countTurns(gameId: String) = db.turns().count(gameId)
    override suspend fun pendingTurns(gameId: String) = db.turns().pending(gameId).map(Mappers::toTurn)

    // --- snapshots ---------------------------------------------------------------------------------------------------
    override suspend fun clearGameEntities(gameId: String) {
        // Join tables go with their parents via CASCADE.
        db.npcs().clearGame(gameId)
        db.locations().clearGame(gameId)
        db.factions().clearGame(gameId)
        db.items().clearGame(gameId)
        db.threads().clearGame(gameId)
        db.relationships().clearGame(gameId)
        db.knowledge().clearGame(gameId)
        db.rumors().clearGame(gameId)
        db.memories().clearGame(gameId)
        db.summaries().clearGame(gameId)
    }

    override suspend fun deleteTurnsAfter(gameId: String, turnNumber: Int) = db.turns().deleteAfter(gameId, turnNumber)

    override suspend fun upsertSnapshot(snapshot: SnapshotRecord) = db.snapshots().upsert(Mappers.toEntity(snapshot))
    override suspend fun latestSnapshotAtOrBefore(gameId: String, turnNumber: Int) =
        db.snapshots().latestAtOrBefore(gameId, turnNumber)?.let(Mappers::toSnapshot)
    override suspend fun snapshots(gameId: String, limit: Int) = db.snapshots().recent(gameId, limit).map(Mappers::toSnapshot)
    override suspend fun deleteSnapshotsAfter(gameId: String, turnNumber: Int) = db.snapshots().deleteAfter(gameId, turnNumber)
    override suspend fun deleteSnapshots(ids: Collection<String>) {
        if (ids.isNotEmpty()) ids.chunked(SQL_CHUNK).forEach { db.snapshots().deleteByIds(it) }
    }

    // --- images / usage ------------------------------------------------------------------------------------------------
    override suspend fun upsertImage(image: ImageRecord) = db.images().upsert(Mappers.toEntity(image))
    override suspend fun canonicalImageFor(gameId: String, entityId: String) =
        db.images().canonicalFor(gameId, entityId)?.let(Mappers::toImage)
    override suspend fun imagesFor(gameId: String, entityId: String) = db.images().forEntity(gameId, entityId).map(Mappers::toImage)
    override suspend fun allImages(gameId: String) = db.images().all(gameId).map(Mappers::toImage)

    override suspend fun deleteImageFiles(gameId: String) {
        // Clears the cache pointer but keeps the record, so the game still knows which images
        // existed and can regenerate them on request (§123).
        db.images().all(gameId).forEach { row ->
            db.images().upsert(Mappers.toEntity(Mappers.toImage(row).copy(localPath = null)))
        }
    }

    override suspend fun recordUsage(usage: UsageRecord) = db.usage().insert(Mappers.toEntity(usage))
    override suspend fun usageFor(gameId: String?, limit: Int) = db.usage().recent(gameId, limit).map(Mappers::toUsage)
    override suspend fun usageTotals(gameId: String?): UsageTotals {
        val row = db.usage().totals(gameId)
        return UsageTotals(
            requests = row.requests, inputTokens = row.inputTokens, outputTokens = row.outputTokens,
            cachedTokens = row.cachedTokens, imageRequests = row.imageRequests,
            estimatedCostUsd = row.estimatedCostUsd, failures = row.failures, averageLatencyMs = row.averageLatencyMs,
        )
    }
}
