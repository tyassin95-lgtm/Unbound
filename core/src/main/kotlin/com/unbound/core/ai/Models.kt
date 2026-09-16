package com.unbound.core.ai

import kotlinx.serialization.Serializable

/**
 * What a model can actually do. The app refuses to make a model active for gameplay unless its
 * profile covers the operations the current configuration needs (§7), and says why rather than
 * silently substituting a different model.
 */
@Serializable
enum class ModelCapability {
    TEXT,
    STRUCTURED_OUTPUT,
    VISION,
    IMAGE_GENERATION,
    TEMPERATURE,
    REASONING,
}

@Serializable
data class ModelProfile(
    val id: String,
    val displayName: String,
    val capabilities: Set<ModelCapability>,
    val contextTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val recommendedFor: String = "",
    val knownLimitations: List<String> = emptyList(),
    /** USD per 1M tokens. Null when unknown — the usage screen then reports tokens only. */
    val inputCostPerMillion: Double? = null,
    val outputCostPerMillion: Double? = null,
    val cachedInputCostPerMillion: Double? = null,
    /** USD per generated image, when this is an image model. */
    val imageCostEach: Double? = null,
    /** True when the profile came from a live account listing rather than the local fallback table. */
    val fromLiveCatalog: Boolean = false,
) {
    val supportsText: Boolean get() = ModelCapability.TEXT in capabilities
    val supportsStructuredOutput: Boolean get() = ModelCapability.STRUCTURED_OUTPUT in capabilities
    val supportsImages: Boolean get() = ModelCapability.IMAGE_GENERATION in capabilities
    val supportsTemperature: Boolean get() = ModelCapability.TEMPERATURE in capabilities

    fun estimateCost(inputTokens: Long, outputTokens: Long, cachedTokens: Long = 0): Double? {
        val inCost = inputCostPerMillion ?: return null
        val outCost = outputCostPerMillion ?: return null
        val cachedCost = cachedInputCostPerMillion ?: inCost
        val fresh = (inputTokens - cachedTokens).coerceAtLeast(0L)
        return (fresh * inCost + cachedTokens * cachedCost + outputTokens * outCost) / 1_000_000.0
    }
}

/** The result of checking a model against what the game needs before activating it. */
data class ModelSuitability(
    val suitable: Boolean,
    val reason: String,
    val missing: Set<ModelCapability> = emptySet(),
)

object ModelRequirements {
    /** A story model must produce text and must honour a strict JSON schema. */
    val NARRATIVE: Set<ModelCapability> = setOf(ModelCapability.TEXT, ModelCapability.STRUCTURED_OUTPUT)
    val IMAGE: Set<ModelCapability> = setOf(ModelCapability.IMAGE_GENERATION)

    fun check(profile: ModelProfile, required: Set<ModelCapability>): ModelSuitability {
        val missing = required - profile.capabilities
        return if (missing.isEmpty()) {
            ModelSuitability(true, "${profile.displayName} supports everything this game needs.")
        } else {
            ModelSuitability(
                suitable = false,
                reason = "${profile.displayName} cannot be used for this: it does not support " +
                    missing.joinToString(", ") { it.name.lowercase().replace('_', ' ') } + ".",
                missing = missing,
            )
        }
    }
}
