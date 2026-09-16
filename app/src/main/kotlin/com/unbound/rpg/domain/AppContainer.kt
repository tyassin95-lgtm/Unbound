package com.unbound.rpg.domain

import android.content.Context
import com.unbound.core.ai.AIProvider
import com.unbound.core.ai.CostEstimator
import com.unbound.core.content.OpeningGenerator
import com.unbound.core.content.WorldGenerator
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.MemoryRetriever
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.engine.WorldStore
import com.unbound.core.journal.JournalBuilder
import com.unbound.core.save.SaveSystem
import com.unbound.core.save.SnapshotService
import com.unbound.rpg.data.ai.openai.OpenAIClient
import com.unbound.rpg.data.ai.openai.OpenAIModelCatalog
import com.unbound.rpg.data.ai.ProviderIds
import com.unbound.rpg.data.ai.ProviderRegistry
import com.unbound.rpg.data.ai.gemini.GeminiClient
import com.unbound.rpg.data.ai.gemini.GeminiModelCatalog
import com.unbound.rpg.data.ai.gemini.GeminiProvider
import com.unbound.rpg.data.ai.openai.OpenAIProvider
import com.unbound.rpg.data.db.RoomWorldStore
import com.unbound.rpg.data.db.UnboundDatabase
import com.unbound.rpg.data.images.ImageService
import com.unbound.rpg.data.security.SecureCredentialStore
import com.unbound.rpg.data.settings.AppSettings
import java.util.UUID

/**
 * Manual dependency wiring.
 *
 * A DI framework would buy nothing here: there is one object graph, it is built once, and it is
 * constructed lazily so that opening the app does not open the database or touch the Keystore
 * until something actually needs them (§122).
 */
class AppContainer(
    context: Context,
    /**
     * Seams, not indirection for its own sake. Without them the whole app layer — sessions,
     * creation, the journal, images — could only be exercised against the real database and the
     * real OpenAI account, which means it could not be exercised at all.
     */
    private val databaseOverride: UnboundDatabase? = null,
    private val providerOverride: AIProvider? = null,
) {

    private val appContext = context.applicationContext

    val clock: () -> Long = { System.currentTimeMillis() }
    val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "").take(20) }

    val database: UnboundDatabase by lazy { databaseOverride ?: UnboundDatabase.get(appContext) }
    val store: WorldStore by lazy { RoomWorldStore(database) }

    /** One credential store per vendor: revoking or replacing one must not disturb the other. */
    val credentials: SecureCredentialStore by lazy { SecureCredentialStore(appContext, ProviderIds.OPENAI) }
    val geminiCredentials: SecureCredentialStore by lazy { SecureCredentialStore(appContext, ProviderIds.GEMINI) }

    fun credentialsFor(providerId: String): SecureCredentialStore =
        if (providerId == ProviderIds.GEMINI) geminiCredentials else credentials

    val settings: AppSettings by lazy { AppSettings(appContext) }

    private val openAIClient: OpenAIClient by lazy { OpenAIClient(credentials) }
    val modelCatalog: OpenAIModelCatalog by lazy { OpenAIModelCatalog(openAIClient) }
    private val openAIProvider: AIProvider by lazy { OpenAIProvider(openAIClient, modelCatalog) }

    private val geminiClient: GeminiClient by lazy { GeminiClient(geminiCredentials) }
    val geminiCatalog: GeminiModelCatalog by lazy { GeminiModelCatalog(geminiClient) }
    private val geminiProvider: AIProvider by lazy { GeminiProvider(geminiClient, geminiCatalog) }

    val registry: ProviderRegistry by lazy {
        ProviderRegistry(
            providers = mapOf(
                ProviderIds.OPENAI to openAIProvider,
                ProviderIds.GEMINI to geminiProvider,
            ),
            defaultProviderId = ProviderIds.OPENAI,
        )
    }

    /**
     * What the engine sees: one provider, which routes on the id each request carries.
     *
     * The fallback is opt-in and only covers a provider being unreachable — never a refusal, a bad
     * key or an exhausted account, because those would fail the same way twice or succeed in a way
     * the player did not ask for.
     */
    val provider: AIProvider by lazy {
        providerOverride ?: registry.routing(fallbackProviderId = fallbackProviderId)
    }

    /** Set from settings at startup and whenever the player changes it. */
    @Volatile var fallbackProviderId: String? = null

    val gameFactory: GameFactory by lazy { GameFactory(store, clock, idFactory) }
    val worldGenerator: WorldGenerator by lazy { WorldGenerator(provider) }
    val openingGenerator: OpeningGenerator by lazy { OpeningGenerator(provider) }
    val retriever: MemoryRetriever by lazy { MemoryRetriever(store) }
    val pipeline: TurnPipeline by lazy { TurnPipeline(store, provider, clock, idFactory, retriever) }
    val journal: JournalBuilder by lazy { JournalBuilder(store) }
    val saveSystem: SaveSystem by lazy { SaveSystem(store, clock, idFactory) }
    val snapshots: SnapshotService by lazy { SnapshotService(store, clock, idFactory) }
    val images: ImageService by lazy { ImageService(appContext, store, provider, clock, idFactory) }

    /**
     * Prices for both vendors, keyed by "provider/model" as well as bare model id.
     *
     * Two vendors ship models with overlapping names, so pooling on the bare id alone would price
     * one of them with the other's rates. The bare key stays as a fallback for rows written before
     * usage recorded a provider.
     */
    val costEstimator: CostEstimator by lazy { CostEstimator(offlinePriceTable()) }

    private fun offlinePriceTable(): Map<String, com.unbound.core.ai.ModelProfile> = buildMap {
        modelCatalog.knownProfiles().forEach {
            put("${ProviderIds.OPENAI}/${it.id}", it)
            putIfAbsent(it.id, it)
        }
        geminiCatalog.knownProfiles().forEach {
            put("${ProviderIds.GEMINI}/${it.id}", it)
            putIfAbsent(it.id, it)
        }
    }

    /** Rebuilt from a live listing so estimates reflect what the account actually has. */
    fun priceTableFrom(
        openAi: List<com.unbound.core.ai.ModelProfile>,
        gemini: List<com.unbound.core.ai.ModelProfile>,
    ): Map<String, com.unbound.core.ai.ModelProfile> = buildMap {
        putAll(offlinePriceTable())
        openAi.forEach { put("${ProviderIds.OPENAI}/${it.id}", it); put(it.id, it) }
        gemini.forEach { put("${ProviderIds.GEMINI}/${it.id}", it); putIfAbsent(it.id, it) }
    }

    /** Rebuilt after a live model fetch so cost estimates reflect what the account actually has. */
    @Volatile var liveCostEstimator: CostEstimator? = null

    fun estimator(): CostEstimator = liveCostEstimator ?: costEstimator
}
