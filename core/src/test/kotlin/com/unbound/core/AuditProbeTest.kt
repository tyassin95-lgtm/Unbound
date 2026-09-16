package com.unbound.core

import com.unbound.core.model.SnapshotReason
import com.unbound.core.save.SaveSystem
import com.unbound.core.save.SnapshotConfig
import com.unbound.core.save.SnapshotService
import com.unbound.core.save.UndoResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Probes written during the production audit. Each targets a suspected defect. */
class AuditProbeTest {

    @Test
    fun `probe - pruning keeps the newest snapshots`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids, SnapshotConfig(everyTurns = 2, keep = 3))
        val game = w.newGame()

        repeat(20) {
            w.pipeline.execute(game.id, "I keep going.")
            val g = w.store.getGame(game.id)!!
            snapshots.shouldSnapshot(g, false)?.let { reason ->
                snapshots.capture(game.id, reason)
                val now = w.store.snapshots(game.id, 100)
                assertTrue(
                    "A restore point must exist at every moment after one is taken (turn ${g.turnNumber})",
                    now.isNotEmpty(),
                )
                assertTrue(
                    "The snapshot just taken must survive its own pruning (turn ${g.turnNumber}, kept ${now.map { s -> s.turnNumber }})",
                    now.any { s -> s.turnNumber == g.turnNumber },
                )
            }
        }

        val kept = w.store.snapshots(game.id, 100)
        val latestTurn = w.store.getGame(game.id)!!.turnNumber
        assertTrue(
            "The most recent snapshot must survive pruning, else undo jumps to the distant past. " +
                "kept=${kept.map { it.turnNumber }} latest=$latestTurn",
            kept.any { it.turnNumber >= latestTurn - 2 },
        )
        assertEquals("keep=3 must mean three are kept", 3, kept.size)
    }

    @Test
    fun `probe - undo preserves the player's usage and cost history`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids)
        val game = w.newGame()

        repeat(4) { w.pipeline.execute(game.id, "I work.") }
        val checkpoint = snapshots.capture(game.id, SnapshotReason.MANUAL)
        repeat(3) { w.pipeline.execute(game.id, "I work more.") }

        val usageBefore = w.store.usageFor(game.id, 100).size
        assertTrue("Precondition: usage was recorded", usageBefore > 0)

        snapshots.undoTo(game.id, checkpoint.turnNumber)

        assertTrue(
            "Undoing a turn must not destroy the record of what the player was charged. " +
                "before=$usageBefore after=${w.store.usageFor(game.id, 100).size}",
            w.store.usageFor(game.id, 100).isNotEmpty(),
        )
    }

    @Test
    fun `probe - undo describes what will actually be lost`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids)
        val game = w.newGame()

        repeat(3) { w.pipeline.execute(game.id, "I work.") }
        val checkpoint = snapshots.capture(game.id, SnapshotReason.MANUAL)
        val eventsAtCheckpoint = w.store.countEvents(game.id)
        repeat(2) { w.pipeline.execute(game.id, "I work more.") }
        val eventsNow = w.store.countEvents(game.id)

        val preview = snapshots.describeUndo(game.id)
        assertNotNull(preview)
        assertEquals(
            "eventsLost must be the events actually discarded, not the whole ledger",
            eventsNow - eventsAtCheckpoint,
            preview!!.eventsLost,
        )
    }

    @Test
    fun `probe - snapshots do not grow without bound as a campaign runs`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids, SnapshotConfig(everyTurns = 10, keep = 4))
        val game = w.newGame()

        repeat(60) {
            w.pipeline.execute(game.id, "I keep going.")
            val g = w.store.getGame(game.id)!!
            snapshots.shouldSnapshot(g, false)?.let { reason -> snapshots.capture(game.id, reason) }
        }

        val stored = w.store.snapshots(game.id, 100)
        val totalBytes = stored.sumOf { it.payloadJson.length.toLong() }
        assertTrue("Pruning must cap how many are retained: ${stored.size}", stored.size <= 6)
        assertTrue(
            "Snapshot storage must not balloon: ${totalBytes / 1024}KB across ${stored.size} snapshots",
            totalBytes < 2_000_000,
        )
    }

    @Test
    fun `probe - memories do not grow without bound in a long campaign`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        // Distinct, genuinely memorable events every turn: the realistic worst case.
        w.behaviour.responder = { input ->
            com.unbound.core.validate.TurnResponseDto(
                narrative = "Something happens.",
                timeAdvanceMinutes = 90,
                presentCharacterIds = listOf(maraId),
                events = listOf(
                    com.unbound.core.validate.EventDto(
                        type = "PLAYER_SPOKE_TO_NPC",
                        actorId = com.unbound.core.model.Ids.PLAYER,
                        targetId = maraId,
                        summary = "They discussed ${input.playerInput.substringAfterLast(' ')} at length.",
                        importance = "MEDIUM",
                    ),
                ),
            )
        }
        repeat(200) { i -> w.pipeline.execute(game.id, "I talk about subject$i") }

        val memories = w.store.countMemories(game.id)
        assertTrue(
            "200 turns produced $memories memories with no consolidation; retrieval and storage " +
                "both suffer if this grows linearly forever",
            memories < 400,
        )
    }

    @Test
    fun `probe - a long-neglected relationship softens on the volatile dimensions`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = { _ ->
            com.unbound.core.validate.TurnResponseDto(
                narrative = "It goes badly.",
                timeAdvanceMinutes = 10,
                presentCharacterIds = listOf(maraId),
                stateChanges = listOf(
                    com.unbound.core.validate.StateChangeDto(
                        type = "NPC_RELATIONSHIP_CHANGE", targetId = maraId,
                        changes = mapOf("fear" to 40, "trust" to -30), reason = "threatened her",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I threaten Mara.")
        val afterThreat = w.store.getRelationship(game.id, com.unbound.core.model.Ids.PLAYER, maraId)!!

        // Now a season passes with no contact at all.
        w.behaviour.responder = { com.unbound.core.validate.TurnResponseDto(narrative = "Time passes.", timeAdvanceMinutes = 60 * 24 * 10) }
        repeat(12) { w.pipeline.execute(game.id, "I stay away.") }

        val later = w.store.getRelationship(game.id, com.unbound.core.model.Ids.PLAYER, maraId)!!
        assertTrue(
            "Fear should ease over months of no contact (was ${afterThreat.vector.fear}, still ${later.vector.fear})",
            later.vector.fear < afterThreat.vector.fear,
        )
        assertTrue(
            "...but broken trust should not quietly repair itself",
            later.vector.trust <= afterThreat.vector.trust + 5,
        )
    }
}
