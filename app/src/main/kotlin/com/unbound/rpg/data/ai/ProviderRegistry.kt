package com.unbound.rpg.data.ai

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIException
import com.unbound.core.ai.AIImageRequest
import com.unbound.core.ai.AIImageResponse
import com.unbound.core.ai.AIProvider
import com.unbound.core.ai.AITextRequest
import com.unbound.core.ai.AITextResponse
import com.unbound.core.ai.ConnectionTestResult
import com.unbound.core.ai.ModelProfile

/**
 * Everything the app knows about who can serve a request.
 *
 * The engine holds a single [AIProvider] and never learns there is more than one vendor; the
 * choice travels on the request as an opaque id read from the save. That is what keeps the game
 * database, the memories, the events and the world logic entirely independent of whose model is
 * running — switching provider changes nothing but the envelope.
 */
class ProviderRegistry(
    private val providers: Map<String, AIProvider>,
    /** Used when a request names no provider, and for anything not tied to a specific campaign. */
    private val defaultProviderId: String,
) {
    val available: List<ProviderInfo>
        get() = providers.values.map { ProviderInfo(it.providerId, it.displayName) }

    fun provider(id: String?): AIProvider =
        providers[id] ?: providers[defaultProviderId] ?: providers.values.first()

    fun providerOrNull(id: String?): AIProvider? = providers[id]

    /**
     * A single [AIProvider] view over all of them, dispatching on the request's own provider id.
     *
     * Everything downstream — the turn pipeline, the image service, world generation — takes this
     * and stays unaware that routing is happening at all.
     */
    fun routing(fallbackProviderId: String? = null): AIProvider = RoutingProvider(fallbackProviderId)

    private inner class RoutingProvider(private val fallbackProviderId: String?) : AIProvider {
        override val providerId = "routing"
        override val displayName = "Selected provider"

        override suspend fun generateText(request: AITextRequest): AITextResponse {
            val chosenId = request.providerId ?: defaultProviderId
            val chosen = provider(chosenId)
            return try {
                chosen.generateText(request).copy(providerId = chosen.providerId)
            } catch (e: AIException) {
                val second = fallbackProviderId
                    ?.takeIf { it != chosen.providerId }
                    ?.let { providers[it] }

                // Only for failures that are about the provider being unreachable, never for a
                // refusal or a bad key — retrying content the first vendor declined on a second
                // one is not resilience, it is laundering.
                if (second == null || !e.kind.worthFailingOver()) throw e

                // The fallback gets the identical request: same context, same schema, same budget.
                // It is a different narrator for one turn, not a different game.
                second.generateText(request.copy(providerId = second.providerId, modelId = request.modelId))
                    .copy(providerId = second.providerId, servedByFallbackFrom = chosen.providerId)
            }
        }

        override suspend fun generateImage(request: AIImageRequest): AIImageResponse =
            provider(request.providerId).generateImage(request)

        override suspend fun listModels(): List<ModelProfile> = provider(null).listModels()

        override suspend fun profileFor(modelId: String): ModelProfile? {
            // A model id alone does not say whose it is, so ask everyone and take the first match.
            providers.values.forEach { p -> p.profileFor(modelId)?.let { return it } }
            return null
        }

        override suspend fun testConnection(): ConnectionTestResult = provider(null).testConnection()
    }
}

data class ProviderInfo(val id: String, val displayName: String)

/**
 * Whether trying a different vendor could plausibly help.
 *
 * A rate limit, an outage or a dropped connection is about *this* provider and says nothing about
 * another. A rejected key, an exhausted account, a missing model or refused content are about the
 * request or the configuration, and would fail identically — or worse, succeed in a way the player
 * did not ask for.
 */
private fun AIErrorKind.worthFailingOver(): Boolean = when (this) {
    AIErrorKind.RATE_LIMITED,
    AIErrorKind.SERVER_ERROR,
    AIErrorKind.TIMEOUT,
    AIErrorKind.NETWORK,
    -> true
    else -> false
}

/** The vendor ids used on a save and in settings. Stable strings — a save stores them. */
object ProviderIds {
    const val OPENAI = "openai"
    const val GEMINI = "gemini"
}
