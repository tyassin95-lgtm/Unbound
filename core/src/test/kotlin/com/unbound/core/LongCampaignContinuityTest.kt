package com.unbound.core

import com.unbound.core.model.Ids
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.KnowledgeChangeDto
import com.unbound.core.validate.MemoryCandidateDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continuity at 50, 100 and 250 turns, measured rather than asserted.
 *
 * Two properties are in tension and both have to hold. Early history must stay *reachable* — the
 * thing a player asks about at turn 250 is usually something from turn 5 — while the context must
 * stay *bounded*, because a context that grows with the campaign makes a long game cost more per
 * turn than a short one and eventually stops fitting at all.
 *
 * Getting one without the other is easy and useless: dropping old history bounds the context, and
 * keeping everything preserves history. This asserts both at once.
 */
class LongCampaignContinuityTest {

    private fun filler(i: Int) = TurnResponseDto(
        narrative = "Turn $i. The smoke does not lift. You keep moving.",
        timeAdvanceMinutes = 40,
    )

    @Test
    fun `early history stays reachable while the context stays bounded, to 250 turns`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        // --- turn 1: the thing that will be asked about 249 turns later ---------------------
        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "She writes your name in the book and does not look up.",
                timeAdvanceMinutes = 20,
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "Mara Venn wrote the player's name in the Kettle's book.",
                        importance = "CRITICAL", knowledgeScope = "WITNESSED",
                    ),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = maraId, factKey = "player.name_in_the_book",
                        statement = "I wrote this one's name in the book myself.",
                        certainty = "KNOWN", subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                ),
                memoryCandidates = listOf(
                    MemoryCandidateDto(
                        ownerId = maraId, text = "I wrote their name in the book the night we met.",
                        entityIds = listOf(Ids.PLAYER), importance = "CRITICAL",
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I give her my name for the book.")

        val samples = mutableMapOf<Int, Int>()
        val memoryCounts = mutableMapOf<Int, Int>()

        suspend fun probeAt(turn: Int) {
            w.behaviour.responder = { TurnResponseDto(narrative = "You ask.", timeAdvanceMinutes = 5) }
            w.pipeline.execute(game.id, "I ask Mara about the night she wrote my name down.")
            val context = w.provider.lastRequest!!.dynamicContext

            assertTrue(
                "At turn $turn the world has forgotten what happened on turn 1",
                context.contains("name in the book") || context.contains("wrote their name"),
            )
            assertTrue("Mara must still be recognised at turn $turn", context.contains("Mara Venn"))

            samples[turn] = context.length
            memoryCounts[turn] = w.store.memoriesOf(game.id, maraId, 10_000).size
        }

        probeAt(2)
        repeat(48) { w.behaviour.responder = { _ -> filler(0) }; w.pipeline.execute(game.id, "I keep at it.") }
        probeAt(51)
        repeat(48) { w.behaviour.responder = { _ -> filler(0) }; w.pipeline.execute(game.id, "I keep at it.") }
        probeAt(100)
        repeat(148) { w.behaviour.responder = { _ -> filler(0) }; w.pipeline.execute(game.id, "I keep at it.") }
        probeAt(249)

        // Bounded: the context at turn 250 is not meaningfully larger than at turn 50. Some growth
        // is legitimate — a longer campaign genuinely has more people and more open situations —
        // so this is a ceiling on the ratio, not a demand that it never move.
        val early = samples[51]!!
        val late = samples[249]!!
        assertTrue(
            "Context grew with campaign age: $samples",
            late <= early * 3 / 2,
        )

        // And memory is compacted rather than accumulating without limit.
        assertTrue(
            "Memories grew without bound: $memoryCounts",
            memoryCounts[249]!! <= memoryCounts[51]!! * 2 + 40,
        )

        assertEquals(249, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `the transcript stays a few turns wide however long the campaign runs`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        repeat(120) { i ->
            w.behaviour.responder = { _ -> filler(i) }
            w.pipeline.execute(game.id, "I keep at it, turn $i.")
        }

        w.behaviour.responder = { TurnResponseDto(narrative = "You look.", timeAdvanceMinutes = 5) }
        w.pipeline.execute(game.id, "I look around.")
        val context = w.provider.lastRequest!!.dynamicContext

        val transcriptLines = context.lines().count { it.startsWith("[turn ") }
        assertTrue("The transcript must stay small: $transcriptLines lines", transcriptLines in 1..12)
        assertTrue("The most recent turn must be in it", context.contains("turn 119"))
        assertTrue("An old turn must not be", !context.contains("turn 3.") || !context.contains("[turn 3]"))
    }

    @Test
    fun `the stable half of the prompt is byte-identical across turns`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = { TurnResponseDto(narrative = "A.", timeAdvanceMinutes = 5) }
        w.pipeline.execute(game.id, "I wait.")
        val first = w.provider.lastRequest!!.stableSystemPrompt

        repeat(30) { w.pipeline.execute(game.id, "I wait again.") }
        val later = w.provider.lastRequest!!.stableSystemPrompt

        // This is the whole basis of the prompt-cache claim: if the prefix moved, the cache would
        // never hit and the claim in the docs would be false.
        assertEquals("The stable prompt must not drift between turns", first, later)
        assertTrue("It must actually carry the rules", first.contains("CONTINUITY"))
    }
}
