package com.unbound.rpg.data.ai.gemini

import com.unbound.core.ai.AIModelCatalog
import com.unbound.core.ai.ModelCapability
import com.unbound.core.ai.ModelProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What this key can actually reach, asked of Google rather than hard-coded.
 *
 * Capabilities come from the account's own listing where the API states them — `generateContent`
 * in `supportedGenerationMethods` is what makes a model usable for a turn at all — and from the
 * model family only where the API does not. Model names change faster than any table in this
 * repository could, so a list baked into the app would be stale before it shipped; the static
 * table exists solely so the settings screen can show something honest while offline.
 *
 * Prices are estimates for the usage screen and are labelled as such throughout. Google's billing
 * console is the authority, and this app never claims otherwise.
 */
class GeminiModelCatalog(private val client: GeminiClient) : AIModelCatalog {

    @Volatile
    private var cached: List<ModelProfile>? = null
    private val refreshLock = Mutex()

    override suspend fun listModels(): List<ModelProfile> = listModels(forceRefresh = false)

    suspend fun listModels(forceRefresh: Boolean): List<ModelProfile> {
        if (!forceRefresh) cached?.let { return it }
        return refreshLock.withLock { fetch(forceRefresh) }
    }

    private suspend fun fetch(forceRefresh: Boolean): List<ModelProfile> {
        if (!forceRefresh) cached?.let { return it }

        // Paged deliberately: the listing is long, and a single page would silently truncate it.
        val collected = mutableListOf<JsonObject>()
        var pageToken: String? = null
        var pages = 0
        do {
            val path = buildString {
                append("/models?pageSize=").append(PAGE_SIZE)
                pageToken?.let { append("&pageToken=").append(it) }
            }
            val response = client.getJson(path)
            (response["models"] as? JsonArray).orEmpty().forEach { collected += it.jsonObject }
            pageToken = response["nextPageToken"]?.jsonPrimitive?.contentOrNull()
            pages++
        } while (pageToken != null && pages < MAX_PAGES)

        val profiles = collected
            .mapNotNull { profileFrom(it) }
            .filter { it.capabilities.isNotEmpty() }
            .sortedWith(compareByDescending<ModelProfile> { it.supportsStructuredOutput }.thenBy { it.id })

        cached = profiles
        return profiles
    }

    override suspend fun profileFor(modelId: String): ModelProfile? =
        cached?.firstOrNull { it.id == modelId }
            ?: classify(modelId, displayName = modelId, methods = emptySet(), fromLive = false)
                .takeIf { it.capabilities.isNotEmpty() }

    /** Used when the device is offline so the settings screen can still show something honest. */
    fun knownProfiles(): List<ModelProfile> =
        FALLBACK_IDS.map { classify(it, it, emptySet(), fromLive = false) }

    private fun profileFrom(model: JsonObject): ModelProfile? {
        // "models/gemini-2.5-flash" -> "gemini-2.5-flash". The bare id is what the player picks and
        // what is stored on a save, so the resource prefix is stripped at the edge.
        val name = model["name"]?.jsonPrimitive?.contentOrNull() ?: return null
        val id = name.removePrefix("models/")
        val methods = (model["supportedGenerationMethods"] as? JsonArray)
            .orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull() }.toSet()

        return classify(
            id = id,
            displayName = model["displayName"]?.jsonPrimitive?.contentOrNull() ?: id,
            methods = methods,
            fromLive = true,
            contextTokens = model["inputTokenLimit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull(),
            maxOutput = model["outputTokenLimit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull(),
            description = model["description"]?.jsonPrimitive?.contentOrNull().orEmpty(),
        )
    }

    private fun classify(
        id: String,
        displayName: String,
        methods: Set<String>,
        fromLive: Boolean,
        contextTokens: Int? = null,
        maxOutput: Int? = null,
        description: String = "",
    ): ModelProfile {
        val lower = id.lowercase()

        val embedding = lower.contains("embedding") || lower.contains("aqa")
        val imageOnly = lower.contains("imagen")
        val imageCapable = imageOnly || lower.contains("image")
        val audioOnly = lower.contains("tts") || lower.contains("native-audio") || lower.contains("live-")
        val veo = lower.contains("veo")

        // From the account's own listing where it says so; from the family only where it does not.
        val generates = if (fromLive) "generateContent" in methods else !embedding && !veo

        val capabilities = buildSet {
            if (embedding || veo || audioOnly) return@buildSet
            if (imageOnly) {
                add(ModelCapability.IMAGE_GENERATION)
                return@buildSet
            }
            if (!generates) return@buildSet
            add(ModelCapability.TEXT)
            add(ModelCapability.TEMPERATURE)
            // Every current Gemini text model honours responseSchema; the families that do not are
            // the ones already excluded above.
            add(ModelCapability.STRUCTURED_OUTPUT)
            add(ModelCapability.VISION)
            if (imageCapable) add(ModelCapability.IMAGE_GENERATION)
            if (lower.contains("thinking") || lower.contains("pro")) add(ModelCapability.REASONING)
        }

        val price = PRICES.entries.firstOrNull { lower.startsWith(it.key) }?.value

        return ModelProfile(
            id = id,
            displayName = displayName,
            capabilities = capabilities,
            contextTokens = contextTokens,
            maxOutputTokens = maxOutput,
            recommendedFor = recommendation(lower, description),
            knownLimitations = limitations(lower),
            inputCostPerMillion = price?.input,
            outputCostPerMillion = price?.output,
            cachedInputCostPerMillion = price?.cached,
            imageCostEach = if (imageOnly) price?.imageEach else null,
            fromLiveCatalog = fromLive,
        )
    }

    private fun recommendation(lower: String, description: String): String = when {
        lower.contains("flash-lite") -> "Cheapest and fastest. Best when you are playing on the free tier."
        lower.contains("flash") -> "A good default: quick, inexpensive, and steady on long scenes."
        lower.contains("pro") -> "Slower and dearer, but holds a complicated scene together best."
        else -> description.take(120)
    }

    private fun limitations(lower: String): List<String> = buildList {
        if (lower.contains("lite")) add("Shorter, plainer prose than the larger models.")
        if (lower.contains("preview") || lower.contains("exp")) {
            add("A preview model. Google can change or withdraw it at any time.")
        }
    }

    private data class Price(
        val input: Double? = null,
        val output: Double? = null,
        val cached: Double? = null,
        val imageEach: Double? = null,
    )

    private companion object {
        const val PAGE_SIZE = 200

        /** A guard, not a policy: the listing is far shorter than this. */
        const val MAX_PAGES = 10

        /**
         * Shown only while offline, so the settings screen is never blank. The live listing
         * replaces these the moment a key is present and reachable.
         */
        val FALLBACK_IDS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
        )

        /** USD per million tokens. Estimates for the usage screen; Google's console is authoritative. */
        val PRICES = mapOf(
            "gemini-2.5-pro" to Price(input = 1.25, output = 10.0, cached = 0.31),
            "gemini-2.5-flash-lite" to Price(input = 0.10, output = 0.40, cached = 0.025),
            "gemini-2.5-flash" to Price(input = 0.30, output = 2.50, cached = 0.075),
        )
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
