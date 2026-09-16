package com.unbound.rpg.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Storage design, and why it is shaped this way.
 *
 * Each table carries **typed, indexed columns for every field the engine actually queries, filters
 * or sorts on**, plus a `payload` column holding the canonical record as JSON. Nothing is queried
 * out of the JSON; the JSON only ever round-trips a row the indexes already found.
 *
 * The alternative — one column per field across eighteen record types — would add several thousand
 * lines of hand-written mapping whose only benefit would be the ability to write queries the engine
 * does not issue, and whose cost would be a migration for every field added to a game model. The
 * `WorldStore` contract defines exactly which reads exist, so the indexed set is knowable and
 * closed.
 *
 * Where a query genuinely needs to match against a *collection* — "memories involving any of these
 * entities" — there is a real join table with a composite index, not a LIKE over a packed string.
 * Those are the queries on the hot path of every turn, and they are the ones that decide whether a
 * 3,000-turn campaign still opens instantly.
 *
 * Every child table is scoped to `gameId` with a CASCADE foreign key, so deleting a save cannot
 * leave orphans behind.
 */

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val id: String,
    val title: String,
    val settingId: String,
    val settingName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val turnNumber: Int,
    val currentLocationId: String,
    val worldMinutes: Long,
    val stateVersion: Long,
    val textModelId: String,
    val payload: String,
)

@Entity(
    tableName = "worlds",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
)
data class WorldEntity(@PrimaryKey val gameId: String, val payload: String)

@Entity(
    tableName = "players",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
)
data class PlayerEntity(
    @PrimaryKey val gameId: String,
    val name: String,
    val currentLocationId: String,
    val payload: String,
)

@Entity(
    tableName = "npcs",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId"),
        Index("gameId", "currentLocationId"),
        Index("gameId", "tier"),
        Index("gameId", "name"),
    ],
)
data class NpcEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val name: String,
    val currentLocationId: String,
    val tier: String,
    val alive: Boolean,
    val payload: String,
)

@Entity(
    tableName = "locations",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("gameId", "discovered")],
)
data class LocationEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val name: String,
    val discovered: Boolean,
    val payload: String,
)

@Entity(
    tableName = "factions",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId")],
)
data class FactionEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val name: String,
    val payload: String,
)

@Entity(
    tableName = "items",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("gameId", "ownerId"), Index("gameId", "locationId")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val name: String,
    val ownerId: String?,
    val locationId: String?,
    val destroyed: Boolean,
    val payload: String,
)

@Entity(
    tableName = "threads",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId"), Index("gameId", "terminal")],
)
data class ThreadEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val title: String,
    /** True once the thread can no longer develop; lets the hot "open situations" query skip them. */
    val terminal: Boolean,
    val rank: Int,
    val payload: String,
)

@Entity(
    tableName = "relationships",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "fromEntityId"), Index("gameId", "fromEntityId", "toEntityId", unique = true)],
)
data class RelationshipEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val fromEntityId: String,
    val toEntityId: String,
    val payload: String,
)

@Entity(
    tableName = "knowledge",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId", "knowerId"),
        Index("gameId", "knowerId", "factKey", unique = true),
    ],
)
data class KnowledgeEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val knowerId: String,
    val factKey: String,
    val certainty: Int,
    val importance: Int,
    val learnedAtWorldMinutes: Long,
    val payload: String,
)

/** Join table: which entities a knowledge row is *about*. */
@Entity(
    tableName = "knowledge_subjects",
    primaryKeys = ["knowledgeId", "entityId"],
    foreignKeys = [ForeignKey(KnowledgeEntity::class, ["id"], ["knowledgeId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "entityId"), Index("knowledgeId")],
)
data class KnowledgeSubjectRef(val knowledgeId: String, val entityId: String, val gameId: String)

@Entity(
    tableName = "rumors",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "virality")],
)
data class RumorEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val factKey: String,
    val virality: Int,
    val payload: String,
)

@Entity(
    tableName = "memories",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId", "ownerId"),
        Index("gameId", "importance"),
        Index("gameId", "locationId"),
        Index("gameId", "threadId"),
        Index("gameId", "lastReinforcedWorldMinutes"),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val ownerId: String,
    val locationId: String?,
    val threadId: String?,
    val importance: Int,
    val lastReinforcedWorldMinutes: Long,
    val payload: String,
)

/** Join table: which entities a memory involves. The hot path of retrieval. */
@Entity(
    tableName = "memory_entities",
    primaryKeys = ["memoryId", "entityId"],
    foreignKeys = [ForeignKey(MemoryEntity::class, ["id"], ["memoryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "entityId"), Index("memoryId")],
)
data class MemoryEntityRef(val memoryId: String, val entityId: String, val gameId: String)

@Entity(
    tableName = "summaries",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "coversToTurn")],
)
data class SummaryEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val coversToTurn: Int,
    val payload: String,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId", "sequence"),
        Index("gameId", "importance", "worldMinutes"),
        Index("gameId", "actorId"),
        Index("gameId", "targetId"),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val sequence: Long,
    val worldMinutes: Long,
    val importance: Int,
    val actorId: String?,
    val targetId: String?,
    val payload: String,
)

@Entity(
    tableName = "event_entities",
    primaryKeys = ["eventId", "entityId"],
    foreignKeys = [ForeignKey(EventEntity::class, ["id"], ["eventId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "entityId"), Index("eventId")],
)
data class EventEntityRef(val eventId: String, val entityId: String, val gameId: String)

@Entity(
    tableName = "turns",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId", "turnNumber"),
        Index("gameId", "idempotencyKey", unique = true),
        Index("gameId", "status"),
    ],
)
data class TurnEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val turnNumber: Int,
    val status: String,
    val idempotencyKey: String,
    val payload: String,
)

@Entity(
    tableName = "snapshots",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "turnNumber")],
)
data class SnapshotEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val turnNumber: Int,
    val stateVersion: Long,
    val worldMinutes: Long,
    val createdAtEpochMs: Long,
    val reason: String,
    val payloadJson: String,
)

@Entity(
    tableName = "images",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("gameId", "entityId"), Index("gameId", "entityId", "canonical")],
)
data class ImageEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val entityId: String?,
    val canonical: Boolean,
    val createdAtEpochMs: Long,
    val payload: String,
)

/**
 * Local usage diagnostics. Never leaves the device — there is no upload path in the codebase, and
 * no network code reads this table (§70, §84).
 */
@Entity(
    tableName = "usage_records",
    indices = [Index("gameId"), Index("timestampMs")],
)
data class UsageEntity(
    @PrimaryKey val id: String,
    val gameId: String?,
    val turnId: String?,
    val timestampMs: Long,
    val requestType: String,
    val modelId: String,
    /** Defaults to openai so rows written before there was a choice price correctly. */
    val providerId: String = "openai",
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int,
    val estimatedCostUsd: Double,
    val latencyMs: Long,
    val success: Boolean,
    val retryCount: Int,
    val errorKind: String?,
)

/**
 * Promises, debts and deals.
 *
 * Indexed on the two parties and on whether it is still open, because the questions asked of this
 * table are always "what does this person owe" and "what is still outstanding" — never "show me
 * every obligation ever made".
 */
@Entity(
    tableName = "commitments",
    foreignKeys = [ForeignKey(GameEntity::class, ["id"], ["gameId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index("gameId", "status"),
        Index("gameId", "fromEntityId"),
        Index("gameId", "toEntityId"),
    ],
)
data class CommitmentEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val fromEntityId: String,
    val toEntityId: String,
    val status: String,
    val createdWorldMinutes: Long,
    val payload: String,
)
