package com.unbound.core.engine

import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.FactionRecord
import com.unbound.core.model.GameRecord
import com.unbound.core.model.ImageRecord
import com.unbound.core.model.Importance
import com.unbound.core.model.ItemRecord
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.SnapshotRecord
import com.unbound.core.model.ThreadRecord
import com.unbound.core.model.TurnRecord
import com.unbound.core.model.UsageRecord
import com.unbound.core.model.WorldRecord
import com.unbound.core.relationship.RelationshipRecord

/**
 * Everything the engine needs from storage, expressed without reference to any database technology.
 *
 * Two properties of this interface matter more than its size:
 *
 *  * Every read is **scoped and bounded**. There is no `allEvents(gameId)`. A campaign with 40,000
 *    events must never be able to load them all, because the moment such a method exists someone
 *    will call it on the main thread (§64).
 *  * [transaction] is the only way to commit a turn, and it must be genuinely atomic. Partial turn
 *    commits are the one failure mode that corrupts a save beyond repair (§44).
 */
interface WorldStore {

    /** Runs [block] atomically. An exception must roll everything back. */
    suspend fun <T> transaction(block: suspend () -> T): T

    // --- games -------------------------------------------------------------------------------
    suspend fun getGame(gameId: String): GameRecord?
    suspend fun listGames(): List<GameRecord>
    suspend fun upsertGame(game: GameRecord)
    suspend fun deleteGame(gameId: String)

    /**
     * The optimistic lock, and the single point at which a turn becomes real.
     *
     * Implementations must perform this as one atomic conditional write — "set version to
     * expected+1 *only if* it is still expected" — and return false otherwise. Returning false
     * means another turn committed first, and the caller abandons rather than merging (§46).
     * Callers must invoke this *before* writing the game row, so the guard cannot be bypassed.
     */
    suspend fun bumpStateVersion(gameId: String, expectedVersion: Long): Boolean

    // --- world / player ----------------------------------------------------------------------
    suspend fun getWorld(gameId: String): WorldRecord?
    suspend fun upsertWorld(world: WorldRecord)
    suspend fun getPlayer(gameId: String): PlayerRecord?
    suspend fun upsertPlayer(player: PlayerRecord)

    // --- npcs --------------------------------------------------------------------------------
    suspend fun getNpc(gameId: String, npcId: String): NpcRecord?
    suspend fun npcsAt(gameId: String, locationId: String): List<NpcRecord>
    suspend fun npcsByIds(gameId: String, ids: Collection<String>): List<NpcRecord>
    suspend fun persistentNpcs(gameId: String, limit: Int = 200): List<NpcRecord>
    suspend fun upsertNpcs(npcs: Collection<NpcRecord>)
    suspend fun countNpcs(gameId: String): Int

    // --- locations ---------------------------------------------------------------------------
    suspend fun getLocation(gameId: String, locationId: String): LocationRecord?
    suspend fun locationsByIds(gameId: String, ids: Collection<String>): List<LocationRecord>
    suspend fun discoveredLocations(gameId: String, limit: Int = 200): List<LocationRecord>
    suspend fun upsertLocations(locations: Collection<LocationRecord>)

    // --- factions ----------------------------------------------------------------------------
    suspend fun getFaction(gameId: String, factionId: String): FactionRecord?
    suspend fun factions(gameId: String): List<FactionRecord>
    suspend fun upsertFactions(factions: Collection<FactionRecord>)

    // --- items -------------------------------------------------------------------------------
    suspend fun getItem(gameId: String, itemId: String): ItemRecord?
    suspend fun itemsOwnedBy(gameId: String, ownerId: String): List<ItemRecord>
    suspend fun itemsByIds(gameId: String, ids: Collection<String>): List<ItemRecord>
    suspend fun itemsAt(gameId: String, locationId: String): List<ItemRecord>
    suspend fun upsertItems(items: Collection<ItemRecord>)

    // --- relationships -----------------------------------------------------------------------
    suspend fun getRelationship(gameId: String, from: String, to: String): RelationshipRecord?
    suspend fun relationshipsFrom(gameId: String, from: String, limit: Int = 100): List<RelationshipRecord>
    suspend fun upsertRelationships(records: Collection<RelationshipRecord>)

    // --- threads -----------------------------------------------------------------------------
    suspend fun activeThreads(gameId: String, limit: Int = 20): List<ThreadRecord>
    suspend fun allThreads(gameId: String, limit: Int = 100): List<ThreadRecord>
    suspend fun getThread(gameId: String, threadId: String): ThreadRecord?
    suspend fun upsertThreads(threads: Collection<ThreadRecord>)

    // --- knowledge ---------------------------------------------------------------------------
    suspend fun knowledgeOf(gameId: String, knowerId: String, limit: Int = 50): List<KnowledgeRecord>
    suspend fun knowledgeOfAbout(gameId: String, knowerId: String, subjectIds: Collection<String>, limit: Int = 20): List<KnowledgeRecord>
    suspend fun hasFact(gameId: String, knowerId: String, factKey: String): Boolean
    suspend fun upsertKnowledge(records: Collection<KnowledgeRecord>)

    suspend fun activeRumors(gameId: String, limit: Int = 40): List<RumorRecord>
    suspend fun upsertRumors(records: Collection<RumorRecord>)

    // --- memory ------------------------------------------------------------------------------
    /**
     * Structured pre-filter for retrieval. Implementations must push this down to indexed SQL —
     * the scorer only ever sees the candidates this returns, never the whole table.
     */
    suspend fun memoryCandidates(
        gameId: String,
        ownerIds: Collection<String>,
        entityIds: Collection<String>,
        locationId: String?,
        threadIds: Collection<String>,
        minImportance: Importance,
        limit: Int,
    ): List<MemoryRecord>

    suspend fun memoriesOf(gameId: String, ownerId: String, limit: Int = 50): List<MemoryRecord>
    suspend fun upsertMemories(records: Collection<MemoryRecord>)
    suspend fun deleteMemories(ids: Collection<String>)
    suspend fun countMemories(gameId: String): Int

    suspend fun summaries(gameId: String, limit: Int = 5): List<SummaryRecord>
    suspend fun upsertSummaries(records: Collection<SummaryRecord>)

    // --- event ledger -------------------------------------------------------------------------
    suspend fun appendEvents(events: Collection<GameEvent>)
    suspend fun recentEvents(gameId: String, limit: Int): List<GameEvent>
    suspend fun importantEventsBefore(gameId: String, beforeWorldMinutes: Long, minImportance: Importance, limit: Int): List<GameEvent>
    suspend fun eventsInvolving(gameId: String, entityIds: Collection<String>, limit: Int): List<GameEvent>
    suspend fun eventsPage(gameId: String, offset: Int, limit: Int): List<GameEvent>
    suspend fun nextEventSequence(gameId: String): Long
    suspend fun countEvents(gameId: String): Int
    suspend fun deleteEventsAfterSequence(gameId: String, sequence: Long)

    // --- whole-game enumeration -----------------------------------------------------------------
    /**
     * Full-table reads, for export and migration only.
     *
     * These are the deliberate exceptions to the "every read is bounded" rule, and they are named
     * so that their cost is obvious at the call site. They must never be used to build a prompt or
     * to populate a screen — [limit] exists so that even a runaway save cannot exhaust memory.
     */
    suspend fun allLocations(gameId: String, limit: Int = 20_000): List<LocationRecord>
    suspend fun allItems(gameId: String, limit: Int = 20_000): List<ItemRecord>
    suspend fun allRelationships(gameId: String, limit: Int = 20_000): List<RelationshipRecord>
    suspend fun allKnowledge(gameId: String, limit: Int = 50_000): List<KnowledgeRecord>
    suspend fun allMemories(gameId: String, limit: Int = 50_000): List<MemoryRecord>
    suspend fun allRumors(gameId: String, limit: Int = 20_000): List<RumorRecord>
    suspend fun allNpcs(gameId: String, limit: Int = 20_000): List<NpcRecord>

    // --- turns --------------------------------------------------------------------------------
    suspend fun upsertTurn(turn: TurnRecord)
    suspend fun getTurn(turnId: String): TurnRecord?
    suspend fun turnByIdempotencyKey(gameId: String, key: String): TurnRecord?
    suspend fun recentTurns(gameId: String, limit: Int): List<TurnRecord>
    suspend fun turnsPage(gameId: String, offset: Int, limit: Int): List<TurnRecord>
    suspend fun countTurns(gameId: String): Int
    suspend fun pendingTurns(gameId: String): List<TurnRecord>

    // --- snapshots ----------------------------------------------------------------------------
    suspend fun upsertSnapshot(snapshot: SnapshotRecord)
    suspend fun latestSnapshotAtOrBefore(gameId: String, turnNumber: Int): SnapshotRecord?
    suspend fun snapshots(gameId: String, limit: Int = 20): List<SnapshotRecord>
    suspend fun deleteSnapshotsAfter(gameId: String, turnNumber: Int)

    // --- images / usage -------------------------------------------------------------------------
    suspend fun upsertImage(image: ImageRecord)
    suspend fun canonicalImageFor(gameId: String, entityId: String): ImageRecord?
    suspend fun imagesFor(gameId: String, entityId: String): List<ImageRecord>
    suspend fun allImages(gameId: String): List<ImageRecord>
    suspend fun deleteImageFiles(gameId: String)

    suspend fun recordUsage(usage: UsageRecord)
    suspend fun usageFor(gameId: String?, limit: Int = 500): List<UsageRecord>
    suspend fun usageTotals(gameId: String?): UsageTotals
}

data class UsageTotals(
    val requests: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val imageRequests: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val failures: Int = 0,
    val averageLatencyMs: Long = 0,
)
