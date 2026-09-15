package com.unbound.core.images

import com.unbound.core.model.Appearance
import com.unbound.core.model.ImageKind
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.WorldRecord
import com.unbound.core.model.WorldTime

/**
 * Deterministic image prompts built from canonical structured data (§89).
 *
 * The prompt is assembled from facts, in a fixed order, with no poetry. That is what makes the same
 * character come back looking like the same character: the identity clause is byte-identical
 * between the first portrait and the fiftieth, and only the situational clause changes.
 */
class ImagePromptBuilder {

    fun forCharacter(
        name: String,
        age: Int,
        gender: String,
        appearance: Appearance,
        world: WorldRecord,
        settingName: String,
        situation: String? = null,
        isFirstImage: Boolean,
    ): ImagePrompt {
        val identity = identityClause(age, gender, appearance)
        val prompt = buildString {
            append("Character portrait. ")
            append(identity)
            append(" Setting: $settingName, ${world.era}, ${world.region}.")
            if (!situation.isNullOrBlank()) append(" $situation")
            append(" Realistic proportions, neutral lighting, plain background.")
        }
        return ImagePrompt(
            prompt = prompt,
            kind = ImageKind.CHARACTER,
            identityClause = identity,
            visualTraits = appearance.visualFacts(),
            appearanceVersion = appearance.version,
            isReferenceCreation = isFirstImage,
            cacheKey = cacheKey(name, appearance.version, situation),
        )
    }

    fun forNpc(npc: NpcRecord, world: WorldRecord, settingName: String, situation: String? = null, isFirstImage: Boolean) =
        forCharacter(npc.name, npc.age, npc.gender, npc.appearance, world, settingName, situation, isFirstImage)
            .copy(kind = ImageKind.NPC, cacheKey = cacheKey(npc.id, npc.appearance.version, situation))

    fun forPlayer(player: PlayerRecord, world: WorldRecord, settingName: String, situation: String? = null, isFirstImage: Boolean) =
        forCharacter(player.name, player.age, player.gender, player.appearance, world, settingName, situation, isFirstImage)

    fun forLocation(
        location: LocationRecord,
        world: WorldRecord,
        time: WorldTime,
        settingName: String,
        isFirstImage: Boolean,
    ): ImagePrompt {
        // The identity clause for a place is its architecture and layout; only light, weather and
        // condition are allowed to vary between images of the same location.
        val identity = buildString {
            append(location.name).append(": ").append(location.description)
            if (location.geography.isNotBlank()) append(' ').append(location.geography)
            append(" Type: ").append(location.type.ifBlank { "interior" }).append('.')
        }
        val prompt = buildString {
            append("Environment illustration. ")
            append(identity)
            append(" Setting: $settingName, ${world.era}.")
            append(" Conditions: ${world.weather}, ${time.partOfDay.name.lowercase().replace('_', ' ')}.")
            if (location.condition != "intact") append(" The place is ${location.condition}.")
            if (location.environmentState.isNotBlank()) append(' ').append(location.environmentState)
            append(" No people in frame.")
        }
        return ImagePrompt(
            prompt = prompt,
            kind = ImageKind.LOCATION,
            identityClause = identity,
            visualTraits = listOf(location.type, location.condition),
            appearanceVersion = 1,
            isReferenceCreation = isFirstImage,
            cacheKey = cacheKey(location.id, 1, "${world.weather}/${time.partOfDay}/${location.condition}"),
        )
    }

    /**
     * A moment rather than a subject: the place, the light, and whoever is standing in it.
     *
     * Consistency here works differently from a portrait. A group shot cannot be an edit of one
     * canonical reference, so identity is carried by *text*: each character contributes the same
     * identity clause used for their own portrait, verbatim. That is why [identityClause] is built
     * from structured facts in a fixed order — it has to be reusable in three different prompts
     * without drifting.
     */
    fun forScene(
        location: LocationRecord,
        world: WorldRecord,
        time: WorldTime,
        settingName: String,
        present: List<CharacterLikeness>,
        moment: String,
        kind: ImageKind = ImageKind.SCENE,
    ): ImagePrompt {
        val cast = present.take(MAX_FIGURES)
        val identity = buildString {
            append(location.name).append(": ").append(location.description)
            if (cast.isNotEmpty()) {
                append(" Present: ")
                append(cast.joinToString(" ") { "${it.name} — ${identityClause(it.age, it.gender, it.appearance)}" })
            }
        }
        val prompt = buildString {
            append(if (kind == ImageKind.EVENT) "Illustration of a moment. " else "Scene illustration. ")
            append(identity)
            append(" Setting: $settingName, ${world.era}, ${world.region}.")
            append(" Conditions: ${world.weather}, ${time.partOfDay.name.lowercase().replace('_', ' ')}.")
            if (location.condition != "intact") append(" The place is ${location.condition}.")
            if (moment.isNotBlank()) append(" What is happening: ").append(moment.trim())
            append(" Realistic proportions, natural light, no text or captions.")
        }
        return ImagePrompt(
            prompt = prompt,
            kind = kind,
            identityClause = identity,
            visualTraits = cast.flatMap { it.appearance.visualFacts() },
            appearanceVersion = cast.sumOf { it.appearance.version },
            // A moment is never a canonical reference: it depicts an event, not an identity.
            isReferenceCreation = false,
            cacheKey = cacheKey(
                location.id + "|" + cast.joinToString(",") { it.id },
                cast.sumOf { it.appearance.version },
                moment.take(120),
            ),
        )
    }

    /** The people rather than the room: two or three figures, and what is passing between them. */
    fun forInteraction(
        location: LocationRecord,
        world: WorldRecord,
        time: WorldTime,
        settingName: String,
        participants: List<CharacterLikeness>,
        moment: String,
    ): ImagePrompt = forScene(
        location = location,
        world = world,
        time = time,
        settingName = settingName,
        present = participants,
        moment = moment,
        kind = ImageKind.EVENT,
    ).let { base ->
        base.copy(
            prompt = base.prompt.replace(
                "Illustration of a moment. ",
                "Close illustration of people, framed on their faces and hands. ",
            ),
        )
    }

    /**
     * The clause that must not drift. Age is stated explicitly and first, because the image model
     * needs it as much as the text model does.
     */
    private fun identityClause(age: Int, gender: String, appearance: Appearance): String = buildString {
        append("A $age year old ")
        append(gender.ifBlank { "person" })
        append(". ")
        append(appearance.visualFacts().joinToString(", "))
        append('.')
    }

    private fun cacheKey(subject: String, version: Int, variation: String?): String =
        listOf(subject, version.toString(), variation.orEmpty()).joinToString("|").hashCode().toString()

    private companion object {
        /** Beyond this, image models reliably lose faces; naming fewer people keeps them coherent. */
        const val MAX_FIGURES = 4
    }
}

/**
 * Anything that can be drawn as a person. Lets the player and NPCs share one prompt path, which is
 * what keeps their identity clauses identical across portrait, scene and interaction.
 */
data class CharacterLikeness(
    val id: String,
    val name: String,
    val age: Int,
    val gender: String,
    val appearance: Appearance,
) {
    companion object {
        fun of(npc: NpcRecord) = CharacterLikeness(npc.id, npc.name, npc.age, npc.gender, npc.appearance)
        fun of(player: PlayerRecord) = CharacterLikeness(player.id, player.name, player.age, player.gender, player.appearance)
    }
}

data class ImagePrompt(
    val prompt: String,
    val kind: ImageKind,
    /** The stable part. Reused verbatim for every later image of the same subject. */
    val identityClause: String,
    val visualTraits: List<String>,
    val appearanceVersion: Int,
    /** True when this call establishes the canonical reference for the subject. */
    val isReferenceCreation: Boolean,
    val cacheKey: String,
)
