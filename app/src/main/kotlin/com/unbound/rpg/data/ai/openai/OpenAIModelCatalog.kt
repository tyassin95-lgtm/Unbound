package com.unbound.rpg.data.ai.openai

import com.unbound.core.ai.AIModelCatalog
import com.unbound.core.ai.ModelCapability
import com.unbound.core.ai.ModelProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Model discovery (§6, §7).
 *
 * The list of models is fetched live from the player's own account, because that — not a constant
 * in this file — is the truth about what their key can reach. What cannot be discovered from
 * `GET /v1/models` is *capability*: the endpoint returns ids and nothing about structured output,
 * vision or pricing.
 *
 * So capability is inferred from a small, explicitly-versioned rule table over id *families*
 * rather than exact model names. When OpenAI ships a new model in a known family it is classified
 * correctly without a code change; when it ships something genuinely new, the conservative
 * fallback applies and the player is told plainly rather than being handed a model that will fail
 * mid-turn.
 *
 * Prices are estimates for the usage screen only, are labelled as such in the UI, and are absent
 * (rather than guessed) for unrecognised models.
 */
class OpenAIModelCatalog(private val client: OpenAIClient) : AIModelCatalog {

    private var cached: List<ModelProfile>? = null

    override suspend fun listModels(): List<ModelProfile> = listModels(forceRefresh = false)

    suspend fun listModels(forceRefresh: Boolean): List<ModelProfile> {
        if (!forceRefresh) cached?.let { return it }
        val response = client.getJson("/models")
        val ids = (response["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        val profiles = ids.map { classify(it, fromLive = true) }
            .filter { it.capabilities.isNotEmpty() }
            .sortedWith(compareByDescending<ModelProfile> { it.supportsStructuredOutput }.thenBy { it.id })
        cached = profiles
        return profiles
    }

    override suspend fun profileFor(modelId: String): ModelProfile? =
        cached?.firstOrNull { it.id == modelId } ?: classify(modelId, fromLive = false).takeIf { it.capabilities.isNotEmpty() }

    /** Used when the device is offline so the settings screen can still show something honest. */
    fun knownProfiles(): List<ModelProfile> = FALLBACK_IDS.map { classify(it, fromLive = false) }

    private fun classify(id: String, fromLive: Boolean): ModelProfile {
        val lower = id.lowercase()

        val isImage = lower.startsWith("gpt-image") || lower.startsWith("dall-e")
        val isAudioOnly = lower.contains("whisper") || lower.contains("tts") || lower.contains("audio") ||
            lower.contains("realtime") || lower.contains("transcribe")
        val isEmbedding = lower.startsWith("text-embedding")
        val isModeration = lower.contains("moderation")
        val isLegacyCompletion = lower.startsWith("davinci") || lower.startsWith("babbage") || lower.startsWith("curie")

        if (isAudioOnly || isEmbedding || isModeration || isLegacyCompletion) {
            return ModelProfile(id, id, emptySet(), fromLiveCatalog = fromLive)
        }

        if (isImage) {
            return ModelProfile(
                id = id,
                displayName = prettify(id),
                capabilities = setOf(ModelCapability.IMAGE_GENERATION),
                recommendedFor = "Portraits and scene illustration",
                knownLimitations = if (lower.startsWith("dall-e")) {
                    listOf("Returns a temporary URL rather than image data, so images cannot be cached on this device.")
                } else {
                    emptyList()
                },
                imageCostEach = IMAGE_PRICES[priceFamily(lower)],
                fromLiveCatalog = fromLive,
            )
        }

        // Text families. Structured output is the capability the game actually requires, and it is
        // the one that separates the current families from the older ones.
        val structured = STRUCTURED_OUTPUT_FAMILIES.any { lower.startsWith(it) }
        val reasoning = REASONING_FAMILIES.any { lower.startsWith(it) }
        // Reasoning models reject a temperature parameter; sending one is a 400.
        val temperature = !reasoning
        val vision = structured && !lower.contains("instruct")

        val capabilities = buildSet {
            add(ModelCapability.TEXT)
            if (structured) add(ModelCapability.STRUCTURED_OUTPUT)
            if (vision) add(ModelCapability.VISION)
            if (temperature) add(ModelCapability.TEMPERATURE)
            if (reasoning) add(ModelCapability.REASONING)
        }

        val price = PRICES[priceFamily(lower)]

        return ModelProfile(
            id = id,
            displayName = prettify(id),
            capabilities = capabilities,
            contextTokens = null,
            recommendedFor = when {
                reasoning -> "Careful, slower storytelling. Higher cost per turn."
                lower.contains("mini") || lower.contains("nano") || lower.contains("flare") ->
                    "Cheap. Good for summarising and bookkeeping."
                structured -> "Storytelling"
                else -> "Text only"
            },
            knownLimitations = buildList {
                if (!structured) {
                    add("Cannot be used as a story model: it does not support strict structured output.")
                }
                if (reasoning) add("Rejects a temperature setting, and spends extra tokens thinking.")
            },
            inputCostPerMillion = price?.first,
            outputCostPerMillion = price?.second,
            cachedInputCostPerMillion = price?.third,
            fromLiveCatalog = fromLive,
        )
    }

    private fun prettify(id: String) = id.split('-', '.')
        .joinToString(" ") { part -> if (part.all { it.isDigit() }) part else part.replaceFirstChar { it.uppercase() } }

    /** Collapses a dated model id ("gpt-5.1-2026-01-01") onto its pricing family. */
    private fun priceFamily(lower: String): String =
        lower.replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
            .replace(Regex("-(latest|preview)$"), "")

    private companion object {
        /**
         * Families that support strict JSON-schema structured output. Matched as *prefixes*, so a
         * future dated or point release inside a family is recognised without a code change.
         */
        val STRUCTURED_OUTPUT_FAMILIES = listOf(
            "gpt-6", "gpt-5", "gpt-4.1", "gpt-4o", "o4", "o3", "o1",
        )

        val REASONING_FAMILIES = listOf("o4", "o3", "o1", "gpt-5-thinking", "gpt-6-thinking")

        /** input, output, cached-input — USD per million tokens. Estimates, labelled as such in the UI. */
        val PRICES: Map<String, Triple<Double, Double, Double>> = mapOf(
            "gpt-4o" to Triple(2.50, 10.00, 1.25),
            "gpt-4o-mini" to Triple(0.15, 0.60, 0.075),
            "gpt-4.1" to Triple(2.00, 8.00, 0.50),
            "gpt-4.1-mini" to Triple(0.40, 1.60, 0.10),
            "gpt-4.1-nano" to Triple(0.10, 0.40, 0.025),
        )

        val IMAGE_PRICES: Map<String, Double> = mapOf(
            "dall-e-3" to 0.040,
            "dall-e-2" to 0.020,
        )

        /** Shown when the device is offline. Never the only source of truth (§6). */
        val FALLBACK_IDS = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini", "gpt-image-1", "dall-e-3")
    }
}
