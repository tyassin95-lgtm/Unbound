package com.unbound.rpg.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unbound.core.ai.CostEstimator
import com.unbound.core.content.Settings as SettingContent
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.model.Appearance
import com.unbound.core.model.ImageMode
import com.unbound.core.model.NarrationLength
import com.unbound.rpg.data.security.SecureCredentialStore
import com.unbound.rpg.domain.AppContainer
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

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _createdGameId = MutableStateFlow<String?>(null)
    val createdGameId: StateFlow<String?> = _createdGameId.asStateFlow()

    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()

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
        val records = container.store.usageFor(null, 2000)
        _settings.update {
            it.copy(
                usage = container.estimator().summarise(records),
                imageCacheBytes = container.images.cacheSizeBytes(),
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
    fun setNarrationLength(length: NarrationLength) = viewModelScope.launch { container.settings.setNarrationLength(length) }
    fun setSuggestions(enabled: Boolean) = viewModelScope.launch { container.settings.setSuggestedActions(enabled) }
    fun setReducedMotion(enabled: Boolean) = viewModelScope.launch { container.settings.setReducedMotion(enabled) }
    fun setDeveloperMode(enabled: Boolean) = viewModelScope.launch { container.settings.setDeveloperMode(enabled) }
    fun setLimits(limits: List<String>) = viewModelScope.launch { container.settings.setPersonalLimits(limits) }

    fun clearImageCache() = viewModelScope.launch {
        container.store.listGames().forEach { container.images.clearCache(it.id) }
        refreshUsage()
    }

    // --- games ---------------------------------------------------------------------------------
    fun createGame(draft: NewGameDraft) = viewModelScope.launch {
        val seed = draft.seed ?: return@launch
        val settings = _settings.value.settings
        val model = settings.defaultTextModelId
        if (model == null) {
            _settings.update { it.copy(modelError = "Choose a story model before starting a game.") }
            return@launch
        }
        _creating.value = true
        val game = runCatching {
            container.gameFactory.createGame(
                NewGameRequest(
                    seed = seed,
                    name = draft.name.trim(),
                    age = draft.ageInt ?: 18,
                    gender = draft.gender.trim(),
                    appearance = Appearance(summary = draft.appearance.trim()),
                    personality = draft.personality.trim(),
                    desires = draft.desires.trim(),
                    fears = draft.fears.trim(),
                    skills = draft.skills.split(',').map { it.trim() }.filter { it.isNotBlank() },
                    weaknesses = draft.weaknesses.split(',').map { it.trim() }.filter { it.isNotBlank() },
                    background = draft.background.trim(),
                    goals = listOfNotNull(draft.goal.trim().takeIf { it.isNotBlank() }),
                    secrets = listOfNotNull(draft.secret.trim().takeIf { it.isNotBlank() }),
                    startingCurrency = draft.template?.startingCurrency ?: 25,
                    textModelId = model,
                    imageModelId = settings.defaultImageModelId,
                    imageMode = settings.imageMode,
                    tone = draft.tone,
                    limits = (draft.limits.split(',').map { it.trim() }.filter { it.isNotBlank() })
                        .ifEmpty { settings.personalLimits },
                ),
            )
        }.getOrNull()
        _creating.value = false
        if (game != null) {
            // The opening scene is a normal turn, so it goes through the same validated pipeline
            // as everything else rather than a special path.
            val opening = container.gameFactory.openingInstruction(seed, draft.hook)
            container.pipeline.execute(game.id, opening)
            refreshSaves()
            _createdGameId.value = game.id
        }
    }

    fun consumeCreatedGame() { _createdGameId.value = null }

    fun renameGame(gameId: String, title: String) = viewModelScope.launch {
        container.store.getGame(gameId)?.let { container.store.upsertGame(it.copy(title = title, updatedAtEpochMs = container.clock())) }
        refreshSaves()
    }

    fun duplicateGame(gameId: String) = viewModelScope.launch {
        val original = container.store.getGame(gameId) ?: return@launch
        runCatching { container.saveSystem.duplicate(gameId, original.title + " (copy)") }
        refreshSaves()
    }

    fun deleteGame(gameId: String) = viewModelScope.launch {
        container.store.deleteGame(gameId)
        refreshSaves()
    }

    fun exportGame(gameId: String) = viewModelScope.launch {
        _exported.value = runCatching { container.saveSystem.export(gameId) }.getOrNull()
    }

    fun importGame(text: String) = viewModelScope.launch {
        runCatching { container.saveSystem.import(text) }
        refreshSaves()
    }

    fun consumeExport() { _exported.value = null }

    fun markOnboarded() = viewModelScope.launch { container.settings.setOnboardingComplete(true) }
}
