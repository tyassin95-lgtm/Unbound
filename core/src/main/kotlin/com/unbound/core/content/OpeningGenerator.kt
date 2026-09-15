package com.unbound.core.content

import com.unbound.core.ai.AITextProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.ai.AIUsage
import com.unbound.core.model.Tone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Generates the ways a story could begin (§12 step 3, §16 "the player may reject suggested hooks").
 *
 * These are produced *after* the character and the world are both known, so they are specific to
 * this person in this place — "the harbour master has your name on a list" rather than a generic
 * tavern opening. One cheap call, and the player can always ignore all of them and write their own.
 *
 * The authored settings ship static hooks as a fallback, so world creation still works offline or
 * when this call fails; a failure here must never block starting a game.
 */
class OpeningGenerator(
    private val provider: AITextProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
) {

    suspend fun generate(request: OpeningGenerationRequest): OpeningGenerationResult {
        val response = provider.generateText(
            AITextRequest(
                modelId = request.modelId,
                stableSystemPrompt = SYSTEM_PROMPT,
                dynamicContext = buildContext(request),
                userInput = "Give me the ways this could begin.",
                jsonSchema = WorldGenerationSchema.openingsSchema(),
                schemaName = "unbound_openings",
                maxOutputTokens = 1800,
            ),
        )

        val dto = json.decodeFromString(GeneratedOpeningsDto.serializer(), extractJson(response.text))
        val openings = dto.openings
            .filter { it.situation.isNotBlank() }
            .map {
                OpeningOption(
                    title = it.title.ifBlank { it.situation.take(40) },
                    situation = it.situation.trim(),
                    pressure = it.pressure.trim(),
                )
            }
            .distinctBy { it.situation }
            .take(MAX_OPENINGS)

        return OpeningGenerationResult(
            openings = openings,
            // Negative money is never a sensible start; beyond that the model's judgement stands,
            // and the player can edit it before they begin.
            startingCurrency = dto.startingCurrency.coerceIn(0, MAX_STARTING_CURRENCY),
            startingCurrencyReason = dto.startingCurrencyReason.trim(),
            startingPossessions = dto.startingPossessions.map { it.trim() }.filter { it.isNotBlank() }.take(5),
            usage = response.usage,
            modelId = response.modelId,
        )
    }

    private fun buildContext(request: OpeningGenerationRequest) = buildString {
        val seed = request.seed
        appendLine("## THE WORLD")
        appendLine("${seed.name} — ${seed.region}, ${seed.era}")
        appendLine(seed.summary)
        appendLine("Weather: ${seed.weather}. Money: ${seed.currencyName}.")
        if (seed.conflicts.isNotEmpty()) appendLine("Already running: ${seed.conflicts.joinToString("; ")}")
        appendLine()

        appendLine("## WHERE PLAY BEGINS")
        val start = seed.locations.firstOrNull { it.key == seed.startLocationKey } ?: seed.locations.first()
        appendLine("${start.name}: ${start.description}")
        appendLine()

        appendLine("## PEOPLE HERE")
        seed.npcs.forEach { npc ->
            appendLine("- ${npc.name} [${npc.key}], ${npc.age}, ${npc.occupation}. Wants: ${npc.wants}")
        }
        appendLine()

        if (seed.factions.isNotEmpty()) {
            appendLine("## POWERS")
            seed.factions.forEach { appendLine("- ${it.name} [${it.key}]: ${it.purpose}") }
            appendLine()
        }

        if (seed.threads.isNotEmpty()) {
            appendLine("## SITUATIONS ALREADY IN MOTION")
            seed.threads.forEach { appendLine("- ${it.title}: ${it.description} At stake: ${it.stakes}") }
            appendLine()
        }

        appendLine("## THE CHARACTER")
        appendLine("${request.characterName}, age ${request.characterAge}, ${request.characterGender}.")
        if (request.characterBackground.isNotBlank()) appendLine("Background: ${request.characterBackground}")
        if (request.characterPersonality.isNotBlank()) appendLine("Personality: ${request.characterPersonality}")
        if (request.characterGoal.isNotBlank()) appendLine("Wants: ${request.characterGoal}")
        if (request.characterSecret.isNotBlank()) appendLine("Secret: ${request.characterSecret}")
        if (request.characterSkills.isNotEmpty()) appendLine("Good at: ${request.characterSkills.joinToString(", ")}")
        if (request.characterWeaknesses.isNotEmpty()) appendLine("Bad at: ${request.characterWeaknesses.joinToString(", ")}")

        request.tone?.let {
            appendLine()
            appendLine("## TONE")
            appendLine("${it.display} — ${it.guidance}")
        }

        if (request.limits.isNotEmpty()) {
            appendLine()
            appendLine("## HARD LIMITS — respect these exactly")
            request.limits.forEach { appendLine("- $it") }
        }
    }

    private fun extractJson(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{")) return text
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.startsWith("{") }?.let { return it }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else text
    }

    companion object {
        const val MAX_OPENINGS = 6

        /** A ceiling on a generated purse, so one stray zero cannot trivialise a campaign. */
        const val MAX_STARTING_CURRENCY = 100_000

        val SYSTEM_PROMPT = """
You propose the ways one specific character's story could begin in one specific place.

Give five or six openings that differ in *kind*, not in wording: one social, one dangerous, one
quiet, one that starts mid-problem, one that starts with something the character already did. Draw
on the people, factions and situations you were given — an opening that ignores them is wasted.

Each opening is a moment, not a mission. It puts the character somewhere with something pressing
nearby and stops. Never state what the character thinks, feels, intends or decides, and never give
them a goal they did not bring with them. No opening may open with the character being handed a
quest by a stranger.

Also judge what this character has on them right now: how much money, and which specific objects.
Read it off who they are and what they have been doing, not off a table. A dock hand three days
from payday and a merchant's heir carry very different amounts, and either may carry none.

Return only the structured object.
""".trim()
    }
}

data class OpeningGenerationRequest(
    val seed: SeedWorld,
    val modelId: String,
    val characterName: String,
    val characterAge: Int,
    val characterGender: String,
    val characterBackground: String = "",
    val characterPersonality: String = "",
    val characterGoal: String = "",
    val characterSecret: String = "",
    val characterSkills: List<String> = emptyList(),
    val characterWeaknesses: List<String> = emptyList(),
    val tone: Tone? = null,
    val limits: List<String> = emptyList(),
)

data class OpeningOption(val title: String, val situation: String, val pressure: String)

data class OpeningGenerationResult(
    val openings: List<OpeningOption>,
    /** What this character plausibly has on them, judged from who they are — not a fixed default. */
    val startingCurrency: Int,
    val startingCurrencyReason: String,
    val startingPossessions: List<String>,
    val usage: AIUsage,
    val modelId: String,
)

@Serializable
internal data class GeneratedOpeningsDto(
    val openings: List<GeneratedOpeningDto> = emptyList(),
    @SerialName("starting_currency") val startingCurrency: Int = 0,
    @SerialName("starting_currency_reason") val startingCurrencyReason: String = "",
    @SerialName("starting_possessions") val startingPossessions: List<String> = emptyList(),
)

@Serializable
internal data class GeneratedOpeningDto(
    val title: String = "",
    val situation: String = "",
    val pressure: String = "",
    @SerialName("involves") val involves: List<String> = emptyList(),
)
