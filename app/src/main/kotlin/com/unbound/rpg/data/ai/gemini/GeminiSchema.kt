package com.unbound.rpg.data.ai.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Translates the engine's JSON Schema into the OpenAPI subset Gemini accepts as a `responseSchema`.
 *
 * The engine owns exactly one schema for a turn, and both providers are held to it — that is what
 * "provider parity" has to mean in practice, because a second schema would drift and one provider
 * would quietly start returning a different shape. So the translation happens here, at the edge,
 * and the shared schema stays the single definition.
 *
 * What differs, and why each is handled:
 *
 *  * Types are an enum in Gemini's dialect (`"STRING"`), not JSON Schema's lowercase strings.
 *  * `additionalProperties` does not exist there, and sending it is an error rather than a no-op.
 *  * Property order is not implied by the JSON object, so `propertyOrdering` is set explicitly —
 *    it measurably improves adherence on nested objects.
 *  * An empty `properties` map is rejected, so an untyped object degrades to a string.
 */
object GeminiSchema {

    fun translate(schema: JsonObject): JsonObject = convert(schema)

    private fun convert(node: JsonObject): JsonObject = buildJsonObject {
        // Read defensively: `type` is a primitive in most nodes and an array in a nullable union,
        // and reaching for .jsonPrimitive on the array form throws rather than returning null.
        val declared = (node["type"] as? JsonPrimitive)?.contentOrNull()

        // A union with "null" is JSON Schema's way of saying optional; Gemini spells that
        // `nullable`, and would reject the array form outright.
        val (type, nullable) = resolveType(node, declared)
        put("type", type)
        if (nullable) put("nullable", true)

        node["description"]?.let { put("description", it) }

        node["enum"]?.let { enum ->
            // Gemini only allows an enum on a string.
            if (type == "STRING") {
                put("enum", enum)
                put("format", "enum")
            }
        }

        when (type) {
            "OBJECT" -> {
                val properties = node["properties"]?.jsonObject
                if (properties.isNullOrEmpty()) {
                    // An object with no declared properties is not expressible; describing it as a
                    // string keeps the response parseable rather than failing the request.
                    return buildJsonObject {
                        put("type", "STRING")
                        node["description"]?.let { put("description", it) }
                    }
                }
                put(
                    "properties",
                    buildJsonObject { properties.forEach { (k, v) -> put(k, convert(v.jsonObject)) } },
                )
                node["required"]?.jsonArray?.let { put("required", it) }
                // Ordering is not carried by a JSON object, and stating it improves adherence.
                put("propertyOrdering", buildJsonArray { properties.keys.forEach { add(JsonPrimitive(it)) } })
            }

            "ARRAY" -> {
                val items = node["items"]?.jsonObject
                put("items", items?.let { convert(it) } ?: buildJsonObject { put("type", "STRING") })
            }
        }
    }

    /** Returns the Gemini type name and whether the node is nullable. */
    private fun resolveType(node: JsonObject, declared: String?): Pair<String, Boolean> {
        val typeNode = node["type"]
        if (typeNode is JsonArray) {
            val names = typeNode.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
            val nullable = names.any { it.equals("null", true) }
            val concrete = names.firstOrNull { !it.equals("null", true) } ?: "string"
            return geminiType(concrete) to nullable
        }
        val inferred = declared ?: if (node["properties"] != null) "object" else "string"
        return geminiType(inferred) to false
    }

    private fun geminiType(jsonSchemaType: String): String = when (jsonSchemaType.lowercase()) {
        "object" -> "OBJECT"
        "array" -> "ARRAY"
        "integer" -> "INTEGER"
        "number" -> "NUMBER"
        "boolean" -> "BOOLEAN"
        else -> "STRING"
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

}
