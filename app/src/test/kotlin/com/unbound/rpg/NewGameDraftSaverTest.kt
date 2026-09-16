package com.unbound.rpg

import androidx.compose.runtime.saveable.SaverScope
import com.unbound.core.content.Settings
import com.unbound.core.content.Characters
import com.unbound.rpg.ui.setup.NewGameDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Character creation spans several steps of typing. The draft used to be merely `remember`ed while
 * the step number was saveable, so a rotation put the player back on step three with an empty
 * character. This pins the round-trip, including the fields that are not primitives.
 */
class NewGameDraftSaverTest {

    @Test
    fun `a fully filled draft survives being saved and restored`() {
        val draft = NewGameDraft(
            name = "Rook Vance",
            age = "31",
            gender = "man",
            appearance = "Lean, weather-worn.",
            personality = "Wary but decent.",
            desires = "To stop running.",
            fears = "Being recognised.",
            skills = "reading a room, knots",
            weaknesses = "owes money",
            background = "Ran cargo that was not his.",
            goal = "Get clear of the debt",
            secret = "He still has the manifest",
            seed = Settings.ALL.first(),
            premise = "A foundry city under permanent smoke.",
            hook = "Your name is on the gate roster.",
            customOpening = "",
            startingCurrency = "11",
            startingCurrencyReason = "Paid short on the last run.",
            startingPossessions = listOf("A folded manifest", "A bad coat"),
            tone = com.unbound.core.model.Tone.DARK,
            limits = "no torture",
        )

        val saved = with(NewGameDraft.Saver) { SaverScope { true }.save(draft) }
        assertNotNull("The draft must be saveable at all", saved)
        val restored = NewGameDraft.Saver.restore(saved!!)

        assertEquals("Every field must come back, not just the primitives", draft, restored)
    }

    @Test
    fun `a draft carrying a chosen template round-trips too`() {
        val draft = NewGameDraft(template = Characters.ALL.first(), name = "Someone")
        val saved = with(NewGameDraft.Saver) { SaverScope { true }.save(draft) }
        assertEquals(draft, NewGameDraft.Saver.restore(saved!!))
    }

    @Test
    fun `an unreadable saved value restores to nothing rather than crashing`() {
        assertEquals(null, NewGameDraft.Saver.restore("not json"))
    }
}
