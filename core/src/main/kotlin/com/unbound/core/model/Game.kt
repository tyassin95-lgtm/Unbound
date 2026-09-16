package com.unbound.core.model

import kotlinx.serialization.Serializable

/** Narrative tone presets. Stored per game and changeable mid-play via `Tone: ...`. */
enum class Tone(val display: String, val guidance: String) {
    LIGHT("Light", "Keep the mood warm and forgiving. Consequences land softly."),
    NORMAL("Normal", "Balanced tone. Neither grim nor whimsical."),
    DARK("Dark", "Consequences bite. The world is indifferent and often unkind."),
    VERY_DARK("Very Dark", "Bleak and unsparing. Violence and loss are concrete and ugly."),
    HUMOROUS("Humorous", "Dry wit is welcome. People are absurd without the world becoming a joke."),
    SERIOUS("Serious", "Grounded and sober. No comic relief unless the player introduces it."),
    CINEMATIC("Cinematic", "Strong visual staging, sharp cuts, momentum."),
    FAST("Fast", "Move quickly. Compress travel and routine. Favour short scenes."),
    SLOW_BURN("Slow Burn", "Linger. Let tension build across turns. Small details matter.");

    companion object {
        fun parse(raw: String): Tone? {
            val k = raw.trim().lowercase().replace(' ', '_').replace('-', '_')
            return entries.firstOrNull { it.name.lowercase() == k || it.display.lowercase() == raw.trim().lowercase() }
        }
    }
}

enum class NarrationLength(val display: String, val minWords: Int, val maxWords: Int) {
    BRIEF("Brief", 100, 300),
    NORMAL("Normal", 200, 600),
    LONG("Long", 400, 1200);

    val range: String get() = "$minWords-$maxWords words"
}

enum class ImageMode(val display: String) {
    DISABLED("Disabled"),
    ON_DEMAND("On demand"),
    AUTOMATIC_IMPORTANT("Automatic for important scenes");
}

/**
 * A save. One row per campaign; everything else in the database is scoped to a [id] so that games
 * are fully isolated from one another.
 *
 * [stateVersion] is the optimistic lock: every committed turn increments it, and a turn that was
 * built against a stale version is rejected rather than merged.
 */
@Serializable
data class GameRecord(
    val id: String,
    val title: String,
    val settingId: String,
    val settingName: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val turnNumber: Int = 0,
    val playerId: String = Ids.PLAYER,
    val currentLocationId: String,
    val worldTime: WorldTime = WorldTime.DEFAULT,
    val tone: Tone = Tone.NORMAL,
    val narrationLength: NarrationLength = NarrationLength.NORMAL,
    val limits: List<String> = emptyList(),
    val textModelId: String,
    /**
     * Which vendor runs this campaign's story, defaulted for saves written before there was a
     * choice. Stored per game rather than globally: switching provider mid-campaign is the
     * player's call, and two campaigns can reasonably run on different ones.
     */
    val textProviderId: String = DEFAULT_PROVIDER_ID,
    val imageProviderId: String? = null,
    val imageModelId: String? = null,
    val imageMode: ImageMode = ImageMode.ON_DEMAND,
    val stateVersion: Long = 1,
    val previewImageId: String? = null,
    val suggestedActionsEnabled: Boolean = true,
) {
    companion object {
        /**
         * What a campaign written before providers were a choice is assumed to have used, because
         * that is what it did use. Never guessed at from settings: a save's provider is part of the
         * save.
         */
        const val DEFAULT_PROVIDER_ID = "openai"
    }
}
