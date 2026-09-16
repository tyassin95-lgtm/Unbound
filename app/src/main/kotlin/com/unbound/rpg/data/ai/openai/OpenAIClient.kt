package com.unbound.rpg.data.ai.openai

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIException
import com.unbound.rpg.data.security.SecureCredentialStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The only place in the application that holds an OpenAI credential in memory, and only for the
 * duration of a single call.
 *
 * The key is read from the Keystore per request and used to build one header. It is never cached in
 * a field, never logged (see [com.unbound.rpg.data.security.SafeLog]), and never placed in a URL
 * or a query parameter where it could end up in a proxy log.
 *
 * Verified against the official OpenAI OpenAPI specification (v2.3.0): `POST /v1/responses`,
 * `GET /v1/models`, `POST /v1/images/generations` and `POST /v1/images/edits`.
 */
class OpenAIClient(
    private val credentials: SecureCredentialStore,
    private val baseUrl: String = DEFAULT_BASE_URL,
    httpClient: OkHttpClient? = null,
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Generous: a long narrative turn on a reasoning model legitimately takes a while, and
        // timing out early would fail a turn the player has already paid for.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun hasCredential(): Boolean = credentials.hasKey()

    private fun authorizedBuilder(path: String): Request.Builder {
        val key = credentials.readKey()
            ?: throw AIException(AIErrorKind.NO_CREDENTIAL, "No OpenAI API key is configured on this device.")
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
    }

    suspend fun postJson(path: String, body: JsonObject): JsonObject =
        execute(authorizedBuilder(path).post(body.toString().toRequestBody(JSON_MEDIA)).build())

    suspend fun postMultipart(path: String, body: RequestBody): JsonObject {
        val key = credentials.readKey()
            ?: throw AIException(AIErrorKind.NO_CREDENTIAL, "No OpenAI API key is configured on this device.")
        return execute(
            Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $key").post(body).build(),
        )
    }

    suspend fun getJson(path: String): JsonObject = execute(authorizedBuilder(path).get().build())

    /**
     * Every request goes through here, and the dispatch to IO lives here rather than at each call
     * site. OkHttp's `execute` blocks, and a caller that forgot to wrap it — `listModels` did —
     * would block whatever thread it was on, which on Android means the main thread.
     */
    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AIException(AIErrorKind.TIMEOUT, "The request to OpenAI timed out.", cause = e)
        } catch (e: UnknownHostException) {
            throw AIException(AIErrorKind.NETWORK, "Could not reach OpenAI. Check the network connection.", cause = e)
        } catch (e: SSLException) {
            throw AIException(AIErrorKind.NETWORK, "The secure connection to OpenAI failed.", cause = e)
        } catch (e: IOException) {
            throw AIException(AIErrorKind.NETWORK, "The connection to OpenAI failed.", cause = e)
        }

        response.use {
            val bodyText = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw classify(it, bodyText)
            try {
                json.parseToJsonElement(bodyText).jsonObject
            } catch (e: Exception) {
                throw AIException(AIErrorKind.MALFORMED_RESPONSE, "OpenAI returned a response this app could not parse.", cause = e)
            }
        }
    }

    /**
     * Maps HTTP status and the documented error `code`/`type` onto the engine's error taxonomy, so
     * the UI can say something true and specific — "your key was rejected" rather than "error 401".
     */
    private fun classify(response: Response, bodyText: String): AIException {
        val error = runCatching {
            json.parseToJsonElement(bodyText).jsonObject["error"]?.jsonObject
        }.getOrNull()
        val code = error?.get("code")?.jsonPrimitive?.contentOrNullSafe()
        val type = error?.get("type")?.jsonPrimitive?.contentOrNullSafe()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNullSafe()
        val retryAfter = response.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong()

        val kind = when {
            response.code == 401 -> AIErrorKind.INVALID_KEY
            response.code == 403 && code == "account_deactivated" -> AIErrorKind.REVOKED_KEY
            response.code == 403 -> AIErrorKind.INVALID_KEY
            response.code == 429 && (code == "insufficient_quota" || type == "insufficient_quota") -> AIErrorKind.INSUFFICIENT_QUOTA
            response.code == 429 -> AIErrorKind.RATE_LIMITED
            response.code == 404 && (code == "model_not_found" || message?.contains("model", true) == true) -> AIErrorKind.MODEL_UNAVAILABLE
            response.code == 400 && message?.contains("does not support", true) == true -> AIErrorKind.UNSUPPORTED_FEATURE
            response.code == 400 && (code == "content_policy_violation" || type == "image_generation_user_error") -> AIErrorKind.CONTENT_REFUSED
            response.code in 500..599 -> AIErrorKind.SERVER_ERROR
            else -> AIErrorKind.UNKNOWN
        }

        val friendly = when (kind) {
            AIErrorKind.INVALID_KEY -> "OpenAI rejected the API key. Check it in Settings, or replace it with a new one."
            AIErrorKind.REVOKED_KEY -> "This OpenAI key has been revoked or the account is deactivated."
            AIErrorKind.INSUFFICIENT_QUOTA -> "The OpenAI account has no remaining credit. Billing is handled in your OpenAI account."
            AIErrorKind.RATE_LIMITED -> "OpenAI is rate-limiting this key. The turn was not applied — try again " +
                (retryAfter?.let { "in ${kotlin.math.max(1L, it / 1000)} seconds." } ?: "shortly.")
            AIErrorKind.MODEL_UNAVAILABLE -> "This account cannot use that model. Choose a different one in Settings."
            AIErrorKind.UNSUPPORTED_FEATURE -> "The selected model does not support something this game needs: ${message.orEmpty()}"
            AIErrorKind.CONTENT_REFUSED -> "OpenAI declined this request: ${message.orEmpty()}"
            AIErrorKind.SERVER_ERROR -> "OpenAI had a server error. The turn was not applied — try again."
            else -> message ?: "OpenAI returned HTTP ${response.code}."
        }
        return AIException(kind, friendly, retryAfterMs = retryAfter)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { if (this is kotlinx.serialization.json.JsonNull) null else content }.getOrNull()
