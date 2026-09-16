package com.unbound.rpg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unbound.core.content.Characters
import com.unbound.core.content.Settings
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import com.unbound.rpg.data.db.UnboundDatabase
import com.unbound.rpg.domain.AppContainer
import com.unbound.rpg.ui.AppViewModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Importing a save was wired to a button that navigated to a screen with no import on it, and the
 * view-model call it should have reached swallowed every failure. Both halves are covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveTransferTest {

    private lateinit var db: UnboundDatabase
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnboundDatabase::class.java).allowMainThreadQueries().build()
        container = AppContainer(context, databaseOverride = db, providerOverride = MockAIProvider(MockBehaviour()))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newGame() = container.gameFactory.createGame(
        Characters.byId("smuggler")!!.let { c ->
            NewGameRequest(
                seed = Settings.byId("ashmarket")!!,
                name = c.name, age = c.age, gender = c.gender, appearance = c.appearance,
                personality = c.personality, skills = c.skills, weaknesses = c.weaknesses,
                background = c.background, goals = c.goals, secrets = c.secrets,
                startingCurrency = c.startingCurrency, textModelId = "mock-story",
            )
        },
    )

    @Test
    fun `importing a real save adds it and says so`() = runTest {
        val game = newGame()
        val text = container.saveSystem.export(game.id)

        val notice = AppViewModel(container).importSave(text)

        assertEquals("The import must be a separate save", 2, container.store.listGames().size)
        assertNotNull("A successful import must be reported", notice)
        assertTrue(notice.contains("Imported"))
    }

    @Test
    fun `a file that is not a save is refused out loud, and changes nothing`() = runTest {
        newGame()
        val vm = AppViewModel(container)

        val refused = vm.importSave("{\"this\":\"is not a save\"}")
        assertEquals("Nothing may be created from an unreadable file", 1, container.store.listGames().size)
        assertTrue("The player must be told why nothing happened", refused.contains("not an UNBOUND save"))

        assertEquals("An empty file must be named as such", "That file was empty.", vm.importSave(""))
        assertEquals(1, container.store.listGames().size)
    }
}
