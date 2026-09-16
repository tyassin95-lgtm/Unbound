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
import com.unbound.rpg.domain.ImageOptions
import com.unbound.rpg.domain.ImageRequestKind
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
        /** Lines the protagonist spoke, used to colour their own words in the prose. */
        val playerDialogue: List<String> = emptyList(),
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
    /** Guards the scrollback pager: two concurrent pages would prepend the same turns twice. */
    val loadingMore: Boolean = false,
    val error: String? = null,
    val errorRetryable: Boolean = false,
    val developerMode: Boolean = false,
    val canLoadMore: Boolean = false,
    /** What stepping the world back would cost, or null when there is no restore point yet. */
    val undoPreview: com.unbound.core.save.UndoPreview? = null,
    /** Rebuilt every turn, so the picture menu always reflects the scene as it stands now. */
    val imageOptions: ImageOptions = ImageOptions(),
    val generatingImage: Boolean = false,
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

    /**
     * The opening turn has no player input — it was staged, not typed — so it contributes narration
     * only. Echoing a blank bubble, or the staging instruction itself, is not what happened.
     */
    private fun entriesFor(turn: com.unbound.core.model.TurnRecord): List<SceneEntry> = buildList {
        if (turn.playerInput.isNotBlank()) add(SceneEntry.PlayerAction(nextId(), turn.playerInput))
        add(SceneEntry.Narration(nextId(), turn.narrative, turn.turnNumber, turn.playerDialogue))
    }

    init {
        viewModelScope.launch {
            // Only the most recent turns are materialised; older ones page in on scroll (§64).
            val history = session.history(limit = INITIAL_HISTORY)
            val entries = history.flatMap { turn -> entriesFor(turn) }
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
        // Read first, then publish. `update` re-runs its lambda on contention, and re-running this
        // one would re-issue every query against the database.
        val game = session.game()
        val player = session.player()
        val world = session.world()
        val location = session.location()
        val present = session.presentNpcs()
        val imageOptions = session.imageOptions()
        val undo = session.undoPreview()
        _state.update {
            it.copy(
                game = game,
                player = player,
                world = world,
                location = location,
                presentNpcs = present,
                imageOptions = imageOptions,
                undoPreview = undo,
            )
        }
    }

    /**
     * Stepping the world back is destructive and was previously reachable only by typing "undo",
     * with no warning about what it would cost. The screen now offers it explicitly, and the
     * player sees how many turns they are about to discard before it happens.
     */
    fun undo() {
        if (_state.value.thinking) return
        _state.value.undoPreview ?: return
        _state.update { it.copy(thinking = true, error = null) }
        viewModelScope.launch {
            when (val result = session.undo()) {
                is SessionResult.Undone -> {
                    val history = session.history(limit = INITIAL_HISTORY)
                    _state.update { state ->
                        state.copy(
                            thinking = false,
                            entries = history.flatMap { turn -> entriesFor(turn) } + SceneEntry.SystemNote(
                                nextId(),
                                "The world has been stepped back to turn ${result.turnNumber}. " +
                                    "${result.turnsUndone} turn(s) undone.",
                            ),
                            suggestedActions = emptyList(),
                        )
                    }
                    refreshWorld()
                }
                is SessionResult.LocalAnswer -> _state.update {
                    it.copy(thinking = false, entries = it.entries + SceneEntry.SystemNote(nextId(), result.text))
                }
                else -> _state.update { it.copy(thinking = false) }
            }
        }
    }

    fun loadOlder() {
        val current = _state.value
        if (!current.canLoadMore || current.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val alreadyShown = current.entries.count { it is SceneEntry.Narration }
            val older = session.history(offset = alreadyShown, limit = PAGE)
            if (older.isEmpty()) {
                _state.update { it.copy(canLoadMore = false, loadingMore = false) }
                return@launch
            }
            val prepend = older.flatMap { turn -> entriesFor(turn) }
            _state.update {
                it.copy(entries = prepend + it.entries, canLoadMore = older.size >= PAGE, loadingMore = false)
            }
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
                                nextId(), result.narrative, result.turnNumber, result.playerDialogue,
                                result.diagnostics, result.rejected,
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
                            entries = history.flatMap { turn -> entriesFor(turn) } + SceneEntry.SystemNote(
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
        // Without this guard a second tap starts a second turn while the first is still in flight,
        // which is the one thing the idempotency key cannot protect against.
        if (_state.value.thinking) return
        val lastAction = _state.value.entries.filterIsInstance<SceneEntry.PlayerAction>().lastOrNull() ?: return
        _state.update { it.copy(error = null, thinking = true) }
        viewModelScope.launch {
            // Straight to the turn, not back through the command parser: this is a retry of a
            // narrative turn, and re-parsing could run a local command instead.
            when (val result = session.takeTurn(lastAction.text)) {
                is SessionResult.Narrated -> {
                    _state.update {
                        it.copy(
                            entries = it.entries + SceneEntry.Narration(
                                nextId(), result.narrative, result.turnNumber, result.playerDialogue,
                                result.diagnostics, result.rejected,
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

    /**
     * Pictures are requested explicitly and generated one at a time, because each one is a separate
     * charge on the player's own key. The button disables itself while a request is in flight
     * rather than queueing.
     */
    fun requestImage(kind: ImageRequestKind) {
        if (_state.value.generatingImage) return
        _state.update { it.copy(generatingImage = true) }
        viewModelScope.launch {
            when (val result = session.generateImage(kind)) {
                is SessionResult.ImageReady -> _state.update {
                    it.copy(
                        generatingImage = false,
                        entries = it.entries + SceneEntry.Picture(
                            nextId(), result.image, captionFor(kind, result.fromCache),
                        ),
                    )
                }
                is SessionResult.LocalAnswer -> _state.update {
                    it.copy(generatingImage = false, entries = it.entries + SceneEntry.SystemNote(nextId(), result.text))
                }
                else -> _state.update { it.copy(generatingImage = false) }
            }
        }
    }

    private fun captionFor(kind: ImageRequestKind, fromCache: Boolean): String {
        val what = when (kind) {
            is ImageRequestKind.Scene -> "This moment"
            is ImageRequestKind.Place -> _state.value.location?.name ?: "This place"
            is ImageRequestKind.Person -> if (kind.npcId == null) {
                _state.value.player?.name ?: "You"
            } else {
                _state.value.presentNpcs.firstOrNull { it.id == kind.npcId }?.name ?: "A face"
            }
            is ImageRequestKind.Interaction -> "Between you"
        }
        return if (fromCache) "$what · already on this device" else what
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val INITIAL_HISTORY = 24
        const val PAGE = 24
    }
}
