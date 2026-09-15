package com.unbound.rpg.domain

import com.unbound.core.ai.AIErrorKind
import com.unbound.core.command.CommandParser
import com.unbound.core.command.PlayerCommand
import com.unbound.core.engine.TurnDiagnostics
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.journal.Journal
import com.unbound.core.model.*
import com.unbound.core.save.UndoResult
import com.unbound.rpg.data.images.ImageOutcome
import java.util.UUID

/**
 * What the UI talks to. One object per open campaign.
 *
 * It exists so that the ViewModel never touches the pipeline, the store or the provider directly,
 * and so that the local-command path — status, journal, inventory, tone, limits — is resolved here
 * *without a model call*, which is a large part of the cost story (§66).
 */
class GameSession(
    private val container: AppContainer,
    val gameId: String,
) {
    private val parser = CommandParser()

    /**
     * Held so a failed turn can be retried under the same key. It is cleared only when a turn
     * actually commits, which is what makes the retry safe rather than a second charge (§45).
     */
    private var pendingIdempotencyKey: String? = null

    suspend fun game(): GameRecord? = container.store.getGame(gameId)
    suspend fun player(): PlayerRecord? = container.store.getPlayer(gameId)
    suspend fun world(): WorldRecord? = container.store.getWorld(gameId)
    suspend fun location(): LocationRecord? =
        player()?.let { container.store.getLocation(gameId, it.currentLocationId) }
    suspend fun presentNpcs(): List<NpcRecord> =
        player()?.let { container.store.npcsAt(gameId, it.currentLocationId).filter { n -> n.alive } } ?: emptyList()

    suspend fun history(offset: Int = 0, limit: Int = 30): List<TurnRecord> =
        container.store.turnsPage(gameId, offset, limit).sortedBy { it.turnNumber }

    suspend fun journal(): Journal = container.journal.build(gameId)
    suspend fun status(): String = container.journal.status(gameId)

    /**
     * Resolves one player input.
     *
     * Local commands return immediately and cost nothing. Anything else is a narrative turn.
     */
    suspend fun submit(input: String): SessionResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return SessionResult.Ignored

        return when (val command = parser.parse(trimmed)) {
            is PlayerCommand.Status -> SessionResult.LocalAnswer(status())
            is PlayerCommand.Journal -> SessionResult.OpenJournal
            is PlayerCommand.Inventory -> SessionResult.LocalAnswer(inventoryText())
            is PlayerCommand.Help -> SessionResult.LocalAnswer(HELP_TEXT)
            is PlayerCommand.Save -> SessionResult.LocalAnswer("Your game is saved continuously. Every completed turn is already on this device.")
            is PlayerCommand.Load -> SessionResult.OpenSaves
            is PlayerCommand.Undo -> undoLastTurn()
            is PlayerCommand.SetTone -> setTone(command.tone)
            is PlayerCommand.ShiftTone -> shiftTone(command.darker, command.faster)
            is PlayerCommand.SetLimits -> setLimits(command.limits)
            is PlayerCommand.RequestImage -> requestImage(command.subject)
            is PlayerCommand.Narrative -> takeTurn(trimmed)
        }
    }

    suspend fun takeTurn(input: String): SessionResult {
        val key = pendingIdempotencyKey ?: UUID.randomUUID().toString().also { pendingIdempotencyKey = it }

        return when (val outcome = container.pipeline.execute(gameId, input, key)) {
            is TurnOutcome.Success -> {
                pendingIdempotencyKey = null
                maybeSnapshot(outcome)
                SessionResult.Narrated(
                    narrative = outcome.narrative,
                    suggestedActions = outcome.suggestedActions,
                    turnNumber = outcome.turn.turnNumber,
                    diagnostics = outcome.diagnostics,
                    rejected = outcome.issues.map { "${it.code}: ${it.detail}" },
                )
            }
            is TurnOutcome.Replayed -> {
                pendingIdempotencyKey = null
                SessionResult.Narrated(outcome.turn.narrative, outcome.turn.suggestedActions, outcome.turn.turnNumber, null, emptyList())
            }
            is TurnOutcome.Failure -> {
                // A retryable failure keeps the key so the retry is the *same* turn, not a new one.
                if (!outcome.retryable) pendingIdempotencyKey = null
                SessionResult.Failed(outcome.message, outcome.kind, outcome.retryable)
            }
        }
    }

    /** True when the last turn failed in a way that can be retried without duplicating anything. */
    fun hasRetryableTurn(): Boolean = pendingIdempotencyKey != null

    private suspend fun maybeSnapshot(outcome: TurnOutcome.Success) {
        val reason = container.snapshots.shouldSnapshot(outcome.game, outcome.diagnostics.sceneIsSignificant) ?: return
        runCatching { container.snapshots.capture(gameId, reason) }
    }

    private suspend fun inventoryText(): String {
        val items = container.store.itemsOwnedBy(gameId, Ids.PLAYER)
        val player = player() ?: return "No character."
        val world = world()!!
        return buildString {
            appendLine("${player.currency} ${world.currencyName}")
            if (items.isEmpty()) {
                appendLine("You are carrying nothing worth listing.")
            } else {
                items.forEach { appendLine("· ${it.name}${if (it.quantity > 1) " x${it.quantity}" else ""} (${it.condition.name.lowercase()})") }
            }
        }.trim()
    }

    private suspend fun setTone(tone: Tone): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        container.store.upsertGame(game.copy(tone = tone, updatedAtEpochMs = container.clock()))
        return SessionResult.LocalAnswer("Tone set to ${tone.display}. ${tone.guidance}")
    }

    private suspend fun shiftTone(darker: Boolean?, faster: Boolean?): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        val order = listOf(Tone.LIGHT, Tone.NORMAL, Tone.DARK, Tone.VERY_DARK)
        val next = when {
            darker != null -> {
                val index = order.indexOf(game.tone).takeIf { it >= 0 } ?: 1
                order[(if (darker) index + 1 else index - 1).coerceIn(0, order.lastIndex)]
            }
            faster == true -> Tone.FAST
            faster == false -> Tone.SLOW_BURN
            else -> game.tone
        }
        return setTone(next)
    }

    private suspend fun setLimits(limits: List<String>): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        container.store.upsertGame(game.copy(limits = limits, updatedAtEpochMs = container.clock()))
        return SessionResult.LocalAnswer(
            if (limits.isEmpty()) "Limits cleared." else "Noted, and they will be respected: ${limits.joinToString("; ")}.",
        )
    }

    private suspend fun undoLastTurn(): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        val preview = container.snapshots.describeUndo(gameId)
            ?: return SessionResult.LocalAnswer("There is no restore point to go back to yet.")
        return when (val result = container.snapshots.undoTo(gameId, preview.toTurn)) {
            is UndoResult.Restored -> SessionResult.Undone(result.turnNumber, result.turnsUndone)
            is UndoResult.Failed -> SessionResult.LocalAnswer(result.reason)
        }
    }

    /**
     * Resolves "image of X" against canonical entities before generating anything, so the picture
     * is of the character the database knows rather than a fresh invention (§108).
     */
    suspend fun requestImage(subject: String): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        if (game.imageMode == ImageMode.DISABLED) {
            return SessionResult.LocalAnswer("Image generation is switched off. You can enable it in Settings.")
        }
        val modelId = game.imageModelId
            ?: return SessionResult.LocalAnswer("No image model is selected. Choose one in Settings first.")

        val needle = subject.trim().lowercase().removePrefix("the ").trim()
        val player = player()!!

        val outcome = when {
            needle == "me" || needle == "myself" || needle == player.name.lowercase() ->
                container.images.imageForPlayer(gameId, modelId)
            else -> {
                val npc = container.store.persistentNpcs(gameId, 300)
                    .firstOrNull { it.name.lowercase() == needle || it.name.lowercase().startsWith("$needle ") }
                val location = container.store.discoveredLocations(gameId, 300)
                    .firstOrNull { it.name.lowercase().contains(needle) }
                when {
                    npc != null -> container.images.imageForNpc(gameId, npc.id, modelId)
                    location != null -> container.images.imageForLocation(gameId, location.id, modelId)
                    else -> return SessionResult.LocalAnswer(
                        "There is nobody and nowhere called \"$subject\" in this world yet.",
                    )
                }
            }
        }

        return when (outcome) {
            is ImageOutcome.Ready -> SessionResult.ImageReady(outcome.record, outcome.fromCache)
            is ImageOutcome.Failed -> SessionResult.LocalAnswer("The picture could not be made: ${outcome.reason}")
        }
    }

    private companion object {
        val HELP_TEXT = """
            Type whatever you want to do, in your own words. You are not limited to any list.

            A few shortcuts:
              status      where you are, how you are, what is pressing
              journal     people, places, factions, open situations
              inventory   what you are carrying
              undo        step the world back to the last restore point
              image of X  a picture of a person or place you know
              tone: darker / lighter / faster / slower
              limits: no spiders, no sexual violence
        """.trimIndent()
    }
}

sealed interface SessionResult {
    data object Ignored : SessionResult
    data object OpenJournal : SessionResult
    data object OpenSaves : SessionResult
    data class LocalAnswer(val text: String) : SessionResult
    data class Narrated(
        val narrative: String,
        val suggestedActions: List<String>,
        val turnNumber: Int,
        val diagnostics: TurnDiagnostics?,
        val rejected: List<String>,
    ) : SessionResult
    data class Failed(val message: String, val kind: AIErrorKind, val retryable: Boolean) : SessionResult
    data class Undone(val turnNumber: Int, val turnsUndone: Int) : SessionResult
    data class ImageReady(val image: ImageRecord, val fromCache: Boolean) : SessionResult
}
