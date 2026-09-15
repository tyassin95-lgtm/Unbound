package com.unbound.core

import com.unbound.core.engine.TurnOutcome
import com.unbound.core.model.Ids
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.KnowledgeChangeDto
import com.unbound.core.validate.NewCharacterDto
import com.unbound.core.validate.NpcActionDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for a bug reported from play.
 *
 * A character was introduced in the opening scene, kept the location she had arrived *from*, and
 * was thereafter treated as absent from the conversation she was having: she could not be pictured,
 * she witnessed nothing, and the validator rejected the knowledge she had obviously just acquired
 * in person with `IMPOSSIBLE_KNOWLEDGE`.
 */
class SceneParticipationTest {

    /** Introduces a character whose stored location is somewhere other than the player's. */
    private suspend fun TestWorld.introduceElsewhere(gameId: String, name: String): String {
        val elsewhere = store.allLocations(gameId).first { it.id != store.getPlayer(gameId)!!.currentLocationId }
        behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "A door bangs and $name comes out alone.",
                timeAdvanceMinutes = 2,
                newCharacters = listOf(
                    NewCharacterDto(
                        name = name, age = 27, gender = "woman",
                        appearance = "Glitter on one cheek, no coat.",
                        // The narrator says where she came from, not where she now stands.
                        locationId = elsewhere.id,
                    ),
                ),
            )
        }
        pipeline.execute(gameId, "I watch the door.")
        return store.allNpcs(gameId).first { it.name == name }.id
    }

    @Test
    fun `a character speaking in the scene is not rejected as absent`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val livId = w.introduceElsewhere(game.id, "Liv")

        // Exactly the reported turn: she answers, and the model records what she now knows.
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "\"I-maybe,\" she says. \"Are you with them?\"",
                timeAdvanceMinutes = 2,
                presentCharacterIds = listOf(livId),
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = livId,
                        summary = "Adrian called out to Liv and asked whether she needed help.",
                        importance = "MEDIUM",
                    ),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = livId, factKey = "adrian.offered_help",
                        statement = "Adrian called out and asked whether I needed help.",
                        certainty = "KNOWN", subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                ),
            )
        }

        val outcome = w.pipeline.execute(game.id, "I ask Liv if she's alright.")
        assertTrue(outcome is TurnOutcome.Success)
        outcome as TurnOutcome.Success

        assertEquals("Nothing should have been rejected: $outcome", emptyList<String>(), outcome.issues.map { it.code })
        assertTrue(
            "She must actually learn what was said to her face",
            w.store.knowledgeOf(game.id, livId, 50).any { it.factKey == "adrian.offered_help" },
        )
    }

    @Test
    fun `taking part in a scene brings a character's location back in line with the story`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val livId = w.introduceElsewhere(game.id, "Liv")
        val here = w.store.getPlayer(game.id)!!.currentLocationId

        assertFalse("Precondition: she starts out filed elsewhere", w.store.getNpc(game.id, livId)!!.currentLocationId == here)

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "She takes one careful step nearer.",
                timeAdvanceMinutes = 2,
                npcActions = listOf(NpcActionDto(npcId = livId, action = "keeps her distance but stays")),
            )
        }
        w.pipeline.execute(game.id, "I wait.")

        assertEquals("She is where the story has her", here, w.store.getNpc(game.id, livId)!!.currentLocationId)
        assertTrue(
            "...so she can be pictured, spoken to, and counted as present",
            w.store.npcsAt(game.id, here).any { it.id == livId },
        )
    }

    @Test
    fun `a character introduced this turn witnesses and remembers the scene they arrive in`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "A woman comes down the steps. You call out.",
                timeAdvanceMinutes = 3,
                newCharacters = listOf(
                    NewCharacterDto(name = "Liv", age = 27, gender = "woman", appearance = "Glitter on one cheek."),
                ),
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER,
                        summary = "Adrian called out to the woman on the steps.",
                        importance = "MEDIUM", knowledgeScope = "WITNESSED",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I call out to her.")

        val liv = w.store.allNpcs(game.id).first { it.name == "Liv" }
        assertTrue(
            "Someone introduced into a scene must witness that scene, not the next one",
            w.store.knowledgeOf(game.id, liv.id, 50).isNotEmpty(),
        )
        assertTrue(w.store.memoriesOf(game.id, liv.id, 50).isNotEmpty())
        assertEquals(w.store.getPlayer(game.id)!!.currentLocationId, liv.currentLocationId)
    }

    @Test
    fun `a character who walks out is not dragged back into the scene`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")
        val here = w.store.getPlayer(game.id)!!.currentLocationId
        val elsewhere = w.store.allLocations(game.id).first { it.id != here }

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "Mara goes out the back without a word.",
                timeAdvanceMinutes = 5,
                npcActions = listOf(
                    NpcActionDto(npcId = maraId, action = "leaves", movesToLocationId = elsewhere.id),
                ),
            )
        }
        w.pipeline.execute(game.id, "I watch her go.")

        assertEquals("An explicit exit must stand", elsewhere.id, w.store.getNpc(game.id, maraId)!!.currentLocationId)
        assertFalse(w.store.npcsAt(game.id, here).any { it.id == maraId })
    }

    @Test
    fun `someone genuinely absent still cannot learn things first-hand`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val surrinId = w.npcId(game.id, "surrin")
        val elsewhere = w.store.allLocations(game.id).first { it.id != w.store.getPlayer(game.id)!!.currentLocationId }
        w.store.upsertNpcs(listOf(w.store.getNpc(game.id, surrinId)!!.copy(currentLocationId = elsewhere.id)))

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You say it quietly, to nobody in particular.",
                timeAdvanceMinutes = 2,
                // Surrin is named nowhere: not present, no action, no event.
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = surrinId, factKey = "player.secret",
                        statement = "The player admitted everything.", certainty = "KNOWN",
                    ),
                ),
            )
        }

        // Naming her puts her in the turn's context without putting her in the room, which is
        // exactly the case the rule exists for.
        val outcome = w.pipeline.execute(game.id, "I mutter it to myself, hoping Surrin never hears.")
        assertTrue(outcome is TurnOutcome.Success)
        outcome as TurnOutcome.Success

        assertEquals(
            "The rule must still hold for people who are actually absent",
            listOf("IMPOSSIBLE_KNOWLEDGE"),
            outcome.issues.map { it.code },
        )
        assertFalse(w.store.knowledgeOf(game.id, surrinId, 50).any { it.factKey == "player.secret" })
    }

    @Test
    fun `an ambient walk-on who takes part stops being disposable`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val here = w.store.getPlayer(game.id)!!.currentLocationId
        val ambient = w.store.getNpc(game.id, w.npcId(game.id, "coll"))!!
            .copy(tier = com.unbound.core.model.NpcTier.AMBIENT, currentLocationId = here)
        w.store.upsertNpcs(listOf(ambient))

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "He turns out to have plenty to say.",
                timeAdvanceMinutes = 5,
                npcActions = listOf(NpcActionDto(npcId = ambient.id, action = "starts talking")),
            )
        }
        w.pipeline.execute(game.id, "I ask him about it.")

        assertEquals(
            com.unbound.core.model.NpcTier.SEMI_PERSISTENT,
            w.store.getNpc(game.id, ambient.id)!!.tier,
        )
        assertNotNull(w.store.getNpc(game.id, ambient.id)!!.lastSeenTurn)
    }
}
