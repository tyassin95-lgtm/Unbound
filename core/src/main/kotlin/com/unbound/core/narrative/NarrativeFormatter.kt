package com.unbound.core.narrative

/** How a run of narrative text should be presented. */
enum class NarrativeStyleKind {
    /** Ordinary narration. */
    PROSE,

    /** Something the protagonist said aloud. */
    PLAYER_SPEECH,

    /** Something another character said aloud, in the room. */
    SPEECH,

    /** A text, email, call, letter, broadcast — anything arriving from elsewhere. */
    COMMUNICATION,
}

data class NarrativeSpan(val text: String, val kind: NarrativeStyleKind)

data class NarrativeParagraph(val spans: List<NarrativeSpan>) {
    val plainText: String get() = spans.joinToString("") { it.text }
}

/**
 * Turns a turn's narrative into styled runs.
 *
 * Two conventions are recognised, and both are designed to fail harmlessly — a model that forgets
 * one produces plain prose rather than visible junk or a lost turn:
 *
 *  * `[[...]]` wraps remote communication. The brackets are stripped.
 *  * Quoted text is speech. Whether it is the *protagonist's* speech is decided by matching against
 *    `player_dialogue`, which the model returns separately, rather than by parsing attribution like
 *    "you said" — attribution parsing gets it wrong in exactly the cases that matter (reported
 *    speech, interruptions, a line split across a dialogue tag).
 *
 * Matching is done on normalised text so that a curly apostrophe in one field and a straight one in
 * the other still count as the same line.
 */
object NarrativeFormatter {

    fun format(narrative: String, playerDialogue: List<String> = emptyList()): List<NarrativeParagraph> {
        if (narrative.isBlank()) return emptyList()

        val playerLines = playerDialogue
            .map { normalise(it) }
            .filter { it.length >= MIN_MATCH_LENGTH }

        return narrative
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { paragraph -> NarrativeParagraph(parseParagraph(paragraph, playerLines)) }
    }

    private fun parseParagraph(paragraph: String, playerLines: List<String>): List<NarrativeSpan> {
        val spans = mutableListOf<NarrativeSpan>()

        // Communications first: they may legitimately contain quoted speech, and that speech
        // belongs to the message rather than to the room.
        var index = 0
        while (index < paragraph.length) {
            val open = paragraph.indexOf(COMM_OPEN, index)
            if (open < 0) {
                spans += parseSpeech(paragraph.substring(index), playerLines)
                break
            }
            val close = paragraph.indexOf(COMM_CLOSE, open + COMM_OPEN.length)
            if (close < 0) {
                // An unterminated marker is a model slip. Show the rest as prose rather than
                // swallowing it or styling to the end of the paragraph.
                spans += parseSpeech(paragraph.substring(index).replace(COMM_OPEN, ""), playerLines)
                break
            }
            if (open > index) {
                spans += parseSpeech(paragraph.substring(index, open), playerLines)
            }
            val body = paragraph.substring(open + COMM_OPEN.length, close).trim()
            if (body.isNotEmpty()) spans += NarrativeSpan(body, NarrativeStyleKind.COMMUNICATION)
            index = close + COMM_CLOSE.length
        }

        return spans.filter { it.text.isNotEmpty() }
    }

    private fun parseSpeech(text: String, playerLines: List<String>): List<NarrativeSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<NarrativeSpan>()
        var index = 0

        while (index < text.length) {
            val open = text.indexOfAny(OPEN_QUOTES, index)
            if (open < 0) {
                spans += NarrativeSpan(text.substring(index), NarrativeStyleKind.PROSE)
                break
            }
            val closeChar = closingQuoteFor(text[open])
            val close = text.indexOf(closeChar, open + 1)
            if (close < 0) {
                spans += NarrativeSpan(text.substring(index), NarrativeStyleKind.PROSE)
                break
            }

            if (open > index) spans += NarrativeSpan(text.substring(index, open), NarrativeStyleKind.PROSE)

            // The quotation marks stay inside the styled run, so the colour reads as belonging to
            // the line rather than leaving orphaned punctuation in the prose colour.
            val quoted = text.substring(open, close + 1)
            val inner = normalise(text.substring(open + 1, close))
            val isPlayer = playerLines.any { line -> inner == line || inner.contains(line) || line.contains(inner) }
            spans += NarrativeSpan(quoted, if (isPlayer) NarrativeStyleKind.PLAYER_SPEECH else NarrativeStyleKind.SPEECH)

            index = close + 1
        }

        return spans
    }

    private fun closingQuoteFor(open: Char): Char = when (open) {
        '“' -> '”'
        else -> open
    }

    /** Lowercased, with curly punctuation and runs of whitespace flattened, for reliable matching. */
    internal fun normalise(text: String): String = text
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('"', '\'')
        .lowercase()

    private const val COMM_OPEN = "[["
    private const val COMM_CLOSE = "]]"
    private val OPEN_QUOTES = charArrayOf('"', '“')

    /**
     * Below this, a "match" is more likely to be coincidence than the player's line — a one-word
     * "Yes." said by the player would otherwise colour every other character's "Yes." too.
     */
    private const val MIN_MATCH_LENGTH = 3
}
