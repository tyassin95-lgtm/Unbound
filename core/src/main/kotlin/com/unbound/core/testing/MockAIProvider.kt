package com.unbound.core.testing

import com.unbound.core.ai.AIException
import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIImageRequest
import com.unbound.core.ai.AIImageResponse
import com.unbound.core.ai.AIProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.ai.AITextResponse
import com.unbound.core.ai.AIUsage
import com.unbound.core.ai.ConnectionTestResult
import com.unbound.core.ai.ModelCapability
import com.unbound.core.ai.ModelProfile
import com.unbound.core.validate.TurnResponseDto
import kotlinx.serialization.json.Json

/**
 * A deterministic stand-in for a real model (§131).
 *
 * It is not a stub that returns a fixed string: it reads the dynamic context it was given and
 * responds to the player's verb, so the tests exercise the *real* pipeline — validation, event
 * emission, knowledge propagation, memory reconciliation — without a network or an API key.
 *
 * It can also be told to fail in specific ways, which is how the failure-path tests simulate
 * timeouts, malformed JSON and invalid credentials.
 */
class MockAIProvider(
    private val behaviour: MockBehaviour = MockBehaviour(),
) : AIProvider {

    override val providerId = "mock"
    override val displayName = "Deterministic Mock"

    var callCount: Int = 0
        private set

    /** The dynamic context of the most recent call. Tests assert on its size and contents. */
    var lastRequest: AITextRequest? = null
        private set

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    override suspend fun generateText(request: AITextRequest): AITextResponse {
        callCount++
        lastRequest = request
        if (behaviour.latencyMs > 0) kotlinx.coroutines.delay(behaviour.latencyMs)

        behaviour.failWith?.let { kind ->
            if (behaviour.failuresRemaining > 0) {
                behaviour.failuresRemaining--
                throw AIException(kind, "Mock failure: $kind")
            }
        }

        if (behaviour.returnMalformedJson) {
            return AITextResponse("this is not json at all", AIUsage(100, 20), request.modelId)
        }

        // A raw hook, for callers whose schema is not the turn schema — world and opening
        // generation return their own shapes.
        behaviour.rawResponder?.let { raw ->
            return AITextResponse(raw(request), usageFor(request), request.modelId)
        }

        behaviour.scriptedResponses.removeFirstOrNull()?.let { scripted ->
            return AITextResponse(json.encodeToString(TurnResponseDto.serializer(), scripted), usageFor(request), request.modelId)
        }

        val dto = behaviour.responder(MockTurnInput(request.userInput, request.dynamicContext))
        return AITextResponse(json.encodeToString(TurnResponseDto.serializer(), dto), usageFor(request), request.modelId)
    }

    private fun usageFor(request: AITextRequest): AIUsage {
        // Roughly four characters per token — enough for the context-growth assertions to be
        // meaningful without pretending to be a real tokenizer.
        val input = (request.stableSystemPrompt.length + request.dynamicContext.length + request.userInput.length) / 4
        return AIUsage(inputTokens = input, outputTokens = 260, cachedInputTokens = request.stableSystemPrompt.length / 4)
    }

    override suspend fun generateImage(request: AIImageRequest): AIImageResponse {
        if (behaviour.failImages) throw AIException(AIErrorKind.SERVER_ERROR, "Mock image failure")
        return AIImageResponse(FAKE_PNG, "image/png", request.modelId, latencyMs = 5)
    }

    override suspend fun listModels(): List<ModelProfile> = behaviour.models

    override suspend fun profileFor(modelId: String) = behaviour.models.firstOrNull { it.id == modelId }

    override suspend fun testConnection() = ConnectionTestResult(true, "Mock provider is always reachable.", behaviour.models.size)

    companion object {
        /** A 1x1 transparent PNG. Small enough to keep in source, real enough to decode. */
        val FAKE_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06,
            0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0x00, 0x00, 0x00, 0x0A,
            0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
            0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        val DEFAULT_MODELS = listOf(
            ModelProfile(
                id = "mock-story",
                displayName = "Mock Story Model",
                capabilities = setOf(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT, ModelCapability.TEMPERATURE),
                contextTokens = 128_000,
                inputCostPerMillion = 1.0,
                outputCostPerMillion = 4.0,
            ),
            ModelProfile(
                id = "mock-image",
                displayName = "Mock Image Model",
                capabilities = setOf(ModelCapability.IMAGE_GENERATION),
                imageCostEach = 0.04,
            ),
            ModelProfile(
                id = "mock-text-only",
                displayName = "Mock Text Only (no structured output)",
                capabilities = setOf(ModelCapability.TEXT),
            ),
        )
    }
}

data class MockTurnInput(val playerInput: String, val context: String)

class MockBehaviour(
    var failWith: AIErrorKind? = null,
    var failuresRemaining: Int = 0,
    var returnMalformedJson: Boolean = false,
    var failImages: Boolean = false,
    val scriptedResponses: ArrayDeque<TurnResponseDto> = ArrayDeque(),
    val models: List<ModelProfile> = MockAIProvider.DEFAULT_MODELS,
    var responder: (MockTurnInput) -> TurnResponseDto = ::defaultResponder,
    /**
     * Returns the response body verbatim, bypassing the turn-schema DTO. Used by tests for world
     * and opening generation, which ask for a different shape entirely.
     */
    var rawResponder: ((AITextRequest) -> String)? = null,
    /**
     * Suspends for this long before answering. A real model call always suspends; without one the
     * whole pipeline runs to completion inside a single coroutine resumption, which quietly turns
     * a concurrency test into a sequential one.
     */
    var latencyMs: Long = 0,
)

/**
 * The default behaviour: narrate the action back, advance a plausible amount of time, and produce
 * no state changes. Tests that care about consequences script their own responses.
 */
fun defaultResponder(input: MockTurnInput): TurnResponseDto {
    val verb = input.playerInput.trim().substringBefore(' ').lowercase()
    val minutes = when (verb) {
        "wait" -> 60
        "sleep", "rest" -> 480
        "travel", "go", "walk", "ride" -> 30
        "look", "examine", "observe" -> 1
        else -> 5
    }
    return TurnResponseDto(
        narrative = "You ${input.playerInput.trim().ifBlank { "stand still" }}. The world goes on around you.",
        timeAdvanceMinutes = minutes,
        suggestedActions = listOf("Look around", "Speak to someone", "Leave", "Wait"),
    )
}
