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
            }
        }
        refreshSaves()
        refreshCredentialState()
        // Show whatever is known offline immediately; a live fetch replaces it when asked.
        _settings.update { it.copy(models = container.modelCatalog.knownProfiles()) }
        refreshUsage()
        // Reclaim anything a crash or an older build left behind, once, at launch.
        viewModelScope.launch { runCatching { container.images.sweepOrphans() } }
    }

    fun hasCredential(): Boolean = container.credentials.hasKey()

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

    private fun refreshCredentialState() {
        _settings.update { it.copy(maskedKey = container.credentials.maskedKey()) }
    }

    fun refreshUsage() = viewModelScope.launch {
        // Rolled up in SQL. Loading the most recent 2,000 records and adding them up was only a
        // total until the 2,001st request, after which the screen understated what the player had
        // actually spent — on their own key.
        val aggregates = container.store.usageByModel(null)
        val cacheBytes = container.images.cacheSizeBytes()
        _settings.update {
            it.copy(
                usage = container.estimator().summariseAggregates(aggregates),
                imageCacheBytes = cacheBytes,
            )
        }
    }

    // --- credential ---------------------------------------------------------------------------
    fun storeKey(raw: String) {
        container.credentials.storeKey(raw)
        refreshCredentialState()
        _settings.update { it.copy(testResult = null, testSucceeded = null) }
    }

    fun removeKey() {
        container.credentials.clear()
        refreshCredentialState()
        _settings.update { it.copy(testResult = null, testSucceeded = null, models = container.modelCatalog.knownProfiles()) }
    }

    fun testConnection() = viewModelScope.launch {
        _settings.update { it.copy(testing = true, testResult = null, testSucceeded = null) }
        val result = withContext(Dispatchers.IO) { container.provider.testConnection() }
        _settings.update { it.copy(testing = false, testResult = result.message, testSucceeded = result.ok) }
        if (result.ok) refreshModels()
    }

    fun refreshModels() = viewModelScope.launch {
        _settings.update { it.copy(loadingModels = true, modelError = null) }
        val result = runCatching { withContext(Dispatchers.IO) { container.modelCatalog.listModels(forceRefresh = true) } }
        result.onSuccess { models ->
            // Cost estimates should reflect the models this account actually has.
            container.liveCostEstimator = CostEstimator(models.associateBy { it.id })
            _settings.update { it.copy(loadingModels = false, models = models) }
            refreshUsage()
            // Pick a sensible default the first time, rather than leaving the player stuck.
            if (_settings.value.settings.defaultTextModelId == null) {
                models.firstOrNull { it.supportsStructuredOutput }?.let { setTextModel(it.id) }
            }
        }.onFailure { error ->
            _settings.update {
                it.copy(
                    loadingModels = false,
                    modelError = error.message ?: "Could not fetch the model list.",
                    models = if (it.models.isEmpty()) container.modelCatalog.knownProfiles() else it.models,
                )
            }
        }
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
                outcome.openingWarning?.let { warning ->
                    _settings.update { it.copy(modelError = warning) }
                }
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
