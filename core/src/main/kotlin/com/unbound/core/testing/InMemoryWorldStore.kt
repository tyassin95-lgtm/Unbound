package com.unbound.core.testing

import com.unbound.core.engine.UsageTotals
import com.unbound.core.engine.WorldStore
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A complete [WorldStore] held in memory.
 *
 * This is not only a test double. It is the reference implementation of the store contract: the
 * Room implementation is expected to behave identically, and the engine test-suite runs against
 * this one so that engine failures are never confused with SQL failures. It is also what Compose
 * previews and the demo campaign run on.
 *
 * The [transaction] implementation is genuinely transactional: it snapshots every table, runs the
 * block, and restores the snapshot if anything throws.
 */
class InMemoryWorldStore : WorldStore {

    private val lock = Mutex()

    private val games = linkedMapOf<String, GameRecord>()
    private val worlds = linkedMapOf<String, WorldRecord>()
    private val players = linkedMapOf<String, PlayerRecord>()
    private val npcs = linkedMapOf<String, NpcRecord>()
    private val locations = linkedMapOf<String, LocationRecord>()
    private val factions = linkedMapOf<String, FactionRecord>()
    private val items = linkedMapOf<String, ItemRecord>()
    private val relationships = linkedMapOf<String, RelationshipRecord>()
    private val knowledge = linkedMapOf<String, KnowledgeRecord>()
    private val rumors = linkedMapOf<String, RumorRecord>()
    private val memories = linkedMapOf<String, MemoryRecord>()
    private val summaries = linkedMapOf<String, SummaryRecord>()
    private val threads = linkedMapOf<String, ThreadRecord>()
    private val events = mutableListOf<GameEvent>()
    private val turns = linkedMapOf<String, TurnRecord>()
    private val snapshots = linkedMapOf<String, SnapshotRecord>()
    private val images = linkedMapOf<String, ImageRecord>()
    private val usage = mutableListOf<UsageRecord>()
    private val commitments = linkedMapOf<String, com.unbound.core.continuity.CommitmentRecord>()

    /**
     * Transaction membership is a property of the *calling coroutine*, not of the store. A plain
     * field would let a second coroutine that arrives while the holder is suspended see
     * `inTransaction == true` and skip the lock, interleaving two transactions — which is exactly
     * the class of bug these tests exist to catch.
     */
    private class InTransaction : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<InTransaction>
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (kotlin.coroutines.coroutineContext[InTransaction] != null) return block()
        return lock.withLock {
            val backup = snapshotAll()
            try {
                kotlinx.coroutines.withContext(InTransaction()) { block() }
            } catch (t: Throwable) {
                restore(backup)
                throw t
            }
        }
    }

    private fun snapshotAll() = Backup(
        games.toMap(), worlds.toMap(), players.toMap(), npcs.toMap(), locations.toMap(),
        factions.toMap(), items.toMap(), relationships.toMap(), knowledge.toMap(), rumors.toMap(),
        memories.toMap(), summaries.toMap(), threads.toMap(), events.toList(), turns.toMap(),
        snapshots.toMap(), images.toMap(), usage.toList(),
    )

    private fun restore(b: Backup) {
        fun <K, V> replace(target: MutableMap<K, V>, src: Map<K, V>) { target.clear(); target.putAll(src) }
        replace(games, b.games); replace(worlds, b.worlds); replace(players, b.players)
        replace(npcs, b.npcs); replace(locations, b.locations); replace(factions, b.factions)
        replace(items, b.items); replace(relationships, b.relationships); replace(knowledge, b.knowledge)
        replace(rumors, b.rumors); replace(memories, b.memories); replace(summaries, b.summaries)
        replace(threads, b.threads); replace(turns, b.turns); replace(snapshots, b.snapshots)
        replace(images, b.images)
        events.clear(); events.addAll(b.events)
        usage.clear(); usage.addAll(b.usage)
    }

    private class Backup(
        val games: Map<String, GameRecord>, val worlds: Map<String, WorldRecord>,
        val players: Map<String, PlayerRecord>, val npcs: Map<String, NpcRecord>,
        val locations: Map<String, LocationRecord>, val factions: Map<String, FactionRecord>,
        val items: Map<String, ItemRecord>, val relationships: Map<String, RelationshipRecord>,
        val knowledge: Map<String, KnowledgeRecord>, val rumors: Map<String, RumorRecord>,
        val memories: Map<String, MemoryRecord>, val summaries: Map<String, SummaryRecord>,
        val threads: Map<String, ThreadRecord>, val events: List<GameEvent>,
        val turns: Map<String, TurnRecord>, val snapshots: Map<String, SnapshotRecord>,
        val images: Map<String, ImageRecord>, val usage: List<UsageRecord>,
    )

    // --- games -------------------------------------------------------------------------------
    override suspend fun getGame(gameId: String) = games[gameId]
    override suspend fun listGames() = games.values.sortedByDescending { it.updatedAtEpochMs }
    override suspend fun upsertGame(game: GameRecord) { games[game.id] = game }
    override suspend fun deleteGame(gameId: String) {
        games.remove(gameId); worlds.remove(gameId); players.remove(gameId)
        npcs.values.removeAll { it.gameId == gameId }
        locations.values.removeAll { it.gameId == gameId }
        factions.values.removeAll { it.gameId == gameId }
        items.values.removeAll { it.gameId == gameId }
        relationships.values.removeAll { it.gameId == gameId }
        knowledge.values.removeAll { it.gameId == gameId }
        rumors.values.removeAll { it.gameId == gameId }
        memories.values.removeAll { it.gameId == gameId }
        summaries.values.removeAll { it.gameId == gameId }
        threads.values.removeAll { it.gameId == gameId }
        turns.values.removeAll { it.gameId == gameId }
        snapshots.values.removeAll { it.gameId == gameId }
        images.values.removeAll { it.gameId == gameId }
        events.removeAll { it.gameId == gameId }
        // Matches the Room implementation: the save is gone, so its cost history goes with it.
        usage.removeAll { it.gameId == gameId }
        commitments.values.removeAll { it.gameId == gameId }
    }

    override suspend fun bumpStateVersion(gameId: String, expectedVersion: Long): Boolean {
        val g = games[gameId] ?: return false
        if (g.stateVersion != expectedVersion) return false
        games[gameId] = g.copy(stateVersion = expectedVersion + 1)
        return true
    }

    // --- world / player ----------------------------------------------------------------------
    override suspend fun getWorld(gameId: String) = worlds[gameId]
    override suspend fun upsertWorld(world: WorldRecord) { worlds[world.gameId] = world }
    override suspend fun getPlayer(gameId: String) = players[gameId]
    override suspend fun upsertPlayer(player: PlayerRecord) { players[player.gameId] = player }

    // --- npcs --------------------------------------------------------------------------------
    override suspend fun getNpc(gameId: String, npcId: String) = npcs[npcId]?.takeIf { it.gameId == gameId }
    override suspend fun npcsAt(gameId: String, locationId: String) =
        npcs.values.filter { it.gameId == gameId && it.currentLocationId == locationId }
    override suspend fun npcsByIds(gameId: String, ids: Collection<String>) =
        ids.mapNotNull { npcs[it] }.filter { it.gameId == gameId }
    override suspend fun persistentNpcs(gameId: String, limit: Int) =
        npcs.values.filter { it.gameId == gameId && it.tier != NpcTier.AMBIENT }.take(limit)
    override suspend fun upsertNpcs(npcs: Collection<NpcRecord>) { npcs.forEach { this.npcs[it.id] = it } }

    // --- locations ---------------------------------------------------------------------------
    override suspend fun getLocation(gameId: String, locationId: String) = locations[locationId]?.takeIf { it.gameId == gameId }
    override suspend fun locationsByIds(gameId: String, ids: Collection<String>) =
        ids.mapNotNull { locations[it] }.filter { it.gameId == gameId }
    override suspend fun discoveredLocations(gameId: String, limit: Int) =
        locations.values.filter { it.gameId == gameId && it.discovered }.take(limit)
    override suspend fun upsertLocations(locations: Collection<LocationRecord>) { locations.forEach { this.locations[it.id] = it } }

    // --- factions ----------------------------------------------------------------------------
    override suspend fun getFaction(gameId: String, factionId: String) = factions[factionId]?.takeIf { it.gameId == gameId }
    override suspend fun factions(gameId: String) = factions.values.filter { it.gameId == gameId }
    override suspend fun upsertFactions(factions: Collection<FactionRecord>) { factions.forEach { this.factions[it.id] = it } }

    // --- items -------------------------------------------------------------------------------
    override suspend fun getItem(gameId: String, itemId: String) = items[itemId]?.takeIf { it.gameId == gameId }
    override suspend fun itemsOwnedByAny(gameId: String, ownerIds: Collection<String>) =
        items.values.filter { it.gameId == gameId && it.ownerId in ownerIds && it.condition != ItemCondition.DESTROYED }

    override suspend fun itemsOwnedBy(gameId: String, ownerId: String) =
        items.values.filter { it.gameId == gameId && it.ownerId == ownerId && it.condition != ItemCondition.DESTROYED }
    override suspend fun itemsAt(gameId: String, locationId: String) =
        items.values.filter { it.gameId == gameId && it.locationId == locationId }
    override suspend fun upsertItems(items: Collection<ItemRecord>) { items.forEach { this.items[it.id] = it } }

    // --- relationships -----------------------------------------------------------------------
    override suspend fun getRelationship(gameId: String, from: String, to: String) =
        relationships[RelationshipRecord.key(gameId, from, to)]
    override suspend fun relationshipsFrom(gameId: String, from: String, limit: Int) =
        relationships.values.filter { it.gameId == gameId && it.fromEntityId == from }.take(limit)
    override suspend fun upsertRelationships(records: Collection<RelationshipRecord>) { records.forEach { relationships[it.id] = it } }

    // --- threads -----------------------------------------------------------------------------
    override suspend fun activeThreads(gameId: String, limit: Int) = threads.values
        .filter { it.gameId == gameId && it.status !in setOf(ThreadStatus.RESOLVED, ThreadStatus.FAILED, ThreadStatus.ABANDONED) }
        .sortedByDescending { it.importance.weight * 100 + it.momentum }
        .take(limit)
    override suspend fun allThreads(gameId: String, limit: Int) = threads.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun upsertThreads(threads: Collection<ThreadRecord>) { threads.forEach { this.threads[it.id] = it } }

    // --- knowledge ---------------------------------------------------------------------------
    override suspend fun knowledgeOf(gameId: String, knowerId: String, limit: Int) =
        knowledge.values.filter { it.gameId == gameId && it.knowerId == knowerId }
            .sortedByDescending { it.learnedAtWorldMinutes }
            .take(limit)

    override suspend fun knowledgeForKnowers(gameId: String, knowerIds: Collection<String>, limitPerKnower: Int) =
        knowledge.values
            .filter { it.gameId == gameId && it.knowerId in knowerIds }
            .groupBy { it.knowerId }
            .flatMap { (_, rows) ->
                rows.sortedWith(
                    compareByDescending<com.unbound.core.knowledge.KnowledgeRecord> { it.importance.weight }
                        .thenByDescending { it.learnedAtWorldMinutes },
                ).take(limitPerKnower)
            }

    override suspend fun knowledgeOfAbout(gameId: String, knowerId: String, subjectIds: Collection<String>, limit: Int) =
        knowledge.values.filter {
            it.gameId == gameId && it.knowerId == knowerId &&
                (it.subjectEntityIds.any { s -> s in subjectIds } || subjectIds.any { s -> it.factKey.contains(s) })
        }.sortedByDescending { it.importance.weight }.take(limit)

    override suspend fun hasFact(gameId: String, knowerId: String, factKey: String) =
        knowledge.values.any { it.gameId == gameId && it.knowerId == knowerId && it.factKey == factKey }

    override suspend fun upsertKnowledge(records: Collection<KnowledgeRecord>) {
        for (r in records) {
            // A knower holds one view per fact; a stronger certainty supersedes a weaker one.
            val existing = knowledge.values.firstOrNull {
                it.gameId == r.gameId && it.knowerId == r.knowerId && it.factKey == r.factKey
            }
            if (existing == null) {
                knowledge[r.id] = r
            } else if (r.certainty.ordinal < existing.certainty.ordinal) {
                knowledge[existing.id] = r.copy(id = existing.id)
            }
        }
    }

    override suspend fun activeRumors(gameId: String, limit: Int) =
        rumors.values.filter { it.gameId == gameId && it.virality > 5 }.sortedByDescending { it.virality }.take(limit)
    override suspend fun upsertRumors(records: Collection<RumorRecord>) { records.forEach { rumors[it.id] = it } }

    // --- memory ------------------------------------------------------------------------------
    override suspend fun memoryCandidates(
        gameId: String,
        ownerIds: Collection<String>,
        entityIds: Collection<String>,
        locationId: String?,
        threadIds: Collection<String>,
        minImportance: Importance,
        limit: Int,
    ): List<MemoryRecord> = memories.values.asSequence()
        .filter { it.gameId == gameId }
        .filter { it.importance.ordinal >= minImportance.ordinal }
        .filter {
            it.ownerId in ownerIds ||
                it.entityIds.any { e -> e in entityIds } ||
                (locationId != null && it.locationId == locationId) ||
                (it.threadId != null && it.threadId in threadIds)
        }
        .sortedByDescending { it.importance.weight * 1_000_000 + it.lastReinforcedWorldMinutes }
        .take(limit)
        .toList()

    override suspend fun memoriesOf(gameId: String, ownerId: String, limit: Int) =
        memories.values.filter { it.gameId == gameId && it.ownerId == ownerId }
            .sortedByDescending { it.lastReinforcedWorldMinutes }.take(limit)

    override suspend fun upsertMemories(records: Collection<MemoryRecord>) { records.forEach { memories[it.id] = it } }
    override suspend fun deleteMemories(ids: Collection<String>) { ids.forEach { memories.remove(it) } }
    override suspend fun countMemories(gameId: String) = memories.values.count { it.gameId == gameId }

    override suspend fun summaries(gameId: String, limit: Int) =
        summaries.values.filter { it.gameId == gameId }.sortedByDescending { it.coversToTurn }.take(limit)
    override suspend fun upsertSummaries(records: Collection<SummaryRecord>) { records.forEach { summaries[it.id] = it } }

    // --- events ------------------------------------------------------------------------------
    override suspend fun appendEvents(events: Collection<GameEvent>) { this.events.addAll(events) }

    override suspend fun recentEvents(gameId: String, limit: Int) =
        events.filter { it.gameId == gameId }.sortedByDescending { it.sequence }.take(limit)

    override suspend fun importantEventsBefore(gameId: String, beforeWorldMinutes: Long, minImportance: Importance, limit: Int) =
        events.filter {
            it.gameId == gameId && it.worldMinutes < beforeWorldMinutes && it.importance.ordinal >= minImportance.ordinal
        }.sortedByDescending { it.sequence }.take(limit)

    override suspend fun eventsInvolving(gameId: String, entityIds: Collection<String>, limit: Int) =
        events.filter {
            it.gameId == gameId &&
                (it.actorId in entityIds || it.targetId in entityIds || it.relatedEntityIds.any { e -> e in entityIds })
        }.sortedByDescending { it.sequence }.take(limit)

    override suspend fun eventsPage(gameId: String, offset: Int, limit: Int) =
        events.filter { it.gameId == gameId }.sortedByDescending { it.sequence }.drop(offset).take(limit)

    override suspend fun eventsByIds(gameId: String, ids: Collection<String>) =
        events.filter { it.gameId == gameId && it.id in ids }

    override suspend fun nextEventSequence(gameId: String) =
        (events.filter { it.gameId == gameId }.maxOfOrNull { it.sequence } ?: 0L) + 1

    override suspend fun countEvents(gameId: String) = events.count { it.gameId == gameId }

    override suspend fun deleteEventsAfterSequence(gameId: String, sequence: Long) {
        events.removeAll { it.gameId == gameId && it.sequence > sequence }
    }

    // --- whole-game enumeration -----------------------------------------------------------------
    override suspend fun allLocations(gameId: String, limit: Int) = locations.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allItems(gameId: String, limit: Int) = items.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allRelationships(gameId: String, limit: Int) = relationships.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allKnowledge(gameId: String, limit: Int) = knowledge.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allMemories(gameId: String, limit: Int) = memories.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allRumors(gameId: String, limit: Int) = rumors.values.filter { it.gameId == gameId }.take(limit)
    override suspend fun allNpcs(gameId: String, limit: Int) = npcs.values.filter { it.gameId == gameId }.take(limit)

    // --- turns --------------------------------------------------------------------------------
    override suspend fun upsertTurn(turn: TurnRecord) { turns[turn.id] = turn }
    override suspend fun turnByIdempotencyKey(gameId: String, key: String) =
        turns.values.filter { it.gameId == gameId && it.idempotencyKey == key }
            .minByOrNull { if (it.status == TurnStatus.COMPLETE) 0 else 1 }
    override suspend fun recentTurns(gameId: String, limit: Int) =
        turns.values.filter { it.gameId == gameId && it.status == TurnStatus.COMPLETE }
            .sortedByDescending { it.turnNumber }.take(limit)
    override suspend fun turnsPage(gameId: String, offset: Int, limit: Int) =
        turns.values.filter { it.gameId == gameId && it.status == TurnStatus.COMPLETE }
            .sortedByDescending { it.turnNumber }.drop(offset).take(limit)
    override suspend fun countTurns(gameId: String) = turns.values.count { it.gameId == gameId && it.status == TurnStatus.COMPLETE }
    override suspend fun pendingTurns(gameId: String) =
        turns.values.filter { it.gameId == gameId && it.status in setOf(TurnStatus.PENDING, TurnStatus.AWAITING_COMMIT) }

    // --- snapshots ----------------------------------------------------------------------------
    override suspend fun clearGameEntities(gameId: String) {
        npcs.values.removeAll { it.gameId == gameId }
        locations.values.removeAll { it.gameId == gameId }
        factions.values.removeAll { it.gameId == gameId }
        items.values.removeAll { it.gameId == gameId }
        threads.values.removeAll { it.gameId == gameId }
        relationships.values.removeAll { it.gameId == gameId }
        knowledge.values.removeAll { it.gameId == gameId }
        rumors.values.removeAll { it.gameId == gameId }
        memories.values.removeAll { it.gameId == gameId }
        summaries.values.removeAll { it.gameId == gameId }
        commitments.values.removeAll { it.gameId == gameId }
    }

    override suspend fun deleteTurnsAfter(gameId: String, turnNumber: Int) {
        turns.values.removeAll { it.gameId == gameId && it.turnNumber > turnNumber }
    }

    override suspend fun upsertSnapshot(snapshot: SnapshotRecord) { snapshots[snapshot.id] = snapshot }
    override suspend fun latestSnapshotAtOrBefore(gameId: String, turnNumber: Int) =
        snapshots.values.filter { it.gameId == gameId && it.turnNumber <= turnNumber }.maxByOrNull { it.turnNumber }
    override suspend fun snapshots(gameId: String, limit: Int) =
        snapshots.values.filter { it.gameId == gameId }.sortedByDescending { it.turnNumber }.take(limit)
    override suspend fun deleteSnapshotsAfter(gameId: String, turnNumber: Int) {
        snapshots.values.removeAll { it.gameId == gameId && it.turnNumber > turnNumber }
    }

    override suspend fun deleteSnapshots(ids: Collection<String>) { ids.forEach { snapshots.remove(it) } }

    // --- images / usage --------------------------------------------------------------------------
    override suspend fun upsertImage(image: ImageRecord) { images[image.id] = image }
    override suspend fun canonicalImageFor(gameId: String, entityId: String) =
        images.values.firstOrNull { it.gameId == gameId && it.entityId == entityId && it.canonical }
    override suspend fun imagesFor(gameId: String, entityId: String) =
        images.values.filter { it.gameId == gameId && it.entityId == entityId }.sortedByDescending { it.createdAtEpochMs }
    override suspend fun allImages(gameId: String) = images.values.filter { it.gameId == gameId }
    override suspend fun deleteImageFiles(gameId: String) {
        images.values.filter { it.gameId == gameId }.forEach { images[it.id] = it.copy(localPath = null) }
    }

    override suspend fun openCommitments(gameId: String, limit: Int) =
        commitments.values.filter { it.gameId == gameId && it.isOpen() }
            .sortedByDescending { it.createdWorldMinutes }.take(limit)

    override suspend fun allCommitments(gameId: String) =
        commitments.values.filter { it.gameId == gameId }.sortedBy { it.createdWorldMinutes }

    override suspend fun commitmentsInvolving(gameId: String, entityId: String, limit: Int) =
        commitments.values
            .filter { it.gameId == gameId && (it.fromEntityId == entityId || it.toEntityId == entityId) }
            .sortedByDescending { it.createdWorldMinutes }.take(limit)

    override suspend fun upsertCommitments(commitments: Collection<com.unbound.core.continuity.CommitmentRecord>) {
        commitments.forEach { this.commitments[it.id] = it }
    }

    override suspend fun recordUsage(usage: UsageRecord) { this.usage.add(usage) }
    override suspend fun usageFor(gameId: String?, limit: Int) =
        usage.filter { gameId == null || it.gameId == gameId }.sortedByDescending { it.timestampMs }.take(limit)

    override suspend fun usageTotals(gameId: String?): UsageTotals {
        val rows = usage.filter { gameId == null || it.gameId == gameId }
        if (rows.isEmpty()) return UsageTotals()
        return UsageTotals(
            requests = rows.size,
            inputTokens = rows.sumOf { it.inputTokens.toLong() },
            outputTokens = rows.sumOf { it.outputTokens.toLong() },
            cachedTokens = rows.sumOf { it.cachedTokens.toLong() },
            imageRequests = rows.count { it.requestType == RequestType.IMAGE },
            failures = rows.count { !it.success },
            averageLatencyMs = rows.map { it.latencyMs }.average().toLong(),
        )
    }

    override suspend fun usageByModel(gameId: String?): List<com.unbound.core.engine.UsageAggregate> =
        usage.filter { gameId == null || it.gameId == gameId }
            .groupBy { Triple(it.modelId, it.providerId, it.requestType) }
            .map { (key, rows) ->
                com.unbound.core.engine.UsageAggregate(
                    modelId = key.first,
                    providerId = key.second,
                    requestType = key.third,
                    requests = rows.size,
                    inputTokens = rows.sumOf { it.inputTokens.toLong() },
                    outputTokens = rows.sumOf { it.outputTokens.toLong() },
                    cachedTokens = rows.sumOf { it.cachedTokens.toLong() },
                    failures = rows.count { !it.success },
                    totalLatencyMs = rows.sumOf { it.latencyMs },
                )
            }
}
