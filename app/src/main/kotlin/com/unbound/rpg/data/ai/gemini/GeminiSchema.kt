package com.unbound.rpg.data.ai.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Normalises the engine's JSON Schema for Gemini's `responseSchema`.
 *
 * This deliberately does **not** convert dialects. Gemini's structured output takes a subset of
 * standard JSON Schema — lowercase `type`, `properties`, `required`, `items`, `enum`,
 * `description`, `additionalProperties` — which is exactly what the engine already produces for
 * OpenAI. So the schema is passed through, and only the handful of things Gemini does not accept
 * are adjusted.
 *
 * An earlier version of this file translated into the older OpenAPI-flavoured `Schema` proto:
 * uppercase type names (`"STRING"`), `propertyOrdering`, `format: "enum"`, `nullable`. That dialect
 * is no longer what the API documents, and sending it turned a perfectly valid schema into one the
 * service rejected — world generation failed with a 500 before a single world could be built. The
 * lesson is worth keeping: translating a schema is a liability, and the less of it done the better.
 *
 * What is still adjusted, and why:
 *
 *  * `$schema` and `$defs`/`$ref` are dropped — the engine emits no references, and the meta key
 *    is not part of any request.
 *  * A `["string", "null"]` union becomes plain `"string"`, since the field is already absent from
 *    `required` and a union type is not in the documented subset.
 */
object GeminiSchema {

    fun translate(schema: JsonObject): JsonObject = normalise(schema)

    private fun normalise(node: JsonObject): JsonObject = buildJsonObject {
        for ((key, value) in node) {
            when (key) {
                // Not request fields. The engine emits no references, so nothing is lost.
                "\$schema", "\$id", "\$defs", "definitions" -> Unit

                "type" -> put("type", normaliseType(value))

                "properties" -> put(
                    "properties",
                    buildJsonObject {
                        value.jsonObject.forEach { (name, sub) -> put(name, normalise(sub.jsonObject)) }
                    },
                )

                "items" -> put("items", normalise(value.jsonObject))

                // Tuple-typed arrays, kept as-is apart from normalising each member.
                "prefixItems" -> put(
                    "prefixItems",
                    buildJsonArray { value.jsonArray.forEach { add(normalise(it.jsonObject)) } },
                )

                // anyOf/oneOf members are schemas in their own right.
                "anyOf", "oneOf" -> put(
                    key,
                    buildJsonArray { value.jsonArray.forEach { add(normalise(it.jsonObject)) } },
                )

                else -> put(key, value)
            }
        }
    }

    /**
     * A union with `"null"` collapses to the concrete type.
     *
     * The engine writes `["string", "null"]` for an optional field, which OpenAI's strict mode
     * requires because every property must appear in `required`. Gemini's subset does not document
     * union types, and the field is optional there by simply not being required — so the union is
     * flattened rather than risking a rejection over a distinction that carries no meaning here.
     */
    private fun normaliseType(value: JsonElement): JsonElement {
        if (value !is JsonArray) return value
        val names = value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull() }
        val concrete = names.firstOrNull { !it.equals("null", ignoreCase = true) }
        return JsonPrimitive(concrete ?: "string")
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
