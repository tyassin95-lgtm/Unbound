package com.unbound.core

import com.unbound.core.continuity.CommitmentStatus
import com.unbound.core.knowledge.Certainty
import com.unbound.core.model.Ids
import com.unbound.core.model.ItemRecord
import com.unbound.core.validate.CommitmentChangeDto
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.KnowledgeChangeDto
import com.unbound.core.validate.MemoryCandidateDto
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The continuity scenarios, executed rather than described.
 *
 * Every assertion here is about **persisted state or the context actually handed to the model** —
 * never about whether a generated paragraph sounds right. A test that checks prose would pass on a
 * world that had forgotten everything and was improvising convincingly, which is precisely the
 * failure these exist to catch.
 */
class ContinuityScenarioTest {

    /** Nothing happens, at length. The filler between the moments that matter. */
    private suspend fun TestWorld.drift(turns: Int, text: String = "I go about my business.") {
        repeat(turns) {
            behaviour.responder = { TurnResponseDto(narrative = "Time passes.", timeAdvanceMinutes = 45) }
            pipeline.execute(store.listGames().first().id, text)
        }
    }

    private suspend fun TestWorld.contextNow(gameId: String, input: String = "I look around."): String {
        behaviour.responder = { TurnResponseDto(narrative = "You look.", timeAdvanceMinutes = 1) }
        pipeline.execute(gameId, input)
        return provider.lastRequest!!.dynamicContext
    }

    // --- forgotten encounter ----------------------------------------------------------------

    @Test
    fun `someone met once is still known two hundred turns later`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "She tells you her name is Mara and that she runs the place.",
                timeAdvanceMinutes = 15,
                events = listOf(
                    EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player met Mara Venn for the first time.",
                        importance = "MEDIUM", knowledgeScope = "WITNESSED",
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I introduce myself to the woman behind the bar.")

        val metOnTurn = w.store.getNpc(game.id, maraId)!!.firstEncounteredTurn
        assertNotNull("Meeting someone must be recorded when it happens", metOnTurn)

        w.drift(200)

        // The record survives, unchanged, two hundred turns later.
        val mara = w.store.getNpc(game.id, maraId)!!
        assertEquals("The first meeting must not drift", metOnTurn, mara.firstEncounteredTurn)
        assertTrue(mara.introduced)

        // And it reaches the model when she is the subject of the turn.
        val context = w.contextNow(game.id, "I ask Mara how we first met.")
        assertTrue("She must be in the context at all", context.contains("Mara Venn"))
        assertTrue("How long they have known each other must be stated", context.contains("first met on turn $metOnTurn"))
    }

    // --- secret ------------------------------------------------------------------------------

    @Test
    fun `a secret told to one person does not reach an unrelated one`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")
        val surrinId = w.npcId(game.id, "surrin")

        // Surrin is across town for the whole scenario.
        val lane = w.store.allLocations(game.id).first { it.name.contains("Lane", true) }
        w.store.upsertNpcs(listOf(w.store.getNpc(game.id, surrinId)!!.copy(currentLocationId = lane.id)))

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "You tell her, quietly, where the manifest is.",
                timeAdvanceMinutes = 10,
                events = listOf(
                    EventDto(
                        type = "PLAYER_TOLD_TRUTH", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player told Mara where the manifest is hidden.",
                        importance = "HIGH", knowledgeScope = "SECRET", witnessIds = listOf(maraId),
                    ),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = maraId, factKey = "player.manifest_location",
                        statement = "The manifest is under the floor of the let room.",
                        certainty = "KNOWN", sourceEntityId = Ids.PLAYER, secret = true,
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I tell Mara where I hid the manifest.")
        w.drift(40)

        val maraKnows = w.store.knowledgeOf(game.id, maraId, 100)
        val surrinKnows = w.store.knowledgeOf(game.id, surrinId, 100)

        assertTrue(
            "The person who was told must hold it",
            maraKnows.any { it.factKey == "player.manifest_location" },
        )
        assertFalse(
            "A secret must not leak to someone who was not there and was never told",
            surrinKnows.any { it.factKey == "player.manifest_location" },
        )
    }

    // --- betrayal ----------------------------------------------------------------------------

    @Test
    fun `a betrayal changes the relationship and the world can say why`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "The watch comes through the door behind you. Mara does not look at you again.",
                timeAdvanceMinutes = 30,
                events = listOf(
                    EventDto(
                        type = "PLAYER_LIED", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player gave Mara Venn's name to the watch.",
                        importance = "CRITICAL", knowledgeScope = "WITNESSED",
                    ),
                ),
                relationshipChanges = listOf(
                    com.unbound.core.validate.RelationshipChangeDto(
                        fromEntityId = Ids.PLAYER, toEntityId = maraId,
                        changes = mapOf("trust" to -70, "affection" to -50),
                        reason = "You gave her name to the watch.",
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I give the watch her name.")

        val rel = w.store.relationshipsFrom(game.id, Ids.PLAYER, 50).first { it.toEntityId == maraId }
        assertTrue("The betrayal must move the relationship", rel.vector.trust < 0)
        assertTrue(
            "The world must be able to say why, not merely that",
            rel.history.any { it.reason.contains("watch", true) },
        )

        w.drift(60)
        val context = w.contextNow(game.id, "I try to talk to Mara.")
        assertTrue("The reason must still reach the model afterwards", context.contains("because:"))
        assertTrue(context.contains("watch", true))
    }

    // --- ownership ---------------------------------------------------------------------------

    @Test
    fun `an important item that changes hands stays tracked, with its history`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        val ledger = ItemRecord(
            id = "item_ledger", gameId = game.id, name = "the Combine ledger",
            description = "A water-stained account book.", ownerId = maraId, unique = true,
        )
        w.store.upsertItems(listOf(ledger))

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "She slides it across the bar.",
                timeAdvanceMinutes = 5,
                stateChanges = listOf(
                    StateChangeDto(
                        type = "ITEM_TRANSFER", itemId = ledger.id,
                        entityId = maraId, targetId = Ids.PLAYER, reason = "given for safekeeping",
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I ask her for the ledger.")

        val held = w.store.itemsOwnedBy(game.id, Ids.PLAYER).firstOrNull { it.id == ledger.id }
        assertNotNull("Ownership must actually move", held)
        assertTrue("The transfer must be recorded on the item", held!!.provenance.isNotEmpty())

        w.drift(120)

        val stillHeld = w.store.itemsOwnedBy(game.id, Ids.PLAYER).firstOrNull { it.id == ledger.id }
        assertNotNull("Ownership must survive a long campaign", stillHeld)

        val context = w.contextNow(game.id, "I check the ledger is still in my coat.")
        assertTrue("A carried item must be in the context", context.contains("Combine ledger"))
        assertTrue("With where it came from", context.contains("moved to"))
    }

    // --- location change ---------------------------------------------------------------------

    @Test
    fun `leaving after something happened and returning finds the place changed`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val kettleId = w.store.getPlayer(game.id)!!.currentLocationId
        val lane = w.store.allLocations(game.id).first { it.id != kettleId }

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "The lamp goes over. The bar takes light along its whole length.",
                timeAdvanceMinutes = 20,
                stateChanges = listOf(
                    StateChangeDto(
                        type = "LOCATION_CONDITION_CHANGE", locationId = kettleId,
                        value = "fire-damaged", reason = "The bar is burned along its length.",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I knock the lamp over.")

        assertEquals("fire-damaged", w.store.getLocation(game.id, kettleId)!!.condition)

        // Leave, let time pass, come back.
        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "You walk out into the lane.",
                timeAdvanceMinutes = 15,
                stateChanges = listOf(StateChangeDto(type = "PLAYER_MOVE", locationId = lane.id, reason = "left")),
            )
        }
        w.pipeline.execute(game.id, "I go out to the lane.")
        w.drift(80)

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "You go back in.",
                timeAdvanceMinutes = 15,
                stateChanges = listOf(StateChangeDto(type = "PLAYER_MOVE", locationId = kettleId, reason = "returned")),
            )
        }
        w.pipeline.execute(game.id, "I go back to the Kettle.")

        assertEquals(
            "What happened to a place must still be true when the player returns",
            "fire-damaged",
            w.store.getLocation(game.id, kettleId)!!.condition,
        )
        val context = w.contextNow(game.id, "I look at the bar.")
        assertTrue("And it must reach the model", context.contains("fire-damaged"))
    }

    // --- faction consequence ------------------------------------------------------------------

    @Test
    fun `helping one faction is still remembered by the other much later`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val factions = w.store.factions(game.id)
        val helped = factions.first()
        val other = factions.last()

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "You carry the crate through the gate for them.",
                timeAdvanceMinutes = 60,
                stateChanges = listOf(
                    StateChangeDto(type = "FACTION_STANDING_CHANGE", entityId = helped.id, amount = 35, reason = "ran the crate through"),
                    StateChangeDto(type = "FACTION_STANDING_CHANGE", entityId = other.id, amount = -30, reason = "ran the crate through"),
                ),
                events = listOf(
                    EventDto(
                        type = "PLAYER_HELPED_NPC", actorId = Ids.PLAYER,
                        summary = "The player moved goods for ${helped.name} against ${other.name}.",
                        importance = "HIGH", knowledgeScope = "LOCAL_RUMOR",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I run the crate through the gate for them.")

        w.drift(150)

        val after = w.store.factions(game.id)
        assertTrue("Helping must raise standing", after.first { it.id == helped.id }.playerStanding > 0)
        assertTrue("And cost standing with the other", after.first { it.id == other.id }.playerStanding < 0)
    }

    // --- time passage --------------------------------------------------------------------------

    @Test
    fun `weeks of world time leave the world changed rather than paused`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val before = w.store.getGame(game.id)!!.worldTime.totalMinutes

        repeat(30) {
            w.behaviour.responder = { TurnResponseDto(narrative = "You sleep.", timeAdvanceMinutes = 720) }
            w.pipeline.execute(game.id, "I rest until tomorrow.")
        }

        val after = w.store.getGame(game.id)!!
        assertTrue("Time must actually move", after.worldTime.totalMinutes - before >= 30 * 720)
        assertTrue("Several days must have passed", after.worldTime.absoluteDay >= 14)

        // The world moved on its own while the player slept, and said so.
        val turns = w.store.turnsPage(game.id, 0, 40)
        assertTrue(
            "The simulator must have produced something over two weeks",
            turns.any { it.worldNotes.isNotEmpty() },
        )
    }

    // --- obligations ---------------------------------------------------------------------------

    @Test
    fun `a promise outlives the scene, and can only be discharged deliberately`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "\"Before the week is out,\" you tell her.",
                timeAdvanceMinutes = 10,
                commitmentChanges = listOf(
                    CommitmentChangeDto(
                        action = "MADE", kind = "DEBT", fromEntityId = Ids.PLAYER, toEntityId = maraId,
                        terms = "Forty crowns against the Kettle's debt", amount = 40,
                        dueInHours = 72, importance = "HIGH",
                    ),
                ),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I tell her I will cover forty crowns of it.")

        val open = w.store.openCommitments(game.id)
        assertEquals("The obligation must be recorded", 1, open.size)
        assertEquals(40L, open.first().amount)
        assertEquals(CommitmentStatus.OUTSTANDING, open.first().status)

        // A hundred turns later it is still owed, and the model is told so.
        w.drift(100)
        assertEquals("An obligation does not expire quietly", 1, w.store.openCommitments(game.id).size)

        val context = w.contextNow(game.id, "I go and find Mara.")
        assertTrue("The debt must reach the model", context.contains("Forty crowns"))
        assertTrue("Marked overdue once its deadline has passed", context.contains("OVERDUE"))

        // Narrating it settled is not enough; it has to be reported.
        w.behaviour.responder = {
            TurnResponseDto(narrative = "You put the coins on the bar and she takes them.", timeAdvanceMinutes = 5)
        }
        w.pipeline.execute(game.id, "I pay her the forty crowns.")
        assertEquals(
            "Prose alone must not discharge a debt",
            1,
            w.store.openCommitments(game.id).size,
        )

        // Reported, it closes — and stays closed.
        val id = w.store.openCommitments(game.id).first().id
        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "She counts it twice and writes it off.",
                timeAdvanceMinutes = 5,
                commitmentChanges = listOf(CommitmentChangeDto(action = "KEPT", commitmentId = id)),
                presentCharacterIds = listOf(maraId),
            )
        }
        w.pipeline.execute(game.id, "I pay her the forty crowns.")

        assertTrue("A settled debt must leave the open set", w.store.openCommitments(game.id).isEmpty())
        assertEquals(
            CommitmentStatus.KEPT,
            w.store.allCommitments(game.id).first { it.id == id }.status,
        )
    }

    @Test
    fun `a turn cannot settle an obligation that was never made`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = {
            TurnResponseDto(
                narrative = "\"We're square,\" she says.",
                timeAdvanceMinutes = 5,
                commitmentChanges = listOf(CommitmentChangeDto(action = "KEPT", commitmentId = "cmt_invented")),
            )
        }
        val outcome = w.pipeline.execute(game.id, "I tell her we are square.")

        assertTrue(outcome is com.unbound.core.engine.TurnOutcome.Success)
        assertTrue("Nothing may be created from an invented obligation", w.store.allCommitments(game.id).isEmpty())
        assertTrue(
            "And the rejection must be reported rather than swallowed",
            (outcome as com.unbound.core.engine.TurnOutcome.Success).issues.any { it.code == "NO_SUCH_COMMITMENT" },
        )
    }

    // --- the transcript ------------------------------------------------------------------------

    @Test
    fun `the model is shown the conversation in progress, not only a summary of it`() = runTest {
        val w = TestWorld()
        val game = w.newGame()

        w.behaviour.responder = {
            TurnResponseDto(narrative = "\"Ask me again when you're sober,\" she says.", timeAdvanceMinutes = 5)
        }
        w.pipeline.execute(game.id, "I ask her who runs the water rights.")

        val context = w.contextNow(game.id, "I ask again.")

        assertTrue("What the player said must be shown back", context.contains("who runs the water rights"))
        assertTrue("And what the world answered", context.contains("Ask me again when you're sober"))
        assertTrue(context.contains("THE SCENE SO FAR"))
    }
}
