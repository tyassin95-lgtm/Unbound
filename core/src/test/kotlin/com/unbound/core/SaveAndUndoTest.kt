package com.unbound.core

import com.unbound.core.model.Ids
import com.unbound.core.model.SnapshotReason
import com.unbound.core.save.SaveSystem
import com.unbound.core.save.SnapshotService
import com.unbound.core.save.UndoResult
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveAndUndoTest {

    @Test
    fun `an exported save round-trips without losing the world`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val game = w.newGame()
        repeat(6) { w.pipeline.execute(game.id, "I look into things.") }

        val json = save.export(game.id)
        val imported = save.import(json)

        assertTrue("An import must not overwrite the original", imported.id != game.id)
        assertEquals(w.store.countEvents(game.id), w.store.countEvents(imported.id))
        assertEquals(w.store.getPlayer(game.id)!!.name, w.store.getPlayer(imported.id)!!.name)
        assertEquals(w.store.getPlayer(game.id)!!.currency, w.store.getPlayer(imported.id)!!.currency)
        assertEquals(w.store.allNpcs(game.id).size, w.store.allNpcs(imported.id).size)
        assertEquals(w.store.allKnowledge(game.id).size, w.store.allKnowledge(imported.id).size)
        assertEquals(w.store.allMemories(game.id).size, w.store.allMemories(imported.id).size)
        assertEquals(w.store.countTurns(game.id), w.store.countTurns(imported.id))
        assertTrue("The imported campaign must not share entity ids with the original", 
            w.store.allNpcs(game.id).map { it.id }.intersect(w.store.allNpcs(imported.id).map { it.id }.toSet()).isEmpty())
        assertEquals(w.store.getGame(game.id)!!.turnNumber, imported.turnNumber)
    }

    @Test
    fun `an exported save contains nothing that looks like a credential`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val game = w.newGame()
        repeat(3) { w.pipeline.execute(game.id, "I ask around.") }

        val json = save.export(game.id)
        val forbidden = listOf("sk-", "api_key", "apiKey", "Authorization", "Bearer ", "openai_key")
        for (needle in forbidden) {
            assertFalse("A save must never contain '$needle'", json.contains(needle, ignoreCase = true))
        }
    }

    @Test
    fun `undo restores the world, not just the last paragraph`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids)
        val game = w.newGame()

        repeat(4) { w.pipeline.execute(game.id, "I work quietly.") }
        val checkpoint = snapshots.capture(game.id, SnapshotReason.MANUAL)
        val currencyAtCheckpoint = w.store.getPlayer(game.id)!!.currency
        val eventsAtCheckpoint = w.store.countEvents(game.id)

        // Now do something expensive and irreversible-feeling.
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You hand over everything you have.",
                timeAdvanceMinutes = 10,
                stateChanges = listOf(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -40, reason = "paid off")),
            )
        }
        w.pipeline.execute(game.id, "I pay them everything.")
        w.behaviour.responder = ::defaultResponderShim
        repeat(2) { w.pipeline.execute(game.id, "I walk away.") }

        assertEquals(7, w.store.getGame(game.id)!!.turnNumber)
        assertEquals(currencyAtCheckpoint - 40, w.store.getPlayer(game.id)!!.currency)

        val result = snapshots.undoTo(game.id, checkpoint.turnNumber)
        assertTrue(result is UndoResult.Restored)

        assertEquals("Turn number must roll back", checkpoint.turnNumber, w.store.getGame(game.id)!!.turnNumber)
        assertEquals("Money must come back", currencyAtCheckpoint, w.store.getPlayer(game.id)!!.currency)
        assertEquals("The ledger must be truncated, not merely appended to", eventsAtCheckpoint, w.store.countEvents(game.id))
        assertEquals(checkpoint.turnNumber, w.store.countTurns(game.id))

        // And play resumes cleanly from the restored point.
        val outcome = w.pipeline.execute(game.id, "I try again.")
        assertTrue(outcome is com.unbound.core.engine.TurnOutcome.Success)
        assertEquals(checkpoint.turnNumber + 1, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `undo refuses rather than guessing when no snapshot covers the target`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val snapshots = SnapshotService(w.store, save, w.clock, w.ids)
        val game = w.newGame()
        repeat(3) { w.pipeline.execute(game.id, "I keep going.") }

        val result = snapshots.undoTo(game.id, 1)
        assertTrue(result is UndoResult.Failed)
        assertEquals("The world must be untouched by a refused undo", 3, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `duplicating a save produces an independent campaign`() = runTest {
        val w = TestWorld()
        val save = SaveSystem(w.store, w.clock, w.ids)
        val game = w.newGame()
        repeat(3) { w.pipeline.execute(game.id, "I settle in.") }

        val copy = save.duplicate(game.id, "Branch")
        assertEquals("Branch", copy.title)

        // Diverge the copy; the original must not move.
        w.pipeline.execute(copy.id, "I do something different.")
        assertEquals(4, w.store.getGame(copy.id)!!.turnNumber)
        assertEquals(3, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `deleting a game removes all of its rows and leaves others alone`() = runTest {
        val w = TestWorld()
        val a = w.newGame()
        val b = w.newGame()
        repeat(3) { w.pipeline.execute(a.id, "I look around.") }
        repeat(2) { w.pipeline.execute(b.id, "I look around.") }

        w.store.deleteGame(a.id)

        assertEquals(0, w.store.countEvents(a.id))
        assertEquals(0, w.store.allNpcs(a.id).size)
        assertEquals(0, w.store.allMemories(a.id).size)
        assertTrue(w.store.countEvents(b.id) > 0)
        assertEquals(2, w.store.getGame(b.id)!!.turnNumber)
    }
}

private fun defaultResponderShim(input: MockShimInput): TurnResponseDto =
    TurnResponseDto(narrative = "You ${input.playerInput}.", timeAdvanceMinutes = 10)

private typealias MockShimInput = com.unbound.core.testing.MockTurnInput
