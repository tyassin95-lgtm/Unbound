package com.unbound.rpg.domain

import com.unbound.core.ai.AIUsage
import com.unbound.core.content.OpeningGenerationRequest
import com.unbound.core.content.OpeningGenerator
import com.unbound.core.content.OpeningOption
import com.unbound.core.content.SeedWorld
import com.unbound.core.content.WorldGenerationRequest
import com.unbound.core.content.WorldGenerator
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.engine.WorldStore
import com.unbound.core.model.Appearance
import com.unbound.core.model.GameRecord
import com.unbound.core.model.ImageMode
import com.unbound.core.model.NarrationLength
import com.unbound.core.model.RequestType
import com.unbound.core.model.Tone
import com.unbound.core.model.UsageRecord
import com.unbound.rpg.ui.CreationStage
import com.unbound.rpg.ui.CreationState

/**
 * Orchestrates everything between "Begin" and a playable campaign.
 *
 * This is a plain class rather than logic inside the ViewModel for one reason: the progress state
 * must stay busy across *every* network call, and that is an ordering property which is only
 * testable if the orchestration can be driven without Android. An earlier version set the progress
 * back to idle before the opening-scene request, which re-enabled the button while the request was
 * still in flight and invited the player to tap it again. `GameCreatorTest` now pins the sequence.
 */
class GameCreator(
    private val store: WorldStore,
    private val gameFactory: GameFactory,
    private val pipeline: TurnPipeline,
    private val worldGenerator: WorldGenerator,
    private val openingGenerator: OpeningGenerator,
    private val clock: () -> Long,
    private val idFactory: () -> String,
) {

    suspend fun generateWorld(spec: CreationSpec, onProgress: (CreationState) -> Unit): Result<SeedWorld> {
        onProgress(CreationState.Working(CreationStage.FORGING_WORLD))
        return runCatching {
            worldGenerator.generate(
                WorldGenerationRequest(
                    premise = spec.premise,
                    modelId = spec.textModelId,
                    characterName = spec.name,
                    characterAge = spec.age,
                    characterGender = spec.gender,
                    characterBackground = spec.background,
                    characterPersonality = spec.personality,
                    characterGoal = spec.goals.firstOrNull().orEmpty(),
                    tone = spec.tone,
                    limits = spec.limits,
                ),
            )
        }.onSuccess {
            record(RequestType.WORLD_GENERATION, it.modelId, it.usage)
            onProgress(CreationState.Idle)
        }.onFailure {
            onProgress(CreationState.Failed(it.userMessage(FORGE_FALLBACK), CreationStage.FORGING_WORLD))
        }.map { it.seed }
    }

    /**
     * Openings never block starting a game: a failure falls back to the world's authored hooks, or
     * to none at all, rather than stranding the player on a step they cannot leave.
     */
    suspend fun generateOpenings(
        seed: SeedWorld,
        spec: CreationSpec,
        onProgress: (CreationState) -> Unit,
    ): OpeningSuggestions {
        onProgress(CreationState.Working(CreationStage.FINDING_OPENINGS))
        val result = runCatching {
            openingGenerator.generate(
                OpeningGenerationRequest(
                    seed = seed,
                    modelId = spec.textModelId,
                    characterName = spec.name,
                    characterAge = spec.age,
                    characterGender = spec.gender,
                    characterBackground = spec.background,
                    characterPersonality = spec.personality,
                    characterGoal = spec.goals.firstOrNull().orEmpty(),
                    characterSecret = spec.secrets.firstOrNull().orEmpty(),
                    characterSkills = spec.skills,
                    characterWeaknesses = spec.weaknesses,
                    tone = spec.tone,
                    limits = spec.limits,
                ),
            )
        }
        onProgress(CreationState.Idle)

        val generated = result.getOrNull()
        if (generated == null) {
            // Authored worlds carry static hooks; a generated world may have none, and starting
            // with no offered opening is a legitimate outcome rather than an error. The purse is
            // left unset so the player decides rather than inheriting an arbitrary number.
            return OpeningSuggestions(
                openings = seed.openingHooks.map { hook -> OpeningOption(hook.take(48), hook, "") },
            )
        }
        record(RequestType.WORLD_GENERATION, generated.modelId, generated.usage)
        return OpeningSuggestions(
            openings = generated.openings,
            startingCurrency = generated.startingCurrency,
            startingCurrencyReason = generated.startingCurrencyReason,
            startingPossessions = generated.startingPossessions,
        )
    }

    suspend fun create(
        seed: SeedWorld,
        spec: CreationSpec,
        opening: String?,
        onProgress: (CreationState) -> Unit,
    ): CreationOutcome {
        onProgress(CreationState.Working(CreationStage.BUILDING_WORLD))

        val game = try {
            gameFactory.createGame(
                NewGameRequest(
                    seed = seed,
                    name = spec.name,
                    age = spec.age,
                    gender = spec.gender,
                    appearance = Appearance(summary = spec.appearance),
                    personality = spec.personality,
                    desires = spec.desires,
                    fears = spec.fears,
                    skills = spec.skills,
                    weaknesses = spec.weaknesses,
                    background = spec.background,
                    goals = spec.goals,
                    secrets = spec.secrets,
                    startingCurrency = spec.startingCurrency ?: 0,
                    startingPossessions = spec.startingPossessions,
                    narrationLength = spec.narrationLength,
                    textModelId = spec.textModelId,
                    imageModelId = spec.imageModelId,
                    imageMode = spec.imageMode,
                    tone = spec.tone,
                    limits = spec.limits,
                ),
            )
        } catch (e: Exception) {
            onProgress(CreationState.Failed(e.userMessage(BUILD_FALLBACK), CreationStage.BUILDING_WORLD))
            return CreationOutcome.Failed(e.userMessage(BUILD_FALLBACK))
        }

        // The opening scene is a network call. The progress state must stay busy across it — this
        // is the ordering the test pins.
        onProgress(CreationState.Working(CreationStage.OPENING_SCENE))
        val outcome = pipeline.execute(game.id, gameFactory.openingInstruction(seed, opening))
        onProgress(CreationState.Idle)

        // The world exists and is entirely playable even if only the first paragraph failed, so
        // the player is taken into it rather than losing everything they just built.
        val warning = (outcome as? TurnOutcome.Failure)?.message
        return CreationOutcome.Created(game, warning)
    }

    private suspend fun record(type: RequestType, modelId: String, usage: AIUsage) {
        store.recordUsage(
            UsageRecord(
                id = idFactory(),
                gameId = null,
                turnId = null,
                timestampMs = clock(),
                requestType = type,
                modelId = modelId,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                cachedTokens = usage.cachedInputTokens,
                success = true,
            ),
        )
    }

    private fun Throwable.userMessage(fallback: String) = message?.takeIf { it.isNotBlank() } ?: fallback

    private companion object {
        const val FORGE_FALLBACK =
            "The world could not be built. Nothing was saved — try again, or describe it differently."
        const val BUILD_FALLBACK = "The world could not be created."
    }
}

/** Everything creation needs, with no dependency on the UI's draft type. */
data class CreationSpec(
    val name: String,
    val age: Int,
    val gender: String,
    val appearance: String,
    val personality: String,
    val desires: String = "",
    val fears: String = "",
    val skills: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val background: String = "",
    val goals: List<String> = emptyList(),
    val secrets: List<String> = emptyList(),
    /**
     * Null means "nobody has decided yet" — the opening generator judges an amount that fits this
     * character in this world, rather than every protagonist starting with the same purse.
     */
    val startingCurrency: Long? = null,
    val startingPossessions: List<String> = emptyList(),
    val textModelId: String,
    val imageModelId: String? = null,
    val imageMode: ImageMode = ImageMode.ON_DEMAND,
    val tone: Tone? = null,
    val narrationLength: NarrationLength = NarrationLength.NORMAL,
    val limits: List<String> = emptyList(),
    val premise: String = "",
)

/**
 * What the opening call produced: the ways in, and what this particular character plausibly has in
 * their pockets. The purse is a *suggestion* — the player sees it and can change it before
 * beginning.
 */
data class OpeningSuggestions(
    val openings: List<OpeningOption> = emptyList(),
    val startingCurrency: Int? = null,
    val startingCurrencyReason: String = "",
    val startingPossessions: List<String> = emptyList(),
)

sealed interface CreationOutcome {
    data class Created(val game: GameRecord, val openingWarning: String?) : CreationOutcome
    data class Failed(val message: String) : CreationOutcome
}
