package com.unbound.rpg.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unbound.core.engine.TurnDiagnostics
import com.unbound.core.model.GameRecord
import com.unbound.core.model.ImageRecord
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.WorldRecord
import com.unbound.rpg.domain.AppContainer
import com.unbound.rpg.domain.GameSession
import com.unbound.rpg.domain.SessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One entry per thing shown in the scroll. Player input is echoed as its own entry so the log reads
 * as a record of what happened rather than a wall of narration.
 */
sealed interface SceneEntry {
    val id: String

    data class PlayerAction(override val id: String, val text: String) : SceneEntry
    data class Narration(
        override val id: String,
        val text: String,
        val turnNumber: Int,
        val diagnostics: TurnDiagnostics? = null,
        val rejected: List<String> = emptyList(),
    ) : SceneEntry
    data class SystemNote(override val id: String, val text: String) : SceneEntry
    data class Picture(override val id: String, val image: ImageRecord, val caption: String) : SceneEntry
}

data class PlayUiState(
    val loading: Boolean = true,
    val game: GameRecord? = null,
    val player: PlayerRecord? = null,
    val world: WorldRecord? = null,
    val location: LocationRecord? = null,
    val presentNpcs: List<NpcRecord> = emptyList(),
    val entries: List<SceneEntry> = emptyList(),
    val suggestedActions: List<String> = emptyList(),
    val thinking: Boolean = false,
    val error: String? = null,
    val errorRetryable: Boolean = false,
    val developerMode: Boolean = false,
    val canLoadMore: Boolean = false,
)

class PlayViewModel(
    private val container: AppContainer,
    gameId: String,
) : ViewModel() {

    private val session = GameSession(container, gameId)
    private val _state = MutableStateFlow(PlayUiState())
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    private var entrySeq = 0
    private fun nextId() = "e${++entrySeq}"

    init {
        viewModelScope.launch {
            // Only the most recent turns are materialised; older ones page in on scroll (§64).
            val history = session.history(limit = INITIAL_HISTORY)
            val entries = history.flatMap { turn ->
                listOf(
                    SceneEntry.PlayerAction(nextId(), turn.playerInput),
                    SceneEntry.Narration(nextId(), turn.narrative, turn.turnNumber),
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    suggestedActions = history.lastOrNull()?.suggestedActions.orEmpty(),
                    canLoadMore = history.size >= INITIAL_HISTORY,
                )
            }
            refreshWorld()
        }
        viewModelScope.launch {
            container.settings.state.collect { settings ->
                _state.update { it.copy(developerMode = settings.developerMode) }
            }
        }
    }

    private suspend fun refreshWorld() {
        _state.update {
            it.copy(
                game = session.game(),
                player = session.player(),
                world = session.world(),
                location = session.location(),
                presentNpcs = session.presentNpcs(),
            )
        }
    }

    fun loadOlder() {
        val current = _state.value
        if (!current.canLoadMore) return
        viewModelScope.launch {
            val alreadyShown = current.entries.count { it is SceneEntry.Narration }
            val older = session.history(offset = alreadyShown, limit = PAGE)
            if (older.isEmpty()) {
                _state.update { it.copy(canLoadMore = false) }
                return@launch
            }
            val prepend = older.flatMap { turn ->
                listOf(
                    SceneEntry.PlayerAction(nextId(), turn.playerInput),
                    SceneEntry.Narration(nextId(), turn.narrative, turn.turnNumber),
                )
            }
            _state.update { it.copy(entries = prepend + it.entries, canLoadMore = older.size >= PAGE) }
        }
    }

    fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _state.value.thinking) return

        _state.update {
            it.copy(
                entries = it.entries + SceneEntry.PlayerAction(nextId(), text),
                thinking = true,
                error = null,
                suggestedActions = emptyList(),
            )
        }

        viewModelScope.launch {
            when (val result = session.submit(text)) {
                is SessionResult.Narrated -> {
                    _state.update {
                        it.copy(
                            entries = it.entries + SceneEntry.Narration(
                                nextId(), result.narrative, result.turnNumber, result.diagnostics, result.rejected,
                            ),
                            suggestedActions = result.suggestedActions,
                            thinking = false,
                        )
                    }
                    refreshWorld()
                    maybeAutoImage(result)
                }

                is SessionResult.LocalAnswer -> _state.update {
                    it.copy(entries = it.entries + SceneEntry.SystemNote(nextId(), result.text), thinking = false)
                }

                is SessionResult.Failed -> _state.update {
                    // The player's echoed action stays in the log: their input was not lost, and
                    // the retry will re-send exactly it.
                    it.copy(thinking = false, error = result.message, errorRetryable = result.retryable)
                }

                is SessionResult.Undone -> {
                    val history = session.history(limit = INITIAL_HISTORY)
                    _state.update { state ->
                        state.copy(
                            thinking = false,
                            entries = history.flatMap { turn ->
                                listOf(
                                    SceneEntry.PlayerAction(nextId(), turn.playerInput),
                                    SceneEntry.Narration(nextId(), turn.narrative, turn.turnNumber),
                                )
                            } + SceneEntry.SystemNote(
                                nextId(),
                                "The world has been stepped back to turn ${result.turnNumber}. " +
                                    "${result.turnsUndone} turn(s) undone.",
                            ),
                            suggestedActions = emptyList(),
                        )
                    }
                    refreshWorld()
                }

                is SessionResult.ImageReady -> _state.update {
                    it.copy(
                        thinking = false,
                        entries = it.entries + SceneEntry.Picture(
                            nextId(), result.image, if (result.fromCache) "From this device" else "Newly drawn",
                        ),
                    )
                }

                SessionResult.OpenJournal -> _state.update {
                    it.copy(thinking = false, entries = it.entries + SceneEntry.SystemNote(nextId(), "Opening the journal."))
                }
                SessionResult.OpenSaves -> _state.update { it.copy(thinking = false) }
                SessionResult.Ignored -> _state.update { it.copy(thinking = false) }
            }
        }
    }

    /** Automatic imagery only when the player asked for it *and* the model flagged the scene (§9). */
    private fun maybeAutoImage(result: SessionResult.Narrated) {
        val game = _state.value.game ?: return
        if (game.imageMode != com.unbound.core.model.ImageMode.AUTOMATIC_IMPORTANT) return
        if (result.diagnostics?.sceneIsSignificant != true) return
        val locationId = _state.value.location?.id ?: return
        val modelId = game.imageModelId ?: return
        viewModelScope.launch {
            val outcome = container.images.imageForLocation(game.id, locationId, modelId)
            if (outcome is com.unbound.rpg.data.images.ImageOutcome.Ready) {
                _state.update {
                    it.copy(entries = it.entries + SceneEntry.Picture(nextId(), outcome.record, "This moment"))
                }
            }
        }
    }

    fun retry() {
        val lastAction = _state.value.entries.filterIsInstance<SceneEntry.PlayerAction>().lastOrNull() ?: return
        _state.update { it.copy(error = null, thinking = true) }
        viewModelScope.launch {
            when (val result = session.submit(lastAction.text)) {
                is SessionResult.Narrated -> {
                    _state.update {
                        it.copy(
                            entries = it.entries + SceneEntry.Narration(
                                nextId(), result.narrative, result.turnNumber, result.diagnostics, result.rejected,
                            ),
                            suggestedActions = result.suggestedActions,
                            thinking = false,
                        )
                    }
                    refreshWorld()
                }
                is SessionResult.Failed -> _state.update {
                    it.copy(thinking = false, error = result.message, errorRetryable = result.retryable)
                }
                else -> _state.update { it.copy(thinking = false) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val INITIAL_HISTORY = 24
        const val PAGE = 24
    }
}
