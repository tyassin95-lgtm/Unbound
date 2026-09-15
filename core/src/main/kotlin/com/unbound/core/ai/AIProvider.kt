package com.unbound.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AIUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
) {
    operator fun plus(other: AIUsage) = AIUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cachedInputTokens + other.cachedInputTokens,
    )
}

/**
 * A request for text. [stableSystemPrompt] is kept byte-identical across turns so providers that
 * cache prompt prefixes can actually hit that cache (§72); everything volatile goes in
 * [dynamicContext] and [userInput].
 */
data class AITextRequest(
    val modelId: String,
    val stableSystemPrompt: String,
    val dynamicContext: String,
    val userInput: String,
    /** When non-null, the provider must constrain output to this JSON Schema. */
    val jsonSchema: JsonObject? = null,
    val schemaName: String = "response",
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

data class AITextResponse(
    val text: String,
    val usage: AIUsage = AIUsage(),
    val modelId: String,
    val latencyMs: Long = 0,
    val finishedEarly: Boolean = false,
)

data class AIImageRequest(
    val modelId: String,
    val prompt: String,
    val size: String = "1024x1024",
    /** Bytes of a canonical reference image, when the provider supports reference-preserving edits. */
    val referenceImage: ByteArray? = null,
    val referenceMimeType: String = "image/png",
) {
    override fun equals(other: Any?): Boolean =
        other is AIImageRequest && modelId == other.modelId && prompt == other.prompt && size == other.size
    override fun hashCode(): Int = (modelId + prompt + size).hashCode()
}

data class AIImageResponse(
    val bytes: ByteArray,
    val mimeType: String,
    val modelId: String,
    val revisedPrompt: String? = null,
    val latencyMs: Long = 0,
) {
    override fun equals(other: Any?): Boolean =
        other is AIImageResponse && bytes.contentEquals(other.bytes) && modelId == other.modelId
    override fun hashCode(): Int = bytes.contentHashCode() * 31 + modelId.hashCode()
}

/** Classified failure. The turn pipeline branches on this, never on message text. */
enum class AIErrorKind {
    NO_CREDENTIAL,
    INVALID_KEY,
    REVOKED_KEY,
    INSUFFICIENT_QUOTA,
    RATE_LIMITED,
    MODEL_UNAVAILABLE,
    UNSUPPORTED_FEATURE,
    TIMEOUT,
    NETWORK,
    MALFORMED_RESPONSE,
    SERVER_ERROR,
    CONTENT_REFUSED,
    UNKNOWN;

    /** Whether repeating the identical request could plausibly succeed. */
    val retryable: Boolean
        get() = this == RATE_LIMITED || this == TIMEOUT || this == NETWORK ||
            this == SERVER_ERROR || this == MALFORMED_RESPONSE
}

class AIException(
    val kind: AIErrorKind,
    override val message: String,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface AITextProvider {
    suspend fun generateText(request: AITextRequest): AITextResponse
}

interface AIImageProvider {
    suspend fun generateImage(request: AIImageRequest): AIImageResponse
}

interface AIModelCatalog {
    /** Models the credential can actually reach. Falls back to a static table when offline. */
    suspend fun listModels(): List<ModelProfile>
    suspend fun profileFor(modelId: String): ModelProfile?
}

/** Everything the engine is allowed to know about an AI vendor. */
interface AIProvider : AITextProvider, AIImageProvider, AIModelCatalog {
    val providerId: String
    val displayName: String

    /** Verifies the stored credential. Never logs or returns the credential itself. */
    suspend fun testConnection(): ConnectionTestResult
}

data class ConnectionTestResult(
    val ok: Boolean,
    val message: String,
    val modelCount: Int = 0,
    val errorKind: AIErrorKind? = null,
)
