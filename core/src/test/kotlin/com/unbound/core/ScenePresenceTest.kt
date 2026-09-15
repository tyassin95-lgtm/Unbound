package com.unbound.core

import com.unbound.core.model.Ids
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.NpcActionDto
import com.unbound.core.validate.ScenePresence
import com.unbound.core.validate.TurnResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePresenceTest {

    private val known = setOf("npc_liv", "npc_marisol", "npc_caleb", "npc_faraway")
    private val here = "loc_corner"

    private fun resolve(response: TurnResponseDto, stored: Set<String> = emptySet()) =
        ScenePresence.participants(
            response.presentCharacterIds, response.npcActions, response.events, here, known, stored,
        )

    @Test
    fun `a character the narrator declares present is present, whatever their stored location says`() {
        // The reported bug: Liv walked into the scene and her stored location never caught up.
        val response = TurnResponseDto(narrative = "x", presentCharacterIds = listOf("npc_liv"))
        assertTrue("npc_liv" in resolve(response))
    }

    @Test
    fun `acting in a scene means being in it`() {
        val response = TurnResponseDto(
            narrative = "x",
            npcActions = listOf(NpcActionDto(npcId = "npc_liv", action = "takes a step back")),
        )
        assertTrue("npc_liv" in resolve(response))
    }

    @Test
    fun `being the actor or target of an event here means being in it`() {
        val response = TurnResponseDto(
            narrative = "x",
            events = listOf(
                EventDto(type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = "npc_liv", locationId = here),
                EventDto(type = "NPC_MOVED", actorId = "npc_marisol", locationId = null),
            ),
        )
        val present = resolve(response)
        assertTrue("npc_liv" in present)
        assertTrue("An event with no location is taken as happening here", "npc_marisol" in present)
    }

    @Test
    fun `an event explicitly placed elsewhere does not make its cast present`() {
        val response = TurnResponseDto(
            narrative = "x",
            events = listOf(
                EventDto(type = "NPC_ACTED_OFFSCREEN", actorId = "npc_faraway", locationId = "loc_somewhere_else"),
            ),
        )
        assertFalse("npc_faraway" in resolve(response))
    }

    @Test
    fun `ids that are not characters in this game are ignored`() {
        val response = TurnResponseDto(
            narrative = "x",
            presentCharacterIds = listOf("npc_invented", "loc_corner", "player"),
            npcActions = listOf(NpcActionDto(npcId = "npc_also_invented", action = "waves")),
        )
        assertEquals(emptySet<String>(), resolve(response))
    }

    @Test
    fun `someone the narrator walks out of the scene is a participant but does not remain`() {
        val response = TurnResponseDto(
            narrative = "x",
            presentCharacterIds = listOf("npc_liv", "npc_caleb"),
            npcActions = listOf(
                NpcActionDto(npcId = "npc_caleb", action = "walks off down Ninth", movesToLocationId = "loc_ninth"),
            ),
        )
        val participants = resolve(response)
        val remaining = ScenePresence.remaining(
            response.presentCharacterIds, response.npcActions, response.events, here, known,
        )

        assertTrue("They were here while it happened, so they witnessed it", "npc_caleb" in participants)
        assertFalse("...but they are not here at the end of the turn", "npc_caleb" in remaining)
        assertTrue("npc_liv" in remaining)
    }

    @Test
    fun `an action that moves someone to where the player already is keeps them present`() {
        val response = TurnResponseDto(
            narrative = "x",
            npcActions = listOf(NpcActionDto(npcId = "npc_liv", action = "crosses over", movesToLocationId = here)),
        )
        assertTrue(
            "npc_liv" in ScenePresence.remaining(
                response.presentCharacterIds, response.npcActions, response.events, here, known,
            ),
        )
    }

    @Test
    fun `stored presence is still honoured when the narrator says nothing`() {
        val response = TurnResponseDto(narrative = "x")
        assertEquals(setOf("npc_marisol"), resolve(response, stored = setOf("npc_marisol")))
    }
}
