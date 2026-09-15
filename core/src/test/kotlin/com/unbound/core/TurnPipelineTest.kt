package com.unbound.core

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.model.Ids
import com.unbound.core.model.TurnStatus
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPipelineTest {

    @Test
    fun `a turn advances time, records events and increments the state version`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        val outcome = w.pipeline.execute(game.id, "I order a drink and listen.")
        assertTrue(outcome is TurnOutcome.Success)
        outcome as TurnOutcome.Success

        assertEquals(1, outcome.game.turnNumber)
        assertEquals(game.stateVersion + 1, outcome.game.stateVersion)
        assertTrue(outcome.game.worldTime > game.worldTime)
        assertTrue(outcome.narrative.isNotBlank())
        assertEquals(TurnStatus.COMPLETE, outcome.turn.status)
        assertTrue("A turn must leave a trace in the ledger", w.store.countEvents(game.id) > 1)
    }

    @Test
    fun `retrying with the same idempotency key does not apply the turn twice`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        val first = w.pipeline.execute(game.id, "I pay the barkeep five crowns.", idempotencyKey = "key-1")
        assertTrue(first is TurnOutcome.Success)
        val eventsAfterFirst = w.store.countEvents(game.id)
        val versionAfterFirst = w.store.getGame(game.id)!!.stateVersion

        val second = w.pipeline.execute(game.id, "I pay the barkeep five crowns.", idempotencyKey = "key-1")
        assertTrue("The second call must be recognised as a replay", second is TurnOutcome.Replayed)
        assertEquals(eventsAfterFirst, w.store.countEvents(game.id))
        assertEquals(versionAfterFirst, w.store.getGame(game.id)!!.stateVersion)
        assertEquals(1, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `currency is never double-charged across a failed then retried turn`() = runTest {
        val w = TestWorld()
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You slide five crowns across the bar.",
                timeAdvanceMinutes = 5,
                stateChanges = listOf(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -5, reason = "bought information")),
            )
        }
        val game = w.newGame()
        val startingCurrency = w.store.getPlayer(game.id)!!.currency

        // First attempt dies in the network.
        w.behaviour.failWith = AIErrorKind.TIMEOUT
        w.behaviour.failuresRemaining = 1
        val failed = w.pipeline.execute(game.id, "I bribe the barkeep.", idempotencyKey = "bribe-1")
        assertTrue(failed is TurnOutcome.Failure)
        assertEquals("Nothing may be charged for a turn that did not happen", startingCurrency, w.store.getPlayer(game.id)!!.currency)

        // Retry under the same key succeeds, and charges exactly once.
        val retried = w.pipeline.execute(game.id, "I bribe the barkeep.", idempotencyKey = "bribe-1")
        assertTrue(retried is TurnOutcome.Failure || retried is TurnOutcome.Success)
        assertEquals(startingCurrency - 5, w.store.getPlayer(game.id)!!.currency)

        // And a third call under the same key changes nothing further.
        w.pipeline.execute(game.id, "I bribe the barkeep.", idempotencyKey = "bribe-1")
        assertEquals(startingCurrency - 5, w.store.getPlayer(game.id)!!.currency)
    }

    @Test
    fun `a malformed model response leaves the world untouched and stays retryable`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val before = w.store.getGame(game.id)!!

        w.behaviour.returnMalformedJson = true
        val outcome = w.pipeline.execute(game.id, "I look around.", idempotencyKey = "k")
        assertTrue(outcome is TurnOutcome.Failure)
        outcome as TurnOutcome.Failure
        assertEquals(AIErrorKind.MALFORMED_RESPONSE, outcome.kind)
        assertTrue(outcome.retryable)

        val after = w.store.getGame(game.id)!!
        assertEquals(before.stateVersion, after.stateVersion)
        assertEquals(before.turnNumber, after.turnNumber)
        assertEquals(before.worldTime, after.worldTime)

        // The pending row survives so the turn can be resumed rather than silently skipped.
        assertTrue(w.store.pendingTurns(game.id).isNotEmpty())

        w.behaviour.returnMalformedJson = false
        val retried = w.pipeline.execute(game.id, "I look around.", idempotencyKey = "k")
        assertTrue(retried is TurnOutcome.Success)
        assertEquals(1, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `a response that contradicts canonical state is rejected without committing`() = runTest {
        val w = TestWorld()
        w.behaviour.responder = { _ ->
            TurnResponseDto(narrative = "", timeAdvanceMinutes = 5) // empty narrative is fatal
        }
        val game = w.newGame()
        val before = w.store.getGame(game.id)!!

        val outcome = w.pipeline.execute(game.id, "I do something.")
        assertTrue(outcome is TurnOutcome.Failure)
        assertEquals(before.stateVersion, w.store.getGame(game.id)!!.stateVersion)
    }

    @Test
    fun `an impossible spend is dropped but the rest of the turn still commits`() = runTest {
        val w = TestWorld()
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You count out coins you do not have.",
                timeAdvanceMinutes = 10,
                stateChanges = listOf(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -5000, reason = "showing off")),
            )
        }
        val game = w.newGame()
        val startingCurrency = w.store.getPlayer(game.id)!!.currency

        val outcome = w.pipeline.execute(game.id, "I flash my money around.")
        assertTrue(outcome is TurnOutcome.Success)
        outcome as TurnOutcome.Success
        assertEquals(startingCurrency, w.store.getPlayer(game.id)!!.currency)
        assertEquals(1, outcome.issues.size)
        assertEquals("INSUFFICIENT_FUNDS", outcome.issues.single().code)
        assertEquals(1, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `relationships change from events even when the model states no delta`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You help Mara shift the barrels.",
                timeAdvanceMinutes = 20,
                events = listOf(
                    EventDto(
                        type = "PLAYER_HELPED_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player helped Mara shift barrels.", importance = "MEDIUM",
                    ),
                ),
            )
        }

        w.pipeline.execute(game.id, "I help Mara with the barrels.")
        val rel = w.store.getRelationship(game.id, Ids.PLAYER, maraId)
        assertNotNull(rel)
        assertTrue("Helping someone should build trust", rel!!.vector.trust > 0)
        assertTrue(rel.vector.familiarity > 0)
        assertTrue("The reason for the change must be recorded", rel.history.isNotEmpty())
    }

    @Test
    fun `an invalid key is reported clearly and is not retryable`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        w.behaviour.failWith = AIErrorKind.INVALID_KEY
        w.behaviour.failuresRemaining = 1

        val outcome = w.pipeline.execute(game.id, "I look up.")
        assertTrue(outcome is TurnOutcome.Failure)
        outcome as TurnOutcome.Failure
        assertEquals(AIErrorKind.INVALID_KEY, outcome.kind)
        assertTrue(!outcome.retryable)
        assertEquals(0, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `the world keeps moving during a long wait even though no model call decides it`() = runTest {
        val w = TestWorld()
        w.behaviour.responder = { _ ->
            TurnResponseDto(narrative = "You wait out the afternoon.", timeAdvanceMinutes = 60 * 8)
        }
        val game = w.newGame()
        val callsBefore = w.provider.callCount

        w.pipeline.execute(game.id, "I wait.")

        assertEquals("Waiting must cost exactly one model call", callsBefore + 1, w.provider.callCount)
        val events = w.store.recentEvents(game.id, 50)
        assertTrue("Time passing must be recorded", events.any { it.type.name == "TIME_ADVANCED" })
    }
}
