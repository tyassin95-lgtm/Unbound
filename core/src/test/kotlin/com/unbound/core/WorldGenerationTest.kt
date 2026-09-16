package com.unbound.core

import com.unbound.core.content.GeneratedExitDto
import com.unbound.core.content.GeneratedFactionDto
import com.unbound.core.content.GeneratedLocationDto
import com.unbound.core.content.GeneratedNpcDto
import com.unbound.core.content.GeneratedThreadDto
import com.unbound.core.content.GeneratedWorldDto
import com.unbound.core.content.OpeningGenerationRequest
import com.unbound.core.content.OpeningGenerator
import com.unbound.core.content.Settings
import com.unbound.core.content.WorldGenerationRequest
import com.unbound.core.content.WorldGenerator
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.model.Appearance
import com.unbound.core.model.Importance
import com.unbound.core.model.ThreadType
import com.unbound.core.model.Tone
import com.unbound.core.safety.ContentGuard
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A generated world is untrusted input, exactly like a turn response. These tests encode the
 * failures actually worth defending against — the ones a model makes when asked for eight
 * interconnected locations in one shot.
 */
class WorldSanitiserTest {

    private val generator = WorldGenerator(MockAIProvider())

    private val request = WorldGenerationRequest(
        premise = "A mountain monastery sheltering refugees as the passes close.",
        modelId = "mock-story",
        characterName = "Odo",
        characterAge = 38,
        characterGender = "man",
    )

    private fun world(
        locations: List<GeneratedLocationDto>,
        npcs: List<GeneratedNpcDto> = emptyList(),
        factions: List<GeneratedFactionDto> = emptyList(),
        threads: List<GeneratedThreadDto> = emptyList(),
        start: String = "hall",
    ) = GeneratedWorldDto(
        name = "Cold Refuge",
        summary = "A monastery above the snowline.",
        startLocationKey = start,
        locations = locations,
        npcs = npcs,
        factions = factions,
        threads = threads,
    )

    private fun loc(key: String, vararg exits: Pair<String, String>) = GeneratedLocationDto(
        key = key,
        name = key.replaceFirstChar { it.uppercase() },
        description = "A place.",
        type = "room",
        exits = exits.map { GeneratedExitDto(it.first, it.second) },
    )

    @Test
    fun `exits to places that were never defined are dropped, not invented`() {
        val seed = generator.sanitise(
            world(listOf(loc("hall", "out" to "courtyard", "down" to "the_undercity_of_nowhere"), loc("courtyard"))),
            request,
        )
        val hall = seed.locations.first { it.key == "hall" }
        assertEquals("Only the real exit survives", mapOf("out" to "courtyard"), hall.exits)
    }

    @Test
    fun `a location cannot exit into itself`() {
        val seed = generator.sanitise(world(listOf(loc("hall", "around" to "hall"), loc("yard"))), request)
        assertTrue(seed.locations.first { it.key == "hall" }.exits.isEmpty())
    }

    @Test
    fun `a missing or invented start location falls back to a real one`() {
        val invented = generator.sanitise(world(listOf(loc("hall"), loc("yard")), start = "somewhere_else"), request)
        assertEquals("hall", invented.startLocationKey)

        val missing = generator.sanitise(world(listOf(loc("hall"), loc("yard")), start = ""), request)
        assertEquals("hall", missing.startLocationKey)
    }

    @Test
    fun `an NPC placed nowhere is put at the start rather than dropped`() {
        val seed = generator.sanitise(
            world(
                locations = listOf(loc("hall"), loc("yard")),
                npcs = listOf(
                    GeneratedNpcDto(key = "brother", name = "Brother Auren", age = 54, locationKey = "the_moon"),
                ),
            ),
            request,
        )
        assertEquals(1, seed.npcs.size)
        assertEquals("hall", seed.npcs.single().locationKey)
    }

    @Test
    fun `an NPC with no usable age gets an explicit adult one rather than an inferred one`() {
        val seed = generator.sanitise(
            world(
                locations = listOf(loc("hall")),
                npcs = listOf(
                    GeneratedNpcDto(key = "a", name = "No Age", age = 0, locationKey = "hall"),
                    GeneratedNpcDto(key = "b", name = "Silly Age", age = 900, locationKey = "hall"),
                    GeneratedNpcDto(key = "c", name = "Real Age", age = 61, locationKey = "hall"),
                ),
            ),
            request,
        )
        val byName = seed.npcs.associateBy { it.name }
        assertEquals(WorldGenerator.DEFAULT_NPC_AGE, byName.getValue("No Age").age)
        assertEquals(WorldGenerator.DEFAULT_NPC_AGE, byName.getValue("Silly Age").age)
        assertEquals(61, byName.getValue("Real Age").age)
        assertTrue(
            "Every generated character must carry an explicit, sane age",
            seed.npcs.all { it.age > 0 && ContentGuard.isAdult(it.age) },
        )
    }

    @Test
    fun `an NPC in a faction that does not exist simply has no faction`() {
        val seed = generator.sanitise(
            world(
                locations = listOf(loc("hall")),
                npcs = listOf(GeneratedNpcDto(key = "a", name = "Auren", age = 40, locationKey = "hall", factionKey = "ghosts")),
                factions = listOf(GeneratedFactionDto(key = "order", name = "The Order", purpose = "keep the gate")),
            ),
            request,
        )
        assertEquals(null, seed.npcs.single().factionKey)
    }

    @Test
    fun `duplicate keys are collapsed and sizes are capped`() {
        val seed = generator.sanitise(
            world(
                locations = (1..30).map { loc("room$it") } + listOf(loc("room1")),
                npcs = (1..30).map { GeneratedNpcDto(key = "n$it", name = "Person $it", age = 30, locationKey = "room1") },
            ),
            request,
        )
        assertEquals(seed.locations.size, seed.locations.map { it.key }.distinct().size)
        assertTrue(seed.locations.size <= WorldGenerator.MAX_LOCATIONS)
        assertTrue(seed.npcs.size <= WorldGenerator.MAX_NPCS)
    }

    @Test
    fun `a world with no usable locations is refused rather than half-built`() {
        var threw = false
        try {
            generator.sanitise(world(emptyList()), request)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("An unusable world must fail loudly, not produce a broken campaign", threw)
    }

    @Test
    fun `thread type and importance fall back safely on unrecognised values`() {
        val seed = generator.sanitise(
            world(
                locations = listOf(loc("hall")),
                threads = listOf(
                    GeneratedThreadDto(title = "The passes", description = "Snow.", type = "BLIZZARD", importance = "APOCALYPTIC"),
                ),
            ),
            request,
        )
        assertEquals(ThreadType.OTHER, seed.threads.single().type)
        assertEquals(Importance.MEDIUM, seed.threads.single().importance)
    }

    @Test
    fun `the player's chosen tone wins over the model's suggestion`() {
        val seed = generator.sanitise(
            world(listOf(loc("hall"))).copy(tone = "HUMOROUS"),
            request.copy(tone = Tone.VERY_DARK),
        )
        assertEquals(Tone.VERY_DARK, seed.toneHint)
    }

    @Test
    fun `blank fields are backfilled so the world is never empty on screen`() {
        val seed = generator.sanitise(
            GeneratedWorldDto(startLocationKey = "hall", locations = listOf(loc("hall"))),
            request,
        )
        assertTrue(seed.name.isNotBlank())
        assertTrue(seed.summary.isNotBlank())
        assertTrue(seed.currencyName.isNotBlank())
        assertTrue(seed.weather.isNotBlank())
        assertTrue(seed.blurb.isNotBlank())
    }
}

/**
 * A generated world must be playable through the ordinary engine — that is the whole point of
 * funnelling it through `SeedWorld` rather than giving custom worlds their own path.
 */
class GeneratedWorldPlayableTest {

    @Test
    fun `a sanitised generated world creates a campaign and plays turns`() = runTest {
        val w = TestWorld()
        val generator = WorldGenerator(w.provider)

        val dto = GeneratedWorldDto(
            name = "Cold Refuge",
            region = "the high passes",
            era = "the third winter",
            summary = "A monastery above the snowline, full of people who cannot go home.",
            currencyName = "marks",
            weather = "driving snow",
            startLocationKey = "hall",
            locations = listOf(
                GeneratedLocationDto("hall", "The Great Hall", "Cold stone, one fire.", "hall", listOf(GeneratedExitDto("through the arch", "cells"))),
                GeneratedLocationDto("cells", "The Guest Cells", "Six rooms, all occupied.", "lodging", listOf(GeneratedExitDto("back to the hall", "hall"))),
            ),
            npcs = listOf(
                GeneratedNpcDto("auren", "Brother Auren", 54, "man", "keeps the stores", "Stooped, chapped hands.", "Patient.", "To make the grain last.", "He has been skimming the count.", "hall"),
                GeneratedNpcDto("vess", "Vess", 29, "woman", "refugee", "Sharp-faced, frostbitten ear.", "Wary.", "To get over the pass.", "She is not who her papers say.", "cells"),
            ),
            factions = listOf(GeneratedFactionDto("order", "The Order", "Keeps the refuge.", listOf("Survive the winter"), "Respected, resented.")),
            threads = listOf(GeneratedThreadDto("The grain count", "The stores will not last.", "People starve by spring.", "SURVIVAL", "HIGH", listOf("auren"))),
        )
        val seed = generator.sanitise(dto, WorldGenerationRequest("premise", "mock-story", characterName = "Odo", characterAge = 38, characterGender = "man"))

        val game = w.factory.createGame(
            NewGameRequest(
                seed = seed, name = "Odo Wren", age = 38, gender = "man",
                appearance = Appearance("Stooped, careful hands."), personality = "Quiet.",
                textModelId = "mock-story",
            ),
        )

        assertEquals("Cold Refuge", game.settingName)
        assertEquals(2, w.store.allNpcs(game.id).size)
        assertEquals(2, w.store.allLocations(game.id).size)
        assertEquals(1, w.store.allThreads(game.id, 20).size)
        assertEquals("marks", w.store.getWorld(game.id)!!.currencyName)

        // Each generated NPC starts knowing their own secret and nobody else's.
        val auren = w.store.allNpcs(game.id).first { it.name == "Brother Auren" }
        val vess = w.store.allNpcs(game.id).first { it.name == "Vess" }
        assertTrue(w.store.knowledgeOf(game.id, auren.id, 10).any { it.statement.contains("skimming") })
        assertFalse(w.store.knowledgeOf(game.id, vess.id, 10).any { it.statement.contains("skimming") })

        // And it plays.
        val outcome = w.pipeline.execute(game.id, "I go and look at the grain stores.")
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(1, w.store.getGame(game.id)!!.turnNumber)
    }
}

class OpeningGeneratorTest {

    @Test
    fun `openings are parsed, deduplicated and capped`() = runTest {
        val behaviour = MockBehaviour()
        val provider = MockAIProvider(behaviour)
        var seenContext = ""
        behaviour.rawResponder = { request ->
            seenContext = request.dynamicContext
            """
            {"openings":[
              {"title":"The tab comes due","situation":"Mara sets the book on the bar.","pressure":"She wants it tonight.","involves":["mara"]},
              {"title":"A letter with your name","situation":"Someone left it on your table.","pressure":"The seal is Combine.","involves":[]},
              {"title":"Duplicate","situation":"Someone left it on your table.","pressure":"","involves":[]},
              {"title":"Blank one","situation":"","pressure":"","involves":[]},
              {"title":"Third","situation":"The door opens and the room goes quiet.","pressure":"","involves":[]}
            ]}
            """.trimIndent()
        }

        val result = OpeningGenerator(provider).generate(
            OpeningGenerationRequest(
                seed = Settings.byId("ashmarket")!!,
                modelId = "mock-story",
                characterName = "Rook Vance",
                characterAge = 31,
                characterGender = "man",
                characterGoal = "Clear the debt",
            ),
        )

        assertEquals("Blank and duplicate openings are discarded", 3, result.openings.size)
        assertEquals("The tab comes due", result.openings.first().title)
        assertTrue(result.openings.all { it.situation.isNotBlank() })

        // The world and the character must both reach the prompt, or the openings would be generic.
        assertTrue(seenContext.contains("Mara Venn"))
        assertTrue(seenContext.contains("Rook Vance"))
        assertTrue(seenContext.contains("Clear the debt"))
        assertTrue("The starting location should be named", seenContext.contains("The Black Kettle"))
    }

    @Test
    fun `a malformed response surfaces as an error rather than a silent empty list`() = runTest {
        val behaviour = MockBehaviour()
        behaviour.rawResponder = { "not json" }
        var threw = false
        try {
            OpeningGenerator(MockAIProvider(behaviour)).generate(
                OpeningGenerationRequest(
                    seed = Settings.byId("ashmarket")!!, modelId = "mock-story",
                    characterName = "Rook", characterAge = 31, characterGender = "man",
                ),
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("The caller must be able to fall back deliberately", threw)
    }
}
