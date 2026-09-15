package com.unbound.core.model

import kotlinx.serialization.Serializable

enum class TurnStatus {
    /** Player input persisted; AI not yet called. Safe to retry. */
    PENDING,

    /** AI called, response not yet committed. Safe to retry with the same idempotency key. */
    AWAITING_COMMIT,

    /** Fully committed. */
    COMPLETE,

    /** Failed and rolled back. Nothing was applied. */
    FAILED,
}

/**
 * One exchange. The row is written *before* the network call so that a crash mid-turn leaves a
 * resumable record rather than a lost turn (§96), and [idempotencyKey] makes the retry safe (§45).
 */
@Serializable
data class TurnRecord(
    val id: String,
    val gameId: String,
    val turnNumber: Int,
    val playerInput: String,
    val narrative: String = "",
    val status: TurnStatus = TurnStatus.PENDING,
    val idempotencyKey: String,
    /** The game's stateVersion this turn was built against. */
    val baseStateVersion: Long,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    val worldMinutesBefore: Long,
    val worldMinutesAfter: Long? = null,
    val modelId: String? = null,
    val suggestedActions: List<String> = emptyList(),
    /** Lines the protagonist spoke this turn, so the narrative can still be coloured after reload. */
    val playerDialogue: List<String> = emptyList(),
    val errorMessage: String? = null,
    val imageId: String? = null,
)

@Serializable
data class SnapshotRecord(
    val id: String,
    val gameId: String,
    val turnNumber: Int,
    val stateVersion: Long,
    val worldMinutes: Long,
    val createdAtEpochMs: Long,
    val reason: SnapshotReason,
    /** Full serialized canonical state. Large but rare; the ledger remains authoritative. */
    val payloadJson: String,
)

enum class SnapshotReason { INTERVAL, BEFORE_IRREVERSIBLE, AFTER_MAJOR_EVENT, MANUAL, GAME_START }

@Serializable
data class ImageRecord(
    val id: String,
    val gameId: String,
    val entityId: String?,
    val kind: ImageKind,
    val localPath: String?,
    val remoteUrl: String? = null,
    val prompt: String,
    val modelId: String,
    val createdAtEpochMs: Long,
    /** True for the reference image that defines this entity's visual identity. */
    val canonical: Boolean = false,
    /** Appearance version this image depicts, so a haircut does not invalidate older images. */
    val appearanceVersion: Int = 1,
    val visualTraits: List<String> = emptyList(),
    val turnId: String? = null,
)

enum class ImageKind { CHARACTER, NPC, LOCATION, ITEM, SCENE, EVENT }

@Serializable
data class UsageRecord(
    val id: String,
    val gameId: String?,
    val turnId: String?,
    val timestampMs: Long,
    val requestType: RequestType,
    val modelId: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val latencyMs: Long = 0,
    val success: Boolean = true,
    val retryCount: Int = 0,
    val errorKind: String? = null,
)

enum class RequestType { NARRATIVE_TURN, WORLD_GENERATION, MEMORY_EXTRACTION, SUMMARIZATION, INTENT_PARSE, IMAGE, MODEL_LIST, CONNECTION_TEST }
