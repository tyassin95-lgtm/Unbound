package com.unbound.core.content

import com.unbound.core.ai.AITextProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.ai.AIUsage
import com.unbound.core.model.Importance
import com.unbound.core.model.ThreadType
import com.unbound.core.model.Tone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Builds a playable world from a sentence the player wrote (§16 "custom world", §17).
 *
 * The generated world goes through exactly the same door as the six authored ones: it becomes a
 * [SeedWorld] and is handed to `GameFactory`. Nothing downstream knows or cares whether a campaign
 * started from an authored setting or an invented one.
 *
 * Generated content is **not trusted**. A model asked for eight locations will occasionally wire an
 * exit to a place it never defined, put an NPC nowhere, or forget a start location. [sanitise]
 * repairs what it can and drops what it cannot, on the same principle as the state validator:
 * prefer a slightly smaller world over a broken one.
 */
class WorldGenerator(
    private val provider: AITextProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
) {

    suspend fun generate(request: WorldGenerationRequest): WorldGenerationResult {
        val response = provider.generateText(
            AITextRequest(
                modelId = request.modelId,
                stableSystemPrompt = SYSTEM_PROMPT,
                dynamicContext = buildContext(request),
                userInput = "Build this world.",
                jsonSchema = WorldGenerationSchema.schema(),
                schemaName = "unbound_world",
                maxOutputTokens = 6000,
            ),
        )

        val dto = json.decodeFromString(GeneratedWorldDto.serializer(), extractJson(response.text))
        val seed = sanitise(dto, request)
        return WorldGenerationResult(seed, response.usage, response.modelId)
    }

    private fun buildContext(request: WorldGenerationRequest) = buildString {
        appendLine("## THE PLAYER'S PREMISE")
        appendLine(request.premise)
        appendLine()
        appendLine("## WHO WILL LIVE IN IT")
        appendLine("${request.characterName}, age ${request.characterAge}, ${request.characterGender}.")
        if (request.characterBackground.isNotBlank()) appendLine("Background: ${request.characterBackground}")
        if (request.characterPersonality.isNotBlank()) appendLine("Personality: ${request.characterPersonality}")
        if (request.characterGoal.isNotBlank()) appendLine("Wants: ${request.characterGoal}")
        appendLine()
        appendLine("Build the world around this person without building it *for* them: they should")
        appendLine("have somewhere to stand and something pressing nearby, not a prepared quest.")
        if (request.tone != null) {
            appendLine()
            appendLine("## TONE")
            appendLine("${request.tone.display} — ${request.tone.guidance}")
        }
        if (request.limits.isNotEmpty()) {
            appendLine()
            appendLine("## HARD LIMITS — respect these exactly")
            request.limits.forEach { appendLine("- $it") }
        }
    }

    /**
     * Turns a model's answer into a world that cannot break the engine.
     *
     * Every repair here corresponds to a failure actually worth defending against: dangling exits,
     * homeless NPCs, a missing or invented start location, duplicate keys, and ages that would
     * defeat the content guard.
     */
    internal fun sanitise(dto: GeneratedWorldDto, request: WorldGenerationRequest): SeedWorld {
        val locations = dto.locations
            .filter { it.key.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_LOCATIONS)
        require(locations.isNotEmpty()) { "The generated world had no usable locations." }

        val locationKeys = locations.map { it.key }.toSet()

        val factions = dto.factions
            .filter { it.key.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_FACTIONS)
        val factionKeys = factions.map { it.key }.toSet()

        // A start location the model forgot, or invented, falls back to the first real one.
        val startKey = dto.startLocationKey.takeIf { it in locationKeys } ?: locations.first().key

        val seedLocations = locations.map { loc ->
            SeedLocation(
                key = loc.key,
                name = loc.name,
                description = loc.description.ifBlank { loc.name },
                type = loc.type.ifBlank { "place" },
                // Exits to places that were never defined are dropped, not invented.
                exits = loc.exits
                    .filter { it.to in locationKeys && it.to != loc.key && it.label.isNotBlank() }
                    .associate { it.label to it.to },
                hazards = loc.hazards.filter { it.isNotBlank() },
                hidden = loc.hiddenDetails.filter { it.isNotBlank() },
            )
        }

        val npcs = dto.npcs
            .filter { it.key.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.key }
            .take(MAX_NPCS)
            .map { npc ->
                SeedNpc(
                    key = npc.key,
                    name = npc.name,
                    // The content guard requires an explicit age. A model that omitted or
                    // fumbled it gets a plainly adult default rather than an inference.
                    age = if (npc.age in 1..120) npc.age else DEFAULT_NPC_AGE,
                    gender = npc.gender,
                    occupation = npc.occupation,
                    appearance = npc.appearance.ifBlank { "Unremarkable, and easy to overlook." },
                    personality = npc.personality.ifBlank { "Guarded." },
                    wants = npc.wants,
                    secret = npc.secret.ifBlank { "Keeps something back about ${npc.name}'s own past." },
                    locationKey = npc.locationKey.takeIf { it in locationKeys } ?: startKey,
                    factionKey = npc.factionKey?.takeIf { it in factionKeys },
                )
            }
        val npcKeys = npcs.map { it.key }.toSet()

        val threads = dto.threads
            .filter { it.title.isNotBlank() }
            .take(MAX_THREADS)
            .map { thread ->
                SeedThread(
                    title = thread.title,
                    description = thread.description.ifBlank { thread.title },
                    stakes = thread.stakes,
                    type = ThreadType.entries.firstOrNull { it.name == thread.type.uppercase() } ?: ThreadType.OTHER,
                    importance = Importance.entries.firstOrNull { it.name == thread.importance.uppercase() } ?: Importance.MEDIUM,
                    involvedKeys = thread.involvedKeys.filter { it in npcKeys || it in factionKeys },
                )
            }

        return SeedWorld(
            id = "custom_" + dto.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "world" },
            name = dto.name.ifBlank { "Custom world" },
            blurb = dto.blurb.ifBlank { request.premise.take(120) },
            region = dto.region.ifBlank { dto.name },
            era = dto.era.ifBlank { "the present day" },
            summary = dto.summary.ifBlank { request.premise },
            currencyName = dto.currencyName.ifBlank { "coins" },
            weather = dto.weather.ifBlank { "still and overcast" },
            socialHierarchy = dto.socialHierarchy,
            economy = dto.economy,
            laws = dto.laws,
            religion = dto.religion,
            dangers = dto.dangers.filter { it.isNotBlank() },
            history = dto.history.filter { it.isNotBlank() },
            conflicts = dto.conflicts.filter { it.isNotBlank() },
            specialRules = dto.specialRules.filter { it.isNotBlank() },
            toneHint = request.tone ?: Tone.entries.firstOrNull { it.name == dto.tone.uppercase() } ?: Tone.NORMAL,
            startLocationKey = startKey,
            locations = seedLocations,
            npcs = npcs,
            factions = factions.map {
                SeedFaction(
                    key = it.key,
                    name = it.name,
                    purpose = it.purpose,
                    objectives = it.objectives.filter { o -> o.isNotBlank() },
                    reputation = it.reputation,
                )
            },
            threads = threads,
            openingHooks = emptyList(),
        )
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
        const val MAX_LOCATIONS = 9
        const val MAX_NPCS = 7
        const val MAX_FACTIONS = 4
        const val MAX_THREADS = 3
        const val DEFAULT_NPC_AGE = 35

        val SYSTEM_PROMPT = """
You build the starting region of a persistent role-playing world from a player's premise.

Build a place, not a plot. The world must already be running before the player arrives: people with
their own business, quarrels that predate them, and pressures that will continue whether or not
they engage.

Requirements:
- 5 to 9 locations, tightly connected and all within reach of each other on foot. This is a
  starting district, not a continent. Every exit must point at a location you actually defined.
- 4 to 7 named people who live here. Each needs a concrete appearance, something they want, and a
  secret that is theirs alone. Give every one of them an explicit numeric age.
- 2 to 4 factions with real, conflicting objectives.
- 1 to 3 situations already in motion that the player has not caused and is not the centre of.
- Concrete, specific detail over atmosphere. Name things. Say what people do with their hands.

Do not write the player into the world, do not prepare quests for them, and do not describe them.
Return only the structured object.
""".trim()
    }
}

data class WorldGenerationRequest(
    val premise: String,
    val modelId: String,
    val characterName: String,
    val characterAge: Int,
    val characterGender: String,
    val characterBackground: String = "",
    val characterPersonality: String = "",
    val characterGoal: String = "",
    val tone: Tone? = null,
    val limits: List<String> = emptyList(),
)

data class WorldGenerationResult(val seed: SeedWorld, val usage: AIUsage, val modelId: String)

// --- wire format --------------------------------------------------------------------------------

@Serializable
data class GeneratedWorldDto(
    val name: String = "",
    val blurb: String = "",
    val region: String = "",
    val era: String = "",
    val summary: String = "",
    @SerialName("currency_name") val currencyName: String = "",
    val weather: String = "",
    @SerialName("social_hierarchy") val socialHierarchy: String = "",
    val economy: String = "",
    val laws: String = "",
    val religion: String = "",
    val dangers: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
    @SerialName("special_rules") val specialRules: List<String> = emptyList(),
    val tone: String = "NORMAL",
    @SerialName("start_location_key") val startLocationKey: String = "",
    val locations: List<GeneratedLocationDto> = emptyList(),
    val npcs: List<GeneratedNpcDto> = emptyList(),
    val factions: List<GeneratedFactionDto> = emptyList(),
    val threads: List<GeneratedThreadDto> = emptyList(),
)

@Serializable
data class GeneratedExitDto(val label: String = "", val to: String = "")

@Serializable
data class GeneratedLocationDto(
    val key: String = "",
    val name: String = "",
    val description: String = "",
    val type: String = "",
    val exits: List<GeneratedExitDto> = emptyList(),
    val hazards: List<String> = emptyList(),
    @SerialName("hidden_details") val hiddenDetails: List<String> = emptyList(),
)

@Serializable
data class GeneratedNpcDto(
    val key: String = "",
    val name: String = "",
    val age: Int = 0,
    val gender: String = "",
    val occupation: String = "",
    val appearance: String = "",
    val personality: String = "",
    val wants: String = "",
    val secret: String = "",
    @SerialName("location_key") val locationKey: String = "",
    @SerialName("faction_key") val factionKey: String? = null,
)

@Serializable
data class GeneratedFactionDto(
    val key: String = "",
    val name: String = "",
    val purpose: String = "",
    val objectives: List<String> = emptyList(),
    val reputation: String = "",
)

@Serializable
data class GeneratedThreadDto(
    val title: String = "",
    val description: String = "",
    val stakes: String = "",
    val type: String = "OTHER",
    val importance: String = "MEDIUM",
    @SerialName("involved_keys") val involvedKeys: List<String> = emptyList(),
)
