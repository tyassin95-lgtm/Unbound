package com.unbound.rpg.data.ai.gemini

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIException
import com.unbound.core.ai.AIImageRequest
import com.unbound.core.ai.AIImageResponse
import com.unbound.core.ai.AIProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.ai.AITextResponse
import com.unbound.core.ai.AIUsage
import com.unbound.core.ai.ConnectionTestResult
import com.unbound.core.ai.ModelProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import android.util.Base64

/**
 * Gemini, held to exactly the same contract as OpenAI.
 *
 * Nothing about the game leaks in here and nothing about Gemini leaks out: the engine hands over an
 * [AITextRequest] and gets an [AITextResponse], and every difference between the two vendors —
 * endpoint shape, where the system prompt goes, how a schema is spelled, what the token counters
 * are called — is absorbed by this class.
 *
 * Parity is deliberate. The same assembled context, the same schema and the same budget go to both
 * providers; only the envelope differs. A provider that received a thinner context would produce a
 * worse world, and the player would reasonably blame the model rather than the app.
 */
class GeminiProvider(
    private val client: GeminiClient,
    private val catalog: GeminiModelCatalog,
) : AIProvider {

    override val providerId = "gemini"
    override val displayName = "Google Gemini"

    override suspend fun generateText(request: AITextRequest): AITextResponse {
        val started = System.currentTimeMillis()

        val body = buildJsonObject {
            // The stable half goes in systemInstruction, byte-identical across turns for a given
            // configuration — the same arrangement that lets a prompt-prefix cache hit on OpenAI.
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", request.stableSystemPrompt) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put(
                                "text",
                                buildString {
                                    append(request.dynamicContext)
                                    append("\n\n## PLAYER INPUT\n")
                                    append(request.userInput)
                                },
                            )
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                request.maxOutputTokens?.let { put("maxOutputTokens", it) }
                request.temperature?.let { put("temperature", it) }
                request.jsonSchema?.let { schema ->
                    // Gemini's own dialect, translated from the engine's single shared schema.
                    put("responseMimeType", "application/json")
                    put("responseSchema", GeminiSchema.translate(schema))
                }
            }
        }

        val response = client.postJson("/models/${request.modelId}:generateContent", body)
        val latency = System.currentTimeMillis() - started

        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
        val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull()

        // A safety block returns candidates with no parts, which is a refusal rather than a bug.
        if (finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT" || finishReason == "BLOCKLIST") {
            throw AIException(
                AIErrorKind.CONTENT_REFUSED,
                "Gemini declined this request on safety grounds. The turn was not applied.",
            )
        }
        if (finishReason == "RECITATION") {
            throw AIException(AIErrorKind.CONTENT_REFUSED, "Gemini stopped this response as recitation.")
        }

        val text = extractText(candidate)
        if (text.isBlank()) {
            val blockReason = (response["promptFeedback"] as? JsonObject)
                ?.get("blockReason")?.jsonPrimitive?.contentOrNull()
            if (blockReason != null) {
                throw AIException(AIErrorKind.CONTENT_REFUSED, "Gemini blocked the request: $blockReason")
            }
            throw AIException(AIErrorKind.MALFORMED_RESPONSE, "Gemini returned an empty response.")
        }

        return AITextResponse(
            text = text,
            usage = extractUsage(response),
            modelId = response["modelVersion"]?.jsonPrimitive?.contentOrNull() ?: request.modelId,
            latencyMs = latency,
            // MAX_TOKENS means the JSON is truncated; the pipeline's parse then fails cleanly
            // rather than committing half a turn.
            finishedEarly = finishReason == "MAX_TOKENS",
        )
    }

    private fun extractText(candidate: JsonObject?): String {
        val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray ?: return ""
        return parts.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull() }
            .joinToString("")
    }

    private fun extractUsage(response: JsonObject): AIUsage {
        val usage = response["usageMetadata"] as? JsonObject ?: return AIUsage()
        return AIUsage(
            inputTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedInputTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /**
     * Images, through the same generateContent endpoint.
     *
     * A reference image is sent as an extra inline part, which is how Gemini is told "keep this
     * face" — the same job `input_fidelity` does on OpenAI's edit endpoint, and the reason a
     * character's canonical image keeps working when the player switches providers.
     */
    override suspend fun generateImage(request: AIImageRequest): AIImageResponse {
        val started = System.currentTimeMillis()

        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        request.referenceImage?.let { bytes ->
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", request.referenceMimeType)
                                    put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                }
                            }
                        }
                        addJsonObject { put("text", request.prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("IMAGE") }
            }
        }

        val response = client.postJson("/models/${request.modelId}:generateContent", body)

        val parts = ((response["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?.get("content") as? JsonObject)?.get("parts") as? JsonArray
        val inline = parts.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("inlineData") as? JsonObject }
            .firstOrNull()
            ?: throw AIException(AIErrorKind.MALFORMED_RESPONSE, "Gemini returned no image.")

        val data = inline["data"]?.jsonPrimitive?.contentOrNull()
            ?: throw AIException(AIErrorKind.MALFORMED_RESPONSE, "Gemini returned an image with no data.")

        return AIImageResponse(
            bytes = Base64.decode(data, Base64.DEFAULT),
            mimeType = inline["mimeType"]?.jsonPrimitive?.contentOrNull() ?: "image/png",
            modelId = request.modelId,
            latencyMs = System.currentTimeMillis() - started,
        )
    }

    override suspend fun listModels(): List<ModelProfile> = catalog.listModels()

    override suspend fun profileFor(modelId: String): ModelProfile? = catalog.profileFor(modelId)

    override suspend fun testConnection(): ConnectionTestResult {
        if (!client.hasCredential()) {
            return ConnectionTestResult(false, "No Gemini API key has been added yet.", errorKind = AIErrorKind.NO_CREDENTIAL)
        }
        return try {
            val models = catalog.listModels(forceRefresh = true)
            val usable = models.count { it.supportsStructuredOutput }
            ConnectionTestResult(
                ok = true,
                message = "Connected. $usable of ${models.size} models can run a story.",
                modelCount = models.size,
            )
        } catch (e: AIException) {
            ConnectionTestResult(false, e.message, errorKind = e.kind)
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
