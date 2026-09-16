package com.unbound.rpg.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GameDao {
    @Query("SELECT * FROM games WHERE id = :gameId")
    suspend fun get(gameId: String): GameEntity?

    @Query("SELECT * FROM games ORDER BY updatedAtEpochMs DESC")
    suspend fun list(): List<GameEntity>

    @Upsert suspend fun upsert(game: GameEntity)

    @Query("DELETE FROM games WHERE id = :gameId")
    suspend fun delete(gameId: String)

    /**
     * The optimistic lock, as one atomic conditional write. SQLite evaluates the WHERE and the SET
     * in a single statement, so two concurrent turns cannot both see the same version and both win
     * — exactly one UPDATE reports a changed row.
     */
    @Query("UPDATE games SET stateVersion = stateVersion + 1 WHERE id = :gameId AND stateVersion = :expected")
    suspend fun bumpVersion(gameId: String, expected: Long): Int
}

@Dao
interface WorldDao {
    @Query("SELECT * FROM worlds WHERE gameId = :gameId") suspend fun get(gameId: String): WorldEntity?
    @Upsert suspend fun upsert(world: WorldEntity)
}

@Dao
interface PlayerDao {
    @Query("SELECT * FROM players WHERE gameId = :gameId") suspend fun get(gameId: String): PlayerEntity?
    @Upsert suspend fun upsert(player: PlayerEntity)
}

@Dao
interface NpcDao {
    @Query("DELETE FROM npcs WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM npcs WHERE id = :id AND gameId = :gameId")
    suspend fun get(gameId: String, id: String): NpcEntity?

    @Query("SELECT * FROM npcs WHERE gameId = :gameId AND currentLocationId = :locationId")
    suspend fun at(gameId: String, locationId: String): List<NpcEntity>

    @Query("SELECT * FROM npcs WHERE gameId = :gameId AND id IN (:ids)")
    suspend fun byIds(gameId: String, ids: Collection<String>): List<NpcEntity>

    @Query("SELECT * FROM npcs WHERE gameId = :gameId AND tier != 'AMBIENT' LIMIT :limit")
    suspend fun persistent(gameId: String, limit: Int): List<NpcEntity>

    @Query("SELECT * FROM npcs WHERE gameId = :gameId LIMIT :limit")
    suspend fun all(gameId: String, limit: Int): List<NpcEntity>

    @Upsert suspend fun upsertAll(npcs: Collection<NpcEntity>)
}

@Dao
interface LocationDao {
    @Query("DELETE FROM locations WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM locations WHERE id = :id AND gameId = :gameId") suspend fun get(gameId: String, id: String): LocationEntity?
    @Query("SELECT * FROM locations WHERE gameId = :gameId AND id IN (:ids)") suspend fun byIds(gameId: String, ids: Collection<String>): List<LocationEntity>
    @Query("SELECT * FROM locations WHERE gameId = :gameId AND discovered = 1 LIMIT :limit") suspend fun discovered(gameId: String, limit: Int): List<LocationEntity>
    @Query("SELECT * FROM locations WHERE gameId = :gameId LIMIT :limit") suspend fun all(gameId: String, limit: Int): List<LocationEntity>
    @Upsert suspend fun upsertAll(locations: Collection<LocationEntity>)
}

@Dao
interface FactionDao {
    @Query("DELETE FROM factions WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM factions WHERE id = :id AND gameId = :gameId") suspend fun get(gameId: String, id: String): FactionEntity?
    @Query("SELECT * FROM factions WHERE gameId = :gameId") suspend fun all(gameId: String): List<FactionEntity>
    @Upsert suspend fun upsertAll(factions: Collection<FactionEntity>)
}

@Dao
interface ItemDao {
    @Query("DELETE FROM items WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM items WHERE id = :id AND gameId = :gameId") suspend fun get(gameId: String, id: String): ItemEntity?
    @Query("SELECT * FROM items WHERE gameId = :gameId AND ownerId = :ownerId AND destroyed = 0") suspend fun ownedBy(gameId: String, ownerId: String): List<ItemEntity>
    @Query("SELECT * FROM items WHERE gameId = :gameId AND ownerId IN (:ownerIds) AND destroyed = 0")
    suspend fun ownedByAny(gameId: String, ownerIds: Collection<String>): List<ItemEntity>
    @Query("SELECT * FROM items WHERE gameId = :gameId AND locationId = :locationId") suspend fun at(gameId: String, locationId: String): List<ItemEntity>
    @Query("SELECT * FROM items WHERE gameId = :gameId LIMIT :limit") suspend fun all(gameId: String, limit: Int): List<ItemEntity>
    @Upsert suspend fun upsertAll(items: Collection<ItemEntity>)
}

@Dao
interface ThreadDao {
    @Query("DELETE FROM threads WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM threads WHERE gameId = :gameId AND terminal = 0 ORDER BY rank DESC LIMIT :limit")
    suspend fun active(gameId: String, limit: Int): List<ThreadEntity>

    @Query("SELECT * FROM threads WHERE gameId = :gameId LIMIT :limit") suspend fun all(gameId: String, limit: Int): List<ThreadEntity>
    @Upsert suspend fun upsertAll(threads: Collection<ThreadEntity>)
}

@Dao
interface RelationshipDao {
    @Query("DELETE FROM relationships WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM relationships WHERE gameId = :gameId AND fromEntityId = :from AND toEntityId = :to")
    suspend fun get(gameId: String, from: String, to: String): RelationshipEntity?

    @Query("SELECT * FROM relationships WHERE gameId = :gameId AND fromEntityId = :from LIMIT :limit")
    suspend fun from(gameId: String, from: String, limit: Int): List<RelationshipEntity>

    @Query("SELECT * FROM relationships WHERE gameId = :gameId LIMIT :limit")
    suspend fun all(gameId: String, limit: Int): List<RelationshipEntity>

    @Upsert suspend fun upsertAll(records: Collection<RelationshipEntity>)
}

@Dao
interface KnowledgeDao {
    @Query("DELETE FROM knowledge WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM knowledge WHERE gameId = :gameId AND knowerId = :knowerId ORDER BY learnedAtWorldMinutes DESC LIMIT :limit")
    suspend fun of(gameId: String, knowerId: String, limit: Int): List<KnowledgeEntity>

    /**
     * "What does this character know about any of these people or things?" — the per-NPC knowledge
     * block on every turn. The join keeps it index-backed rather than a scan.
     */
    @Query(
        """
        SELECT k.* FROM knowledge k
        INNER JOIN knowledge_subjects s ON s.knowledgeId = k.id
        WHERE k.gameId = :gameId AND k.knowerId = :knowerId AND s.entityId IN (:subjectIds)
        GROUP BY k.id
        ORDER BY k.importance DESC, k.learnedAtWorldMinutes DESC
        LIMIT :limit
        """,
    )
    suspend fun about(gameId: String, knowerId: String, subjectIds: Collection<String>, limit: Int): List<KnowledgeEntity>

    /**
     * Everything several knowers hold, in one read.
     *
     * The per-knower window is applied in SQL so a single character with a thousand facts cannot
     * crowd out everyone else in the scene. Without the window this would have to over-fetch the
     * whole table and trim in memory, which is the scan the join above exists to avoid.
     */
    @Query(
        """
        SELECT * FROM (
            SELECT k.*, ROW_NUMBER() OVER (
                PARTITION BY k.knowerId ORDER BY k.importance DESC, k.learnedAtWorldMinutes DESC
            ) AS rn
            FROM knowledge k
            WHERE k.gameId = :gameId AND k.knowerId IN (:knowerIds)
        )
        WHERE rn <= :limitPerKnower
        """,
    )
    suspend fun forKnowers(gameId: String, knowerIds: Collection<String>, limitPerKnower: Int): List<KnowledgeEntity>

    @Query("SELECT * FROM knowledge WHERE gameId = :gameId AND knowerId = :knowerId AND factKey = :factKey LIMIT 1")
    suspend fun byFact(gameId: String, knowerId: String, factKey: String): KnowledgeEntity?

    @Query("SELECT * FROM knowledge WHERE gameId = :gameId LIMIT :limit")
    suspend fun all(gameId: String, limit: Int): List<KnowledgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(record: KnowledgeEntity)
    @Query("DELETE FROM knowledge WHERE id = :id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSubjects(refs: Collection<KnowledgeSubjectRef>)
}

@Dao
interface RumorDao {
    @Query("DELETE FROM rumors WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM rumors WHERE gameId = :gameId AND virality > 5 ORDER BY virality DESC LIMIT :limit")
    suspend fun active(gameId: String, limit: Int): List<RumorEntity>
    @Query("SELECT * FROM rumors WHERE gameId = :gameId LIMIT :limit") suspend fun all(gameId: String, limit: Int): List<RumorEntity>
    @Upsert suspend fun upsertAll(records: Collection<RumorEntity>)
}

@Dao
interface MemoryDao {
    @Query("DELETE FROM memories WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    /**
     * The retrieval pre-filter. Everything is index-backed and the result is capped before any
     * scoring happens, so the candidate set is bounded no matter how large the campaign is.
     */
    @Query(
        """
        SELECT m.* FROM memories m
        WHERE m.gameId = :gameId AND m.importance >= :minImportance
          AND (
            m.ownerId IN (:ownerIds)
            OR (:locationId IS NOT NULL AND m.locationId = :locationId)
            OR m.threadId IN (:threadIds)
            OR m.id IN (SELECT r.memoryId FROM memory_entities r WHERE r.gameId = :gameId AND r.entityId IN (:entityIds))
          )
        ORDER BY m.importance DESC, m.lastReinforcedWorldMinutes DESC
        LIMIT :limit
        """,
    )
    suspend fun candidates(
        gameId: String,
        ownerIds: Collection<String>,
        entityIds: Collection<String>,
        locationId: String?,
        threadIds: Collection<String>,
        minImportance: Int,
        limit: Int,
    ): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE gameId = :gameId AND ownerId = :ownerId ORDER BY lastReinforcedWorldMinutes DESC LIMIT :limit")
    suspend fun of(gameId: String, ownerId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE gameId = :gameId LIMIT :limit") suspend fun all(gameId: String, limit: Int): List<MemoryEntity>
    @Query("SELECT COUNT(*) FROM memories WHERE gameId = :gameId") suspend fun count(gameId: String): Int
    @Upsert suspend fun upsertAll(records: Collection<MemoryEntity>)
    @Query("DELETE FROM memories WHERE id IN (:ids)") suspend fun delete(ids: Collection<String>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRefs(refs: Collection<MemoryEntityRef>)
    @Query("DELETE FROM memory_entities WHERE memoryId IN (:ids)") suspend fun deleteRefs(ids: Collection<String>)
}

@Dao
interface SummaryDao {
    @Query("DELETE FROM summaries WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Query("SELECT * FROM summaries WHERE gameId = :gameId ORDER BY coversToTurn DESC LIMIT :limit")
    suspend fun recent(gameId: String, limit: Int): List<SummaryEntity>
    @Upsert suspend fun upsertAll(records: Collection<SummaryEntity>)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(events: Collection<EventEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRefs(refs: Collection<EventEntityRef>)

    @Query("SELECT * FROM events WHERE gameId = :gameId ORDER BY sequence DESC LIMIT :limit")
    suspend fun recent(gameId: String, limit: Int): List<EventEntity>

    @Query(
        """
        SELECT * FROM events
        WHERE gameId = :gameId AND worldMinutes < :before AND importance >= :minImportance
        ORDER BY sequence DESC LIMIT :limit
        """,
    )
    suspend fun importantBefore(gameId: String, before: Long, minImportance: Int, limit: Int): List<EventEntity>

    @Query(
        """
        SELECT e.* FROM events e
        WHERE e.gameId = :gameId
          AND (e.actorId IN (:entityIds) OR e.targetId IN (:entityIds)
               OR e.id IN (SELECT r.eventId FROM event_entities r WHERE r.gameId = :gameId AND r.entityId IN (:entityIds)))
        ORDER BY e.sequence DESC LIMIT :limit
        """,
    )
    suspend fun involving(gameId: String, entityIds: Collection<String>, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE gameId = :gameId ORDER BY sequence DESC LIMIT :limit OFFSET :offset")
    suspend fun page(gameId: String, offset: Int, limit: Int): List<EventEntity>

    @Query("SELECT COALESCE(MAX(sequence), 0) FROM events WHERE gameId = :gameId")
    suspend fun maxSequence(gameId: String): Long

    @Query("SELECT COUNT(*) FROM events WHERE gameId = :gameId") suspend fun count(gameId: String): Int
    @Query("DELETE FROM events WHERE gameId = :gameId AND sequence > :sequence") suspend fun deleteAfter(gameId: String, sequence: Long)
    @Query("SELECT * FROM events WHERE gameId = :gameId AND id IN (:ids)")
    suspend fun byIds(gameId: String, ids: Collection<String>): List<EventEntity>

}

@Dao
interface TurnDao {
    @Upsert suspend fun upsert(turn: TurnEntity)
    @Query("SELECT * FROM turns WHERE gameId = :gameId AND idempotencyKey = :key ORDER BY CASE status WHEN 'COMPLETE' THEN 0 ELSE 1 END LIMIT 1")
    suspend fun byIdempotencyKey(gameId: String, key: String): TurnEntity?

    @Query("SELECT * FROM turns WHERE gameId = :gameId AND status = 'COMPLETE' ORDER BY turnNumber DESC LIMIT :limit")
    suspend fun recent(gameId: String, limit: Int): List<TurnEntity>

    @Query("SELECT * FROM turns WHERE gameId = :gameId AND status = 'COMPLETE' ORDER BY turnNumber DESC LIMIT :limit OFFSET :offset")
    suspend fun page(gameId: String, offset: Int, limit: Int): List<TurnEntity>

    @Query("SELECT COUNT(*) FROM turns WHERE gameId = :gameId AND status = 'COMPLETE'") suspend fun count(gameId: String): Int

    @Query("SELECT * FROM turns WHERE gameId = :gameId AND status IN ('PENDING', 'AWAITING_COMMIT')")
    suspend fun pending(gameId: String): List<TurnEntity>

    @Query("DELETE FROM turns WHERE gameId = :gameId AND turnNumber > :turnNumber") suspend fun deleteAfter(gameId: String, turnNumber: Int)
}

@Dao
interface SnapshotDao {
    @Upsert suspend fun upsert(snapshot: SnapshotEntity)
    @Query("SELECT * FROM snapshots WHERE gameId = :gameId AND turnNumber <= :turnNumber ORDER BY turnNumber DESC LIMIT 1")
    suspend fun latestAtOrBefore(gameId: String, turnNumber: Int): SnapshotEntity?
    @Query("SELECT * FROM snapshots WHERE gameId = :gameId ORDER BY turnNumber DESC LIMIT :limit")
    suspend fun recent(gameId: String, limit: Int): List<SnapshotEntity>
    @Query("DELETE FROM snapshots WHERE gameId = :gameId AND turnNumber > :turnNumber")
    suspend fun deleteAfter(gameId: String, turnNumber: Int)
    @Query("DELETE FROM snapshots WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Collection<String>)
}

@Dao
interface ImageDao {
    @Upsert suspend fun upsert(image: ImageEntity)
    @Query("SELECT * FROM images WHERE gameId = :gameId AND entityId = :entityId AND canonical = 1 LIMIT 1")
    suspend fun canonicalFor(gameId: String, entityId: String): ImageEntity?
    @Query("SELECT * FROM images WHERE gameId = :gameId AND entityId = :entityId ORDER BY createdAtEpochMs DESC")
    suspend fun forEntity(gameId: String, entityId: String): List<ImageEntity>
    @Query("SELECT * FROM images WHERE gameId = :gameId") suspend fun all(gameId: String): List<ImageEntity>
}

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(usage: UsageEntity)
    @Query("SELECT * FROM usage_records WHERE (:gameId IS NULL OR gameId = :gameId) ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(gameId: String?, limit: Int): List<UsageEntity>
    @Query(
        """
        SELECT COUNT(*) AS requests,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(cachedTokens), 0) AS cachedTokens,
               COALESCE(SUM(CASE WHEN requestType = 'IMAGE' THEN 1 ELSE 0 END), 0) AS imageRequests,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures,
               COALESCE(AVG(latencyMs), 0) AS averageLatencyMs
        FROM usage_records WHERE (:gameId IS NULL OR gameId = :gameId)
        """,
    )
    suspend fun totals(gameId: String?): UsageTotalsRow

    @Query(
        """
        SELECT modelId,
               requestType,
               COUNT(*) AS requests,
               COALESCE(SUM(inputTokens), 0) AS inputTokens,
               COALESCE(SUM(outputTokens), 0) AS outputTokens,
               COALESCE(SUM(cachedTokens), 0) AS cachedTokens,
               COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures,
               COALESCE(SUM(latencyMs), 0) AS totalLatencyMs
        FROM usage_records
        WHERE (:gameId IS NULL OR gameId = :gameId)
        GROUP BY modelId, requestType
        """,
    )
    suspend fun byModel(gameId: String?): List<UsageByModelRow>
    @Query("DELETE FROM usage_records WHERE gameId = :gameId") suspend fun deleteForGame(gameId: String)
}

data class UsageTotalsRow(
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val imageRequests: Int,
    val failures: Int,
    val averageLatencyMs: Long,
)

data class UsageByModelRow(
    val modelId: String,
    val requestType: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failures: Int,
    val totalLatencyMs: Long,
)

@Dao
interface CommitmentDao {
    @Query("DELETE FROM commitments WHERE gameId = :gameId") suspend fun clearGame(gameId: String)
    @Upsert suspend fun upsertAll(commitments: Collection<CommitmentEntity>)

    @Query(
        "SELECT * FROM commitments WHERE gameId = :gameId AND status = 'OUTSTANDING' " +
            "ORDER BY createdWorldMinutes DESC LIMIT :limit",
    )
    suspend fun open(gameId: String, limit: Int): List<CommitmentEntity>

    @Query("SELECT * FROM commitments WHERE gameId = :gameId ORDER BY createdWorldMinutes ASC")
    suspend fun all(gameId: String): List<CommitmentEntity>

    @Query(
        "SELECT * FROM commitments WHERE gameId = :gameId AND (fromEntityId = :entityId OR toEntityId = :entityId) " +
            "ORDER BY createdWorldMinutes DESC LIMIT :limit",
    )
    suspend fun involving(gameId: String, entityId: String, limit: Int): List<CommitmentEntity>
}
