package com.unbound.core.content

import com.unbound.core.model.Importance
import com.unbound.core.model.ThreadType
import com.unbound.core.model.Tone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Strict schemas for world and opening generation.
 *
 * Note the exit shape: `[{label, to}]` rather than a `{label: to}` map. Strict structured output
 * requires `additionalProperties: false` at every level, which makes arbitrary object keys
 * impossible — so anything map-like has to travel as a list of pairs.
 */
internal object WorldGenerationSchema {

    fun schema(): JsonObject = obj(
        listOf(
            "name", "blurb", "region", "era", "summary", "currency_name", "weather",
            "social_hierarchy", "economy", "laws", "religion", "dangers", "history", "conflicts",
            "special_rules", "tone", "start_location_key", "locations", "npcs", "factions", "threads",
        ),
        buildJsonObject {
            put("name", str("A short, evocative name for this place. Two to four words."))
            put("blurb", str("One sentence a player would read when choosing this world."))
            put("region", str("The specific district, town or area play begins in."))
            put("era", str("When this is, in the world's own terms."))
            put("summary", str("Three or four sentences: what this place runs on and who suffers for it."))
            put("currency_name", str("What money is called here, plural and lowercase."))
            put("weather", str("The weather right now."))
            put("social_hierarchy", str("Who is above whom."))
            put("economy", str("What is bought, sold and owed."))
            put("laws", str("Who enforces what, and how honestly."))
            put("religion", str("What people believe, or perform believing."))
            put("dangers", strArray("Concrete everyday dangers."))
            put("history", strArray("Two or three events that shaped this place."))
            put("conflicts", strArray("Tensions already running before the player arrives."))
            put("special_rules", strArray("Anything that overrides ordinary physics — magic, technology, the supernatural. Empty if the world is mundane."))
            put("tone", str("", Tone.entries.map { it.name }))
            put("start_location_key", str("The key of the location the player begins in. Must be one you defined."))
            put("locations", arr(locationSchema(), "5 to 9 connected locations within walking distance."))
            put("npcs", arr(npcSchema(), "4 to 7 people who live here."))
            put("factions", arr(factionSchema(), "2 to 4 groups with conflicting aims."))
            put("threads", arr(threadSchema(), "1 to 3 situations already in motion."))
        },
    )

    fun openingsSchema(): JsonObject = obj(
        listOf("openings"),
        buildJsonObject {
            put(
                "openings",
                arr(openingSchema(), "5 to 6 genuinely different ways this character's story could begin here."),
            )
        },
    )

    private fun openingSchema() = obj(
        listOf("title", "situation", "pressure", "involves"),
        buildJsonObject {
            put("title", str("Four to seven words. What this opening is, not a chapter heading."))
            put(
                "situation",
                str(
                    "Two or three sentences in second person, present tense, describing the moment " +
                        "play begins. End before the player acts. Never say what they think, feel or decide.",
                ),
            )
            put("pressure", str("The one thing making this urgent right now."))
            put("involves", strArray("Keys of the people or factions involved, if any."))
        },
    )

    private fun locationSchema() = obj(
        listOf("key", "name", "description", "type", "exits", "hazards", "hidden_details"),
        buildJsonObject {
            put("key", str("Short lowercase identifier, letters and underscores only, e.g. 'salt_market'."))
            put("name", str("What people call it."))
            put("description", str("Two sentences. What it looks, sounds and smells like, and who is usually in it."))
            put("type", str("tavern, street, workshop, temple, dock, and so on."))
            put(
                "exits",
                arr(
                    obj(
                        listOf("label", "to"),
                        buildJsonObject {
                            put("label", str("How a person would describe going that way, e.g. 'down to the quay'."))
                            put("to", str("The key of another location you defined in this same world."))
                        },
                    ),
                    "Ways out. Every destination must be a location you defined.",
                ),
            )
            put("hazards", strArray("Things that can hurt someone here."))
            put("hidden_details", strArray("Things a careful person could discover here."))
        },
    )

    private fun npcSchema() = obj(
        listOf("key", "name", "age", "gender", "occupation", "appearance", "personality", "wants", "secret", "location_key", "faction_key"),
        buildJsonObject {
            put("key", str("Short lowercase identifier, e.g. 'mara'."))
            put("name", str())
            put("age", int("Required. An explicit number. Never omit or guess this."))
            put("gender", str())
            put("occupation", str("What they actually do all day."))
            put("appearance", str("Concrete visual facts: face, hair, skin, build, clothing, marks. No mood words."))
            put("personality", str("How they behave toward a stranger."))
            put("wants", str("What they are trying to get, right now."))
            put("secret", str("Something only they know. Nobody else starts aware of it."))
            put("location_key", str("Where they usually are. Must be a location you defined."))
            put("faction_key", nullableStr("Their faction, if any."))
        },
    )

    private fun factionSchema() = obj(
        listOf("key", "name", "purpose", "objectives", "reputation"),
        buildJsonObject {
            put("key", str("Short lowercase identifier."))
            put("name", str())
            put("purpose", str("What they exist to do."))
            put("objectives", strArray("What they are pursuing right now."))
            put("reputation", str("How ordinary people speak about them."))
        },
    )

    // --- primitives -----------------------------------------------------------------------------

    private fun obj(required: List<String>, props: JsonObject): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", props)
        putJsonArray("required") { required.forEach { add(it) } }
    }

    private fun arr(items: JsonObject, description: String = ""): JsonObject = buildJsonObject {
        put("type", "array")
        if (description.isNotEmpty()) put("description", description)
        put("items", items)
    }

    private fun str(description: String = "", enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        if (description.isNotEmpty()) put("description", description)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    }

    private fun nullableStr(description: String = ""): JsonObject = buildJsonObject {
        putJsonArray("type") { add("string"); add("null") }
        if (description.isNotEmpty()) put("description", description)
    }

    private fun int(description: String = ""): JsonObject = buildJsonObject {
        put("type", "integer")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun strArray(description: String = ""): JsonObject = buildJsonObject {
        put("type", "array")
        if (description.isNotEmpty()) put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    val THREAD_TYPES: List<String> = ThreadType.entries.map { it.name }
    val IMPORTANCES: List<String> = Importance.entries.map { it.name }

    private fun threadSchema() = obj(
        listOf("title", "description", "stakes", "type", "importance", "involved_keys"),
        buildJsonObject {
            put("title", str("Short. What the situation is called."))
            put("description", str("Two sentences. What is happening and who is pushing it."))
            put("stakes", str("What happens if nobody intervenes."))
            put("type", str("", THREAD_TYPES))
            put("importance", str("", IMPORTANCES))
            put("involved_keys", strArray("Keys of the people or factions caught up in it."))
        },
    )
}
