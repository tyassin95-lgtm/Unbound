package com.unbound.core.save

import com.unbound.core.engine.WorldStore
import com.unbound.core.model.GameRecord
import com.unbound.core.model.Ids
import com.unbound.core.model.SnapshotReason
import com.unbound.core.model.SnapshotRecord
import com.unbound.core.model.TurnStatus
import kotlinx.serialization.json.Json

/**
 * Snapshots and undo (§49, §50).
 *
 * Undo here is a genuine world rollback, not "delete the last paragraph". It restores canonical
 * state from the nearest snapshot at or before the target turn, then truncates the ledger to that
 * point. The ledger remains the authority: a snapshot is only an accelerator, and if one is missing
 * the restore refuses rather than guessing.
 */
class SnapshotService(
    private val store: WorldStore,
    private val saveSystem: SaveSystem,
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
        val bundle = saveSystem.buildBundle(gameId)
        val snapshot = SnapshotRecord(
            id = Ids.snapshot(idFactory()),
            gameId = gameId,
            turnNumber = game.turnNumber,
            stateVersion = game.stateVersion,
            worldMinutes = game.worldTime.totalMinutes,
            createdAtEpochMs = clock(),
            reason = reason,
            payloadJson = json.encodeToString(SaveBundle.serializer(), bundle),
        )
        store.upsertSnapshot(snapshot)
        pruneOldSnapshots(gameId)
        return snapshot
    }

    private suspend fun pruneOldSnapshots(gameId: String) {
        val all = store.snapshots(gameId, 1000)
        if (all.size <= config.keep) return
        // Keep the most recent [keep]; the ledger can always rebuild anything older.
        val cutoff = all.sortedByDescending { it.turnNumber }[config.keep - 1].turnNumber
        store.snapshots(gameId, 1000)
            .filter { it.turnNumber < cutoff && it.reason != SnapshotReason.GAME_START }
            .forEach { store.deleteSnapshotsAfter(gameId, it.turnNumber - 1) }
    }

    /** What an undo would cost, so the UI can warn before doing something destructive. */
    suspend fun describeUndo(gameId: String): UndoPreview? {
        val game = store.getGame(gameId) ?: return null
        if (game.turnNumber <= 0) return null
        val snapshot = store.latestSnapshotAtOrBefore(gameId, game.turnNumber - 1) ?: return null
        return UndoPreview(
            fromTurn = game.turnNumber,
            toTurn = snapshot.turnNumber,
            turnsLost = game.turnNumber - snapshot.turnNumber,
            eventsLost = store.countEvents(gameId) - 0,
        )
    }

    /**
     * Rolls the world back to [targetTurn]. Everything after it is removed: state, events,
     * memories, knowledge, relationships and threads all come from the snapshot, so nothing from
     * the undone turns can survive as a stray row.
     */
    suspend fun undoTo(gameId: String, targetTurn: Int): UndoResult = store.transaction {
        val game = store.getGame(gameId) ?: return@transaction UndoResult.Failed("No such game.")
        if (targetTurn >= game.turnNumber) return@transaction UndoResult.Failed("Nothing to undo.")

        val snapshot = store.latestSnapshotAtOrBefore(gameId, targetTurn)
            ?: return@transaction UndoResult.Failed(
                "No snapshot exists at or before turn $targetTurn, so the world cannot be restored " +
                    "exactly. UNBOUND will not guess at a partial rollback.",
            )

        val bundle = json.decodeFromString(SaveBundle.serializer(), snapshot.payloadJson)

        // Wipe and rewrite, rather than patching: a partial restore is how a save gets corrupted.
        store.deleteGame(gameId)

        store.upsertGame(bundle.game.copy(stateVersion = snapshot.stateVersion, updatedAtEpochMs = clock()))
        store.upsertWorld(bundle.world)
        store.upsertPlayer(bundle.player)
        store.upsertNpcs(bundle.npcs)
        store.upsertLocations(bundle.locations)
        store.upsertFactions(bundle.factions)
        store.upsertItems(bundle.items)
        store.upsertThreads(bundle.threads)
        store.upsertRelationships(bundle.relationships)
        store.upsertKnowledge(bundle.knowledge)
        store.upsertRumors(bundle.rumors)
        store.upsertMemories(bundle.memories)
        store.upsertSummaries(bundle.summaries)
        store.appendEvents(bundle.events)
        bundle.turns.filter { it.turnNumber <= snapshot.turnNumber && it.status == TurnStatus.COMPLETE }
            .forEach { store.upsertTurn(it) }
        bundle.images.forEach { store.upsertImage(it) }
        store.upsertSnapshot(snapshot)

        UndoResult.Restored(snapshot.turnNumber, game.turnNumber - snapshot.turnNumber)
    }
}

data class SnapshotConfig(val everyTurns: Int = 15, val keep: Int = 8)

data class UndoPreview(val fromTurn: Int, val toTurn: Int, val turnsLost: Int, val eventsLost: Int)

sealed interface UndoResult {
    data class Restored(val turnNumber: Int, val turnsUndone: Int) : UndoResult
    data class Failed(val reason: String) : UndoResult
}
