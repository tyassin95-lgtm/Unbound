package com.unbound.core

import com.unbound.core.model.*
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.StateOp
import com.unbound.core.validate.StateValidator
import com.unbound.core.validate.TurnResponseDto
import com.unbound.core.validate.ValidationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The validator is the boundary between "the model said so" and "it is true". These tests encode
 * the rejection cases named in §43 directly.
 */
class StateValidatorTest {

    private val validator = StateValidator()

    private val here = LocationRecord(
        id = "loc_here", gameId = "g", name = "Here", description = "",
        exits = mapOf("out" to "loc_next"),
    )
    private val next = LocationRecord(id = "loc_next", gameId = "g", name = "Next door", description = "")
    private val far = LocationRecord(id = "loc_far", gameId = "g", name = "Another city", description = "")

    private val player = PlayerRecord(
        gameId = "g", name = "Rook", age = 31, gender = "man",
        appearance = Appearance("lean"), personality = "wary",
        currency = 112, currentLocationId = "loc_here",
    )

    private val mara = NpcRecord(
        id = "npc_mara", gameId = "g", name = "Mara", age = 43,
        appearance = Appearance("heavy-set"), currentLocationId = "loc_here",
    )
    private val ghost = mara.copy(id = "npc_ghost", name = "Ghost", alive = false)

    private val ring = ItemRecord(id = "item_ring", gameId = "g", name = "silver ring", ownerId = "npc_mara", unique = true)

    private fun ctx(vararg extraLocations: LocationRecord) = ValidationContext(
        game = GameRecord(
            id = "g", title = "t", settingId = "s", settingName = "S",
            createdAtEpochMs = 0, updatedAtEpochMs = 0, currentLocationId = "loc_here", textModelId = "m",
        ),
        player = player,
        world = WorldRecord(gameId = "g", summary = "", region = "", era = "", weather = "clear"),
        npcs = mapOf(mara.id to mara, ghost.id to ghost),
        locations = (listOf(here, next) + extraLocations).associateBy { it.id },
        factions = emptyMap(),
        items = mapOf(ring.id to ring),
        reachableLocationIds = setOf("loc_here", "loc_next"),
    )

    private fun response(vararg changes: StateChangeDto, minutes: Int = 5) =
        TurnResponseDto(narrative = "Something happens.", timeAdvanceMinutes = minutes, stateChanges = changes.toList())

    @Test
    fun `the canonical gold example is rejected`() {
        // Database says 112. The model tries to spend 500.
        val result = validator.validate(
            response(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -500, reason = "paid")),
            ctx(),
        )
        assertTrue(result.ops.isEmpty())
        assertEquals("INSUFFICIENT_FUNDS", result.issues.single().code)
        assertFalse("A rejected spend must not cost the player their turn", result.hasFatal)
    }

    @Test
    fun `an affordable spend is accepted`() {
        val result = validator.validate(
            response(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -5, reason = "bribe")),
            ctx(),
        )
        assertEquals(1, result.ops.size)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `two spends that are individually affordable but jointly are not`() {
        val result = validator.validate(
            response(
                StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -100, reason = "a"),
                StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -100, reason = "b"),
            ),
            ctx(),
        )
        assertEquals(1, result.ops.size)
        assertEquals("INSUFFICIENT_FUNDS", result.issues.single().code)
    }

    @Test
    fun `time cannot run backwards and that is fatal`() {
        val result = validator.validate(response(minutes = -30), ctx())
        assertTrue(result.hasFatal)
        assertEquals("TIME_REVERSAL", result.issues.first().code)
    }

    @Test
    fun `an empty narrative is fatal`() {
        val result = validator.validate(TurnResponseDto(narrative = "  "), ctx())
        assertTrue(result.hasFatal)
    }

    @Test
    fun `a dead npc cannot act`() {
        val result = validator.validate(
            TurnResponseDto(
                narrative = "x",
                npcActions = listOf(com.unbound.core.validate.NpcActionDto(npcId = "npc_ghost", action = "waves")),
            ),
            ctx(),
        )
        assertEquals("DEAD_NPC_ACTING", result.issues.single().code)
    }

    @Test
    fun `a dead npc cannot be killed twice`() {
        val result = validator.validate(
            response(StateChangeDto(type = "NPC_DEATH", entityId = "npc_ghost", reason = "again")),
            ctx(),
        )
        assertTrue(result.ops.isEmpty())
        assertEquals("ALREADY_DEAD", result.issues.single().code)
    }

    @Test
    fun `a unique item cannot go to two places at once`() {
        val result = validator.validate(
            response(
                StateChangeDto(type = "ITEM_TRANSFER", itemId = "item_ring", entityId = "npc_mara", targetId = "player"),
                StateChangeDto(type = "ITEM_TRANSFER", itemId = "item_ring", entityId = "npc_mara", locationId = "loc_next"),
            ),
            ctx(),
        )
        assertEquals(1, result.ops.size)
        assertEquals("ITEM_DUPLICATION", result.issues.single().code)
    }

    @Test
    fun `an item cannot be given away by someone who does not have it`() {
        val result = validator.validate(
            response(StateChangeDto(type = "ITEM_TRANSFER", itemId = "item_ring", entityId = "player", targetId = "npc_mara")),
            ctx(),
        )
        assertEquals("WRONG_OWNER", result.issues.single().code)
    }

    @Test
    fun `a duplicate unique item cannot be conjured`() {
        val result = validator.validate(
            response(StateChangeDto(type = "ITEM_CREATE", itemName = "silver ring", entityId = "player")),
            ctx(),
        )
        assertEquals("DUPLICATE_UNIQUE_ITEM", result.issues.single().code)
    }

    @Test
    fun `teleportation to a distant location is rejected but a long journey is allowed`() {
        val instant = validator.validate(
            response(StateChangeDto(type = "PLAYER_MOVE", locationId = "loc_far"), minutes = 2),
            ctx(far),
        )
        assertEquals("TELEPORTATION", instant.issues.single().code)

        val journey = validator.validate(
            response(StateChangeDto(type = "PLAYER_MOVE", locationId = "loc_far"), minutes = 240),
            ctx(far),
        )
        assertTrue(journey.issues.isEmpty())
        assertEquals(StateOp.PlayerMove("loc_far"), journey.ops.single())
    }

    @Test
    fun `world rules can legitimately override the travel rule`() {
        val supernatural = ctx(far).copy(specialRules = listOf("Portal stones allow instant travel between marked sites."))
        val result = validator.validate(
            response(StateChangeDto(type = "PLAYER_MOVE", locationId = "loc_far"), minutes = 1),
            supernatural,
        )
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `references to entities that do not exist are dropped`() {
        val result = validator.validate(
            response(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "npc_invented", amount = 5, reason = "x")),
            ctx(),
        )
        assertTrue(result.ops.isEmpty())
        assertEquals("DANGLING_REFERENCE", result.issues.single().code)
    }

    @Test
    fun `a relationship cannot change without a reason or beyond the per-turn limit`() {
        val noReason = validator.validate(
            TurnResponseDto(
                narrative = "x",
                stateChanges = listOf(
                    StateChangeDto(type = "NPC_RELATIONSHIP_CHANGE", targetId = "npc_mara", changes = mapOf("trust" to -10)),
                ),
            ),
            ctx(),
        )
        assertEquals("UNEXPLAINED_RELATIONSHIP_CHANGE", noReason.issues.single().code)

        val tooBig = validator.validate(
            response(
                StateChangeDto(
                    type = "NPC_RELATIONSHIP_CHANGE", targetId = "npc_mara",
                    changes = mapOf("trust" to -90), reason = "betrayal",
                ),
            ),
            ctx(),
        )
        assertEquals("IMPLAUSIBLE_RELATIONSHIP_SWING", tooBig.issues.single().code)
    }

    @Test
    fun `an npc who is absent and has no source cannot learn something first-hand`() {
        val absent = mara.copy(currentLocationId = "loc_next")
        val context = ctx().copy(npcs = mapOf(absent.id to absent))
        val result = validator.validate(
            TurnResponseDto(
                narrative = "x",
                knowledgeChanges = listOf(
                    com.unbound.core.validate.KnowledgeChangeDto(
                        knowerId = "npc_mara", factKey = "k", statement = "the player stole the ring", certainty = "KNOWN",
                    ),
                ),
            ),
            context,
        )
        assertEquals("IMPOSSIBLE_KNOWLEDGE", result.issues.single().code)
    }

    @Test
    fun `an unknown change type is discarded rather than crashing the turn`() {
        val result = validator.validate(response(StateChangeDto(type = "REWRITE_UNIVERSE")), ctx())
        assertTrue(result.ops.isEmpty())
        assertFalse(result.hasFatal)
        assertEquals("UNKNOWN_CHANGE_TYPE", result.issues.single().code)
    }
}
