package com.unbound.core

import com.unbound.core.model.Ids
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.MemoryCandidateDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §95 / §149. A long campaign must stay bounded in what it sends, bounded in what it retrieves,
 * and must not lose early history.
 *
 * The assertion that matters most is the one about context growth: if the prompt grew with campaign
 * age, every claim this product makes about cost and long-term memory would be false.
 */
class LongCampaignTest {

    private companion object {
        val SUBJECTS = listOf(
            "A coke contract", "Two water permits", "The Ridge tollbook", "A foundry lease",
            "Scrip from the casting yards", "A magistrate's docket", "Ferry rights at the wash",
            "A haulier's licence", "The night watch roster", "A shipment of pig iron",
            "The tenement rents", "A guild apprenticeship", "Grain from upriver",
            "The Kettle's tab book", "A surveyor's map", "Timber from the north road",
            "A physician's supply", "The carters' contract", "A bailiff's warrant",
            "Salt from the flats", "A quarry claim", "The smelter rota",
        )
    }

    @Test
    fun `a 150 turn campaign stays consistent and does not grow its context with age`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        // Turn 3 plants something important and early; it must still be reachable at turn 150.
        val contextSizes = mutableMapOf<Int, Int>()

        repeat(150) { i ->
            val turn = i + 1
            w.behaviour.responder = { input ->
                contextSizes[turn] = input.context.length
                when (turn) {
                    3 -> TurnResponseDto(
                        narrative = "You promise Mara you will settle the Kettle's note before the month turns.",
                        timeAdvanceMinutes = 20,
                        events = listOf(
                            EventDto(
                                type = "PLAYER_MADE_PROMISE", actorId = Ids.PLAYER, targetId = maraId,
                                summary = "The player promised Mara Venn to settle the Kettle's note.",
                                importance = "CRITICAL", knowledgeScope = "WITNESSED",
                            ),
                        ),
                        memoryCandidates = listOf(
                            MemoryCandidateDto(
                                ownerId = maraId,
                                text = "The player promised to settle the Kettle's note before the month turns.",
                                entityIds = listOf(Ids.PLAYER), importance = "CRITICAL",
                            ),
                        ),
                    )
                    else -> TurnResponseDto(
                        narrative = "Turn $turn. You keep moving.",
                        timeAdvanceMinutes = 30,
                        // Distinct subjects, so the reconciler has no excuse to merge them; the
                        // merging behaviour itself is asserted separately below.
                        memoryCandidates = if (turn % 7 == 0) {
                            listOf(
                                MemoryCandidateDto(
                                    ownerId = "world",
                                    text = "${SUBJECTS[(turn / 7) % SUBJECTS.size]} changed hands in the lower city.",
                                    importance = "MEDIUM",
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    )
                }
            }
            w.pipeline.execute(game.id, "I keep at it, turn $turn.")
        }

        val finalGame = w.store.getGame(game.id)!!
        assertEquals(150, finalGame.turnNumber)
        assertEquals(150, w.store.countTurns(game.id))
        assertEquals("Every committed turn must bump the version exactly once", 151L, finalGame.stateVersion)

        // --- the ledger grows, and stays unique ------------------------------------------------
        val totalEvents = w.store.countEvents(game.id)
        assertTrue("The ledger must actually accumulate: $totalEvents", totalEvents > 150)
        val page = w.store.eventsPage(game.id, 0, 5000)
        assertEquals("Event ids must be unique", page.size, page.map { it.id }.distinct().size)
        assertEquals("Event sequence numbers must be unique", page.size, page.map { it.sequence }.distinct().size)

        // --- context does not grow with campaign age --------------------------------------------
        val early = (5..15).mapNotNull { contextSizes[it] }.average()
        val late = (140..150).mapNotNull { contextSizes[it] }.average()
        assertTrue(
            "Context must not grow with campaign age (early ${early.toInt()} chars, late ${late.toInt()} chars)",
            late < early * 1.6,
        )
        val largest = contextSizes.values.max()
        assertTrue("No single turn may send an unbounded context: $largest chars", largest < 30_000)

        // --- the whole history was never sent -----------------------------------------------------
        val lastContext = w.provider.lastRequest!!.dynamicContext
        val turnsMentioned = Regex("Turn (\\d+)\\.").findAll(lastContext).count()
        assertTrue("The full narrative history must never be replayed: $turnsMentioned turns in context", turnsMentioned < 30)

        // --- early important memory survives ------------------------------------------------------
        val maraMemories = w.store.memoriesOf(game.id, maraId, 500)
        assertTrue(
            "A CRITICAL promise from turn 3 must still be stored at turn 150",
            maraMemories.any { it.text.contains("promised to settle") },
        )

        // And retrievable, not merely stored: ask about it and check it reaches the prompt.
        var recalled = ""
        w.behaviour.responder = { input -> recalled = input.context; TurnResponseDto(narrative = "She remembers.", timeAdvanceMinutes = 5) }
        w.pipeline.execute(game.id, "Mara, about that promise I made you.")
        assertTrue(
            "The turn-3 promise must be retrievable at turn 151",
            recalled.contains("promised to settle"),
        )

        // --- retrieval stayed bounded ----------------------------------------------------------------
        val memoryCount = w.store.countMemories(game.id)
        assertTrue("Memories should accumulate but not explode: $memoryCount", memoryCount in 10..2000)
    }

    @Test
    fun `deterministic operations never reach the model`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val callsAfterCreation = w.provider.callCount
        assertEquals("Creating a world must cost zero model calls", 0, callsAfterCreation)

        repeat(10) { w.pipeline.execute(game.id, "I look around.") }
        assertEquals("Exactly one model call per narrative turn", 10, w.provider.callCount)

        // Reading state back costs nothing at all.
        w.store.getPlayer(game.id)
        w.store.itemsOwnedBy(game.id, Ids.PLAYER)
        w.store.activeThreads(game.id)
        w.store.recentEvents(game.id, 50)
        assertEquals(10, w.provider.callCount)
    }

    @Test
    fun `saving and reloading mid-campaign restores the world exactly`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        repeat(12) { w.pipeline.execute(game.id, "I keep working.") }

        val before = w.store.getGame(game.id)!!
        val playerBefore = w.store.getPlayer(game.id)!!
        val eventsBefore = w.store.countEvents(game.id)

        // "Reload": read everything back through the store as a fresh session would.
        val after = w.store.getGame(game.id)!!
        assertEquals(before, after)
        assertEquals(playerBefore, w.store.getPlayer(game.id))
        assertEquals(eventsBefore, w.store.countEvents(game.id))

        // And play continues from exactly where it stopped.
        val outcome = w.pipeline.execute(game.id, "I carry on.")
        assertTrue(outcome is com.unbound.core.engine.TurnOutcome.Success)
        assertEquals(13, w.store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `repeating the same memory reinforces it instead of duplicating it`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You settle up at the bar.",
                timeAdvanceMinutes = 10,
                memoryCandidates = listOf(
                    MemoryCandidateDto(
                        ownerId = maraId,
                        text = "The player paid their tab in full.",
                        entityIds = listOf(Ids.PLAYER),
                        importance = "MEDIUM",
                    ),
                ),
            )
        }

        repeat(8) { w.pipeline.execute(game.id, "I pay my tab.") }

        val matching = w.store.memoriesOf(game.id, maraId, 200).filter { it.text.contains("paid their tab") }
        assertEquals("Eight identical memories must collapse into one", 1, matching.size)
        assertTrue(
            "...that records how often it happened: ${matching.single().reinforcementCount}",
            matching.single().reinforcementCount >= 8,
        )
    }
}
