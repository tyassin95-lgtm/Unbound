package com.unbound.core

import com.unbound.core.content.Characters
import com.unbound.core.content.SeedWorld
import com.unbound.core.content.Settings
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.model.GameRecord
import com.unbound.core.testing.InMemoryWorldStore
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour

/**
 * Shared harness. Ids and the clock are deterministic counters so that a failing test produces the
 * same ids on every run and can be diffed.
 */
class TestWorld(
    seedId: String = "ashmarket",
    val behaviour: MockBehaviour = MockBehaviour(),
) {
    private var idCounter = 0
    private var clockMs = 1_700_000_000_000L

    val store = InMemoryWorldStore()
    val provider = MockAIProvider(behaviour)
    val seed: SeedWorld = Settings.byId(seedId)!!

    val ids: () -> String = { "id${(++idCounter).toString().padStart(5, '0')}" }
    val clock: () -> Long = { clockMs += 1000; clockMs }

    val factory = GameFactory(store, clock, ids)
    val pipeline = TurnPipeline(store, provider, clock, ids)

    suspend fun newGame(
        template: String = "smuggler",
        modelId: String = "mock-story",
    ): GameRecord {
        val c = Characters.byId(template)!!
        return factory.createGame(
            NewGameRequest(
                seed = seed,
                name = c.name,
                age = c.age,
                gender = c.gender,
                appearance = c.appearance,
                personality = c.personality,
                desires = c.desires,
                fears = c.fears,
                skills = c.skills,
                weaknesses = c.weaknesses,
                background = c.background,
                goals = c.goals,
                secrets = c.secrets,
                startingCurrency = c.startingCurrency,
                textModelId = modelId,
            ),
        )
    }

    /** Resolves a seed npc key (e.g. "mara") to the generated id for this campaign. */
    suspend fun npcId(gameId: String, seedKey: String): String {
        val name = seed.npcs.first { it.key == seedKey }.name
        return store.persistentNpcs(gameId, 200).first { it.name == name }.id
    }

    suspend fun locationId(gameId: String, seedKey: String): String {
        val name = seed.locations.first { it.key == seedKey }.name
        return store.locationsByIds(gameId, seed.locations.map { "" }).firstOrNull { it.name == name }?.id
            ?: store.discoveredLocations(gameId, 200).first { it.name == name }.id
    }
}
