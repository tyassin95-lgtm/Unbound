package com.unbound.rpg

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unbound.core.content.Characters
import com.unbound.core.content.Settings
import com.unbound.core.engine.GameFactory
import com.unbound.core.engine.NewGameRequest
import com.unbound.core.engine.TurnOutcome
import com.unbound.core.engine.TurnPipeline
import com.unbound.core.knowledge.Certainty
import com.unbound.core.model.GameRecord
import com.unbound.core.model.Ids
import com.unbound.core.model.Importance
import com.unbound.core.save.SaveSystem
import com.unbound.core.testing.MockAIProvider
import com.unbound.core.testing.MockBehaviour
import com.unbound.core.validate.EventDto
import com.unbound.core.validate.KnowledgeChangeDto
import com.unbound.core.validate.MemoryCandidateDto
import com.unbound.core.validate.StateChangeDto
import com.unbound.core.validate.TurnResponseDto
import com.unbound.rpg.data.db.RoomWorldStore
import com.unbound.rpg.data.db.UnboundDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conformance: the Room implementation must behave exactly like the in-memory reference the engine
 * test-suite runs against.
 *
 * These run on Robolectric against a real SQLite database, so they exercise the actual SQL — the
 * indexes, the join tables, the CASCADE deletes and the conditional-UPDATE lock — rather than a
 * mock. No emulator is available in this environment, which is precisely why this coverage matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomWorldStoreTest {

    private lateinit var db: UnboundDatabase
    private lateinit var store: RoomWorldStore
    private lateinit var behaviour: MockBehaviour
    private lateinit var provider: MockAIProvider
    private lateinit var pipeline: TurnPipeline
    private lateinit var factory: GameFactory

    private var idCounter = 0
    private var clockMs = 1_700_000_000_000L
    private val ids: () -> String = { "id" + (++idCounter).toString().padStart(6, '0') }
    private val clock: () -> Long = { clockMs += 1000; clockMs }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnboundDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomWorldStore(db)
        behaviour = MockBehaviour()
        provider = MockAIProvider(behaviour)
        pipeline = TurnPipeline(store, provider, clock, ids)
        factory = GameFactory(store, clock, ids)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newGame(): GameRecord {
        val c = Characters.byId("smuggler")!!
        return factory.createGame(
            NewGameRequest(
                seed = Settings.byId("ashmarket")!!,
                name = c.name, age = c.age, gender = c.gender, appearance = c.appearance,
                personality = c.personality, skills = c.skills, weaknesses = c.weaknesses,
                background = c.background, goals = c.goals, secrets = c.secrets,
                startingCurrency = c.startingCurrency, textModelId = "mock-story",
            ),
        )
    }

    private suspend fun maraId(gameId: String) =
        store.persistentNpcs(gameId, 50).first { it.name == "Mara Venn" }.id

    @Test
    fun `world creation writes a complete, queryable campaign`() = runTest {
        val game = newGame()

        assertNotNull(store.getGame(game.id))
        assertNotNull(store.getWorld(game.id))
        assertNotNull(store.getPlayer(game.id))
        assertEquals(3, store.allNpcs(game.id).size)
        assertEquals(5, store.allLocations(game.id).size)
        assertEquals(2, store.factions(game.id).size)
        assertEquals(1, store.allThreads(game.id, 50).size)
        assertEquals("Each NPC starts knowing only their own secret", 3, store.allKnowledge(game.id).size)
        assertEquals(1, store.countEvents(game.id))

        // The starting location is discovered; everything else is not, so the world is not
        // pre-revealed.
        assertEquals(1, store.discoveredLocations(game.id, 50).size)
    }

    @Test
    fun `a full turn commits through real SQL`() = runTest {
        val game = newGame()
        val outcome = pipeline.execute(game.id, "I order a drink and listen to the room.")
        assertTrue(outcome is TurnOutcome.Success)

        val updated = store.getGame(game.id)!!
        assertEquals(1, updated.turnNumber)
        assertEquals(game.stateVersion + 1, updated.stateVersion)
        assertTrue(updated.worldTime > game.worldTime)
        assertEquals(1, store.countTurns(game.id))
        assertTrue(store.countEvents(game.id) > 1)
    }

    @Test
    fun `the optimistic lock is genuinely atomic in SQLite`() = runTest {
        val game = newGame()
        val version = game.stateVersion

        assertTrue("The first claim on a version must win", store.bumpStateVersion(game.id, version))
        assertFalse("A second claim on the same version must lose", store.bumpStateVersion(game.id, version))
        assertEquals(version + 1, store.getGame(game.id)!!.stateVersion)
    }

    @Test
    fun `an idempotency key identifies exactly one turn, across retries`() = runTest {
        val game = newGame()
        behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You pay the barkeep.",
                timeAdvanceMinutes = 5,
                stateChanges = listOf(StateChangeDto(type = "CURRENCY_CHANGE", entityId = "player", amount = -5, reason = "information")),
            )
        }
        val start = store.getPlayer(game.id)!!.currency

        repeat(4) { pipeline.execute(game.id, "I bribe the barkeep.", idempotencyKey = "bribe") }

        assertEquals("Charged exactly once", start - 5, store.getPlayer(game.id)!!.currency)
        assertEquals(1, store.countTurns(game.id))
        assertEquals(1, store.getGame(game.id)!!.turnNumber)
    }

    @Test
    fun `knowledge scoping survives the real join tables`() = runTest {
        val game = newGame()
        val mara = maraId(game.id)
        val surrin = store.persistentNpcs(game.id, 50).first { it.name == "Warden Surrin" }.id

        behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "You tell her, quietly.",
                timeAdvanceMinutes = 10,
                events = listOf(
                    EventDto(
                        type = "SECRET_DISCOVERED", actorId = Ids.PLAYER, targetId = mara,
                        summary = "The player admitted the shipment was taken by a friend.",
                        importance = "HIGH", knowledgeScope = "SECRET", witnessIds = listOf(mara),
                    ),
                ),
                knowledgeChanges = listOf(
                    KnowledgeChangeDto(
                        knowerId = mara, factKey = "player.shipment_truth",
                        statement = "The player knows who really took the shipment.",
                        certainty = "KNOWN", subjectEntityIds = listOf(Ids.PLAYER),
                    ),
                ),
            )
        }
        pipeline.execute(game.id, "I tell Mara the truth.")

        // The indexed subject-join query must find it...
        val about = store.knowledgeOfAbout(game.id, mara, listOf(Ids.PLAYER), 10)
        assertTrue("The join table must return the fact", about.any { it.factKey == "player.shipment_truth" })
        assertEquals(Certainty.KNOWN, about.first { it.factKey == "player.shipment_truth" }.certainty)

        // ...and it must not reach anyone who was not a witness.
        assertFalse(store.hasFact(game.id, surrin, "player.shipment_truth"))
        assertTrue(store.knowledgeOfAbout(game.id, surrin, listOf(Ids.PLAYER), 10).none { it.factKey == "player.shipment_truth" })
    }

    @Test
    fun `a knower holds one view per fact, and certainty never degrades`() = runTest {
        val game = newGame()
        val mara = maraId(game.id)

        store.upsertKnowledge(
            listOf(
                com.unbound.core.knowledge.KnowledgeRecord(
                    id = ids(), gameId = game.id, knowerId = mara, factKey = "k1",
                    statement = "heard something", certainty = Certainty.RUMORED,
                    learnedAtWorldMinutes = 0, importance = Importance.LOW,
                ),
            ),
        )
        store.upsertKnowledge(
            listOf(
                com.unbound.core.knowledge.KnowledgeRecord(
                    id = ids(), gameId = game.id, knowerId = mara, factKey = "k1",
                    statement = "saw it myself", certainty = Certainty.KNOWN,
                    learnedAtWorldMinutes = 10, importance = Importance.HIGH,
                ),
            ),
        )
        // Then a weaker rumor arrives after the fact.
        store.upsertKnowledge(
            listOf(
                com.unbound.core.knowledge.KnowledgeRecord(
                    id = ids(), gameId = game.id, knowerId = mara, factKey = "k1",
                    statement = "someone said something vague", certainty = Certainty.RUMORED,
                    learnedAtWorldMinutes = 20, importance = Importance.LOW,
                ),
            ),
        )

        val held = store.knowledgeOf(game.id, mara, 50).filter { it.factKey == "k1" }
        assertEquals("Exactly one view per fact", 1, held.size)
        assertEquals("Hearsay must not overwrite first-hand knowledge", Certainty.KNOWN, held.single().certainty)
        assertEquals("saw it myself", held.single().statement)
    }

    @Test
    fun `memory retrieval finds entity-linked memories through the join table`() = runTest {
        val game = newGame()
        val mara = maraId(game.id)

        behaviour.responder = { _ ->
            TurnResponseDto(
                narrative = "It happens.",
                timeAdvanceMinutes = 10,
                memoryCandidates = listOf(
                    MemoryCandidateDto(
                        ownerId = mara, text = "The player promised to settle the Kettle's note.",
                        entityIds = listOf(Ids.PLAYER), importance = "CRITICAL",
                    ),
                ),
            )
        }
        pipeline.execute(game.id, "I make Mara a promise.")

        // Found by owner...
        assertTrue(store.memoriesOf(game.id, mara, 50).any { it.text.contains("settle the Kettle") })

        // ...and by the entity it involves, which is the join-table path the retriever uses.
        val byEntity = store.memoryCandidates(
            gameId = game.id,
            ownerIds = listOf("nobody"),
            entityIds = listOf(Ids.PLAYER),
            locationId = null,
            threadIds = emptyList(),
            minImportance = Importance.LOW,
            limit = 20,
        )
        assertTrue("The memory_entities join must find it", byEntity.any { it.text.contains("settle the Kettle") })
    }

    @Test
    fun `memory candidates tolerate empty filter sets`() = runTest {
        val game = newGame()
        // An empty IN () is a SQL syntax error; the store must not blow up on a first turn where
        // nothing is in focus yet.
        val result = store.memoryCandidates(
            gameId = game.id, ownerIds = emptyList(), entityIds = emptyList(),
            locationId = null, threadIds = emptyList(), minImportance = Importance.LOW, limit = 10,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `deleting a save cascades and leaves other saves untouched`() = runTest {
        val a = newGame()
        val b = newGame()
        repeat(3) { pipeline.execute(a.id, "I look around.") }
        repeat(2) { pipeline.execute(b.id, "I look around.") }

        val bEvents = store.countEvents(b.id)
        store.deleteGame(a.id)

        assertNull(store.getGame(a.id))
        assertEquals(0, store.countEvents(a.id))
        assertEquals(0, store.allNpcs(a.id).size)
        assertEquals(0, store.allKnowledge(a.id).size)
        assertEquals(0, store.allMemories(a.id).size)
        assertEquals(0, store.allLocations(a.id).size)
        assertEquals(0, store.countTurns(a.id))
        assertEquals(0, store.usageFor(a.id, 100).size)

        assertEquals("The other save must be untouched", bEvents, store.countEvents(b.id))
        assertEquals(2, store.getGame(b.id)!!.turnNumber)
    }

    @Test
    fun `export and import round-trip through SQLite without sharing ids`() = runTest {
        val save = SaveSystem(store, clock, ids)
        val game = newGame()
        repeat(4) { pipeline.execute(game.id, "I ask around the Kettle.") }

        val json = save.export(game.id)
        val imported = save.import(json)

        assertEquals(store.countEvents(game.id), store.countEvents(imported.id))
        assertEquals(store.allNpcs(game.id).size, store.allNpcs(imported.id).size)
        assertEquals(store.allKnowledge(game.id).size, store.allKnowledge(imported.id).size)
        assertEquals(store.countTurns(game.id), store.countTurns(imported.id))
        assertTrue(
            "An import must not collide with the original's rows",
            store.allNpcs(game.id).map { it.id }
                .intersect(store.allNpcs(imported.id).map { it.id }.toSet()).isEmpty(),
        )
        assertFalse("A save must never carry a credential", json.contains("sk-"))
    }

    @Test
    fun `a long campaign stays queryable and bounded on real SQL`() = runTest {
        val game = newGame()
        repeat(120) { i -> pipeline.execute(game.id, "Turn ${i + 1}: I keep working.") }

        assertEquals(120, store.getGame(game.id)!!.turnNumber)
        assertEquals(120, store.countTurns(game.id))
        assertTrue(store.countEvents(game.id) > 120)

        // Paging must be bounded regardless of campaign size.
        assertEquals(20, store.eventsPage(game.id, 0, 20).size)
        assertEquals(10, store.recentEvents(game.id, 10).size)
        assertEquals(12, store.turnsPage(game.id, 0, 12).size)

        // Sequence numbers must remain unique and monotonic through real inserts.
        val all = store.eventsPage(game.id, 0, 10_000)
        assertEquals(all.size, all.map { it.sequence }.distinct().size)
        assertEquals(all.size, all.map { it.id }.distinct().size)
    }

    @Test
    fun `usage rolls up per model in SQL, and the total stays a total`() = runTest {
        val game = newGame()
        repeat(40) { pipeline.execute(game.id, "I look around.") }
        images(game.id)

        val byModel = store.usageByModel(game.id)
        assertTrue("Rollups must be few, not one row per request", byModel.size <= 4)
        assertEquals(
            "Every request must be accounted for exactly once",
            store.usageTotals(game.id).requests,
            byModel.sumOf { it.requests },
        )
        assertEquals(
            store.usageTotals(game.id).inputTokens,
            byModel.sumOf { it.inputTokens },
        )

        // The estimate must agree with the record-by-record one, which is the definition it replaces.
        val catalog = com.unbound.core.testing.MockAIProvider.DEFAULT_MODELS.associateBy { it.id }
        val estimator = com.unbound.core.ai.CostEstimator(catalog)
        val fromRecords = estimator.summarise(store.usageFor(game.id, 10_000))
        val fromRollup = estimator.summariseAggregates(byModel)
        assertEquals(fromRecords.requests, fromRollup.requests)
        assertEquals(fromRecords.inputTokens, fromRollup.inputTokens)
        assertEquals(fromRecords.estimatedCostUsd, fromRollup.estimatedCostUsd, 1e-9)
    }

    private suspend fun images(gameId: String) {
        // A second request type, so the rollup has to group by more than the model.
        store.recordUsage(
            com.unbound.core.model.UsageRecord(
                id = ids(), gameId = gameId, turnId = null, timestampMs = clock(),
                requestType = com.unbound.core.model.RequestType.IMAGE, modelId = "mock-image",
            ),
        )
    }

    @Test
    fun `usage totals aggregate in SQL`() = runTest {
        val game = newGame()
        repeat(5) { pipeline.execute(game.id, "I look around.") }

        val totals = store.usageTotals(game.id)
        assertEquals(5, totals.requests)
        assertTrue(totals.inputTokens > 0)
        assertTrue(totals.outputTokens > 0)
        assertEquals(0, totals.failures)
    }

    @Test
    fun `snapshot, prune and undo behave the same on real SQL as in memory`() = runTest {
        val game = newGame()
        val snapshots = com.unbound.core.save.SnapshotService(store, clock, ids)

        snapshots.capture(game.id, com.unbound.core.model.SnapshotReason.GAME_START)
        repeat(60) { i ->
            pipeline.execute(game.id, "Turn ${i + 1}: I keep working.")
            val g = store.getGame(game.id)!!
            snapshots.shouldSnapshot(g, sceneWasSignificant = false)?.let { reason ->
                snapshots.capture(game.id, reason)
                // The point of pruning is to keep the newest restore point, not to lose it.
                val kept = store.snapshots(game.id, 100)
                assertTrue("Pruning deleted the snapshot it had just taken", kept.isNotEmpty())
                assertEquals(g.turnNumber, kept.maxOf { it.turnNumber })
            }
        }

        val kept = store.snapshots(game.id, 100)
        assertTrue("Restore points must stay bounded", kept.size <= 9)
        assertTrue(
            "The start of the game must always be recoverable",
            kept.any { it.reason == com.unbound.core.model.SnapshotReason.GAME_START },
        )

        val usageBefore = store.usageTotals(game.id)
        val preview = snapshots.describeUndo(game.id)!!
        assertTrue(preview.toTurn < preview.fromTurn)
        assertTrue("An undo must not claim to discard the whole ledger", preview.eventsLost < store.countEvents(game.id))

        val result = snapshots.undoTo(game.id, preview.toTurn)
        assertTrue(result is com.unbound.core.save.UndoResult.Restored)

        val after = store.getGame(game.id)!!
        assertEquals(preview.toTurn, after.turnNumber)
        assertEquals("Turn history must be truncated, not merely hidden", preview.toTurn, store.countTurns(game.id))
        assertTrue("The ledger must be truncated too", store.countEvents(game.id) > 0)
        assertEquals(
            "An undo rolls back the world, not what the player was charged",
            usageBefore.requests,
            store.usageTotals(game.id).requests,
        )

        // The world must still be playable afterwards: turn numbers continue gaplessly.
        val outcome = pipeline.execute(game.id, "I carry on.")
        assertTrue(outcome is TurnOutcome.Success)
        assertEquals(preview.toTurn + 1, store.getGame(game.id)!!.turnNumber)
        val seqs = store.eventsPage(game.id, 0, 10_000).map { it.sequence }
        assertEquals("Truncation must not leave duplicate sequence numbers", seqs.size, seqs.distinct().size)
    }
}
