package com.unbound.core

import com.unbound.core.command.CommandParser
import com.unbound.core.command.PlayerCommand
import com.unbound.core.images.ImagePromptBuilder
import com.unbound.core.journal.JournalBuilder
import com.unbound.core.knowledge.Certainty
import com.unbound.core.model.*
import com.unbound.core.safety.ContentGuard
import com.unbound.core.simulation.SimulationInput
import com.unbound.core.simulation.WorldSimulator
import com.unbound.core.threads.ThreadEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser()

    @Test
    fun `bare conveniences are recognised`() {
        assertTrue(parser.parse("status") is PlayerCommand.Status)
        assertTrue(parser.parse("Journal") is PlayerCommand.Journal)
        assertTrue(parser.parse("  inventory ") is PlayerCommand.Inventory)
        assertTrue(parser.parse("undo") is PlayerCommand.Undo)
    }

    @Test
    fun `natural language that merely contains a command word stays narrative`() {
        // The whole product breaks if these eat the player's turn.
        assertTrue(parser.parse("I check the status of the wound") is PlayerCommand.Narrative)
        assertTrue(parser.parse("I write in my journal") is PlayerCommand.Narrative)
        assertTrue(parser.parse("I save the child") is PlayerCommand.Narrative)
        assertTrue(parser.parse("I look through my inventory of excuses") is PlayerCommand.Narrative)
    }

    @Test
    fun `tone and limits are parsed`() {
        assertEquals(Tone.VERY_DARK, (parser.parse("Tone: very dark") as PlayerCommand.SetTone).tone)
        assertEquals(true, (parser.parse("tone: darker") as PlayerCommand.ShiftTone).darker)
        val limits = parser.parse("Limits: no spiders, no sexual violence") as PlayerCommand.SetLimits
        assertEquals(listOf("no spiders", "no sexual violence"), limits.limits)
    }

    @Test
    fun `image requests are recognised`() {
        assertEquals("Mara", (parser.parse("Image of Mara") as PlayerCommand.RequestImage).subject)
        assertEquals("the tavern", (parser.parse("picture of the tavern") as PlayerCommand.RequestImage).subject)
    }

    @Test
    fun `entity mentions resolve by full and first name without false positives`() {
        val candidates = mapOf("npc_1" to "Mara Venn", "npc_2" to "Coll Aster", "npc_3" to "Warden Surrin")
        assertEquals(setOf("npc_1"), parser.resolveMentions("I ask Mara about the note.", candidates))
        assertEquals(setOf("npc_1"), parser.resolveMentions("I take Mara Venn aside.", candidates))
        assertEquals(setOf("npc_1", "npc_2"), parser.resolveMentions("Mara and Coll are arguing.", candidates))
        assertEquals(emptySet<String>(), parser.resolveMentions("I look at the marble floor.", candidates))
    }
}

class ThreadEngineTest {
    private val engine = ThreadEngine()

    private fun thread(momentum: Int, deadlineDays: Int?, importance: Importance = Importance.MEDIUM) = ThreadRecord(
        id = "thr_1", gameId = "g", type = ThreadType.DEBT, title = "The note",
        description = "d", lastActivityWorldMinutes = WorldTime.DEFAULT.totalMinutes,
        importance = importance, momentum = momentum,
        deadlineWorldMinutes = deadlineDays?.let { WorldTime.DEFAULT.totalMinutes + it * WorldTime.MINUTES_PER_DAY },
    )

    @Test
    fun `an ignored thread loses momentum and eventually stalls`() {
        var t = thread(momentum = 50, deadlineDays = null)
        val later = WorldTime.DEFAULT.plusMinutes(60 * WorldTime.MINUTES_PER_DAY)
        repeat(6) { t = engine.advance(t, later, playerEngagedThisTurn = false).thread }
        assertTrue("momentum should decay: ${t.momentum}", t.momentum < 50)
        assertEquals(ThreadStatus.STALLED, t.status)
    }

    @Test
    fun `a passed deadline resolves the situation without the player, badly if it was neglected`() {
        val past = WorldTime.DEFAULT.plusMinutes(20 * WorldTime.MINUTES_PER_DAY)

        val neglected = engine.advance(thread(momentum = 5, deadlineDays = 14), past, false)
        assertEquals(ThreadStatus.FAILED, neglected.thread.status)
        assertTrue(neglected.worldNote!!.contains("collapsed"))

        val ownMomentum = engine.advance(thread(momentum = 85, deadlineDays = 14), past, false)
        assertEquals(ThreadStatus.TRANSFORMED, ownMomentum.thread.status)
    }

    @Test
    fun `engaging a thread revives it`() {
        val stalled = thread(momentum = 10, deadlineDays = null).copy(status = ThreadStatus.STALLED)
        val out = engine.advance(stalled, WorldTime.DEFAULT.plusMinutes(1000), playerEngagedThisTurn = true)
        assertEquals(ThreadStatus.ADVANCING, out.thread.status)
        assertTrue(out.thread.momentum > 10)
    }

    @Test
    fun `important threads are given tighter deadlines`() {
        val critical = engine.defaultDeadline(ThreadType.DEBT, Importance.CRITICAL, WorldTime.DEFAULT)!!
        val ordinary = engine.defaultDeadline(ThreadType.DEBT, Importance.MEDIUM, WorldTime.DEFAULT)!!
        assertTrue(critical < ordinary)
    }
}

class WorldSimulatorTest {

    private var counter = 0
    private val ids = { "sim${++counter}" }

    @Test
    fun `simulating a long gap costs no model calls and still moves the world`() {
        val sim = WorldSimulator(ids, random = kotlin.random.Random(7))
        val npc = NpcRecord(
            id = "npc_1", gameId = "g", name = "Coll", age = 29,
            appearance = Appearance("wiry"), currentLocationId = "loc_a",
            schedule = DailySchedule(byHour = (0..23).associateWith { "loc_b" }),
        )
        val out = sim.simulate(
            SimulationInput(
                previous = WorldTime.DEFAULT,
                now = WorldTime.DEFAULT.plusMinutes(3 * WorldTime.MINUTES_PER_DAY),
                playerLocationId = "loc_player",
                weather = "clear",
                npcs = listOf(npc),
                factions = emptyList(),
                threads = emptyList(),
                rumors = emptyList(),
            ),
        )
        assertEquals("The NPC should have followed their schedule", "loc_b", out.npcUpdates.single().currentLocationId)
        assertTrue(out.notes.isNotEmpty())
    }

    @Test
    fun `no elapsed time means nothing happens`() {
        val sim = WorldSimulator(ids)
        val out = sim.simulate(
            SimulationInput(
                previous = WorldTime.DEFAULT, now = WorldTime.DEFAULT,
                playerLocationId = "loc_a", weather = "clear",
                npcs = emptyList(), factions = emptyList(), threads = emptyList(), rumors = emptyList(),
            ),
        )
        assertTrue(out.notes.isEmpty())
        assertTrue(out.npcUpdates.isEmpty())
    }

    @Test
    fun `ambient crowd members are not simulated`() {
        val sim = WorldSimulator(ids, random = kotlin.random.Random(3))
        val ambient = NpcRecord(
            id = "npc_crowd", gameId = "g", name = "a dockhand", age = 30,
            appearance = Appearance("nondescript"), currentLocationId = "loc_a",
            tier = NpcTier.AMBIENT,
            schedule = DailySchedule(byHour = (0..23).associateWith { "loc_b" }),
        )
        val out = sim.simulate(
            SimulationInput(
                previous = WorldTime.DEFAULT,
                now = WorldTime.DEFAULT.plusMinutes(2 * WorldTime.MINUTES_PER_DAY),
                playerLocationId = "loc_player", weather = "clear",
                npcs = listOf(ambient), factions = emptyList(), threads = emptyList(), rumors = emptyList(),
            ),
        )
        assertTrue("Ambient NPCs must not generate simulation work", out.npcUpdates.isEmpty())
    }
}

class ContentGuardTest {

    private val adult = NpcRecord(id = "npc_a", gameId = "g", name = "Mara", age = 43, appearance = Appearance("x"), currentLocationId = "l")
    private val minor = NpcRecord(id = "npc_b", gameId = "g", name = "Piet", age = 17, appearance = Appearance("x"), currentLocationId = "l")
    private val player = PlayerRecord(
        gameId = "g", name = "Rook", age = 31, gender = "man",
        appearance = Appearance("lean"), personality = "wary", currentLocationId = "l",
    )

    @Test
    fun `an all-adult scene costs nothing`() {
        assertNull(ContentGuard.sceneClause(player, listOf(adult)))
        assertTrue(ContentGuard.sexualContentPermitted(player, listOf(adult)))
    }

    @Test
    fun `a minor in the scene produces a naming constraint`() {
        val clause = ContentGuard.sceneClause(player, listOf(adult, minor))!!
        assertTrue(clause.contains("Piet"))
        assertTrue(clause.contains("npc_b"))
        assertTrue(clause.contains("17"))
        assertFalse(ContentGuard.sexualContentPermitted(player, listOf(adult, minor)))
    }

    @Test
    fun `a character cannot enter the world without an explicit age`() {
        var threw = false
        try {
            ContentGuard.requireExplicitAge("Someone", null)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("never infers"))
        }
        assertTrue(threw)
    }

    @Test
    fun `a protagonist under eighteen cannot be created`() {
        var threw = false
        try {
            com.unbound.core.engine.NewGameRequest(
                seed = com.unbound.core.content.Settings.ALL.first(),
                name = "Someone", age = 16, gender = "x",
                appearance = Appearance("y"), personality = "z", textModelId = "m",
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}

class ImagePromptTest {
    private val builder = ImagePromptBuilder()
    private val world = WorldRecord(gameId = "g", summary = "", region = "Ashmarket", era = "the Combine", weather = "smoke")

    private val appearance = Appearance(
        summary = "lean and weather-worn", face = "broken nose", skin = "olive", hair = "black cropped",
        eyes = "dark brown", build = "wiry", scars = listOf("burn on the left forearm"),
        clothing = "oilskin coat",
    )

    @Test
    fun `the identity clause is stable across images so the character stays recognisable`() {
        val first = builder.forCharacter("Rook", 31, "man", appearance, world, "Ashmarket", null, isFirstImage = true)
        val later = builder.forCharacter("Rook", 31, "man", appearance, world, "Ashmarket", "Standing in the rain, soaked.", isFirstImage = false)

        assertEquals("The identity must not drift between images", first.identityClause, later.identityClause)
        assertNotEquals("...but the situation must", first.prompt, later.prompt)
        assertTrue(first.isReferenceCreation)
        assertFalse(later.isReferenceCreation)
    }

    @Test
    fun `age is stated explicitly and concretely, and traits are facts not poetry`() {
        val p = builder.forCharacter("Rook", 31, "man", appearance, world, "Ashmarket", null, isFirstImage = true)
        assertTrue(p.prompt.contains("31 year old"))
        assertTrue(p.prompt.contains("broken nose"))
        assertTrue(p.prompt.contains("scar: burn on the left forearm"))
    }

    @Test
    fun `changing appearance produces a new version and a different cache key`() {
        val before = builder.forCharacter("Rook", 31, "man", appearance, world, "Ashmarket", null, isFirstImage = true)
        val after = builder.forCharacter(
            "Rook", 31, "man", appearance.copy(hair = "shaved", version = 2), world, "Ashmarket", null, isFirstImage = false,
        )
        assertNotEquals(before.cacheKey, after.cacheKey)
        assertEquals(2, after.appearanceVersion)
        assertTrue(after.identityClause.contains("shaved"))
    }

    @Test
    fun `a location keeps its architecture and only changes conditions`() {
        val loc = LocationRecord(id = "loc_1", gameId = "g", name = "The Black Kettle", description = "Low-ceilinged public house", type = "tavern")
        val calm = builder.forLocation(loc, world, WorldTime.of(0, 1, 1, 12), "Ashmarket", isFirstImage = true)
        val burned = builder.forLocation(loc.copy(condition = "fire-damaged"), world, WorldTime.of(0, 1, 1, 22), "Ashmarket", isFirstImage = false)

        assertEquals(calm.identityClause, burned.identityClause)
        assertTrue(burned.prompt.contains("fire-damaged"))
        assertTrue(calm.prompt.contains("afternoon"))
        assertTrue(burned.prompt.contains("night"))
    }
}

class JournalTest {

    @Test
    fun `the journal is derived from state and never leaks hidden knowledge`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = { _ ->
            com.unbound.core.validate.TurnResponseDto(
                narrative = "You talk to Mara for a while.",
                timeAdvanceMinutes = 20,
                events = listOf(
                    com.unbound.core.validate.EventDto(
                        type = "PLAYER_SPOKE_TO_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player spoke with Mara Venn.", importance = "MEDIUM",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I introduce myself to Mara.")

        val journal = JournalBuilder(w.store).build(game.id)

        assertTrue(journal.currentSituation.contains("Rook Vance"))
        assertEquals("Rook Vance", journal.character.name)
        assertTrue("Mara should now be a known person", journal.people.any { it.name == "Mara Venn" })

        // Mara's own secret is in the database, held by her. It must never appear in the player's journal.
        val maraSecret = w.store.knowledgeOf(game.id, maraId, 10).first { it.secret }
        assertFalse(
            "The journal must not expose an NPC's private knowledge",
            journal.people.any { p -> p.knownFacts.any { it.contains(maraSecret.statement) } },
        )

        // A hidden thread surfaces only once the player runs into someone caught up in it, and
        // then only as something suspected — talking to Mara is not the same as being told.
        val encountered = journal.threads.singleOrNull()
        assertNotNull("Meeting Mara should surface the situation she is caught in", encountered)
        assertTrue("...as a suspicion, not as established fact", encountered!!.uncertain)

        // Anything the player has had nothing to do with stays out of the journal entirely.
        val untouched = w.store.allThreads(game.id, 50).filter { it.visibility == ThreadVisibility.HIDDEN }
        assertTrue(journal.threads.none { entry -> untouched.any { it.id == entry.id } })
    }

    @Test
    fun `status is compact rather than a database dump`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        repeat(5) { w.pipeline.execute(game.id, "I get on with it.") }

        val status = JournalBuilder(w.store).status(game.id)
        assertTrue(status.contains("Rook Vance"))
        assertTrue(status.contains("crowns"))
        assertTrue("Status must stay short: ${status.length} chars", status.length < 900)
    }
}

class KnowledgeScopeTest {

    @Test
    fun `a secret reaches only its named witnesses`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")
        val collId = w.npcId(game.id, "coll")
        val surrinId = w.npcId(game.id, "surrin")

        w.behaviour.responder = { _ ->
            com.unbound.core.validate.TurnResponseDto(
                narrative = "You tell Mara, quietly, with Coll listening.",
                timeAdvanceMinutes = 10,
                events = listOf(
                    com.unbound.core.validate.EventDto(
                        type = "SECRET_DISCOVERED", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player admitted to Mara that the shipment was taken by a friend.",
                        importance = "HIGH", knowledgeScope = "SECRET", witnessIds = listOf(maraId, collId),
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I tell Mara the truth about the shipment.")

        val statement = "shipment was taken by a friend"
        assertTrue(w.store.knowledgeOf(game.id, maraId, 50).any { it.statement.contains(statement) })
        assertTrue(w.store.knowledgeOf(game.id, collId, 50).any { it.statement.contains(statement) })
        assertFalse(
            "Warden Surrin was not a witness and must not know",
            w.store.knowledgeOf(game.id, surrinId, 50).any { it.statement.contains(statement) },
        )
    }

    @Test
    fun `a public event becomes a rumor that spreads, arriving as hearsay`() = runTest {
        val w = TestWorld()
        val game = w.newGame()
        val maraId = w.npcId(game.id, "mara")

        w.behaviour.responder = { _ ->
            com.unbound.core.validate.TurnResponseDto(
                narrative = "It happens in front of the whole room.",
                timeAdvanceMinutes = 15,
                events = listOf(
                    com.unbound.core.validate.EventDto(
                        type = "PLAYER_ATTACKED_NPC", actorId = Ids.PLAYER, targetId = maraId,
                        summary = "The player struck Mara Venn in front of the Kettle.",
                        importance = "CRITICAL", knowledgeScope = "LOCAL_RUMOR",
                    ),
                ),
            )
        }
        w.pipeline.execute(game.id, "I hit her.")

        val rumors = w.store.activeRumors(game.id, 20)
        assertTrue("A local-rumor event must put something into circulation", rumors.isNotEmpty())
        assertTrue(rumors.first().virality > 50)

        // The people who were there know it first-hand, not as hearsay.
        val maraFacts = w.store.knowledgeOf(game.id, maraId, 50)
        assertTrue(maraFacts.any { it.certainty == Certainty.KNOWN })
    }
}
