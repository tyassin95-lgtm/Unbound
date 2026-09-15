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
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val clock: () -> Long = { System.currentTimeMillis() }
    val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "").take(20) }

    val database: UnboundDatabase by lazy { UnboundDatabase.get(appContext) }
    val store: WorldStore by lazy { RoomWorldStore(database) }

    val credentials: SecureCredentialStore by lazy { SecureCredentialStore(appContext) }
    val settings: AppSettings by lazy { AppSettings(appContext) }

    private val openAIClient: OpenAIClient by lazy { OpenAIClient(credentials) }
    val modelCatalog: OpenAIModelCatalog by lazy { OpenAIModelCatalog(openAIClient) }
    val provider: AIProvider by lazy { OpenAIProvider(openAIClient, modelCatalog) }

    val gameFactory: GameFactory by lazy { GameFactory(store, clock, idFactory) }
    val worldGenerator: WorldGenerator by lazy { WorldGenerator(provider) }
    val openingGenerator: OpeningGenerator by lazy { OpeningGenerator(provider) }
    val retriever: MemoryRetriever by lazy { MemoryRetriever(store) }
    val pipeline: TurnPipeline by lazy { TurnPipeline(store, provider, clock, idFactory, retriever) }
    val journal: JournalBuilder by lazy { JournalBuilder(store) }
    val saveSystem: SaveSystem by lazy { SaveSystem(store, clock, idFactory) }
    val snapshots: SnapshotService by lazy { SnapshotService(store, saveSystem, clock, idFactory) }
    val images: ImageService by lazy { ImageService(appContext, store, provider, clock, idFactory) }

    val costEstimator: CostEstimator by lazy {
        CostEstimator(modelCatalog.knownProfiles().associateBy { it.id })
    }

    /** Rebuilt after a live model fetch so cost estimates reflect what the account actually has. */
    @Volatile var liveCostEstimator: CostEstimator? = null

    fun estimator(): CostEstimator = liveCostEstimator ?: costEstimator
}
