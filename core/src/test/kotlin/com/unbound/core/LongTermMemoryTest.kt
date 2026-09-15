package com.unbound.core

import com.unbound.core.knowledge.Certainty
import com.unbound.core.model.Ids
import com.unbound.core.model.ItemRecord
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.KnowledgeChangeDto
import com.unbound.core.validate.MemoryCandidateDto
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scenario written out in §94, executed literally.
 *
 * Turn 5 the player steals Mara's ring, turn 20 returns it, turn 40 insults her employer, turn 70
 * joins a rival faction, and at turn 120 asks her for help. The test then asserts what Mara
 * remembers, what she does *not* know, and that the context actually handed to the model at turn
 * 120 carries that history.
 */
class LongTermMemoryTest {

    @Test
    fun `mara remembers across 120 turns, and knows only what reached her`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")
        val surrinId = w.npcId(game.id, "surrin")
        val kettleId = w.store.getPlayer(game.id)!!.currentLocationId

        val ring = ItemRecord(
            id = "item_mara_ring", gameId = game.id, name = "worn silver ring",
            description = "A silver band worn thin.", ownerId = maraId, unique = true,
        )
        w.store.upsertItems(listOf(ring))

        // Warden Surrin is elsewhere for the whole scenario, so nothing should reach her by
        // presence — only by the routes the test explicitly opens.
        val surrin = w.store.getNpc(game.id, surrinId)!!
        val lane = w.store.allLocations(game.id).first { it.name == w.seed.locations.first { l -> l.key == "lane" }.name }
        w.store.upsertNpcs(listOf(surrin.copy(currentLocationId = lane.id)))

        fun filler(text: String) = TurnResponseDto(narrative = "Time passes. $text", timeAdvanceMinutes = 45)

        suspend fun play(turns: Int, text: String = "I go about my business.") {
            repeat(turns) {
                w.behaviour.responder = { filler("Nothing much happens.") }
                w.pipeline.execute(game.id, text)
            }
        }

        // --- turns 1-4 ---------------------------------------------------------------------
        play(4)

        // --- turn 5: the theft --------------------------------------------------------------
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "Mara turns to the taps. The ring is on the shelf. You take it.",
                timeAdvanceMinutes = 5,
                events = listOf(
                    EventDto(
                        type = "PLAYER_STOLE_ITEM", actorId = Ids.PLAYER, targetId = maraId,
                        locationId = kettleId, summary = "The player stole Mara Venn's silver ring.",
                        importance = "HIGH", knowledgeScope = "WITNESSED",
                    ),
                ),
                stateChanges = listOf(
                    StateChangeDto(type = "ITEM_TRANSFER", itemId = ring.id, entityId = maraId, targetId = Ids.PLAYER, reason = "stolen"),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = maraId, factKey = "mara.ring_stolen_by_player",
                        statement = "The player took my ring from the shelf.", certainty = "KNOWN",
                        subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                    // The watch only learns that it went missing — not who took it.
                    KnowledgeChangeDto(
                        knowerId = surrinId, factKey = "mara.ring_missing",
                        statement = "Mara Venn's ring has gone missing.", certainty = "RUMORED",
                        sourceEntityId = maraId,
                    ),
                ),
                memoryCandidates = listOf(
                    MemoryCandidateDto(ownerId = maraId, text = "The player stole my silver ring.", entityIds = listOf(Ids.PLAYER), importance = "HIGH"),
                ),
            )
        }
        w.pipeline.execute(game.id, "I take the ring from the shelf while she is turned away.")

        // --- turns 6-19 ----------------------------------------------------------------------
        play(14)

        // --- turn 20: the ring comes back ------------------------------------------------------
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You put the ring on the bar. Mara looks at it for a long moment.",
                timeAdvanceMinutes = 10,
                events = listOf(
                    EventDto(
                        type = "PLAYER_GAVE_ITEM", actorId = Ids.PLAYER, targetId = maraId,
                        locationId = kettleId, summary = "The player returned the stolen ring to Mara Venn.",
                        importance = "HIGH", knowledgeScope = "WITNESSED",
                    ),
                ),
                stateChanges = listOf(
                    StateChangeDto(type = "ITEM_TRANSFER", itemId = ring.id, entityId = Ids.PLAYER, targetId = maraId, reason = "returned"),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = maraId, factKey = "mara.ring_returned_by_player",
                        statement = "The player brought my ring back.", certainty = "KNOWN", subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                ),
                memoryCandidates = listOf(
                    MemoryCandidateDto(ownerId = maraId, text = "The player gave my ring back without being made to.", entityIds = listOf(Ids.PLAYER), importance = "HIGH"),
                ),
            )
        }
        w.pipeline.execute(game.id, "I give Mara her ring back.")

        // --- turns 21-39 -----------------------------------------------------------------------
        play(19)

        // --- turn 40: the insult ------------------------------------------------------------------
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You say what you think of the Combine factor, loudly, in her house.",
                timeAdvanceMinutes = 15,
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        locationId = kettleId, summary = "The player insulted the Combine factor in front of Mara Venn.",
                        importance = "HIGH", knowledgeScope = "WITNESSED",
                    ),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = maraId, factKey = "mara.player_insulted_factor",
                        statement = "The player insulted the Combine factor in my bar.", certainty = "KNOWN",
                        subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                ),
                memoryCandidates = listOf(
                    MemoryCandidateDto(ownerId = maraId, text = "The player insulted the Combine factor in my bar, where anyone could hear.", entityIds = listOf(Ids.PLAYER), importance = "HIGH"),
                ),
                relationshipChanges = emptyList(),
            )
        }
        w.pipeline.execute(game.id, "I tell the room exactly what I think of the Combine factor.")

        // --- turns 41-69 ------------------------------------------------------------------------
        play(29)

        // --- turn 70: joining the rival faction, away from Mara ---------------------------------
        val cinders = w.store.factions(game.id).first { it.name.contains("Cinders") }
        w.behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You put your name down. Nobody from the Kettle is in the room.",
                timeAdvanceMinutes = 90,
                events = listOf(
                    EventDto(
                        type = "PLAYER_JOINED_FACTION", actorId = Ids.PLAYER, targetId = cinders.id,
                        summary = "The player joined the Cinders Meeting.", importance = "HIGH",
                        // Faction-scoped: it reaches the faction, not the whole city, and not Mara.
                        knowledgeScope = "FACTION",
                    ),
                ),
                stateChanges = listOf(StateChangeDto(type = "FACTION_JOIN", entityId = cinders.id, reason = "signed on")),
            )
        }
        w.pipeline.execute(game.id, "I put my name down with the Cinders.")

        // --- turns 71-119 ------------------------------------------------------------------------
        play(49)

        // --- turn 120: the ask ---------------------------------------------------------------------
        var contextAtAsk = ""
        w.behaviour.responder = { input ->
            contextAtAsk = input.context
            TurnResponseDto(narrative = "Mara does not answer straight away.", timeAdvanceMinutes = 5)
        }
        w.pipeline.execute(game.id, "Mara, I need your help.")

        assertEquals(120, w.store.getGame(game.id)!!.turnNumber)

        // --- what Mara remembers -------------------------------------------------------------------
        val maraMemories = w.store.memoriesOf(game.id, maraId, 200).joinToString(" | ") { it.text }
        assertTrue("Mara must still remember the theft: $maraMemories", maraMemories.contains("stole", ignoreCase = true))
        assertTrue("Mara must remember the ring coming back: $maraMemories", maraMemories.contains("back", ignoreCase = true))
        assertTrue("Mara must remember the insult: $maraMemories", maraMemories.contains("insult", ignoreCase = true))

        // --- what Mara knows, and does not ----------------------------------------------------------
        val maraFacts = w.store.knowledgeOf(game.id, maraId, 200)
        assertTrue(maraFacts.any { it.factKey == "mara.ring_stolen_by_player" && it.certainty == Certainty.KNOWN })
        assertTrue(maraFacts.any { it.factKey == "mara.ring_returned_by_player" })
        assertTrue(maraFacts.any { it.factKey == "mara.player_insulted_factor" })
        assertFalse(
            "Mara was not there and nobody told her — she must not know about the faction",
            maraFacts.any { it.statement.contains("Cinders", ignoreCase = true) },
        )

        // The watch knows only the weaker, secondhand version.
        val surrinFacts = w.store.knowledgeOf(game.id, surrinId, 200)
        val surrinRing = surrinFacts.firstOrNull { it.factKey.startsWith("mara.ring") }
        assertTrue("The watch should have heard something about the ring", surrinRing != null)
        assertEquals("...but only as hearsay", Certainty.RUMORED, surrinRing!!.certainty)
        assertFalse(
            "The watch must not know who took it",
            surrinFacts.any { it.factKey == "mara.ring_stolen_by_player" },
        )

        // --- the relationship reflects the history ---------------------------------------------------
        val rel = w.store.getRelationship(game.id, Ids.PLAYER, maraId)!!
        assertTrue("Theft and return should both have left a mark", rel.history.size >= 2)
        assertTrue(rel.vector.familiarity > 0)

        // --- and the model was actually told ----------------------------------------------------------
        assertTrue("The context at turn 120 must carry the theft", contextAtAsk.contains("stole", ignoreCase = true))
        assertTrue("The context must carry Mara's own knowledge", contextAtAsk.contains("ring", ignoreCase = true))
        assertFalse(
            "The context must never leak the faction membership into Mara's knowledge block",
            contextAtAsk.substringAfter("## KNOWLEDGE").substringBefore("## ").contains("Cinders"),
        )
    }
}
