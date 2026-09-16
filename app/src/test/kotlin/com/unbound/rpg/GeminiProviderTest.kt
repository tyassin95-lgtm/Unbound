package com.unbound.rpg

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.ai.AIException
import com.unbound.core.ai.AITextRequest
import com.unbound.core.prompt.TurnSchema
import com.unbound.rpg.data.ai.gemini.GeminiClient
import com.unbound.rpg.data.ai.gemini.GeminiModelCatalog
import com.unbound.rpg.data.ai.gemini.GeminiProvider
import com.unbound.rpg.data.ai.gemini.GeminiSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Gemini integration, against a real HTTP server serving recorded-shape responses.
 *
 * No live call is made — there is no key here, and a test that needed one could not run — but the
 * request that leaves the app and the response it parses are the genuine article, so a wrong field
 * name fails here rather than on a player's device.
 */
class GeminiProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider
    private val json = Json { ignoreUnknownKeys = true }

    /** Holds a key without touching the Android Keystore, which no unit test can reach. */
    private val credentials = object : com.unbound.rpg.data.security.CredentialSource {
        override fun hasKey() = true
        override fun readKey() = TEST_KEY
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = GeminiClient(credentials, baseUrl = server.url("/v1beta").toString().trimEnd('/'))
        provider = GeminiProvider(client, GeminiModelCatalog(client))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the key travels in a header and never in the URL`() = runTest {
        server.enqueue(MockResponse().setBody(TURN_RESPONSE))

        provider.generateText(
            AITextRequest(
                modelId = "gemini-2.5-flash",
                stableSystemPrompt = "rules",
                dynamicContext = "context",
                userInput = "I wait.",
                jsonSchema = TurnSchema.schema(),
            ),
        )

        val recorded = server.takeRequest()
        assertEquals(TEST_KEY, recorded.getHeader("x-goog-api-key"))
        // Google's own quickstarts put it in ?key=, which lands in proxy logs and history.
        assertFalse("The key must never appear in the URL", recorded.path!!.contains(TEST_KEY))
        assertFalse(recorded.path!!.contains("key="))
        assertTrue(recorded.path!!.contains("/models/gemini-2.5-flash:generateContent"))
    }

    @Test
    fun `the request carries the stable prompt, the schema and the usage comes back`() = runTest {
        server.enqueue(MockResponse().setBody(TURN_RESPONSE))

        val response = provider.generateText(
            AITextRequest(
                modelId = "gemini-2.5-flash",
                stableSystemPrompt = "STABLE RULES",
                dynamicContext = "## WORLD\nthings",
                userInput = "I wait.",
                jsonSchema = TurnSchema.schema(),
                maxOutputTokens = 2000,
            ),
        )

        val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        // The stable half goes where a prompt cache can see it, unmixed with the volatile half.
        assertEquals(
            "STABLE RULES",
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
        val userText = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(userText.contains("## WORLD"))
        assertTrue(userText.contains("I wait."))

        val config = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]?.jsonPrimitive?.content)
        assertEquals(2000, config["maxOutputTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("OBJECT", config["responseSchema"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        assertTrue(response.text.contains("She does not look up"))
        assertEquals(1200, response.usage.inputTokens)
        assertEquals(340, response.usage.outputTokens)
        assertEquals(1024, response.usage.cachedInputTokens)
        assertEquals("gemini-2.5-flash", response.modelId)
    }

    @Test
    fun `a rate limit is told apart from an exhausted account and from a bad key`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for requests per minute."}}""",
            ),
        )
        val rateLimited = runCatching { simpleTurn() }.exceptionOrNull() as AIException
        assertEquals(AIErrorKind.RATE_LIMITED, rateLimited.kind)
        assertTrue("A free-tier player needs to know it resets", rateLimited.message.contains("free tier"))

        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota. Enable billing."}}""",
            ),
        )
        assertEquals(
            AIErrorKind.INSUFFICIENT_QUOTA,
            (runCatching { simpleTurn() }.exceptionOrNull() as AIException).kind,
        )

        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}""",
            ),
        )
        assertEquals(
            AIErrorKind.INVALID_KEY,
            (runCatching { simpleTurn() }.exceptionOrNull() as AIException).kind,
        )
    }

    @Test
    fun `a safety block is a refusal, not a malformed response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}""",
            ),
        )
        val e = runCatching { simpleTurn() }.exceptionOrNull() as AIException
        assertEquals(AIErrorKind.CONTENT_REFUSED, e.kind)
    }

    @Test
    fun `a truncated response is flagged so the turn is not half-committed`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\"narrative\":\"cut off"}]}}]}""",
            ),
        )
        assertTrue(simpleTurn().finishedEarly)
    }

    @Test
    fun `model discovery reads capabilities from the account listing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"models":[
                     {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash",
                      "inputTokenLimit":1048576,"outputTokenLimit":65536,
                      "supportedGenerationMethods":["generateContent","countTokens"]},
                     {"name":"models/text-embedding-004","displayName":"Embedding",
                      "supportedGenerationMethods":["embedContent"]},
                     {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro",
                      "supportedGenerationMethods":["generateContent"]}
                   ]}""",
            ),
        )

        val models = provider.listModels()

        assertEquals("An embedding model cannot run a story and must not be offered", 2, models.size)
        val flash = models.first { it.id == "gemini-2.5-flash" }
        assertTrue(flash.supportsText)
        assertTrue(flash.supportsStructuredOutput)
        assertEquals(1048576, flash.contextTokens)
        assertTrue("Live listings must be marked as such", flash.fromLiveCatalog)
        assertTrue("Prices are estimates but should be present for known families", flash.inputCostPerMillion != null)
    }

    private suspend fun simpleTurn() = provider.generateText(
        AITextRequest(
            modelId = "gemini-2.5-flash",
            stableSystemPrompt = "rules",
            dynamicContext = "context",
            userInput = "I wait.",
            jsonSchema = TurnSchema.schema(),
        ),
    )

    @Test
    fun `the turn schema survives translation into Gemini's dialect`() {
        val translated = GeminiSchema.translate(TurnSchema.schema())

        assertEquals("OBJECT", translated["type"]?.jsonPrimitive?.content)
        // additionalProperties is not part of Gemini's dialect and is an error, not a no-op.
        assertNull("additionalProperties must not survive", translated["additionalProperties"])
        assertTrue(translated.containsKey("propertyOrdering"))

        val properties = translated["properties"]!!.jsonObject
        assertEquals("STRING", properties["narrative"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("INTEGER", properties["time_advance_minutes"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        val events = properties["events"]!!.jsonObject
        assertEquals("ARRAY", events["type"]?.jsonPrimitive?.content)
        assertEquals("OBJECT", events["items"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        // Nested objects must be translated too, not left in the original dialect.
        fun assertNoLowercaseTypes(node: JsonObject) {
            node["type"]?.jsonPrimitive?.content?.let {
                assertTrue("Untranslated type '$it'", it == it.uppercase())
            }
            assertNull("additionalProperties left behind", node["additionalProperties"])
            node["properties"]?.jsonObject?.values?.forEach { assertNoLowercaseTypes(it.jsonObject) }
            node["items"]?.jsonObject?.let { assertNoLowercaseTypes(it) }
        }
        assertNoLowercaseTypes(translated)
    }

    @Test
    fun `an enum is carried across only where Gemini allows one`() {
        val schema = json.parseToJsonElement(
            """{"type":"object","properties":{
                 "kind":{"type":"string","enum":["A","B"]},
                 "count":{"type":"integer","enum":[1,2]}
               },"required":["kind"],"additionalProperties":false}""",
        ).jsonObject

        val out = GeminiSchema.translate(schema)["properties"]!!.jsonObject
        assertEquals(listOf("A", "B"), out["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull("An enum on a non-string is not expressible", out["count"]!!.jsonObject["enum"])
    }

    @Test
    fun `a nullable union becomes Gemini's nullable flag`() {
        val schema = json.parseToJsonElement(
            """{"type":"object","properties":{"who":{"type":["string","null"]}}}""",
        ).jsonObject
        val who = GeminiSchema.translate(schema)["properties"]!!.jsonObject["who"]!!.jsonObject
        assertEquals("STRING", who["type"]?.jsonPrimitive?.content)
        assertEquals(true, who["nullable"]?.jsonPrimitive?.content?.toBoolean())
    }

    private companion object {
        const val TEST_KEY = "AIzaTESTKEYTESTKEYTESTKEY"

        val TURN_RESPONSE = """
            {
              "candidates": [{
                "content": {"parts": [{"text": "{\"narrative\":\"She does not look up.\"}"}], "role": "model"},
                "finishReason": "STOP"
              }],
              "usageMetadata": {
                "promptTokenCount": 1200,
                "candidatesTokenCount": 340,
                "cachedContentTokenCount": 1024,
                "totalTokenCount": 1540
              },
              "modelVersion": "gemini-2.5-flash"
            }
        """.trimIndent()
    }
}
