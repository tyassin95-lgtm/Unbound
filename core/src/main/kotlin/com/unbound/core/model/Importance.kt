package com.unbound.core.model

import kotlinx.serialization.Serializable

/**
 * Drives cost: only [HIGH] and [CRITICAL] events are eligible for expensive semantic-memory
 * processing, and only they survive aggressive retrieval filtering far into a campaign (§30).
 */
@Serializable
enum class Importance(val weight: Double) {
    TRIVIAL(0.1),
    LOW(0.3),
    MEDIUM(0.6),
    HIGH(0.85),
    CRITICAL(1.0);

    val isMemorable: Boolean get() = ordinal >= MEDIUM.ordinal
}
