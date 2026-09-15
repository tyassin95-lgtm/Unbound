package com.unbound.core

import com.unbound.core.narrative.NarrativeFormatter
import com.unbound.core.narrative.NarrativeStyleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer must never lose a word of the story. Every test here checks the round-trip as well
 * as the styling, because a parser that drops text is far worse than one that fails to colour it.
 */
class NarrativeFormatterTest {

    private fun kinds(narrative: String, player: List<String> = emptyList()) =
        NarrativeFormatter.format(narrative, player).flatMap { it.spans }.map { it.kind }

    private fun spanText(narrative: String, player: List<String> = emptyList(), kind: NarrativeStyleKind) =
        NarrativeFormatter.format(narrative, player).flatMap { it.spans }.filter { it.kind == kind }.map { it.text }

    @Test
    fun `plain prose is one span and comes back unchanged`() {
        val text = "The rain has not stopped. Mara wipes the bar without looking at you."
        val paragraphs = NarrativeFormatter.format(text)
        assertEquals(1, paragraphs.size)
        assertEquals(listOf(NarrativeStyleKind.PROSE), paragraphs.single().spans.map { it.kind })
        assertEquals(text, paragraphs.single().plainText)
    }

    @Test
    fun `the protagonist's own line is distinguished from everyone else's`() {
        val narrative = """You put the ring on the bar. "I'm giving it back," you say. Mara looks at it. "Why now?" she asks."""
        val spans = NarrativeFormatter.format(narrative, listOf("I'm giving it back")).flatMap { it.spans }

        val player = spans.filter { it.kind == NarrativeStyleKind.PLAYER_SPEECH }
        val other = spans.filter { it.kind == NarrativeStyleKind.SPEECH }

        assertEquals(1, player.size)
        assertTrue(player.single().text.contains("I'm giving it back"))
        assertEquals(1, other.size)
        assertTrue(other.single().text.contains("Why now?"))
    }

    @Test
    fun `quotation marks stay inside the coloured run`() {
        val spans = NarrativeFormatter.format("""She says "no" and turns away.""").flatMap { it.spans }
        val speech = spans.single { it.kind == NarrativeStyleKind.SPEECH }
        assertEquals("\"no\"", speech.text)
    }

    @Test
    fun `curly quotes and apostrophes still match the player's line`() {
        val narrative = "“I’m not paying that,” you tell him."
        val player = NarrativeFormatter.format(narrative, listOf("I'm not paying that,"))
            .flatMap { it.spans }
            .filter { it.kind == NarrativeStyleKind.PLAYER_SPEECH }
        assertEquals("Typographic quotes must not defeat the match", 1, player.size)
    }

    @Test
    fun `remote communication is lifted out and the brackets are stripped`() {
        val narrative = "Your phone buzzes. [[Mara: don't come to the Kettle tonight.]] You put it face down."
        val spans = NarrativeFormatter.format(narrative).flatMap { it.spans }

        val comms = spans.filter { it.kind == NarrativeStyleKind.COMMUNICATION }
        assertEquals(1, comms.size)
        assertEquals("Mara: don't come to the Kettle tonight.", comms.single().text)
        assertTrue("The brackets must not survive into the UI", spans.none { it.text.contains("[[") || it.text.contains("]]") })
        assertTrue(spans.first().text.startsWith("Your phone buzzes"))
        assertTrue(spans.last().text.contains("face down"))
    }

    @Test
    fun `speech inside a message belongs to the message, not the room`() {
        val narrative = """[[The line crackles. "Two hours," he says.]]"""
        val spans = NarrativeFormatter.format(narrative).flatMap { it.spans }
        assertEquals(listOf(NarrativeStyleKind.COMMUNICATION), spans.map { it.kind })
    }

    @Test
    fun `an unterminated marker degrades to prose instead of eating the paragraph`() {
        val narrative = "Your phone buzzes. [[Mara: don't come tonight. You put it face down."
        val paragraph = NarrativeFormatter.format(narrative).single()

        assertTrue(paragraph.spans.all { it.kind == NarrativeStyleKind.PROSE })
        assertTrue("No text may be lost", paragraph.plainText.contains("You put it face down"))
        assertTrue(paragraph.plainText.contains("Mara: don't come tonight"))
        assertTrue(paragraph.plainText.none { it == '[' })
    }

    @Test
    fun `an unterminated quote degrades to prose`() {
        val paragraph = NarrativeFormatter.format("""He starts to say "something and stops.""").single()
        assertTrue(paragraph.spans.all { it.kind == NarrativeStyleKind.PROSE })
        assertTrue(paragraph.plainText.contains("something and stops"))
    }

    @Test
    fun `paragraphs are preserved and blank lines dropped`() {
        val narrative = "First paragraph.\n\n   \nSecond paragraph."
        val paragraphs = NarrativeFormatter.format(narrative)
        assertEquals(2, paragraphs.size)
        assertEquals("First paragraph.", paragraphs[0].plainText)
        assertEquals("Second paragraph.", paragraphs[1].plainText)
    }

    @Test
    fun `a very short player line does not colour everyone else's identical word`() {
        // "No." said by the player must not turn every other "No." in the scene player-coloured.
        val narrative = """"No," you say. She shakes her head. "No," she repeats."""
        val spans = NarrativeFormatter.format(narrative, listOf("No")).flatMap { it.spans }
        assertEquals(
            "Matches below the safety length are ignored entirely",
            0,
            spans.count { it.kind == NarrativeStyleKind.PLAYER_SPEECH },
        )
    }

    @Test
    fun `several player lines in one turn are all matched`() {
        val narrative = """"I know who took it," you say. She waits. "And you already know too.""""
        val spans = NarrativeFormatter.format(
            narrative,
            listOf("I know who took it,", "And you already know too."),
        ).flatMap { it.spans }
        assertEquals(2, spans.count { it.kind == NarrativeStyleKind.PLAYER_SPEECH })
    }

    @Test
    fun `an empty narrative produces nothing rather than an empty paragraph`() {
        assertTrue(NarrativeFormatter.format("").isEmpty())
        assertTrue(NarrativeFormatter.format("   \n  ").isEmpty())
    }

    @Test
    fun `no text is ever lost, whatever the markup`() {
        val samples = listOf(
            """Plain.""",
            """"Speech," he said.""",
            """[[A message]] and prose.""",
            """[[Unclosed message and prose.""",
            """Mixed "speech" and [[a text]] and more prose.""",
            """"Player line," you say. [[Then a text.]]""",
        )
        for (sample in samples) {
            val rebuilt = NarrativeFormatter.format(sample, listOf("Player line,"))
                .joinToString(" ") { it.plainText }
            val expectedWords = sample.replace("[[", "").replace("]]", "")
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            val actualWords = rebuilt.split(Regex("\\s+")).filter { it.isNotBlank() }
            assertEquals("Words were lost formatting: $sample", expectedWords, actualWords)
        }
    }
}
