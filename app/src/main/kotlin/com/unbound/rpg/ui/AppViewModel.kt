package com.unbound.rpg.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unbound.core.ai.CostEstimator
import com.unbound.core.content.SeedWorld
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.model.Appearance
import com.unbound.core.model.ImageMode
import com.unbound.core.model.NarrationLength
import com.unbound.core.model.RequestType
import com.unbound.core.model.UsageRecord
import com.unbound.rpg.data.security.SecureCredentialStore
import com.unbound.rpg.domain.AppContainer
import com.unbound.rpg.domain.CreationOutcome
import com.unbound.rpg.domain.CreationSpec
import com.unbound.rpg.domain.GameCreator
import com.unbound.rpg.domain.OpeningSuggestions
import com.unbound.rpg.ui.saves.SaveSummary
import com.unbound.rpg.data.ai.ProviderIds
import com.unbound.rpg.ui.settings.ProviderUiState
import com.unbound.rpg.ui.settings.SettingsUiState
import com.unbound.rpg.ui.setup.NewGameDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-level state: the save list, the settings screen, and game creation. One instance for the
 * lifetime of the process; the per-campaign state lives in [com.unbound.rpg.ui.play.PlayViewModel].
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val _saves = MutableStateFlow<List<SaveSummary>>(emptyList())
    val saves: StateFlow<List<SaveSummary>> = _saves.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState())
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _creation = MutableStateFlow<CreationState>(CreationState.Idle)
    val creation: StateFlow<CreationState> = _creation.asStateFlow()

    /** A world the player invented, once it has been generated. */
    private val _customWorld = MutableStateFlow<SeedWorld?>(null)
    val customWorld: StateFlow<SeedWorld?> = _customWorld.asStateFlow()

    /** Ways this character's story could begin here, plus what they have in their pockets. */
    private val _openings = MutableStateFlow(OpeningSuggestions())
    val openings: StateFlow<OpeningSuggestions> = _openings.asStateFlow()

    private val _createdGameId = MutableStateFlow<String?>(null)
    val createdGameId: StateFlow<String?> = _createdGameId.asStateFlow()

    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()

    /** One-shot messages for things that succeed or fail out of sight of the screen. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            container.settings.state.collect { s ->
                _settings.update { it.copy(settings = s) }
                // The routing provider reads this when a turn fails, so it has to track settings.
                container.fallbackProviderId = s.fallbackProviderId
            }
        }
        refreshSaves()
        refreshCredentialState()
        // Show whatever is known offline immediately; a live fetch replaces it when asked.
        _settings.update { state ->
            state.copy(providers = state.providers.map { it.copy(models = offlineModels(it.id)) })
        }
        refreshUsage()
        // Reclaim anything a crash or an older build left behind, once, at launch.
        viewModelScope.launch { runCatching { container.images.sweepOrphans() } }
    }

    /** True when *any* provider can serve a turn — the app needs one key, not both. */
    fun hasCredential(): Boolean = container.registry.available.any { container.credentialsFor(it.id).hasKey() }

    fun refreshSaves() = viewModelScope.launch {
        val list = container.store.listGames().map { game ->
            SaveSummary(
                game = game,
                characterName = container.store.getPlayer(game.id)?.name ?: "—",
                locationName = container.store.getLocation(game.id, game.currentLocationId)?.name ?: "Unknown",
            )
        }
        _saves.value = list
    }

    fun refreshUsage() = viewModelScope.launch {
        // Rolled up in SQL, per model and per provider. Loading the most recent 2,000 records and
        // adding them up was only a total until the 2,001st request.
        val aggregates = container.store.usageByModel(null)
        val cacheBytes = container.images.cacheSizeBytes()
        _settings.update {
            it.copy(
                usage = container.estimator().summariseAggregates(aggregates),
                imageCacheBytes = cacheBytes,
            )
        }
    }

    /** Rebuilds the per-provider view: who is configured, with what, and reachable. */
    private fun refreshCredentialState() {
        val rows = container.registry.available.map { info ->
            val existing = _settings.value.provider(info.id)
            (existing ?: ProviderUiState(info.id, info.displayName)).copy(
                displayName = info.displayName,
                maskedKey = container.credentialsFor(info.id).maskedKey(),
            )
        }
        _settings.update { it.copy(providers = rows) }
    }

    private fun updateProvider(id: String, block: (ProviderUiState) -> ProviderUiState) {
        _settings.update { state ->
            state.copy(providers = state.providers.map { if (it.id == id) block(it) else it })
        }
    }

    // --- credentials --------------------------------------------------------------------------
    fun storeKey(providerId: String, raw: String) {
        container.credentialsFor(providerId).storeKey(raw)
        refreshCredentialState()
        updateProvider(providerId) { it.copy(testResult = null, testSucceeded = null) }
        // A first key should leave the player able to play, not staring at an empty model list.
        if (_settings.value.providers.count { it.connected } == 1) {
            viewModelScope.launch { container.settings.setTextProvider(providerId) }
        }
        refreshModels(providerId)
    }

    fun removeKey(providerId: String) {
        container.credentialsFor(providerId).clear()
        refreshCredentialState()
        updateProvider(providerId) {
            it.copy(testResult = null, testSucceeded = null, models = offlineModels(providerId))
        }
        // Never leave the game pointing at a provider it can no longer reach, and never silently
        // substitute one either: the choice moves to whatever key is still present, or nowhere.
        val current = _settings.value
        if (current.settings.textProviderId == providerId) {
            current.providers.firstOrNull { it.connected }?.let { remaining ->
                viewModelScope.launch { container.settings.setTextProvider(remaining.id) }
            }
        }
        if (current.settings.fallbackProviderId == providerId) {
            viewModelScope.launch { container.settings.setFallbackProvider(null) }
        }
    }

    fun testConnection(providerId: String) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(testing = true, testResult = null, testSucceeded = null) }
        val provider = container.registry.providerOrNull(providerId)
        if (provider == null) {
            updateProvider(providerId) { it.copy(testing = false, testResult = "No such provider.", testSucceeded = false) }
            return@launch
        }
        val result = withContext(Dispatchers.IO) { provider.testConnection() }
        updateProvider(providerId) {
            it.copy(testing = false, testResult = result.message, testSucceeded = result.ok)
        }
        if (result.ok) refreshModels(providerId)
    }

    /** What this vendor is known to offer without a network call, so a list is never blank. */
    private fun offlineModels(providerId: String) = when (providerId) {
        ProviderIds.GEMINI -> container.geminiCatalog.knownProfiles()
        else -> container.modelCatalog.knownProfiles()
    }

    fun refreshModels(providerId: String) = viewModelScope.launch {
        updateProvider(providerId) { it.copy(loadingModels = true, modelError = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                when (providerId) {
                    ProviderIds.GEMINI -> container.geminiCatalog.listModels(forceRefresh = true)
                    else -> container.modelCatalog.listModels(forceRefresh = true)
                }
            }
        }
        result.onSuccess { models ->
            updateProvider(providerId) { it.copy(loadingModels = false, models = models) }
            rebuildPrices()
            refreshUsage()
            // Pick a sensible default the first time, rather than leaving the player stuck. Only
            // for the provider actually selected, and only when nothing is chosen yet: silently
            // changing a model the player picked is exactly what §3 forbids.
            val current = _settings.value
            if (providerId == current.settings.textProviderId && current.settings.defaultTextModelId == null) {
                models.firstOrNull { it.supportsStructuredOutput }?.let { setTextModel(it.id) }
            }
        }.onFailure { error ->
            updateProvider(providerId) {
                it.copy(
                    loadingModels = false,
                    modelError = error.message ?: "Could not fetch the model list.",
                    models = it.models.ifEmpty { offlineModels(providerId) },
                )
            }
        }
    }

    /** Cost estimates should reflect the models each account actually has. */
    private fun rebuildPrices() {
        val state = _settings.value
        container.liveCostEstimator = CostEstimator(
            container.priceTableFrom(
                openAi = state.provider(ProviderIds.OPENAI)?.models.orEmpty(),
                gemini = state.provider(ProviderIds.GEMINI)?.models.orEmpty(),
            ),
        )
    }

    // --- provider choice ----------------------------------------------------------------------
    fun setTextProvider(id: String) = viewModelScope.launch {
        container.settings.setTextProvider(id)
        // The model belongs to the old provider and almost certainly does not exist on the new
        // one, so it is cleared rather than carried across and failing on the next turn.
        container.settings.setTextModel(null)
        refreshModels(id)
    }

    fun setImageProvider(id: String?) = viewModelScope.launch {
        container.settings.setImageProvider(id)
        container.settings.setImageModel(null)
        id?.let { refreshModels(it) }
    }

    fun setFallbackProvider(id: String?) = viewModelScope.launch {
        container.settings.setFallbackProvider(id)
        container.fallbackProviderId = id
    }

    // --- settings -----------------------------------------------------------------------------
    fun setTextModel(id: String) = viewModelScope.launch { container.settings.setTextModel(id) }
    fun setImageModel(id: String?) = viewModelScope.launch { container.settings.setImageModel(id) }
    fun setImageMode(mode: ImageMode) = viewModelScope.launch { container.settings.setImageMode(mode) }
    /**
     * Narration length is a reading preference rather than a property of any one story, so it is
     * applied to every existing campaign as well as to new ones — otherwise changing it in Settings
     * would appear to do nothing to the game actually being played.
     */
    fun setNarrationLength(length: NarrationLength) = viewModelScope.launch {
        container.settings.setNarrationLength(length)
        container.store.listGames().forEach { game ->
            if (game.narrationLength != length) {
                container.store.upsertGame(game.copy(narrationLength = length))
            }
        }
    }
    fun setSuggestions(enabled: Boolean) = viewModelScope.launch { container.settings.setSuggestedActions(enabled) }
    fun setReducedMotion(enabled: Boolean) = viewModelScope.launch { container.settings.setReducedMotion(enabled) }
    fun setDeveloperMode(enabled: Boolean) = viewModelScope.launch { container.settings.setDeveloperMode(enabled) }
    fun setLimits(limits: List<String>) = viewModelScope.launch { container.settings.setPersonalLimits(limits) }

    fun clearImageCache() = viewModelScope.launch {
        container.store.listGames().forEach { container.images.clearCache(it.id) }
        refreshUsage()
    }

    // --- games ---------------------------------------------------------------------------------

    private val creator by lazy {
        GameCreator(
            store = container.store,
            gameFactory = container.gameFactory,
            pipeline = container.pipeline,
            worldGenerator = container.worldGenerator,
            openingGenerator = container.openingGenerator,
            clock = container.clock,
            idFactory = container.idFactory,
        )
    }

    /** Builds a world from the player's own premise (§16). */
    fun generateCustomWorld(draft: NewGameDraft) = viewModelScope.launch {
        val spec = specFor(draft) ?: return@launch
        if (draft.premise.isBlank()) return@launch
        _customWorld.value = null
        _openings.value = OpeningSuggestions()
        creator.generateWorld(spec) { _creation.value = it }
            .onSuccess { _customWorld.value = it }
    }

    /**
     * Openings are generated once the character *and* the world are both known, so they are
     * specific to this person in this place rather than generic.
     */
    fun generateOpenings(draft: NewGameDraft) = viewModelScope.launch {
        val seed = draft.seed ?: return@launch
        val spec = specFor(draft) ?: return@launch
        _openings.value = creator.generateOpenings(seed, spec) { _creation.value = it }
    }

    fun createGame(draft: NewGameDraft) = viewModelScope.launch {
        val seed = draft.seed ?: return@launch
        val spec = specFor(draft) ?: return@launch

        when (val outcome = creator.create(seed, spec, draft.chosenOpening()) { _creation.value = it }) {
            is CreationOutcome.Created -> {
                refreshSaves()
                refreshUsage()
                // The world exists and is playable; only the first paragraph failed. Said plainly
                // where the player will see it rather than buried in a model list.
                outcome.openingWarning?.let { _notice.value = it }
                _createdGameId.value = outcome.game.id
            }
            is CreationOutcome.Failed -> Unit // the progress state already carries the failure
        }
    }

    fun clearGeneratedWorld() {
        _customWorld.value = null
        _openings.value = OpeningSuggestions()
    }

    fun dismissCreationError() {
        _creation.value = CreationState.Idle
    }

    private fun specFor(draft: NewGameDraft): CreationSpec? {
        val settings = _settings.value.settings
        val model = settings.defaultTextModelId
        if (model == null) {
            _creation.value = CreationState.Failed(
                "Choose a story model in Settings before starting a game.",
                CreationStage.BUILDING_WORLD,
            )
            return null
        }
        return CreationSpec(
            name = draft.name.trim(),
            age = draft.ageInt ?: 18,
            gender = draft.gender.trim(),
            appearance = draft.appearance.trim(),
            personality = draft.personality.trim(),
            desires = draft.desires.trim(),
            fears = draft.fears.trim(),
            skills = draft.skills.split(',').map { it.trim() }.filter { it.isNotBlank() },
            weaknesses = draft.weaknesses.split(',').map { it.trim() }.filter { it.isNotBlank() },
            background = draft.background.trim(),
            goals = listOfNotNull(draft.goal.trim().takeIf { it.isNotBlank() }),
            secrets = listOfNotNull(draft.secret.trim().takeIf { it.isNotBlank() }),
            // Whatever the player settled on, which is the generated suggestion unless they
            // changed it. Never a fixed default.
            startingCurrency = draft.currencyAmount,
            startingPossessions = draft.startingPossessions,
            textModelId = model,
            textProviderId = settings.textProviderId,
            imageProviderId = settings.imageProviderId,
            imageModelId = settings.defaultImageModelId,
            imageMode = settings.imageMode,
            tone = draft.tone,
            narrationLength = settings.narrationLength,
            limits = draft.limits.split(',').map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { settings.personalLimits },
            premise = draft.premise.trim(),
        )
    }

    fun consumeCreatedGame() { _createdGameId.value = null }

    fun renameGame(gameId: String, title: String) = viewModelScope.launch {
        container.store.getGame(gameId)?.let { container.store.upsertGame(it.copy(title = title, updatedAtEpochMs = container.clock())) }
        refreshSaves()
    }

    fun duplicateGame(gameId: String) = viewModelScope.launch {
        val original = container.store.getGame(gameId) ?: return@launch
        runCatching { container.saveSystem.duplicate(gameId, original.title + " (copy)") }
            .onSuccess { _notice.value = "Copied \"${original.title}\"." }
            .onFailure { _notice.value = "That save could not be copied: ${it.reason()}" }
        refreshSaves()
    }

    fun deleteGame(gameId: String) = viewModelScope.launch {
        // Files first: the rows are what name them, so deleting the save first would strand every
        // picture it generated on disk.
        runCatching { container.images.deleteFilesFor(gameId) }
        container.store.deleteGame(gameId)
        refreshSaves()
    }

    fun exportGame(gameId: String) = viewModelScope.launch {
        runCatching { container.saveSystem.export(gameId) }
            .onSuccess { _exported.value = it }
            // Silently doing nothing is indistinguishable from a broken button.
            .onFailure { _notice.value = "That save could not be exported: ${it.reason()}" }
    }

    /**
     * Imports a save the player picked from their device. A save that cannot be read must say so:
     * this used to be unreachable from any screen, and swallowed every failure when it was called.
     */
    fun importGame(text: String) = viewModelScope.launch {
        _notice.value = importSave(text)
        refreshSaves()
    }

    /** The import itself, separated from the scope that drives it so it can be tested directly. */
    suspend fun importSave(text: String): String {
        if (text.isBlank()) return "That file was empty."
        return runCatching { container.saveSystem.import(text) }
            .fold(
                onSuccess = { "Imported \"${it.title}\" as a separate save." },
                onFailure = { "That file is not an UNBOUND save: ${it.reason()}" },
            )
    }

    fun consumeNotice() { _notice.value = null }

    private fun Throwable.reason() = message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "unknown error")

    fun consumeExport() { _exported.value = null }

    fun markOnboarded() = viewModelScope.launch { container.settings.setOnboardingComplete(true) }
}
