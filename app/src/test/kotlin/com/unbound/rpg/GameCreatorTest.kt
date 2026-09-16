package com.unbound.rpg

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.content.OpeningGenerator
import com.unbound.core.content.SeedWorld
import com.unbound.core.content.Settings
import com.unbound.core.content.WorldGenerator
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.model.RequestType
import com.unbound.core.testing.InMemoryWorldStore
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import com.unbound.rpg.domain.CreationOutcome
import com.unbound.rpg.domain.CreationSpec
import com.unbound.rpg.domain.GameCreator
import com.unbound.rpg.ui.CreationStage
import com.unbound.rpg.ui.CreationState
import com.unbound.core.validate.TurnResponseDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for a bug a player actually hit: the progress state went idle *before* the
 * opening-scene request, so the Begin button re-enabled while the network call was still running
 * and invited repeated taps.
 *
 * These tests pin the emitted sequence, which is the only way that ordering can be checked without
 * a device.
 */
class GameCreatorTest {

    private var idCounter = 0
    private var clockMs = 1_700_000_000_000L
    private val ids: () -> String = { "id" + (++idCounter) }
    private val clock: () -> Long = { clockMs += 1000; clockMs }

    private val store = InMemoryWorldStore()
    private val behaviour = MockBehaviour()
    private val provider = MockAIProvider(behaviour)

    private val creator = GameCreator(
        store = store,
        gameFactory = GameFactory(store, clock, ids),
        pipeline = TurnPipeline(store, provider, clock, ids),
        worldGenerator = WorldGenerator(provider),
        openingGenerator = OpeningGenerator(provider),
        clock = clock,
        idFactory = ids,
    )

    private val spec = CreationSpec(
        name = "Rook Vance", age = 31, gender = "man",
        appearance = "Lean, weather-worn.", personality = "Wary.",
        textModelId = "mock-story", premise = "A foundry city under permanent smoke.",
    )

    private val seed: SeedWorld = Settings.byId("ashmarket")!!

    private fun record(): Pair<MutableList<CreationState>, (CreationState) -> Unit> {
        val states = mutableListOf<CreationState>()
        return states to { state: CreationState -> states += state }
    }

    @Test
    fun `progress stays busy across every network call, including the opening scene`() = runTest {
        val (states, onProgress) = record()

        val outcome = creator.create(seed, spec, opening = null, onProgress = onProgress)
        assertTrue(outcome is CreationOutcome.Created)

        val stages = states.mapNotNull { (it as? CreationState.Working)?.stage }
        assertEquals(
            "Both the build and the opening scene must be covered, in order",
            listOf(CreationStage.BUILDING_WORLD, CreationStage.OPENING_SCENE),
            stages,
        )

        // The crux: nothing goes idle until the last call has returned.
        val idleIndex = states.indexOfFirst { it is CreationState.Idle }
        val openingIndex = states.indexOfFirst { (it as? CreationState.Working)?.stage == CreationStage.OPENING_SCENE }
        assertTrue("The opening scene must be announced before anything goes idle", openingIndex in 0 until idleIndex)
        assertEquals("Creation must finish idle", CreationState.Idle, states.last())
        assertEquals("...exactly once", 1, states.count { it is CreationState.Idle })
    }

    @Test
    fun `the opening scene is a real turn, so the world is playable immediately`() = runTest {
        val (_, onProgress) = record()
        val outcome = creator.create(seed, spec, opening = "You are three drinks into a tab you cannot pay.", onProgress) as CreationOutcome.Created

        assertEquals(1, store.getGame(outcome.game.id)!!.turnNumber)
        assertEquals(1, store.countTurns(outcome.game.id))
        assertTrue(store.recentTurns(outcome.game.id, 1).single().narrative.isNotBlank())
        assertEquals(null, outcome.openingWarning)
    }

    @Test
    fun `a failed opening scene still yields a playable world rather than discarding it`() = runTest {
        behaviour.failWith = AIErrorKind.TIMEOUT
        behaviour.failuresRemaining = 1

        val (states, onProgress) = record()
        val outcome = creator.create(seed, spec, opening = null, onProgress)

        assertTrue(outcome is CreationOutcome.Created)
        outcome as CreationOutcome.Created
        assertNotNull("The player should be told the first paragraph failed", outcome.openingWarning)
        assertEquals("...but the world exists", 3, store.allNpcs(outcome.game.id).size)
        assertNotNull(store.getPlayer(outcome.game.id))
        assertEquals("Creation must still end idle, not stuck busy", CreationState.Idle, states.last())
    }

    @Test
    fun `world generation reports progress and records its usage`() = runTest {
        behaviour.rawResponder = {
            """
            {"name":"Cold Refuge","summary":"A monastery above the snowline.","start_location_key":"hall",
             "locations":[{"key":"hall","name":"The Great Hall","description":"Cold stone.","type":"hall",
                           "exits":[{"label":"through the arch","to":"cells"}]},
                          {"key":"cells","name":"The Cells","description":"Six rooms.","type":"lodging","exits":[]}],
             "npcs":[{"key":"auren","name":"Brother Auren","age":54,"location_key":"hall"}],
             "factions":[],"threads":[]}
            """.trimIndent()
        }

        val (states, onProgress) = record()
        val result = creator.generateWorld(spec, onProgress)

        assertTrue(result.isSuccess)
        assertEquals("Cold Refuge", result.getOrThrow().name)
        assertEquals(CreationStage.FORGING_WORLD, (states.first() as CreationState.Working).stage)
        assertEquals(CreationState.Idle, states.last())

        val usage = store.usageFor(null, 10)
        assertTrue("Generation is a billable call and must be recorded", usage.any { it.requestType == RequestType.WORLD_GENERATION })
    }

    @Test
    fun `a failed world generation reports a failure and keeps nothing`() = runTest {
        behaviour.rawResponder = { "this is not a world" }

        val (states, onProgress) = record()
        val result = creator.generateWorld(spec, onProgress)

        assertTrue(result.isFailure)
        val failure = states.last()
        assertTrue(failure is CreationState.Failed)
        assertEquals(CreationStage.FORGING_WORLD, (failure as CreationState.Failed).stage)
        assertFalse("A failed state is not a busy state", failure.busy)
        assertTrue("No campaign may be left behind", store.listGames().isEmpty())
    }

    @Test
    fun `failed opening generation falls back to the world's own hooks instead of blocking`() = runTest {
        behaviour.rawResponder = { "not json either" }

        val (states, onProgress) = record()
        val suggestions = creator.generateOpenings(seed, spec, onProgress)

        assertTrue("The authored hooks must stand in", suggestions.openings.isNotEmpty())
        assertTrue(suggestions.openings.all { it.situation.isNotBlank() })
        assertEquals(
            "A failed call must not invent a purse; the player decides instead",
            null,
            suggestions.startingCurrency,
        )
        assertEquals("The player must never be stranded on a busy step", CreationState.Idle, states.last())
    }

    @Test
    fun `generated openings are tailored and offered`() = runTest {
        behaviour.rawResponder = {
            """
            {"openings":[
              {"title":"The tab comes due","situation":"Mara sets the book on the bar and taps it.","pressure":"Tonight.","involves":[]},
              {"title":"A sealed letter","situation":"It has your name on it and no sender.","pressure":"The seal is Combine.","involves":[]}
            ],
             "starting_currency":42,
             "starting_currency_reason":"what is left of a bad week",
             "starting_possessions":["a short gutting knife","a ring of keys that fit nothing he owns"]}
            """.trimIndent()
        }

        val suggestions = creator.generateOpenings(seed, spec) { }
        assertEquals(2, suggestions.openings.size)
        assertEquals("The tab comes due", suggestions.openings.first().title)
        assertTrue(suggestions.openings.first().pressure.isNotBlank())
    }

    @Test
    fun `the starting purse is judged per character rather than defaulted`() = runTest {
        behaviour.rawResponder = {
            """
            {"openings":[{"title":"A","situation":"Something happens.","pressure":"","involves":[]}],
             "starting_currency":3,
             "starting_currency_reason":"everything they have",
             "starting_possessions":["a length of iron chain wrapped at the wrist"]}
            """.trimIndent()
        }

        val suggestions = creator.generateOpenings(seed, spec) { }
        assertEquals(3, suggestions.startingCurrency)
        assertEquals("everything they have", suggestions.startingCurrencyReason)
        assertEquals(listOf("a length of iron chain wrapped at the wrist"), suggestions.startingPossessions)
    }

    @Test
    fun `starting possessions become real items the player owns`() = runTest {
        val (_, onProgress) = record()
        val outcome = creator.create(
            seed,
            spec.copy(startingCurrency = 7, startingPossessions = listOf("a short gutting knife", "a sealed letter")),
            opening = null,
            onProgress = onProgress,
        ) as CreationOutcome.Created

        assertEquals(7L, store.getPlayer(outcome.game.id)!!.currency)

        val carried = store.itemsOwnedBy(outcome.game.id, com.unbound.core.model.Ids.PLAYER)
        assertEquals(2, carried.size)
        assertTrue(carried.any { it.name == "a short gutting knife" })
        assertTrue("They must be real entities, not prose", carried.all { it.id.startsWith("item_") })
    }

    @Test
    fun `no purse is decided means the character starts with nothing rather than a magic number`() = runTest {
        val (_, onProgress) = record()
        val outcome = creator.create(seed, spec, opening = null, onProgress = onProgress) as CreationOutcome.Created
        assertEquals(0L, store.getPlayer(outcome.game.id)!!.currency)
    }
}

/**
 * The opening a player chose or wrote must be the opening they get.
 *
 * It used to arrive as the player's typed action, competing with the canonical clock and weather it
 * was meant to override — and losing. It was also echoed into the log as a wall of GM instructions.
 */
class OpeningDirectiveTest {

    private var idCounter = 0
    private var clockMs = 1_700_000_000_000L
    private val ids: () -> String = { "id" + (++idCounter) }
    private val clock: () -> Long = { clockMs += 1000; clockMs }

    private val store = com.unbound.core.testing.InMemoryWorldStore()
    private val behaviour = com.unbound.core.testing.MockBehaviour()
    private val provider = com.unbound.core.testing.MockAIProvider(behaviour)

    private val creator = com.unbound.rpg.domain.GameCreator(
        store = store,
        gameFactory = com.unbound.core.engine.GameFactory(store, clock, ids),
        pipeline = com.unbound.core.engine.TurnPipeline(store, provider, clock, ids),
        worldGenerator = com.unbound.core.content.WorldGenerator(provider),
        openingGenerator = com.unbound.core.content.OpeningGenerator(provider),
        clock = clock,
        idFactory = ids,
    )

    private val spec = com.unbound.rpg.domain.CreationSpec(
        name = "Adrian Voss", age = 30, gender = "man",
        appearance = "Dark hair, green eyes.", personality = "Introvert.",
        textModelId = "mock-story",
    )

    private val seed = com.unbound.core.content.Settings.byId("ashmarket")!!

    private val opening =
        "You are standing around after your shift. The door of a house party bangs and a woman " +
            "comes out alone, glitter on one cheek."

    @Test
    fun `the chosen opening reaches the model as canon, not as the player's action`() = runTest {
        var seenContext = ""
        var seenInput = ""
        behaviour.responder = { input ->
            seenContext = input.context
            seenInput = input.playerInput
            TurnResponseDto(narrative = "The door bangs.", timeAdvanceMinutes = 0)
        }

        creator.create(seed, spec, opening) { }

        assertTrue("The situation must be in the context", seenContext.contains("house party bangs"))
        assertTrue("...stated as what is true", seenContext.contains("HOW THIS BEGINS"))
        assertTrue(
            "...and allowed to override the starting clock and weather it contradicts",
            seenContext.contains("advance time to reach that hour"),
        )
        assertFalse("It is not something the protagonist typed", seenInput.contains("house party"))
    }

    @Test
    fun `the staging instruction is never shown as something the player said`() = runTest {
        behaviour.responder = { TurnResponseDto(narrative = "It begins.", timeAdvanceMinutes = 0) }
        val outcome = creator.create(seed, spec, opening) { } as CreationOutcome.Created

        val first = store.recentTurns(outcome.game.id, 1).single()
        assertEquals("No player input, because the player did not type one", "", first.playerInput)
        assertFalse(first.playerInput.contains("Open the campaign"))
        assertTrue(first.narrative.isNotBlank())
    }

    @Test
    fun `how the story began is remembered`() = runTest {
        behaviour.responder = { TurnResponseDto(narrative = "It begins.", timeAdvanceMinutes = 0) }
        val outcome = creator.create(seed, spec, opening) { } as CreationOutcome.Created

        val remembered = store.memoriesOf(outcome.game.id, com.unbound.core.memory.MemoryRecord.WORLD_OWNER, 20)
        assertTrue(
            "A campaign must be able to answer 'how did this start' much later",
            remembered.any { it.text.contains("house party bangs") },
        )
    }

    @Test
    fun `starting with no opening still produces a scene`() = runTest {
        behaviour.responder = { TurnResponseDto(narrative = "Ashmarket, at eight in the morning.", timeAdvanceMinutes = 0) }
        val outcome = creator.create(seed, spec, opening = null) { } as CreationOutcome.Created

        assertEquals(1, store.getGame(outcome.game.id)!!.turnNumber)
        assertTrue(store.recentTurns(outcome.game.id, 1).single().narrative.isNotBlank())
    }
}
