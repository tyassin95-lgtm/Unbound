package com.unbound.rpg

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.unbound.rpg.data.db.UnboundDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A player's campaign is the product. A schema change that quietly wipes it would be worse than a
 * crash, because at least a crash is noticed — so the migration is exercised against the *real*
 * exported v1 schema with real rows in it, not asserted about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(schemaDir(), DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = UnboundDatabase::class,
    )

    @Test
    fun `a campaign written by version 1 survives the upgrade to version 2`() {
        // A v1 database with a save in it, created from the committed 1.json schema.
        helper.createDatabase(1).use { db ->
            db.execSQL(
                "INSERT INTO games (id, title, settingId, settingName, createdAtEpochMs, updatedAtEpochMs, " +
                    "turnNumber, currentLocationId, worldMinutes, stateVersion, textModelId, payload) " +
                    "VALUES ('g1', 'Rook Vance', 'ashmarket', 'Dark Industrial Fantasy', 1700000000000, " +
                    "1700000000000, 42, 'loc_kettle', 5000, 7, 'gpt-5', '{}')",
            )
            db.execSQL(
                "INSERT INTO turns (id, gameId, turnNumber, status, idempotencyKey, payload) " +
                    "VALUES ('t1', 'g1', 42, 'COMPLETE', 'k1', '{\"narrative\":\"She does not look up.\"}')",
            )
        }

        helper.runMigrationsAndValidate(2, UnboundDatabase.MIGRATIONS.toList()).use { db ->
            // The campaign is still there, at the turn it was on.
            db.prepare("SELECT turnNumber, stateVersion, settingName FROM games WHERE id = 'g1'").use { s ->
                assertTrue("The save must survive the migration", s.step())
                assertEquals(42, s.getInt(0))
                assertEquals(7, s.getInt(1))
                assertEquals("Dark Industrial Fantasy", s.getText(2))
            }
            db.prepare("SELECT payload FROM turns WHERE id = 't1'").use { s ->
                assertTrue(s.step())
                assertTrue("The story must survive too", s.getText(0).contains("She does not look up"))
            }

            // And the new continuity table exists and is usable.
            db.execSQL(
                "INSERT INTO commitments (id, gameId, fromEntityId, toEntityId, status, createdWorldMinutes, payload) " +
                    "VALUES ('c1', 'g1', 'player', 'npc_mara', 'OUTSTANDING', 5000, '{}')",
            )
            db.prepare("SELECT COUNT(*) FROM commitments WHERE gameId = 'g1'").use { s ->
                assertTrue(s.step())
                assertEquals(1, s.getInt(0))
            }
        }
    }

    /**
     * The new per-turn continuity fields live in an existing JSON payload column, so a turn written
     * before the upgrade must still decode — with the new fields simply empty.
     */
    @Test
    fun `a turn written before the upgrade decodes with the new fields empty`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val oldPayload = """
            {"id":"t1","gameId":"g1","turnNumber":42,"playerInput":"I wait.","narrative":"Nothing comes.",
             "status":"COMPLETE","idempotencyKey":"k1","baseStateVersion":6,"createdAtEpochMs":1700000000000,
             "worldMinutesBefore":4990}
        """.trimIndent()

        val turn = json.decodeFromString(com.unbound.core.model.TurnRecord.serializer(), oldPayload)

        assertEquals(42, turn.turnNumber)
        assertEquals("Nothing comes.", turn.narrative)
        assertTrue("World notes must default to empty, not fail to parse", turn.worldNotes.isEmpty())
        assertNull(turn.providerId)
    }

    private companion object {
        const val DB = "migration-test.db"

        /** The committed schema JSON, read straight off disk rather than packaged into the APK. */
        /** A fresh database file per run; the helper refuses to create tables over an existing one. */
        fun schemaDir(): File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }
            .let { File(it, "app/build/migration-test") }
            .apply { deleteRecursively(); mkdirs() }
    }
}
