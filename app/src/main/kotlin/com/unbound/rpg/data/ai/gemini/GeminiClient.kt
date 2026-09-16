package com.unbound.rpg.data.ai.gemini

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIException
import com.unbound.rpg.data.security.CredentialSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * HTTP for the Gemini API, and nothing else.
 *
 * The key is read from the Keystore per request and sent in the `x-goog-api-key` **header**.
 * Google's own quickstarts often show `?key=...` in the URL; this app does not do that, because a
 * query parameter ends up in proxy logs, browser history and crash reports, and the whole point of
 * the credential store is that the key never lands anywhere it can be read back.
 *
 * Verified against the current Gemini API: `POST /v1beta/models/{model}:generateContent` and
 * `GET /v1beta/models`.
 */
class GeminiClient(
    private val credentials: CredentialSource,
    private val baseUrl: String = DEFAULT_BASE_URL,
    httpClient: OkHttpClient? = null,
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // A long narrative turn on a thinking model legitimately takes a while, and timing out
        // early would fail a turn the player has already been charged for.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun hasCredential(): Boolean = credentials.hasKey()

    private fun authorized(path: String): Request.Builder {
        val key = credentials.readKey()
            ?: throw AIException(AIErrorKind.NO_CREDENTIAL, "No Gemini API key is configured on this device.")
        return Request.Builder()
            .url(baseUrl + path)
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
    }

    suspend fun postJson(path: String, body: JsonObject): JsonObject =
        execute(authorized(path).post(body.toString().toRequestBody(JSON_MEDIA)).build())

    suspend fun getJson(path: String): JsonObject = execute(authorized(path).get().build())

    /** Dispatch lives here so no caller can forget it; OkHttp's `execute` blocks. */
    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AIException(AIErrorKind.TIMEOUT, "The request to Gemini timed out.", cause = e)
        } catch (e: UnknownHostException) {
            throw AIException(AIErrorKind.NETWORK, "Could not reach Gemini. Check the network connection.", cause = e)
        } catch (e: SSLException) {
            throw AIException(AIErrorKind.NETWORK, "The secure connection to Gemini failed.", cause = e)
        } catch (e: IOException) {
            throw AIException(AIErrorKind.NETWORK, "The connection to Gemini failed.", cause = e)
        }

        response.use {
            val bodyText = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw classify(it, bodyText)
            try {
                json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                throw AIException(AIErrorKind.MALFORMED_RESPONSE, "Gemini returned a response this app could not parse.", cause = e)
            }
        }
    }

    /**
     * Maps Google's `error.status` onto the engine's error taxonomy.
     *
     * The free tier makes this matter more than it does for a paid-only provider: a player will
     * routinely hit RESOURCE_EXHAUSTED, and "you are out of free requests for now" is a different
     * thing to tell them than "your key is wrong".
     */
    // The message describes the *request*, never the turn: this client also serves world and
    // opening generation, and telling a player mid-world-build that "the turn was not applied"
    // names something that was never happening.
    private fun classify(response: Response, bodyText: String): AIException {
        val error = runCatching { json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject }.getOrNull()
        val status = error?.get("status")?.jsonPrimitive?.contentOrNullSafe()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNullSafe()
        val retryAfter = response.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong()

        val quotaExhausted = status == "RESOURCE_EXHAUSTED" &&
            message?.contains("quota", true) == true &&
            message.contains("billing", true)

        val kind = when {
            response.code == 400 && status == "INVALID_ARGUMENT" && message?.contains("API key", true) == true ->
                AIErrorKind.INVALID_KEY
            response.code == 401 -> AIErrorKind.INVALID_KEY
            response.code == 403 && status == "PERMISSION_DENIED" -> AIErrorKind.INVALID_KEY
            response.code == 404 -> AIErrorKind.MODEL_UNAVAILABLE
            response.code == 429 && quotaExhausted -> AIErrorKind.INSUFFICIENT_QUOTA
            response.code == 429 -> AIErrorKind.RATE_LIMITED
            response.code == 400 && message?.contains("not supported", true) == true -> AIErrorKind.UNSUPPORTED_FEATURE
            response.code == 400 -> AIErrorKind.UNSUPPORTED_FEATURE
            response.code in 500..599 -> AIErrorKind.SERVER_ERROR
            else -> AIErrorKind.UNKNOWN
        }

        val friendly = when (kind) {
            AIErrorKind.INVALID_KEY ->
                "Google rejected the Gemini API key. Check it in Settings, or replace it with a new one."
            AIErrorKind.INSUFFICIENT_QUOTA ->
                "This Gemini key has no quota left. Free-tier limits reset over time; a billed project removes them."
            AIErrorKind.RATE_LIMITED ->
                "Gemini is rate-limiting this key — the free tier allows only so many requests a minute. " +
                    "Try again " +
                    (retryAfter?.let { "in ${kotlin.math.max(1L, it / 1000)} seconds." } ?: "shortly.")
            AIErrorKind.MODEL_UNAVAILABLE -> "This key cannot use that model. Choose a different one in Settings."
            AIErrorKind.UNSUPPORTED_FEATURE -> "Gemini rejected something this game needs: ${message.orEmpty()}"
            AIErrorKind.SERVER_ERROR -> "Gemini had a server error. Nothing was changed — try again."
            else -> message ?: "Gemini returned HTTP ${response.code}."
        }
        return AIException(kind, friendly, retryAfterMs = retryAfter)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
