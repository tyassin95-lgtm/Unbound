package com.unbound.core.prompt

import com.unbound.core.ledger.EventType
import com.unbound.core.model.Importance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON Schema handed to the provider for strict structured output.
 *
 * Strict mode on current OpenAI models requires that every property be listed in `required` and
 * that `additionalProperties` be false at every level — optionality is expressed by allowing null,
 * not by omitting the key. The DTO layer therefore treats every field as defaulted.
 */
object TurnSchema {

    fun schema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("narrative") {
                put("type", "string")
                put("description", "The prose shown to the player. Second person for the player.")
            }
            putJsonObject("time_advance_minutes") {
                put("type", "integer")
                put("description", "In-world minutes this action consumed. Never negative.")
            }
            putJsonObject("scene_is_significant") {
                put("type", "boolean")
                put("description", "True only for genuinely major moments; drives optional automatic imagery.")
            }
            put("events", arraySchema(eventSchema()))
            put("state_changes", arraySchema(stateChangeSchema()))
            put("knowledge_changes", arraySchema(knowledgeSchema()))
            put("relationship_changes", arraySchema(relationshipSchema()))
            put("memory_candidates", arraySchema(memorySchema()))
            put("thread_changes", arraySchema(threadSchema()))
            put("npc_actions", arraySchema(npcActionSchema()))
            put("world_changes", arraySchema(worldChangeSchema()))
            put("new_characters", arraySchema(newCharacterSchema()))
            putJsonObject("suggested_actions") {
                put("type", "array")
                put("description", "3-5 hints of different kinds. Never a whitelist of legal moves.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        putJsonArray("required") {
            listOf(
                "narrative", "time_advance_minutes", "scene_is_significant", "events", "state_changes",
                "knowledge_changes", "relationship_changes", "memory_candidates", "thread_changes",
                "npc_actions", "world_changes", "new_characters", "suggested_actions",
            ).forEach { add(it) }
        }
    }

    private fun arraySchema(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun obj(required: List<String>, props: JsonObject): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("properties", props)
        putJsonArray("required") { required.forEach { add(it) } }
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

    private fun bool(description: String = ""): JsonObject = buildJsonObject {
        put("type", "boolean")
        if (description.isNotEmpty()) put("description", description)
    }

    private fun strArray(description: String = ""): JsonObject = buildJsonObject {
        put("type", "array")
        if (description.isNotEmpty()) put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun eventSchema() = obj(
        listOf("type", "actor_id", "target_id", "location_id", "summary", "importance", "knowledge_scope", "witness_ids"),
        buildJsonObject {
            put("type", str("Event type.", EventType.entries.map { it.name }))
            put("actor_id", nullableStr("Entity id that acted."))
            put("target_id", nullableStr("Entity id acted upon."))
            put("location_id", nullableStr("Where it happened."))
            put("summary", str("One factual sentence, past tense, no prose flourishes."))
            put("importance", str("", Importance.entries.map { it.name }))
            put(
                "knowledge_scope",
                str(
                    "Who is in a position to learn this.",
                    listOf("PRIVATE", "SECRET", "WITNESSED", "LOCAL_RUMOR", "FACTION", "PUBLIC"),
                ),
            )
            put("witness_ids", strArray("Entity ids that specifically witnessed it."))
        },
    )

    private fun stateChangeSchema() = obj(
        listOf("type", "entity_id", "target_id", "amount", "item_id", "item_name", "location_id", "value", "reason"),
        buildJsonObject {
            put(
                "type",
                str(
                    "The mutation requested.",
                    listOf(
                        "CURRENCY_CHANGE", "HEALTH_CHANGE", "ITEM_TRANSFER", "ITEM_CREATE", "ITEM_DESTROY",
                        "PLAYER_MOVE", "NPC_MOVE", "NPC_DEATH", "NPC_EMOTION", "NPC_RELATIONSHIP_CHANGE",
                        "FACTION_STANDING_CHANGE", "FACTION_JOIN", "FACTION_LEAVE", "APPEARANCE_CHANGE",
                        "LOCATION_CONDITION_CHANGE", "WEATHER_CHANGE", "PLAYER_GOAL_CHANGE",
                    ),
                ),
            )
            put("entity_id", nullableStr("Subject of the change."))
            put("target_id", nullableStr("Second party, for transfers and relationships."))
            put("amount", int("Delta, never an absolute total."))
            put("item_id", nullableStr())
            put("item_name", nullableStr("Only for ITEM_CREATE."))
            put("location_id", nullableStr())
            put("value", nullableStr("Free-text value for weather, condition, mood, goal, appearance."))
            put("reason", str("Why this changed. Required for relationship changes."))
        },
    )

    private fun relationshipSchema() = obj(
        listOf("from_entity_id", "to_entity_id", "changes", "reason"),
        buildJsonObject {
            put("from_entity_id", str("Usually 'player'."))
            put("to_entity_id", str())
            putJsonObject("changes") {
                put("type", "object")
                put("additionalProperties", false)
                put("description", "Deltas, each -45..45.")
                putJsonObject("properties") {
                    listOf(
                        "trust", "respect", "affection", "fear", "suspicion",
                        "attraction", "loyalty", "resentment", "hostility", "obligation", "familiarity",
                    ).forEach { put(it, int()) }
                }
                putJsonArray("required") {
                    listOf(
                        "trust", "respect", "affection", "fear", "suspicion",
                        "attraction", "loyalty", "resentment", "hostility", "obligation", "familiarity",
                    ).forEach { add(it) }
                }
            }
            put("reason", str("Required. A relationship never changes for no reason."))
        },
    )

    private fun knowledgeSchema() = obj(
        listOf("knower_id", "fact_key", "statement", "certainty", "source_entity_id", "subject_entity_ids", "secret", "is_distorted"),
        buildJsonObject {
            put("knower_id", str("Who now holds this."))
            put("fact_key", str("Stable identifier for the proposition, e.g. 'mara.ring_stolen'."))
            put("statement", str("What they hold to be true, in their terms."))
            put("certainty", str("", listOf("KNOWN", "BELIEVED", "RUMORED", "SUSPECTED", "MISTAKEN", "UNKNOWN")))
            put("source_entity_id", nullableStr("Who they got it from. Required unless they witnessed it."))
            put("subject_entity_ids", strArray())
            put("secret", bool("True if they will not pass it on."))
            put("is_distorted", bool("True if what they hold differs from what actually happened."))
        },
    )

    private fun memorySchema() = obj(
        listOf("owner_id", "text", "entity_ids", "importance", "confidence"),
        buildJsonObject {
            put("owner_id", str("Whose memory. 'world' for global."))
            put("text", str("One compact durable sentence."))
            put("entity_ids", strArray())
            put("importance", str("", Importance.entries.map { it.name }))
            putJsonObject("confidence") { put("type", "number"); put("description", "0.0 to 1.0") }
        },
    )

    private fun threadSchema() = obj(
        listOf("thread_id", "action", "title", "description", "type", "stakes", "involved_entity_ids", "importance", "deadline_in_minutes"),
        buildJsonObject {
            put("thread_id", nullableStr("Null when starting a new thread."))
            put("action", str("", listOf("START", "ADVANCE", "STALL", "RESOLVE", "FAIL", "TRANSFORM")))
            put("title", str())
            put("description", str())
            put(
                "type",
                str(
                    "",
                    listOf("PERSONAL", "DEBT", "FEUD", "ROMANCE", "POLITICAL", "CRIMINAL", "MYSTERY", "SURVIVAL", "ECONOMIC", "FACTION", "OTHER"),
                ),
            )
            put("stakes", str("What happens if nobody acts."))
            put("involved_entity_ids", strArray())
            put("importance", str("", Importance.entries.map { it.name }))
            putJsonObject("deadline_in_minutes") {
                putJsonArray("type") { add("integer"); add("null") }
                put("description", "In-world minutes from now until this resolves itself.")
            }
        },
    )

    private fun npcActionSchema() = obj(
        listOf("npc_id", "action", "moves_to_location_id", "becomes_hostile"),
        buildJsonObject {
            put("npc_id", str())
            put("action", str("What they did, one sentence."))
            put("moves_to_location_id", nullableStr())
            put("becomes_hostile", bool())
        },
    )

    private fun newCharacterSchema() = obj(
        listOf("name", "age", "gender", "appearance", "occupation", "personality", "wants", "location_id"),
        buildJsonObject {
            put("name", str("The character's name."))
            put("age", int("Required. An explicit integer age. Never omit or guess this."))
            put("gender", str())
            put("appearance", str("Concrete visual facts: face, hair, skin, build, clothing, marks."))
            put("occupation", str())
            put("personality", str())
            put("wants", str("What they are trying to get."))
            put("location_id", nullableStr("Where they are. Defaults to the player's location."))
        },
    )

    private fun worldChangeSchema() = obj(
        listOf("type", "location_id", "faction_id", "value", "description"),
        buildJsonObject {
            put("type", str("", listOf("WEATHER", "LOCATION_STATE", "FACTION_STATE", "ECONOMY", "OTHER")))
            put("location_id", nullableStr())
            put("faction_id", nullableStr())
            put("value", str())
            put("description", str())
        },
    )
}
