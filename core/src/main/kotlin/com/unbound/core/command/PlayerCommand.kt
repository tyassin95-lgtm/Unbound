package com.unbound.core.command

import com.unbound.core.model.Tone

/**
 * Commands the app handles locally. Every one of these would otherwise be a model call that
 * produced information the database already holds, so parsing them deterministically is one of the
 * larger cost savings in the product (§66).
 */
sealed interface PlayerCommand {
    data object Status : PlayerCommand
    data object Journal : PlayerCommand
    data object Inventory : PlayerCommand
    data object Save : PlayerCommand
    data object Load : PlayerCommand
    data object Undo : PlayerCommand
    data object Help : PlayerCommand
    data class SetTone(val tone: Tone) : PlayerCommand
    data class ShiftTone(val darker: Boolean?, val faster: Boolean?) : PlayerCommand
    data class SetLimits(val limits: List<String>) : PlayerCommand
    data class RequestImage(val subject: String) : PlayerCommand

    /** Anything else. Goes to the model as a free-form action. */
    data class Narrative(val text: String) : PlayerCommand
}

/**
 * Recognises the conveniences in §52 without ever turning the game into a command line. The bar for
 * claiming a command is deliberately high: "status" is a command, but "I check the status of the
 * wound" is narrative, because a false positive silently eats the player's turn.
 */
class CommandParser {

    fun parse(raw: String): PlayerCommand {
        val text = raw.trim()
        val lower = text.lowercase().trim(' ', '.', '!', '?')

        when (lower) {
            "status", "/status" -> return PlayerCommand.Status
            "journal", "/journal", "log" -> return PlayerCommand.Journal
            "inventory", "/inventory", "inv", "items" -> return PlayerCommand.Inventory
            "save", "/save" -> return PlayerCommand.Save
            "load", "/load" -> return PlayerCommand.Load
            "undo", "/undo" -> return PlayerCommand.Undo
            "help", "/help", "commands" -> return PlayerCommand.Help
        }

        TONE_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
            val value = lower.removePrefix(prefix).trim(' ', ':', '-')
            Tone.parse(value)?.let { return PlayerCommand.SetTone(it) }
            return when (value) {
                "darker", "dark" -> PlayerCommand.ShiftTone(darker = true, faster = null)
                "lighter", "light" -> PlayerCommand.ShiftTone(darker = false, faster = null)
                "faster", "fast", "quicker" -> PlayerCommand.ShiftTone(darker = null, faster = true)
                "slower", "slow" -> PlayerCommand.ShiftTone(darker = null, faster = false)
                else -> PlayerCommand.Narrative(text)
            }
        }

        LIMIT_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
            val body = text.substring(prefix.length).trim(' ', ':', '-')
            if (body.isBlank()) return PlayerCommand.SetLimits(emptyList())
            val limits = body.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
            return PlayerCommand.SetLimits(limits)
        }

        IMAGE_PREFIXES.firstOrNull { lower.startsWith(it) }?.let { prefix ->
            val subject = text.substring(prefix.length).trim(' ', ':', '-')
            if (subject.isNotBlank()) return PlayerCommand.RequestImage(subject)
        }

        return PlayerCommand.Narrative(text)
    }

    /**
     * Resolves entity names mentioned in free text to ids, so retrieval can focus on them before
     * any model call happens. Matching is on whole words and on first names, and is case
     * insensitive; ambiguity resolves to all candidates rather than guessing one.
     */
    fun resolveMentions(text: String, candidates: Map<String, String>): Set<String> {
        if (text.isBlank()) return emptySet()
        val lower = " ${text.lowercase()} "
        val hits = mutableSetOf<String>()
        for ((id, name) in candidates) {
            val n = name.lowercase()
            if (n.isBlank()) continue
            if (lower.contains(" $n ") || lower.contains(" $n's ") || lower.contains(" $n,") || lower.contains(" $n.")) {
                hits += id
                continue
            }
            // Any distinctive part of the name, not only the first: people say "Surrin" as
            // readily as "Warden Surrin", and missing that drops them out of the turn's focus.
            val parts = n.split(' ').filter { it.length >= MIN_NAME_PART }
            if (parts.any { part ->
                    lower.contains(" $part ") || lower.contains(" $part's ") ||
                        lower.contains(" $part,") || lower.contains(" $part.") ||
                        lower.contains(" $part?") || lower.contains(" $part!")
                }
            ) {
                hits += id
            }
        }
        return hits
    }

    private companion object {
        val TONE_PREFIXES = listOf("tone:", "tone ", "/tone ")
        val LIMIT_PREFIXES = listOf("limits:", "limit:", "limits ", "/limits ")
        val IMAGE_PREFIXES = listOf("image of ", "picture of ", "show me ", "/image ", "draw ")

        /** Short name fragments collide with ordinary words; this is the length that stops that. */
        const val MIN_NAME_PART = 4
    }
}
