package com.unbound.rpg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unbound.core.content.Characters
import com.unbound.core.content.Settings
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.model.GameRecord
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import com.unbound.rpg.data.db.RoomWorldStore
import com.unbound.rpg.data.db.UnboundDatabase
import com.unbound.rpg.data.images.ImageOutcome
import com.unbound.rpg.data.images.ImageService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Generated pictures are files on the player's device, not rows. Deleting a save used to delete
 * only the rows, stranding every PNG it had generated on disk with nothing left to name it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageStorageTest {

    private lateinit var db: UnboundDatabase
    private lateinit var store: RoomWorldStore
    private lateinit var images: ImageService
    private lateinit var cacheDir: File

    private var idCounter = 0
    private var clockMs = 1_700_000_000_000L
    private val ids: () -> String = { "id" + (++idCounter).toString().padStart(6, '0') }
    private val clock: () -> Long = { clockMs += 1000; clockMs }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, UnboundDatabase::class.java).allowMainThreadQueries().build()
        store = RoomWorldStore(db)
        images = ImageService(context, store, MockAIProvider(MockBehaviour()), clock, ids)
        cacheDir = File(context.filesDir, "images")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newGame(): GameRecord {
        val c = Characters.byId("smuggler")!!
        return GameFactory(store, clock, ids).createGame(
            NewGameRequest(
                seed = Settings.byId("ashmarket")!!,
                name = c.name, age = c.age, gender = c.gender, appearance = c.appearance,
                personality = c.personality, skills = c.skills, weaknesses = c.weaknesses,
                background = c.background, goals = c.goals, secrets = c.secrets,
                startingCurrency = c.startingCurrency, textModelId = "mock-story",
            ),
        )
    }

    @Test
    fun `deleting a save reclaims the pictures it generated`() = runTest {
        val kept = newGame()
        val doomed = newGame()

        val keptImage = images.imageForPlayer(kept.id, "gpt-image-1")
        val doomedImage = images.imageForPlayer(doomed.id, "gpt-image-1")
        assertTrue(keptImage is ImageOutcome.Ready)
        assertTrue(doomedImage is ImageOutcome.Ready)

        val keptFile = File((keptImage as ImageOutcome.Ready).record.localPath!!)
        val doomedFile = File((doomedImage as ImageOutcome.Ready).record.localPath!!)
        assertTrue(keptFile.exists())
        assertTrue(doomedFile.exists())

        images.deleteFilesFor(doomed.id)
        store.deleteGame(doomed.id)

        assertFalse("The deleted save's picture must not survive on disk", doomedFile.exists())
        assertTrue("Another save's pictures must be untouched", keptFile.exists())
        assertEquals(1, store.allImages(kept.id).size)
    }

    @Test
    fun `a file with no record is swept, a file with a record is not`() = runTest {
        val game = newGame()
        val ready = images.imageForPlayer(game.id, "gpt-image-1") as ImageOutcome.Ready
        val real = File(ready.record.localPath!!)

        // As a crash between writing the bytes and committing the row would leave it.
        val orphan = File(cacheDir, "${game.id}_orphan_x.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(orphan.exists())

        images.sweepOrphans()

        assertFalse("An unreferenced file must be reclaimed", orphan.exists())
        assertTrue("A referenced file must survive the sweep", real.exists())
    }
}
