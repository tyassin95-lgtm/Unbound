package com.unbound.rpg

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
import com.unbound.rpg.data.ai.ProviderIds
import com.unbound.rpg.data.ai.ProviderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing and fallback: which vendor serves a turn, and what happens when it cannot.
 *
 * The rule under test is that a substitution is never silent and never opportunistic. A player who
 * chose a provider gets that provider, or a clearly-labelled stand-in for one turn, or an error —
 * and never a second vendor quietly answering a request the first one refused.
 */
class ProviderRoutingTest {

    private class Recorder(
        override val providerId: String,
        private val failWith: AIErrorKind? = null,
    ) : AIProvider {
        override val displayName = providerId
        var calls = 0
            private set
        var lastRequest: AITextRequest? = null
            private set

        override suspend fun generateText(request: AITextRequest): AITextResponse {
            calls++
            lastRequest = request
            failWith?.let { throw AIException(it, "simulated $it") }
            return AITextResponse("{}", AIUsage(10, 5), request.modelId)
        }

        override suspend fun generateImage(request: AIImageRequest) =
            AIImageResponse(ByteArray(1), "image/png", request.modelId)

        override suspend fun listModels(): List<ModelProfile> = emptyList()
        override suspend fun profileFor(modelId: String): ModelProfile? = null
        override suspend fun testConnection() = ConnectionTestResult(true, "ok")
    }

    private fun registry(vararg providers: Recorder) =
        ProviderRegistry(providers.associateBy { it.providerId }, ProviderIds.OPENAI)

    private fun request(providerId: String?) = AITextRequest(
        modelId = "some-model",
        providerId = providerId,
        stableSystemPrompt = "rules",
        dynamicContext = "context",
        userInput = "I wait.",
    )

    @Test
    fun `the request goes to the provider the save names`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI)
        val gemini = Recorder(ProviderIds.GEMINI)
        val routing = registry(openai, gemini).routing()

        routing.generateText(request(ProviderIds.GEMINI))

        assertEquals(1, gemini.calls)
        assertEquals("A campaign on one provider must not touch the other", 0, openai.calls)
    }

    @Test
    fun `a campaign with no provider recorded falls to the default rather than failing`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI)
        val routing = registry(openai, Recorder(ProviderIds.GEMINI)).routing()

        // What a save written before there was a choice looks like.
        routing.generateText(request(null))

        assertEquals(1, openai.calls)
    }

    @Test
    fun `an unreachable provider fails over, and says that it did`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI, failWith = AIErrorKind.RATE_LIMITED)
        val gemini = Recorder(ProviderIds.GEMINI)
        val routing = registry(openai, gemini).routing(fallbackProviderId = ProviderIds.GEMINI)

        val response = routing.generateText(request(ProviderIds.OPENAI))

        assertEquals(1, gemini.calls)
        assertEquals(ProviderIds.GEMINI, response.providerId)
        assertEquals(
            "A substitution the player is not told about is a silent change of narrator",
            ProviderIds.OPENAI,
            response.servedByFallbackFrom,
        )
    }

    @Test
    fun `the fallback receives exactly the same context, not a reduced one`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI, failWith = AIErrorKind.SERVER_ERROR)
        val gemini = Recorder(ProviderIds.GEMINI)
        val routing = registry(openai, gemini).routing(fallbackProviderId = ProviderIds.GEMINI)

        routing.generateText(request(ProviderIds.OPENAI))

        // Parity is the point: a second-class provider would produce a worse world and the player
        // would blame the model.
        assertEquals(openai.lastRequest!!.dynamicContext, gemini.lastRequest!!.dynamicContext)
        assertEquals(openai.lastRequest!!.stableSystemPrompt, gemini.lastRequest!!.stableSystemPrompt)
        assertEquals(openai.lastRequest!!.userInput, gemini.lastRequest!!.userInput)
        assertEquals(openai.lastRequest!!.jsonSchema, gemini.lastRequest!!.jsonSchema)
    }

    @Test
    fun `a refusal, a bad key and an empty account are never retried elsewhere`() = runTest {
        for (kind in listOf(
            AIErrorKind.CONTENT_REFUSED,
            AIErrorKind.INVALID_KEY,
            AIErrorKind.REVOKED_KEY,
            AIErrorKind.INSUFFICIENT_QUOTA,
            AIErrorKind.MODEL_UNAVAILABLE,
            AIErrorKind.MALFORMED_RESPONSE,
        )) {
            val openai = Recorder(ProviderIds.OPENAI, failWith = kind)
            val gemini = Recorder(ProviderIds.GEMINI)
            val routing = registry(openai, gemini).routing(fallbackProviderId = ProviderIds.GEMINI)

            val thrown = runCatching { routing.generateText(request(ProviderIds.OPENAI)) }.exceptionOrNull()

            assertTrue("$kind should surface, not be routed around", thrown is AIException)
            assertEquals(kind, (thrown as AIException).kind)
            assertEquals(
                "Sending content one provider refused to another is laundering, not resilience ($kind)",
                0,
                gemini.calls,
            )
        }
    }

    @Test
    fun `with no fallback configured the failure simply surfaces`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI, failWith = AIErrorKind.RATE_LIMITED)
        val gemini = Recorder(ProviderIds.GEMINI)
        val routing = registry(openai, gemini).routing(fallbackProviderId = null)

        val thrown = runCatching { routing.generateText(request(ProviderIds.OPENAI)) }.exceptionOrNull()

        assertTrue(thrown is AIException)
        assertEquals(0, gemini.calls)
    }

    @Test
    fun `a provider cannot fall back to itself`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI, failWith = AIErrorKind.TIMEOUT)
        val routing = registry(openai).routing(fallbackProviderId = ProviderIds.OPENAI)

        runCatching { routing.generateText(request(ProviderIds.OPENAI)) }

        assertEquals("One attempt, not two against the same unreachable vendor", 1, openai.calls)
    }

    @Test
    fun `pictures follow their own provider, independently of the story`() = runTest {
        val openai = Recorder(ProviderIds.OPENAI)
        val gemini = Recorder(ProviderIds.GEMINI)
        val routing = registry(openai, gemini).routing()

        val response = routing.generateImage(
            AIImageRequest(modelId = "gpt-image-1", providerId = ProviderIds.OPENAI, prompt = "a face"),
        )

        assertEquals("gpt-image-1", response.modelId)
        assertNull("An image request must not disturb the story provider", gemini.lastRequest)
    }
}
