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

/**
 * Counts how many times each store operation is called.
 *
 * A turn's database cost is as much a product property as its token cost: a per-turn query count
 * that grows with campaign size is how a game that felt fine in testing becomes unusable at turn
 * four hundred. This makes that measurable rather than assumed.
 */
class CountingWorldStore(private val delegate: WorldStore) : WorldStore {

    private val counts = linkedMapOf<String, Int>()

    val total: Int get() = counts.values.sum()

    fun reset() = counts.clear()

    fun countOf(operation: String): Int = counts[operation] ?: 0

    fun report(): String = counts.entries
        .sortedByDescending { it.value }
        .joinToString("\n") { "    ${it.value} x ${it.key}" }

    private inline fun <T> track(name: String, block: () -> T): T {
        counts[name] = (counts[name] ?: 0) + 1
        return block()
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = delegate.transaction(block)

    override suspend fun getGame(gameId: String) = track("getGame") { delegate.getGame(gameId) }
    override suspend fun listGames() = track("listGames") { delegate.listGames() }
    override suspend fun upsertGame(game: GameRecord) = track("upsertGame") { delegate.upsertGame(game) }
    override suspend fun deleteGame(gameId: String) = track("deleteGame") { delegate.deleteGame(gameId) }
    override suspend fun bumpStateVersion(gameId: String, expectedVersion: Long) =
        track("bumpStateVersion") { delegate.bumpStateVersion(gameId, expectedVersion) }

    override suspend fun getWorld(gameId: String) = track("getWorld") { delegate.getWorld(gameId) }
    override suspend fun upsertWorld(world: WorldRecord) = track("upsertWorld") { delegate.upsertWorld(world) }
    override suspend fun getPlayer(gameId: String) = track("getPlayer") { delegate.getPlayer(gameId) }
    override suspend fun upsertPlayer(player: PlayerRecord) = track("upsertPlayer") { delegate.upsertPlayer(player) }

    override suspend fun getNpc(gameId: String, npcId: String) = track("getNpc") { delegate.getNpc(gameId, npcId) }
    override suspend fun npcsAt(gameId: String, locationId: String) = track("npcsAt") { delegate.npcsAt(gameId, locationId) }
    override suspend fun npcsByIds(gameId: String, ids: Collection<String>) = track("npcsByIds") { delegate.npcsByIds(gameId, ids) }
    override suspend fun persistentNpcs(gameId: String, limit: Int) = track("persistentNpcs") { delegate.persistentNpcs(gameId, limit) }
    override suspend fun allNpcs(gameId: String, limit: Int) = track("allNpcs") { delegate.allNpcs(gameId, limit) }
    override suspend fun countNpcs(gameId: String) = track("countNpcs") { delegate.countNpcs(gameId) }
    override suspend fun upsertNpcs(npcs: Collection<NpcRecord>) = track("upsertNpcs") { delegate.upsertNpcs(npcs) }

    override suspend fun getLocation(gameId: String, locationId: String) = track("getLocation") { delegate.getLocation(gameId, locationId) }
    override suspend fun locationsByIds(gameId: String, ids: Collection<String>) = track("locationsByIds") { delegate.locationsByIds(gameId, ids) }
    override suspend fun discoveredLocations(gameId: String, limit: Int) = track("discoveredLocations") { delegate.discoveredLocations(gameId, limit) }
    override suspend fun allLocations(gameId: String, limit: Int) = track("allLocations") { delegate.allLocations(gameId, limit) }
    override suspend fun upsertLocations(locations: Collection<LocationRecord>) = track("upsertLocations") { delegate.upsertLocations(locations) }

    override suspend fun getFaction(gameId: String, factionId: String) = track("getFaction") { delegate.getFaction(gameId, factionId) }
    override suspend fun factions(gameId: String) = track("factions") { delegate.factions(gameId) }
    override suspend fun upsertFactions(factions: Collection<FactionRecord>) = track("upsertFactions") { delegate.upsertFactions(factions) }

    override suspend fun getItem(gameId: String, itemId: String) = track("getItem") { delegate.getItem(gameId, itemId) }
    override suspend fun itemsOwnedBy(gameId: String, ownerId: String) = track("itemsOwnedBy") { delegate.itemsOwnedBy(gameId, ownerId) }
    override suspend fun itemsByIds(gameId: String, ids: Collection<String>) = track("itemsByIds") { delegate.itemsByIds(gameId, ids) }
    override suspend fun itemsAt(gameId: String, locationId: String) = track("itemsAt") { delegate.itemsAt(gameId, locationId) }
    override suspend fun allItems(gameId: String, limit: Int) = track("allItems") { delegate.allItems(gameId, limit) }
    override suspend fun upsertItems(items: Collection<ItemRecord>) = track("upsertItems") { delegate.upsertItems(items) }

    override suspend fun activeThreads(gameId: String, limit: Int) = track("activeThreads") { delegate.activeThreads(gameId, limit) }
    override suspend fun allThreads(gameId: String, limit: Int) = track("allThreads") { delegate.allThreads(gameId, limit) }
    override suspend fun getThread(gameId: String, threadId: String) = track("getThread") { delegate.getThread(gameId, threadId) }
    override suspend fun upsertThreads(threads: Collection<ThreadRecord>) = track("upsertThreads") { delegate.upsertThreads(threads) }

    override suspend fun getRelationship(gameId: String, from: String, to: String) = track("getRelationship") { delegate.getRelationship(gameId, from, to) }
    override suspend fun relationshipsFrom(gameId: String, from: String, limit: Int) = track("relationshipsFrom") { delegate.relationshipsFrom(gameId, from, limit) }
    override suspend fun allRelationships(gameId: String, limit: Int) = track("allRelationships") { delegate.allRelationships(gameId, limit) }
    override suspend fun upsertRelationships(records: Collection<RelationshipRecord>) = track("upsertRelationships") { delegate.upsertRelationships(records) }

    override suspend fun knowledgeOf(gameId: String, knowerId: String, limit: Int) = track("knowledgeOf") { delegate.knowledgeOf(gameId, knowerId, limit) }
    override suspend fun knowledgeOfAbout(gameId: String, knowerId: String, subjectIds: Collection<String>, limit: Int) =
        track("knowledgeOfAbout") { delegate.knowledgeOfAbout(gameId, knowerId, subjectIds, limit) }
    override suspend fun hasFact(gameId: String, knowerId: String, factKey: String) = track("hasFact") { delegate.hasFact(gameId, knowerId, factKey) }
    override suspend fun allKnowledge(gameId: String, limit: Int) = track("allKnowledge") { delegate.allKnowledge(gameId, limit) }
    override suspend fun upsertKnowledge(records: Collection<KnowledgeRecord>) = track("upsertKnowledge") { delegate.upsertKnowledge(records) }

    override suspend fun activeRumors(gameId: String, limit: Int) = track("activeRumors") { delegate.activeRumors(gameId, limit) }
    override suspend fun allRumors(gameId: String, limit: Int) = track("allRumors") { delegate.allRumors(gameId, limit) }
    override suspend fun upsertRumors(records: Collection<RumorRecord>) = track("upsertRumors") { delegate.upsertRumors(records) }

    override suspend fun memoryCandidates(
        gameId: String,
        ownerIds: Collection<String>,
        entityIds: Collection<String>,
        locationId: String?,
        threadIds: Collection<String>,
        minImportance: Importance,
        limit: Int,
    ) = track("memoryCandidates") {
        delegate.memoryCandidates(gameId, ownerIds, entityIds, locationId, threadIds, minImportance, limit)
    }

    override suspend fun memoriesOf(gameId: String, ownerId: String, limit: Int) = track("memoriesOf") { delegate.memoriesOf(gameId, ownerId, limit) }
    override suspend fun allMemories(gameId: String, limit: Int) = track("allMemories") { delegate.allMemories(gameId, limit) }
    override suspend fun countMemories(gameId: String) = track("countMemories") { delegate.countMemories(gameId) }
    override suspend fun upsertMemories(records: Collection<MemoryRecord>) = track("upsertMemories") { delegate.upsertMemories(records) }
    override suspend fun deleteMemories(ids: Collection<String>) = track("deleteMemories") { delegate.deleteMemories(ids) }

    override suspend fun summaries(gameId: String, limit: Int) = track("summaries") { delegate.summaries(gameId, limit) }
    override suspend fun upsertSummaries(records: Collection<SummaryRecord>) = track("upsertSummaries") { delegate.upsertSummaries(records) }

    override suspend fun appendEvents(events: Collection<GameEvent>) = track("appendEvents") { delegate.appendEvents(events) }
    override suspend fun recentEvents(gameId: String, limit: Int) = track("recentEvents") { delegate.recentEvents(gameId, limit) }
    override suspend fun importantEventsBefore(gameId: String, beforeWorldMinutes: Long, minImportance: Importance, limit: Int) =
        track("importantEventsBefore") { delegate.importantEventsBefore(gameId, beforeWorldMinutes, minImportance, limit) }
    override suspend fun eventsInvolving(gameId: String, entityIds: Collection<String>, limit: Int) =
        track("eventsInvolving") { delegate.eventsInvolving(gameId, entityIds, limit) }
    override suspend fun eventsPage(gameId: String, offset: Int, limit: Int) = track("eventsPage") { delegate.eventsPage(gameId, offset, limit) }
    override suspend fun nextEventSequence(gameId: String) = track("nextEventSequence") { delegate.nextEventSequence(gameId) }
    override suspend fun countEvents(gameId: String) = track("countEvents") { delegate.countEvents(gameId) }
    override suspend fun deleteEventsAfterSequence(gameId: String, sequence: Long) = track("deleteEventsAfterSequence") { delegate.deleteEventsAfterSequence(gameId, sequence) }

    override suspend fun clearGameEntities(gameId: String) = track("clearGameEntities") { delegate.clearGameEntities(gameId) }
    override suspend fun deleteTurnsAfter(gameId: String, turnNumber: Int) = track("deleteTurnsAfter") { delegate.deleteTurnsAfter(gameId, turnNumber) }

    override suspend fun upsertTurn(turn: TurnRecord) = track("upsertTurn") { delegate.upsertTurn(turn) }
    override suspend fun getTurn(turnId: String) = track("getTurn") { delegate.getTurn(turnId) }
    override suspend fun turnByIdempotencyKey(gameId: String, key: String) = track("turnByIdempotencyKey") { delegate.turnByIdempotencyKey(gameId, key) }
    override suspend fun recentTurns(gameId: String, limit: Int) = track("recentTurns") { delegate.recentTurns(gameId, limit) }
    override suspend fun turnsPage(gameId: String, offset: Int, limit: Int) = track("turnsPage") { delegate.turnsPage(gameId, offset, limit) }
    override suspend fun countTurns(gameId: String) = track("countTurns") { delegate.countTurns(gameId) }
    override suspend fun pendingTurns(gameId: String) = track("pendingTurns") { delegate.pendingTurns(gameId) }

    override suspend fun upsertSnapshot(snapshot: SnapshotRecord) = track("upsertSnapshot") { delegate.upsertSnapshot(snapshot) }
    override suspend fun latestSnapshotAtOrBefore(gameId: String, turnNumber: Int) = track("latestSnapshotAtOrBefore") { delegate.latestSnapshotAtOrBefore(gameId, turnNumber) }
    override suspend fun snapshots(gameId: String, limit: Int) = track("snapshots") { delegate.snapshots(gameId, limit) }
    override suspend fun deleteSnapshotsAfter(gameId: String, turnNumber: Int) = track("deleteSnapshotsAfter") { delegate.deleteSnapshotsAfter(gameId, turnNumber) }
    override suspend fun deleteSnapshots(ids: Collection<String>) = track("deleteSnapshots") { delegate.deleteSnapshots(ids) }

    override suspend fun upsertImage(image: ImageRecord) = track("upsertImage") { delegate.upsertImage(image) }
    override suspend fun canonicalImageFor(gameId: String, entityId: String) = track("canonicalImageFor") { delegate.canonicalImageFor(gameId, entityId) }
    override suspend fun imagesFor(gameId: String, entityId: String) = track("imagesFor") { delegate.imagesFor(gameId, entityId) }
    override suspend fun allImages(gameId: String) = track("allImages") { delegate.allImages(gameId) }
    override suspend fun deleteImageFiles(gameId: String) = track("deleteImageFiles") { delegate.deleteImageFiles(gameId) }

    override suspend fun recordUsage(usage: UsageRecord) = track("recordUsage") { delegate.recordUsage(usage) }
    override suspend fun usageFor(gameId: String?, limit: Int) = track("usageFor") { delegate.usageFor(gameId, limit) }
    override suspend fun usageTotals(gameId: String?): UsageTotals = track("usageTotals") { delegate.usageTotals(gameId) }
}
