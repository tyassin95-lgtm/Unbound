package com.unbound.rpg.data.ai.openai

import com.unbound.core.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Base64

/**
 * The OpenAI implementation of the engine's [AIProvider] interfaces.
 *
 * Everything vendor-specific stops here. The engine knows about `AITextRequest`,
 * `AITextResponse` and `AIUsage`; it does not know that `/v1/responses` exists, what
 * `text.format` means, or that usage arrives under `input_tokens_details.cached_tokens`.
 * Swapping in another vendor means writing another class in this package and nothing else.
 */
class OpenAIProvider(
    private val client: OpenAIClient,
    private val catalog: OpenAIModelCatalog = OpenAIModelCatalog(client),
) : AIProvider {

    override val providerId = "openai"
    override val displayName = "OpenAI"

    override suspend fun generateText(request: AITextRequest): AITextResponse = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val body = buildJsonObject {
            put("model", request.modelId)
            // `instructions` carries the stable half. It is byte-identical across turns for a given
            // configuration, which is what a prompt-prefix cache needs to hit.
            put("instructions", request.stableSystemPrompt)
            putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildString {
                            append(request.dynamicContext)
                            append("\n\n## PLAYER INPUT\n")
                            append(request.userInput)
                        },
                    )
                }
            }
            request.maxOutputTokens?.let { put("max_output_tokens", it) }
            request.temperature?.let { put("temperature", it) }
            // Groups requests that share a prefix for caching purposes. Deliberately not derived
            // from anything player-identifying.
            put("prompt_cache_key", "unbound-turn-v1")
            put("store", false)

            request.jsonSchema?.let { schema ->
                putJsonObject("text") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("name", request.schemaName)
                        put("strict", true)
                        put("schema", schema)
                    }
                }
            }
        }

        val response = client.postJson("/responses", body)
        val latency = System.currentTimeMillis() - started

        val status = response["status"]?.jsonPrimitive?.contentOrNull
        val text = extractText(response)

        if (text.isBlank()) {
            val refusal = extractRefusal(response)
            if (refusal != null) throw AIException(AIErrorKind.CONTENT_REFUSED, refusal)
            throw AIException(AIErrorKind.MALFORMED_RESPONSE, "OpenAI returned an empty response.")
        }

        AITextResponse(
            text = text,
            usage = extractUsage(response),
            modelId = response["model"]?.jsonPrimitive?.contentOrNull ?: request.modelId,
            latencyMs = latency,
            // "incomplete" means the output hit the token ceiling; the JSON will be truncated and
            // the pipeline's parse will fail cleanly rather than committing half a turn.
            finishedEarly = status == "incomplete",
        )
    }

    /**
     * Prefers the `output_text` convenience field and falls back to walking `output[]`, because
     * both are documented and the array form is what arrives when the model also emits reasoning
     * items alongside the message.
     */
    private fun extractText(response: JsonObject): String {
        response["output_text"]?.let { element ->
            if (element is JsonPrimitive && element.isString) return element.content
            if (element is JsonArray) return element.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString("")
        }
        val output = response["output"] as? JsonArray ?: return ""
        return output
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty().mapNotNull { c -> c as? JsonObject } }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
    }

    private fun extractRefusal(response: JsonObject): String? =
        (response["output"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .flatMap { (it["content"] as? JsonArray).orEmpty().mapNotNull { c -> c as? JsonObject } }
            .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "refusal" }
            ?.get("refusal")?.jsonPrimitive?.contentOrNull

    private fun extractUsage(response: JsonObject): AIUsage {
        val usage = response["usage"] as? JsonObject ?: return AIUsage()
        return AIUsage(
            inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedInputTokens = (usage["input_tokens_details"] as? JsonObject)
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    override suspend fun generateImage(request: AIImageRequest): AIImageResponse = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        val response = if (request.referenceImage != null) {
            // With a canonical reference available, edit it rather than generating from scratch:
            // high input fidelity is what actually preserves a face between portraits (§58).
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", request.modelId)
                .addFormDataPart("prompt", request.prompt)
                .addFormDataPart("size", request.size)
                .addFormDataPart("input_fidelity", "high")
                .addFormDataPart("n", "1")
                .addFormDataPart(
                    "image", "reference.png",
                    request.referenceImage!!.toRequestBody(request.referenceMimeType.toMediaType()),
                )
                .build()
            client.postMultipart("/images/edits", multipart)
        } else {
            client.postJson(
                "/images/generations",
                buildJsonObject {
                    put("model", request.modelId)
                    put("prompt", request.prompt)
                    put("size", request.size)
                    put("n", 1)
                },
            )
        }

        val first = (response["data"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AIException(AIErrorKind.MALFORMED_RESPONSE, "OpenAI returned no image data.")

        val b64 = first["b64_json"]?.jsonPrimitive?.contentOrNull
        val bytes = when {
            b64 != null -> Base64.decode(b64, Base64.DEFAULT)
            first["url"]?.jsonPrimitive?.contentOrNull != null ->
                // Older DALL·E models return a short-lived URL instead of bytes.
                throw AIException(
                    AIErrorKind.UNSUPPORTED_FEATURE,
                    "The selected image model returns a temporary URL rather than image data. " +
                        "Choose a GPT image model so the picture can be stored on this device.",
                )
            else -> throw AIException(AIErrorKind.MALFORMED_RESPONSE, "OpenAI returned no usable image.")
        }

        AIImageResponse(
            bytes = bytes,
            mimeType = "image/" + (response["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"),
            modelId = request.modelId,
            revisedPrompt = first["revised_prompt"]?.jsonPrimitive?.contentOrNull,
            latencyMs = System.currentTimeMillis() - started,
        )
    }

    override suspend fun listModels(): List<ModelProfile> = catalog.listModels()

    override suspend fun profileFor(modelId: String): ModelProfile? = catalog.profileFor(modelId)

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!client.hasCredential()) {
            return@withContext ConnectionTestResult(false, "No API key has been added yet.", errorKind = AIErrorKind.NO_CREDENTIAL)
        }
        try {
            val models = catalog.listModels(forceRefresh = true)
            val usable = models.count { it.supportsStructuredOutput }
            ConnectionTestResult(
                ok = true,
                message = "Connected. ${models.size} models available to this key, $usable of them usable for storytelling.",
                modelCount = models.size,
            )
        } catch (e: AIException) {
            ConnectionTestResult(false, e.message, errorKind = e.kind)
        }
    }
}
