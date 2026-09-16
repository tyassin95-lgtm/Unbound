package com.unbound.core

import com.unbound.core.content.FallbackOpenings
import com.unbound.core.content.Settings
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.memory.ChapterSummariser
import com.unbound.core.model.Ids
import com.unbound.core.model.ThreadVisibility
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.NewCharacterDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Long-range continuity: the thing the whole memory architecture exists for.
 *
 * Each of these failed before. The shared-history query was implemented in the store and never
 * called, summaries were modelled and never written, and a first meeting was recorded at an
 * importance below the memorable threshold — so "how did we meet?" had no answer anywhere.
 */
class ContinuityTest {

    @Test
    fun `a hundred turns later, an NPC can still be told how they met`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        // Turn 1: meet someone, in a specific place, at a specific hour.
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "She comes down the steps and stops when she sees you.",
                timeAdvanceMinutes = 5,
                newCharacters = listOf(
                    NewCharacterDto(name = "Liv", age = 27, gender = "woman", appearance = "Glitter on one cheek."),
                ),
            )
        }
        w.pipeline.execute(game.id, "I call out to her.")
        val liv = w.store.allNpcs(game.id).first { it.name == "Liv" }

        // Turn 2: something specific and unremarkable happens between them — the sort of detail a
        // player asks about later precisely because it was not dramatic.
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You buy her a coffee from the machine. It is terrible.",
                timeAdvanceMinutes = 20,
                presentCharacterIds = listOf(liv.id),
                events = listOf(
                    EventDto(
                        type = "PLAYER_GAVE_ITEM", actorId = Ids.PLAYER, targetId = liv.id,
                        summary = "Adrian bought Liv a coffee from the machine on the corner.",
                        importance = "LOW",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I buy her a coffee.")

        // A hundred ordinary turns pass.
        w.behaviour.responder = { input -> TurnResponseDto(narrative = "You go on. ${input.playerInput}", timeAdvanceMinutes = 40) }
        repeat(100) { w.pipeline.execute(game.id, "I keep moving.") }

        // Now ask her.
        var contextAtAsk = ""
        w.behaviour.responder = { input ->
            contextAtAsk = input.context
            TurnResponseDto(narrative = "She thinks about it.", timeAdvanceMinutes = 2)
        }
        w.pipeline.execute(game.id, "Liv, do you remember how we met?")

        assertTrue(
            "The meeting itself must reach the model, not just the fact she exists",
            contextAtAsk.contains("first met Liv", ignoreCase = true),
        )
        assertTrue(
            "And the small things done together, which is what actually gets asked about",
            contextAtAsk.contains("coffee", ignoreCase = true),
        )
        assertTrue(
            "Events must carry an absolute day so 'when' is answerable, not only 'how long ago'",
            Regex("\\(d\\d+ \\d{2}:\\d{2},").containsMatchIn(contextAtAsk),
        )
    }

    @Test
    fun `a first meeting is recorded as memorable for both sides`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You meet.",
                timeAdvanceMinutes = 5,
                newCharacters = listOf(
                    NewCharacterDto(name = "Liv", age = 27, gender = "woman", appearance = "Glitter on one cheek."),
                ),
            )
        }
        w.pipeline.execute(game.id, "I introduce myself.")

        val liv = w.store.allNpcs(game.id).first { it.name == "Liv" }
        val meeting = w.store.eventsInvolving(game.id, listOf(liv.id), 20)
            .firstOrNull { it.summary.contains("first met", ignoreCase = true) }

        assertNotNull("The meeting must be in the ledger", meeting)
        assertTrue("...at an importance that survives into memory", meeting!!.importance.isMemorable)
        assertTrue(
            "The place and time belong in it, so 'where did we meet' is answerable",
            meeting.summary.contains("Black Kettle") && meeting.summary.contains("Day"),
        )
        assertTrue(w.store.memoriesOf(game.id, liv.id, 20).isNotEmpty())
        assertTrue(w.store.memoriesOf(game.id, Ids.PLAYER, 20).any { it.text.contains("first met", ignoreCase = true) })
    }

    @Test
    fun `chapters are written from the ledger and reach the prompt`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = { input ->
            TurnResponseDto(
                narrative = "It happens.",
                timeAdvanceMinutes = 30,
                events = if (input.playerInput.contains("burn")) {
                    listOf(
                        EventDto(
                            type = "LOCATION_CHANGED",
                            summary = "The warehouse on Ninth burned to the ground.",
                            importance = "CRITICAL",
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        }

        w.pipeline.execute(game.id, "I burn it down.")
        repeat(ChapterSummariser.CHAPTER_TURNS - 1) { w.pipeline.execute(game.id, "I lie low.") }

        val summaries = w.store.summaries(game.id, 10)
        assertEquals("A chapter closes every ${ChapterSummariser.CHAPTER_TURNS} turns", 1, summaries.size)
        assertTrue(summaries.single().text.contains("warehouse on Ninth burned"))
        assertEquals(ChapterSummariser.CHAPTER_TURNS, summaries.single().coversToTurn)

        var context = ""
        w.behaviour.responder = { input -> context = input.context; TurnResponseDto(narrative = "x", timeAdvanceMinutes = 5) }
        w.pipeline.execute(game.id, "I think back.")
        assertTrue("Chapters must actually be sent", context.contains("warehouse on Ninth burned"))
    }

    @Test
    fun `a quiet stretch does not produce a filler chapter`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        w.behaviour.responder = { TurnResponseDto(narrative = "Nothing happens.", timeAdvanceMinutes = 30) }
        repeat(ChapterSummariser.CHAPTER_TURNS) { w.pipeline.execute(game.id, "I wait.") }

        assertTrue(
            "An empty chapter would crowd out real ones",
            w.store.summaries(game.id, 10).isEmpty(),
        )
    }

    @Test
    fun `a situation the player runs into stops being invisible in the journal`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        val hidden = w.store.allThreads(game.id, 20).single()
        assertEquals("Precondition: it starts hidden", ThreadVisibility.HIDDEN, hidden.visibility)
        assertTrue(maraId in hidden.involvedEntityIds)
        assertTrue(
            "Precondition: the journal cannot see it",
            com.unbound.core.journal.JournalBuilder(w.store).build(game.id).threads.isEmpty(),
        )

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "Mara says more than she meant to.",
                timeAdvanceMinutes = 10,
                presentCharacterIds = listOf(maraId),
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "Adrian talked with Mara about the note.", importance = "MEDIUM",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I ask Mara about the Kettle.")

        val journal = com.unbound.core.journal.JournalBuilder(w.store).build(game.id)
        assertEquals("Meeting someone caught up in it is how you learn of it", 1, journal.threads.size)
        assertTrue(journal.threads.single().uncertain)
    }

    @Test
    fun `the journal reports where the player actually stands with factions`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val faction = w.store.factions(game.id).first()
        w.store.upsertFactions(listOf(faction.copy(playerStanding = -40)))

        val journal = com.unbound.core.journal.JournalBuilder(w.store).build(game.id)
        assertEquals(
            "Reputation was an always-empty map on the character page",
            -40,
            journal.character.reputation[faction.name],
        )
    }

    @Test
    fun `a world can always offer somewhere to start without a model call`() {
        val seed = Settings.byId("ashmarket")!!
        val options = FallbackOpenings.forWorld(seed, "Rook")

        assertTrue("A generated world would otherwise show an empty step", options.size >= 3)
        assertTrue(options.all { it.situation.isNotBlank() && it.title.isNotBlank() })
        assertTrue(
            "They must be about this world, not generic filler",
            options.any { it.situation.contains("Black Kettle") },
        )
        assertEquals("No duplicates", options.size, options.distinctBy { it.situation }.size)
    }
}
