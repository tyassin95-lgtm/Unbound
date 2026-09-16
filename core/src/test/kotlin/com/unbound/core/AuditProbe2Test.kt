package com.unbound.core

import com.unbound.core.engine.TurnOutcome
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.model.TurnStatus
import com.unbound.core.testing.CountingWorldStore
import com.unbound.core.testing.InMemoryWorldStore
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Second audit pass: concurrency, crash recovery, and the per-turn cost of a turn. */
class AuditProbe2Test {

    @Test
    fun `probe - two turns racing the same game cannot both commit`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        // A model call always suspends. Without that the two turns would simply run one after the
        // other and the probe would prove nothing.
        w.behaviour.latencyMs = 50

        val outcomes = listOf("I go left.", "I go right.").map { input ->
            async { w.pipeline.execute(game.id, input, idempotencyKey = "key-$input") }
        }.awaitAll()

        val committed = outcomes.count { it is TurnOutcome.Success }
        assertEquals("Exactly one of two racing turns may commit", 1, committed)
        assertEquals(1, w.store.getGame(game.id)!!.turnNumber)
        assertEquals(1, w.store.countTurns(game.id))
    }

    @Test
    fun `probe - a turn interrupted before commit leaves a resumable row and no half-state`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val before = w.store.getGame(game.id)!!

        w.behaviour.failWith = com.unbound.core.ai.AIErrorKind.NETWORK
        w.behaviour.failuresRemaining = 1
        w.pipeline.execute(game.id, "I open the door.", idempotencyKey = "resume-me")

        val pending = w.store.pendingTurns(game.id)
        assertEquals("The turn must survive as resumable", 1, pending.size)
        assertEquals(TurnStatus.PENDING, pending.single().status)
        assertEquals("Nothing may have been applied", before.stateVersion, w.store.getGame(game.id)!!.stateVersion)
        assertEquals(before.worldTime, w.store.getGame(game.id)!!.worldTime)

        // A fresh pipeline, as if the app had been killed and reopened.
        val revived = TurnPipeline(w.store, w.provider, w.clock, w.ids)
        val outcome = revived.execute(game.id, "I open the door.", idempotencyKey = "resume-me")

        assertTrue(outcome is TurnOutcome.Success)
        assertEquals("The resumed turn is the same turn, not a second one", 1, w.store.countTurns(game.id))
        assertTrue("No pending rows may be left behind", w.store.pendingTurns(game.id).isEmpty())
    }

    @Test
    fun `probe - the cost of a turn does not grow with the size of the campaign`() = runTest {
        val counting = CountingWorldStore(InMemoryWorldStore())
        val w = TestWorld(store = counting)
        val game = w.newGame()

        repeat(5) { w.pipeline.execute(game.id, "I look around.") }
        counting.reset()
        w.pipeline.execute(game.id, "I look around.")
        val early = counting.total

        repeat(120) { w.pipeline.execute(game.id, "I keep at it.") }
        counting.reset()
        w.pipeline.execute(game.id, "I look around.")
        val late = counting.total

        assertTrue(
            "A turn must not cost more queries as the campaign grows (early $early, late $late)\n" +
                counting.report(),
            late <= early + 4,
        )
        assertTrue(
            "A single turn issues far too many queries: $early\n" + counting.report(),
            early <= 40,
        )
    }

    @Test
    fun `probe - a rejected turn does not consume the idempotency key`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = { TurnResponseDto(narrative = "", timeAdvanceMinutes = 5) }
        val rejected = w.pipeline.execute(game.id, "I try something.", idempotencyKey = "k")
        assertTrue(rejected is TurnOutcome.Failure)

        w.behaviour.responder = { TurnResponseDto(narrative = "It works this time.", timeAdvanceMinutes = 5) }
        val retried = w.pipeline.execute(game.id, "I try something.", idempotencyKey = "k")
        assertTrue("The same key must still be usable after a rejection", retried is TurnOutcome.Success)
        assertEquals(1, w.store.countTurns(game.id))
    }

    @Test
    fun `probe - an interrupted turn never leaves a duplicate event or a skipped turn number`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        repeat(12) { i ->
            val key = "turn-$i"
            if (i % 3 == 0) {
                w.behaviour.failWith = com.unbound.core.ai.AIErrorKind.TIMEOUT
                w.behaviour.failuresRemaining = 1
                w.pipeline.execute(game.id, "I act $i.", key)
            }
            w.behaviour.failWith = null
            w.pipeline.execute(game.id, "I act $i.", key)
        }

        val events = w.store.eventsPage(game.id, 0, 10_000)
        assertEquals("No duplicate event ids", events.size, events.map { it.id }.distinct().size)
        assertEquals("No duplicate sequence numbers", events.size, events.map { it.sequence }.distinct().size)

        val turns = w.store.turnsPage(game.id, 0, 100).sortedBy { it.turnNumber }
        assertEquals(12, turns.size)
        assertEquals("Turn numbers must be a gapless run", (1..12).toList(), turns.map { it.turnNumber })
        assertEquals(12, w.store.getGame(game.id)!!.turnNumber)
    }
}
