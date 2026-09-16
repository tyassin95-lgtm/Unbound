package com.unbound.rpg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.content.Settings
import com.unbound.core.model.ImageMode
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import com.unbound.rpg.data.db.UnboundDatabase
import com.unbound.rpg.domain.AppContainer
import com.unbound.rpg.domain.CreationOutcome
import com.unbound.rpg.domain.CreationSpec
import com.unbound.rpg.domain.GameCreator
import com.unbound.rpg.domain.GameSession
import com.unbound.rpg.domain.ImageRequestKind
import com.unbound.rpg.domain.SessionResult
import com.unbound.rpg.ui.CreationState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole path a player actually walks, end to end, over the real database and the real app
 * wiring: describe a world, pick an opening, play, check the journal, ask for a picture, step the
 * world back, export, and come back to it later.
 *
 * No emulator exists in this environment, so this is the closest thing to playing the game that
 * can be run. It exists to catch the failures that only appear when the layers are joined up — a
 * screen reading a field nobody writes, a save that cannot be reopened — which no unit test sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerJourneyTest {

    private lateinit var db: UnboundDatabase
    private lateinit var container: AppContainer
    private val behaviour = MockBehaviour()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnboundDatabase::class.java).allowMainThreadQueries().build()
        container = AppContainer(context, databaseOverride = db, providerOverride = MockAIProvider(behaviour))
    }

    @After
    fun tearDown() = db.close()

    private fun creator() = GameCreator(
        store = container.store,
        gameFactory = container.gameFactory,
        pipeline = container.pipeline,
        worldGenerator = container.worldGenerator,
        openingGenerator = container.openingGenerator,
        clock = container.clock,
        idFactory = container.idFactory,
    )

    private val spec = CreationSpec(
        name = "Rook Vance", age = 31, gender = "man",
        appearance = "Lean, weather-worn.", personality = "Wary but decent.",
        background = "Ran cargo that was not his.",
        goals = listOf("Get clear of the debt"),
        secrets = listOf("He still has the manifest"),
        textModelId = "mock-story",
        imageModelId = "gpt-image-1",
        imageMode = ImageMode.ON_DEMAND,
        premise = "A foundry city under permanent smoke.",
    )

    /** The world-generation and openings calls answer their own schemas; turns use the turn schema. */
    private fun answerCreationCalls() {
        behaviour.rawResponder = { request: AITextRequest ->
            when (request.schemaName) {
                "unbound_world" -> """
                    {"name":"Cinderhold","summary":"A foundry city under permanent smoke.",
                     "start_location_key":"yard",
                     "locations":[{"key":"yard","name":"The Slag Yard","description":"Ash underfoot.","type":"yard",
                                   "exits":[{"label":"through the gate","to":"office"}]},
                                  {"key":"office","name":"The Weighing Office","description":"Ledgers.","type":"office","exits":[]}],
                     "npcs":[{"key":"liv","name":"Liv Orrin","age":29,"location_key":"yard",
                              "description":"Keeps the yard's books."}],
                     "factions":[],"threads":[]}
                """.trimIndent()
                "unbound_openings" -> """
                    {"openings":[{"title":"The short weight","situation":"Liv finds your load light by a tonne.",
                                  "pressure":"The office closes at dusk.","involves":["Liv Orrin"]},
                                 {"title":"A name on a list","situation":"Your name is on the gate roster.",
                                  "pressure":"The gate guard is already walking over.","involves":[]}],
                     "starting_currency":11,
                     "starting_currency_reason":"He was paid short on the last run.",
                     "starting_possessions":["A folded manifest"]}
                """.trimIndent()
                else -> null
            } ?: error("Unexpected schema ${request.schemaName}")
        }
    }

    /**
     * The same world, played on the other provider.
     *
     * The engine is supposed to be entirely independent of which vendor is running, so this asserts
     * the property directly rather than trusting the layering: a campaign marked as Gemini's
     * produces the same state, the same journal and the same continuity, and the requests that
     * leave carry that provider.
     */
    @Test
    fun `a campaign runs identically on either provider`() = runTest {
        val creator = creator()
        answerCreationCalls()
        val seed = creator.generateWorld(spec) {}.getOrThrow()
        behaviour.rawResponder = null

        val outcomes = listOf("openai", "gemini").map { providerId ->
            val outcome = creator.create(
                seed,
                spec.copy(textProviderId = providerId, name = "Rook $providerId"),
                opening = "The gate roster has your name on it.",
            ) {}
            assertTrue(outcome is CreationOutcome.Created)
            val game = (outcome as CreationOutcome.Created).game

            assertEquals("The save must record whose model runs it", providerId, game.textProviderId)

            val session = GameSession(container, game.id)
            repeat(8) { i -> assertTrue(session.submit("I ask around, take $i.") is SessionResult.Narrated) }

            // Every request left with the campaign's own provider on it.
            assertEquals(providerId, (container.provider as MockAIProvider).lastRequest!!.providerId)

            val g = container.store.getGame(game.id)!!
            val journal = session.journal()
            Triple(g.turnNumber, journal.people.size, container.store.countEvents(game.id))
        }

        assertEquals(
            "The world must not depend on whose model narrated it",
            outcomes.first(),
            outcomes.last(),
        )
        // Usage is attributed to the right vendor, since costs are per provider.
        val byProvider = container.store.usageByModel(null).groupBy { it.providerId }
        assertTrue(byProvider.keys.containsAll(setOf("openai", "gemini")))
    }

    @Test
    fun `a player can build a world, play it, look things up, step back, and come back to it`() = runTest {
        val creator = creator()
        answerCreationCalls()

        // 1. Describe a world and have it built.
        val seed = creator.generateWorld(spec) {}.getOrThrow()
        assertEquals("Cinderhold", seed.name)

        // 2. Openings are offered, and they are about this character in this world.
        val suggestions = creator.generateOpenings(seed, spec) {}
        assertFalse("A generated world must still offer somewhere to start", suggestions.openings.isEmpty())
        assertFalse("Openings must be real, not the failure fallback", suggestions.failed)
        assertEquals(
            "Starting money comes from the story, not a constant",
            11L,
            suggestions.startingCurrency?.toLong(),
        )

        // 3. Pick one and begin. From here on, ordinary turns.
        behaviour.rawResponder = null
        val chosen = suggestions.openings.first()
        val states = mutableListOf<CreationState>()
        val outcome = creator.create(
            seed,
            spec.copy(startingCurrency = suggestions.startingCurrency?.toLong(), startingPossessions = suggestions.startingPossessions),
            opening = chosen.situation,
        ) { states += it }
        assertTrue(outcome is CreationOutcome.Created)
        assertFalse("Creation must never go idle mid-flight", states.dropLast(1).any { it == CreationState.Idle })

        val game = (outcome as CreationOutcome.Created).game
        val session = GameSession(container, game.id)

        // The chosen opening is on the record, so it is answerable later.
        assertEquals(1, container.store.getGame(game.id)!!.turnNumber)
        assertTrue(
            "The opening the player chose must be remembered",
            container.store.allMemories(game.id).any { it.text.contains(chosen.situation.take(20)) },
        )

        // 4. Play. Turns land, time moves, history accumulates.
        repeat(20) { i -> assertTrue(session.submit("I ask around about the shortfall, take $i.") is SessionResult.Narrated) }
        val afterPlay = container.store.getGame(game.id)!!
        assertEquals(21, afterPlay.turnNumber)
        assertTrue("Time must move", afterPlay.worldTime.totalMinutes > game.worldTime.totalMinutes)

        // 5. Local commands answer without spending a request.
        val callsBefore = (container.provider as MockAIProvider).callCount
        assertTrue(session.submit("inventory") is SessionResult.LocalAnswer)
        assertTrue(session.submit("status") is SessionResult.LocalAnswer)
        assertEquals("Local commands must not cost a request", callsBefore, (container.provider as MockAIProvider).callCount)

        // 6. The journal reflects the world as it now stands.
        val journal = session.journal()
        assertEquals("Rook Vance", journal.character.name)
        assertFalse("The journal must know where the player is", journal.currentSituation.isBlank())
        assertFalse("The world's people must be in the journal", journal.people.isEmpty())
        assertFalse("The world's places must be in the journal", journal.places.isEmpty())
        assertTrue("Possessions chosen at creation must be carried", journal.inventory.any { it.name.contains("manifest", true) })

        // 7. Pictures can be asked for, and the picker describes the scene as it is now.
        val options = session.imageOptions()
        assertTrue("The picture menu must be usable", options.available)
        assertEquals(afterPlay.turnNumber, options.turnNumber)
        val picture = session.generateImage(ImageRequestKind.Scene)
        assertTrue("A picture request must produce a picture", picture is SessionResult.ImageReady)

        // 8. Step the world back, and say honestly what it cost.
        val preview = session.undoPreview()
        assertNotNull("There must be a restore point after twenty turns", preview)
        assertTrue(preview!!.turnsLost > 0)
        val undone = session.undo()
        assertTrue(undone is SessionResult.Undone)
        assertEquals(preview.toTurn, container.store.getGame(game.id)!!.turnNumber)

        // The world is still playable afterwards.
        assertTrue(session.submit("I go back to the yard.") is SessionResult.Narrated)

        // 9. Export, and re-import as a separate save that shares nothing with the original.
        val exported = container.saveSystem.export(game.id)
        val imported = container.saveSystem.import(exported)
        assertFalse("An imported save must not overwrite the original", imported.id == game.id)
        assertEquals(2, container.store.listGames().size)

        // 10. Come back to it later: a fresh session over the same database resumes the story.
        val resumed = GameSession(container, game.id)
        val history = resumed.history(limit = 50)
        assertEquals(container.store.getGame(game.id)!!.turnNumber, history.size)
        assertFalse("The story must still be there", history.last().narrative.isBlank())
        assertTrue(resumed.submit("I keep going.") is SessionResult.Narrated)
    }
}
