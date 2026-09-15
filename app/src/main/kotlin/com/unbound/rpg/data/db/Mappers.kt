package com.unbound.rpg.data.db

import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord
import kotlinx.serialization.json.Json

/**
 * Converts between canonical game records and their storage rows.
 *
 * Indexed columns are projections of the payload, never a second source of truth: the payload is
 * always written from the same object the columns were derived from, in one statement.
 */
internal object Mappers {

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        // Tolerates a payload written by an older build that has since gained fields.
        isLenient = true
    }

    // --- game -----------------------------------------------------------------------------------
    fun toEntity(g: GameRecord) = GameEntity(
        id = g.id, title = g.title, settingId = g.settingId, settingName = g.settingName,
        createdAtEpochMs = g.createdAtEpochMs, updatedAtEpochMs = g.updatedAtEpochMs,
        turnNumber = g.turnNumber, currentLocationId = g.currentLocationId,
        worldMinutes = g.worldTime.totalMinutes, stateVersion = g.stateVersion,
        textModelId = g.textModelId,
        payload = json.encodeToString(GameRecord.serializer(), g),
    )

    /**
     * `stateVersion` is read back from the *column*, not the payload, because `bumpVersion` updates
     * the column directly as the atomic commit point. Trusting the payload here would reintroduce
     * the lost-update the lock exists to prevent.
     */
    fun toGame(e: GameEntity): GameRecord =
        json.decodeFromString(GameRecord.serializer(), e.payload).copy(stateVersion = e.stateVersion)

    // --- world / player --------------------------------------------------------------------------
    fun toEntity(w: WorldRecord) = WorldEntity(w.gameId, json.encodeToString(WorldRecord.serializer(), w))
    fun toWorld(e: WorldEntity): WorldRecord = json.decodeFromString(WorldRecord.serializer(), e.payload)

    fun toEntity(p: PlayerRecord) = PlayerEntity(p.gameId, p.name, p.currentLocationId, json.encodeToString(PlayerRecord.serializer(), p))
    fun toPlayer(e: PlayerEntity): PlayerRecord = json.decodeFromString(PlayerRecord.serializer(), e.payload)

    // --- npc --------------------------------------------------------------------------------------
    fun toEntity(n: NpcRecord) = NpcEntity(
        id = n.id, gameId = n.gameId, name = n.name, currentLocationId = n.currentLocationId,
        tier = n.tier.name, alive = n.alive, payload = json.encodeToString(NpcRecord.serializer(), n),
    )
    fun toNpc(e: NpcEntity): NpcRecord = json.decodeFromString(NpcRecord.serializer(), e.payload)

    // --- location ------------------------------------------------------------------------------------
    fun toEntity(l: LocationRecord) = LocationEntity(l.id, l.gameId, l.name, l.discovered, json.encodeToString(LocationRecord.serializer(), l))
    fun toLocation(e: LocationEntity): LocationRecord = json.decodeFromString(LocationRecord.serializer(), e.payload)

    // --- faction ---------------------------------------------------------------------------------------
    fun toEntity(f: FactionRecord) = FactionEntity(f.id, f.gameId, f.name, json.encodeToString(FactionRecord.serializer(), f))
    fun toFaction(e: FactionEntity): FactionRecord = json.decodeFromString(FactionRecord.serializer(), e.payload)

    // --- item -------------------------------------------------------------------------------------------
    fun toEntity(i: ItemRecord) = ItemEntity(
        id = i.id, gameId = i.gameId, name = i.name, ownerId = i.ownerId, locationId = i.locationId,
        destroyed = i.condition == ItemCondition.DESTROYED, payload = json.encodeToString(ItemRecord.serializer(), i),
    )
    fun toItem(e: ItemEntity): ItemRecord = json.decodeFromString(ItemRecord.serializer(), e.payload)

    // --- thread ------------------------------------------------------------------------------------------
    private val TERMINAL = setOf(ThreadStatus.RESOLVED, ThreadStatus.FAILED, ThreadStatus.ABANDONED)
    fun toEntity(t: ThreadRecord) = ThreadEntity(
        id = t.id, gameId = t.gameId, title = t.title, terminal = t.status in TERMINAL,
        rank = (t.importance.weight * 100).toInt() + t.momentum,
        payload = json.encodeToString(ThreadRecord.serializer(), t),
    )
    fun toThread(e: ThreadEntity): ThreadRecord = json.decodeFromString(ThreadRecord.serializer(), e.payload)

    // --- relationship ------------------------------------------------------------------------------------
    fun toEntity(r: RelationshipRecord) = RelationshipEntity(
        r.id, r.gameId, r.fromEntityId, r.toEntityId, json.encodeToString(RelationshipRecord.serializer(), r),
    )
    fun toRelationship(e: RelationshipEntity): RelationshipRecord = json.decodeFromString(RelationshipRecord.serializer(), e.payload)

    // --- knowledge ----------------------------------------------------------------------------------------
    fun toEntity(k: KnowledgeRecord) = KnowledgeEntity(
        id = k.id, gameId = k.gameId, knowerId = k.knowerId, factKey = k.factKey,
        certainty = k.certainty.ordinal, importance = k.importance.ordinal,
        learnedAtWorldMinutes = k.learnedAtWorldMinutes,
        payload = json.encodeToString(KnowledgeRecord.serializer(), k),
    )
    fun toKnowledge(e: KnowledgeEntity): KnowledgeRecord = json.decodeFromString(KnowledgeRecord.serializer(), e.payload)
    fun subjectRefs(k: KnowledgeRecord) = k.subjectEntityIds.distinct().map { KnowledgeSubjectRef(k.id, it, k.gameId) }

    fun toEntity(r: RumorRecord) = RumorEntity(r.id, r.gameId, r.factKey, r.virality, json.encodeToString(RumorRecord.serializer(), r))
    fun toRumor(e: RumorEntity): RumorRecord = json.decodeFromString(RumorRecord.serializer(), e.payload)

    // --- memory -------------------------------------------------------------------------------------------
    fun toEntity(m: MemoryRecord) = MemoryEntity(
        id = m.id, gameId = m.gameId, ownerId = m.ownerId, locationId = m.locationId, threadId = m.threadId,
        importance = m.importance.ordinal, lastReinforcedWorldMinutes = m.lastReinforcedWorldMinutes,
        payload = json.encodeToString(MemoryRecord.serializer(), m),
    )
    fun toMemory(e: MemoryEntity): MemoryRecord = json.decodeFromString(MemoryRecord.serializer(), e.payload)
    fun memoryRefs(m: MemoryRecord) = m.entityIds.distinct().map { MemoryEntityRef(m.id, it, m.gameId) }

    fun toEntity(s: SummaryRecord) = SummaryEntity(s.id, s.gameId, s.coversToTurn, json.encodeToString(SummaryRecord.serializer(), s))
    fun toSummary(e: SummaryEntity): SummaryRecord = json.decodeFromString(SummaryRecord.serializer(), e.payload)

    // --- events --------------------------------------------------------------------------------------------
    fun toEntity(e: GameEvent) = EventEntity(
        id = e.id, gameId = e.gameId, sequence = e.sequence, worldMinutes = e.worldMinutes,
        importance = e.importance.ordinal, actorId = e.actorId, targetId = e.targetId,
        payload = json.encodeToString(GameEvent.serializer(), e),
    )
    fun toEvent(e: EventEntity): GameEvent = json.decodeFromString(GameEvent.serializer(), e.payload)
    fun eventRefs(e: GameEvent) = (e.relatedEntityIds + e.witnessIds).distinct().map { EventEntityRef(e.id, it, e.gameId) }

    // --- turns / snapshots / images -----------------------------------------------------------------------------
    fun toEntity(t: TurnRecord) = TurnEntity(
        t.id, t.gameId, t.turnNumber, t.status.name, t.idempotencyKey, json.encodeToString(TurnRecord.serializer(), t),
    )
    fun toTurn(e: TurnEntity): TurnRecord = json.decodeFromString(TurnRecord.serializer(), e.payload)

    fun toEntity(s: SnapshotRecord) = SnapshotEntity(
        s.id, s.gameId, s.turnNumber, s.stateVersion, s.worldMinutes, s.createdAtEpochMs, s.reason.name, s.payloadJson,
    )
    fun toSnapshot(e: SnapshotEntity) = SnapshotRecord(
        id = e.id, gameId = e.gameId, turnNumber = e.turnNumber, stateVersion = e.stateVersion,
        worldMinutes = e.worldMinutes, createdAtEpochMs = e.createdAtEpochMs,
        reason = SnapshotReason.valueOf(e.reason), payloadJson = e.payloadJson,
    )

    fun toEntity(i: ImageRecord) = ImageEntity(
        i.id, i.gameId, i.entityId, i.canonical, i.createdAtEpochMs, json.encodeToString(ImageRecord.serializer(), i),
    )
    fun toImage(e: ImageEntity): ImageRecord = json.decodeFromString(ImageRecord.serializer(), e.payload)

    fun toEntity(u: UsageRecord) = UsageEntity(
        id = u.id, gameId = u.gameId, turnId = u.turnId, timestampMs = u.timestampMs,
        requestType = u.requestType.name, modelId = u.modelId, inputTokens = u.inputTokens,
        outputTokens = u.outputTokens, cachedTokens = u.cachedTokens, estimatedCostUsd = u.estimatedCostUsd,
        latencyMs = u.latencyMs, success = u.success, retryCount = u.retryCount, errorKind = u.errorKind,
    )
    fun toUsage(e: UsageEntity) = UsageRecord(
        id = e.id, gameId = e.gameId, turnId = e.turnId, timestampMs = e.timestampMs,
        requestType = RequestType.valueOf(e.requestType), modelId = e.modelId,
        inputTokens = e.inputTokens, outputTokens = e.outputTokens, cachedTokens = e.cachedTokens,
        estimatedCostUsd = e.estimatedCostUsd, latencyMs = e.latencyMs, success = e.success,
        retryCount = e.retryCount, errorKind = e.errorKind,
    )
}
