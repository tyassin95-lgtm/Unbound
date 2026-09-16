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
                    playerDialogue = outcome.turn.playerDialogue,
                    servedByFallbackFrom = outcome.servedByFallbackFrom,
                )
            }
            is TurnOutcome.Replayed -> {
                pendingIdempotencyKey = null
                SessionResult.Narrated(
                    narrative = outcome.turn.narrative,
                    suggestedActions = outcome.turn.suggestedActions,
                    turnNumber = outcome.turn.turnNumber,
                    diagnostics = null,
                    rejected = emptyList(),
                    playerDialogue = outcome.turn.playerDialogue,
                )
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

    /** What an undo would discard, so the player can be warned before it happens. */
    suspend fun undoPreview(): com.unbound.core.save.UndoPreview? = container.snapshots.describeUndo(gameId)

    /** The same step-back the "undo" command performs, for the screen's own affordance. */
    suspend fun undo(): SessionResult = undoLastTurn()

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
     * What can be pictured *right now*.
     *
     * Rebuilt from canonical state on every call rather than cached, because the answer changes
     * with the turn: who walked in, where the player moved to, what just happened. The moment text
     * comes from the last narrative, so a scene image depicts what was actually narrated instead of
     * a generic view of the room.
     */
    suspend fun imageOptions(): ImageOptions {
        val game = game() ?: return ImageOptions.unavailable("No game loaded.")
        val player = player() ?: return ImageOptions.unavailable("No character.")
        val location = location()
        val present = presentNpcs()
        val lastTurn = container.store.recentTurns(gameId, 1).firstOrNull()
        val turnNumber = game.turnNumber
        // People met a moment ago are worth being able to draw even if the story has since moved
        // them on — and it keeps the picker useful for a save made before presence was tracked.
        val recentlyMet = container.store.persistentNpcs(gameId, 200)
            .filter { it.alive && present.none { p -> p.id == it.id } }
            .filter { npc -> npc.lastSeenTurn?.let { turnNumber - it <= RECENTLY_MET_TURNS } == true }
            .sortedByDescending { it.lastSeenTurn }
            .take(6)

        val unavailable = when {
            game.imageMode == ImageMode.DISABLED ->
                "Pictures are switched off for this game. You can turn them on in Settings."
            game.imageModelId == null ->
                "No image model is selected. Choose one in Settings first."
            else -> null
        }

        return ImageOptions(
            unavailableReason = unavailable,
            moment = lastTurn?.narrative.orEmpty().let { summariseMoment(it) },
            locationName = location?.name ?: "here",
            playerName = player.name,
            present = present.map { PicturableNpc(it.id, it.name, it.occupation) },
            recentlyMet = recentlyMet.map { PicturableNpc(it.id, it.name, it.occupation) },
            turnNumber = game.turnNumber,
        )
    }

    /**
     * The image prompt gets the shape of the moment, not the whole page: a long narrative would
     * both cost more and bury the thing actually happening.
     */
    private fun summariseMoment(narrative: String): String {
        val cleaned = narrative
            .replace("[[", "")
            .replace("]]", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length <= MOMENT_LIMIT) return cleaned
        // Cut on a sentence boundary so the prompt never ends mid-clause.
        val cut = cleaned.take(MOMENT_LIMIT)
        val lastStop = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (lastStop > MOMENT_LIMIT / 2) cut.take(lastStop + 1) else cut
    }

    suspend fun generateImage(kind: ImageRequestKind): SessionResult {
        val game = game() ?: return SessionResult.Ignored
        if (game.imageMode == ImageMode.DISABLED) {
            return SessionResult.LocalAnswer("Pictures are switched off. You can enable them in Settings.")
        }
        val modelId = game.imageModelId
            ?: return SessionResult.LocalAnswer("No image model is selected. Choose one in Settings first.")

        val options = imageOptions()
        val outcome = when (kind) {
            is ImageRequestKind.Scene -> container.images.imageForScene(
                gameId = gameId, modelId = modelId, moment = options.moment,
            )
            is ImageRequestKind.Place -> {
                val locationId = player()?.currentLocationId
                    ?: return SessionResult.LocalAnswer("Nowhere to draw.")
                container.images.imageForLocation(gameId, locationId, modelId)
            }
            is ImageRequestKind.Person -> if (kind.npcId == null) {
                container.images.imageForPlayer(gameId, modelId)
            } else {
                container.images.imageForNpc(gameId, kind.npcId, modelId)
            }
            is ImageRequestKind.Interaction -> container.images.imageForScene(
                gameId = gameId, modelId = modelId, moment = options.moment,
                npcIds = kind.npcIds, closeUp = true,
            )
        }

        return when (outcome) {
            is ImageOutcome.Ready -> SessionResult.ImageReady(outcome.record, outcome.fromCache)
            is ImageOutcome.Failed -> SessionResult.LocalAnswer("The picture could not be made: ${outcome.reason}")
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
        const val MOMENT_LIMIT = 420
        const val RECENTLY_MET_TURNS = 8

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

/** What the player can ask to be drawn at this exact point in the story. */
sealed interface ImageRequestKind {
    data object Scene : ImageRequestKind
    data object Place : ImageRequestKind

    /** Null means the protagonist. */
    data class Person(val npcId: String?) : ImageRequestKind
    data class Interaction(val npcIds: List<String>) : ImageRequestKind
}

data class PicturableNpc(val id: String, val name: String, val occupation: String)

data class ImageOptions(
    val unavailableReason: String? = null,
    val moment: String = "",
    val locationName: String = "",
    val playerName: String = "",
    val present: List<PicturableNpc> = emptyList(),
    /** People seen recently who are not in the room right now. */
    val recentlyMet: List<PicturableNpc> = emptyList(),
    val turnNumber: Int = 0,
) {
    val available: Boolean get() = unavailableReason == null

    /** An interaction needs someone to interact with. */
    val canDrawInteraction: Boolean get() = present.isNotEmpty()

    companion object {
        fun unavailable(reason: String) = ImageOptions(unavailableReason = reason)
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
        /** Lines the protagonist spoke, so the renderer can colour their own words. */
        val playerDialogue: List<String> = emptyList(),
        /** Set when a second provider stood in for this turn, so the player can be told. */
        val servedByFallbackFrom: String? = null,
    ) : SessionResult
    data class Failed(val message: String, val kind: AIErrorKind, val retryable: Boolean) : SessionResult
    data class Undone(val turnNumber: Int, val turnsUndone: Int) : SessionResult
    data class ImageReady(val image: ImageRecord, val fromCache: Boolean) : SessionResult
}
