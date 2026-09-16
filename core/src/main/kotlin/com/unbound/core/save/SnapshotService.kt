package com.unbound.core.save

import com.unbound.core.engine.WorldStore
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.memory.MemoryRecord
import com.unbound.core.memory.SummaryRecord
import com.unbound.core.model.*
import com.unbound.core.relationship.RelationshipRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Restore points, and undo (§49, §50).
 *
 * A snapshot holds **only mutable canonical state** — not the event ledger, not the turn history,
 * not images or usage. Those are either append-only or belong to the device rather than to the
 * world, so a rollback *truncates* them rather than restoring them from a copy.
 *
 * That distinction is the point of this class, and getting it wrong cost real data. An earlier
 * version serialised the entire save bundle, ledger included, into every snapshot every fifteen
 * turns, and then rolled back by deleting the whole game and rebuilding it — which also destroyed
 * the player's usage and cost history, records that are not in a save bundle and could never come
 * back.
 */
class SnapshotService(
    private val store: WorldStore,
    private val clock: () -> Long,
    private val idFactory: () -> String,
    private val config: SnapshotConfig = SnapshotConfig(),
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun shouldSnapshot(game: GameRecord, sceneWasSignificant: Boolean): SnapshotReason? = when {
        game.turnNumber == 0 -> SnapshotReason.GAME_START
        sceneWasSignificant -> SnapshotReason.AFTER_MAJOR_EVENT
        game.turnNumber % config.everyTurns == 0 -> SnapshotReason.INTERVAL
        else -> null
    }

    suspend fun capture(gameId: String, reason: SnapshotReason): SnapshotRecord {
        val game = store.getGame(gameId) ?: error("No such game")
        val state = StateSnapshot(
            // The ledger position this state corresponds to. Undo deletes everything after it,
            // which is both exact and far cheaper than copying the ledger into every snapshot.
            eventSequence = store.nextEventSequence(gameId) - 1,
            turnNumber = game.turnNumber,
            game = game,
            world = store.getWorld(gameId)!!,
            player = store.getPlayer(gameId)!!,
            npcs = store.allNpcs(gameId),
            locations = store.allLocations(gameId),
            factions = store.factions(gameId),
            items = store.allItems(gameId),
            threads = store.allThreads(gameId, MAX_ROWS),
            relationships = store.allRelationships(gameId),
            knowledge = store.allKnowledge(gameId),
            rumors = store.allRumors(gameId),
            memories = store.allMemories(gameId),
            summaries = store.summaries(gameId, MAX_ROWS),
            commitments = store.allCommitments(gameId),
        )

        val snapshot = SnapshotRecord(
            id = Ids.snapshot(idFactory()),
            gameId = gameId,
            turnNumber = game.turnNumber,
            stateVersion = game.stateVersion,
            worldMinutes = game.worldTime.totalMinutes,
            createdAtEpochMs = clock(),
            reason = reason,
            payloadJson = json.encodeToString(StateSnapshot.serializer(), state),
        )
        store.upsertSnapshot(snapshot)
        prune(gameId)
        return snapshot
    }

    /**
     * Keeps the newest [SnapshotConfig.keep] restore points, plus the one taken at the start of the
     * game, and deletes the rest **by id**.
     *
     * Pruning by turn range, as this once did, deleted every snapshot *newer* than each old one in
     * turn — so every few captures the entire set was wiped, including the snapshot just taken, and
     * undo silently stopped working until enough new ones accumulated.
     */
    private suspend fun prune(gameId: String) {
        val all = store.snapshots(gameId, MAX_ROWS)
        if (all.size <= config.keep) return

        val keep = all.sortedByDescending { it.turnNumber }.take(config.keep).map { it.id }.toMutableSet()
        all.firstOrNull { it.reason == SnapshotReason.GAME_START }?.let { keep += it.id }

        val doomed = all.map { it.id }.filterNot { it in keep }
        if (doomed.isNotEmpty()) store.deleteSnapshots(doomed)
    }

    /** What an undo would actually cost, so the UI can warn before doing something destructive. */
    suspend fun describeUndo(gameId: String): UndoPreview? {
        val game = store.getGame(gameId) ?: return null
        if (game.turnNumber <= 0) return null
        val snapshot = store.latestSnapshotAtOrBefore(gameId, game.turnNumber - 1) ?: return null
        val state = decode(snapshot) ?: return null

        return UndoPreview(
            fromTurn = game.turnNumber,
            toTurn = snapshot.turnNumber,
            turnsLost = game.turnNumber - snapshot.turnNumber,
            // The events actually discarded, not the size of the whole ledger.
            eventsLost = ((store.nextEventSequence(gameId) - 1) - state.eventSequence)
                .coerceAtLeast(0).toInt(),
        )
    }

    /**
     * Rolls the world back to [targetTurn].
     *
     * Canonical state is replaced wholesale from the snapshot, and the ledger and turn history are
     * truncated to the point the snapshot was taken. Usage records and generated images survive:
     * the player was really charged for those requests and the files really are on the device, so
     * pretending otherwise would be a lie rather than a rollback.
     */
    suspend fun undoTo(gameId: String, targetTurn: Int): UndoResult = store.transaction {
        val game = store.getGame(gameId) ?: return@transaction UndoResult.Failed("No such game.")
        if (targetTurn >= game.turnNumber) return@transaction UndoResult.Failed("Nothing to undo.")

        val snapshot = store.latestSnapshotAtOrBefore(gameId, targetTurn)
            ?: return@transaction UndoResult.Failed(
                "No restore point exists at or before turn $targetTurn, so the world cannot be " +
                    "restored exactly. UNBOUND will not guess at a partial rollback.",
            )
        val state = decode(snapshot)
            ?: return@transaction UndoResult.Failed("That restore point could not be read and will not be used.")

        store.clearGameEntities(gameId)
        store.deleteEventsAfterSequence(gameId, state.eventSequence)
        store.deleteTurnsAfter(gameId, snapshot.turnNumber)
        store.deleteSnapshotsAfter(gameId, snapshot.turnNumber)

        store.upsertWorld(state.world)
        store.upsertPlayer(state.player)
        store.upsertNpcs(state.npcs)
        store.upsertLocations(state.locations)
        store.upsertFactions(state.factions)
        store.upsertItems(state.items)
        store.upsertThreads(state.threads)
        store.upsertRelationships(state.relationships)
        store.upsertKnowledge(state.knowledge)
        store.upsertRumors(state.rumors)
        store.upsertMemories(state.memories)
        store.upsertSummaries(state.summaries)
        store.upsertCommitments(state.commitments)
        store.upsertGame(state.game.copy(stateVersion = game.stateVersion + 1, updatedAtEpochMs = clock()))

        UndoResult.Restored(snapshot.turnNumber, game.turnNumber - snapshot.turnNumber)
    }

    private fun decode(snapshot: SnapshotRecord): StateSnapshot? =
        runCatching { json.decodeFromString(StateSnapshot.serializer(), snapshot.payloadJson) }.getOrNull()

    private companion object {
        const val MAX_ROWS = 20_000
    }
}

/**
 * The mutable half of a campaign: everything a turn can change.
 *
 * Deliberately excludes events, turns, images and usage. Those are append-only or device-local,
 * and copying them into every restore point made snapshots grow with the campaign.
 */
@Serializable
data class StateSnapshot(
    val eventSequence: Long,
    val turnNumber: Int,
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
    /** Defaulted so a restore point written before obligations existed still decodes. */
    val commitments: List<com.unbound.core.continuity.CommitmentRecord> = emptyList(),
)

data class SnapshotConfig(val everyTurns: Int = 15, val keep: Int = 8)

data class UndoPreview(val fromTurn: Int, val toTurn: Int, val turnsLost: Int, val eventsLost: Int)

sealed interface UndoResult {
    data class Restored(val turnNumber: Int, val turnsUndone: Int) : UndoResult
    data class Failed(val reason: String) : UndoResult
}
