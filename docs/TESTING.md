# Testing

205 tests, 0 failures. No test makes a network call or needs an API key.

```bash
./gradlew :core:test            # 142 tests — engine and continuity, pure JVM
./gradlew :app:testDebugUnitTest # 63 tests — Room on real SQLite, both providers, the player journey, security
./gradlew test                   # everything
```

## Results

| Suite | Tests | Time | What it covers |
|---|---:|---:|---|
| `StateValidatorTest` | 16 | 0.0s | every §43 rejection case |
| `TurnPipelineTest` | 9 | 0.0s | transactions, idempotency, failure paths |
| `RoomWorldStoreTest` | 12 | 8.2s | the Room store on real SQLite |
| `SaveAndUndoTest` | 6 | 0.1s | export/import, undo, deletion |
| `CommandParserTest` | 5 | 0.1s | local commands vs. narrative |
| `SecretScanTest` | 5 | 0.8s | source, BuildConfig and **APK** secret scans |
| `PlayerJourneyTest` | 1 | 6.0s | the whole player path end to end on real SQLite |
| `AuditProbeTest` / `AuditProbe2Test` | 11 | 2.5s | snapshots, undo, bounded growth, concurrency, per-turn cost |
| `SaveTransferTest` | 2 | 1.5s | importing a save, and refusing one that is not |
| `ImageStorageTest` | 2 | 1.4s | reclaiming picture files, sweeping orphans |
| `NewGameDraftSaverTest` | 3 | 0.0s | the creation draft surviving a rotation |
| `ContinuityScenarioTest` | 10 | 1.2s | the nine continuity scenarios, on persisted state |
| `LongCampaignContinuityTest` | 3 | 4.5s | early history reachable *and* context bounded, to 249 turns |
| `GeminiProviderTest` | 8 | 1.0s | the Gemini wire format, against a real HTTP server |
| `ProviderRoutingTest` | 8 | 0.1s | routing, parity and when a fallback may and may not fire |
| `MigrationTest` | 2 | 1.4s | a v1 campaign surviving the upgrade, against the real schema |
| `WorldTimeTest` | 5 | 0.0s | calendar, seasons, elapsed-time phrasing |
| `LongCampaignTest` | 4 | 0.4s | 150 turns, context bounds, cost |
| `ContentGuardTest` | 4 | 0.0s | age gating |
| `ImagePromptTest` | 4 | 0.0s | visual consistency |
| `ThreadEngineTest` | 4 | 0.0s | decay, deadlines, self-resolution |
| `WorldSimulatorTest` | 3 | 0.0s | off-screen world movement |
| `JournalTest` | 2 | 0.3s | derived views, no knowledge leaks |
| `KnowledgeScopeTest` | 2 | 0.0s | secrets and rumors |
| `LongTermMemoryTest` | 1 | 0.2s | the §94 scenario across 120 turns |
| `NarrativeFormatterTest` | 13 | 0.0s | speech and message styling; no text is ever lost |
| `ContinuityTest` | 7 | 0.6s | recall across 100+ turns, chapters, thread discovery |
| `ScenePresenceTest` | 8 | 0.0s | who counts as being in the room |
| `OpeningDirectiveTest` | 4 | 0.1s | the chosen opening governs the opening scene |
| `SceneParticipationTest` | 6 | 0.1s | characters stay in the scene they are in |
| `WorldSanitiserTest` | 11 | 0.0s | generated worlds treated as untrusted input |
| `GameCreatorTest` | 7 | 0.1s | creation progress ordering, failure recovery |
| `OpeningGeneratorTest` | 2 | 0.0s | opening parsing and tailoring |
| `GeneratedWorldPlayableTest` | 1 | 0.0s | an invented world plays through the ordinary engine |

## The mock provider

Tests run against `MockAIProvider`, which is not a stub returning a fixed string: it reads the
dynamic context it was given and responds to the player's verb, so the **real** pipeline is
exercised — validation, event emission, knowledge propagation, memory reconciliation, simulation,
commit. It can also be told to fail in specific ways (`TIMEOUT`, `INVALID_KEY`, malformed JSON), and
it records the exact request, which is how the context-growth assertions are made.

## The two headline tests

### §94 — long-term memory (`LongTermMemoryTest`)

The scenario, played literally: turn 5 the player steals Mara's ring; turn 20 returns it; turn 40
insults her employer; turn 70 joins a rival faction across town; turn 120 asks her for help.

Asserted at turn 120:

* Mara remembers the theft, the return and the insult
* Mara does **not** know about the faction — she was not there and nobody told her
* Warden Surrin heard about the ring only as `RUMORED`, and does not know who took it
* the relationship records both the theft and the return with their reasons
* the context actually handed to the model carries the theft and Mara's knowledge
* the faction membership does **not** appear in Mara's knowledge block

### §95/§149 — 150 turns (`LongCampaignTest`)

* 150 turns commit; `stateVersion` is exactly 151 (one bump per turn)
* event ids and sequence numbers are unique across the whole ledger
* **late-game context stays under 1.6× early-game context**
* no single turn sends more than 30,000 characters
* fewer than 30 turns' prose appears in the final context
* a `CRITICAL` memory from turn 3 is still *retrievable* at turn 151, not merely stored
* world creation costs 0 model calls; 10 turns cost exactly 10

## Acceptance coverage

| Spec | Where |
|---|---|
| §141 basic game | `RoomWorldStoreTest.world creation…`, `a full turn commits…` |
| §142 long-term memory | `LongTermMemoryTest` |
| §143 knowledge scoping | `KnowledgeScopeTest`, `RoomWorldStoreTest.knowledge scoping…` |
| §144 world change | `WorldSimulatorTest`, `ThreadEngineTest` |
| §145 image consistency | `ImagePromptTest` |
| §146 cost | `LongCampaignTest.deterministic operations never reach the model` |
| §147 security | `SecretScanTest` (5) |
| §148 failure | `TurnPipelineTest` (malformed, timeout, invalid key, rejected patch) |
| §149 100+ turns | `LongCampaignTest` (150) + `RoomWorldStoreTest` (120 on SQLite) |

## Bugs these tests actually found

Not hypothetical — each of these was caught by a failing test during development and fixed:

1. **Retry double-charging.** A retry opened a *second* turn row under the same idempotency key, so
   the "already committed?" check could match the stale one and apply the turn twice. Retries now
   resume the existing row.
2. **Lost update.** `bumpStateVersion` was called *after* the game row was written, so the
   optimistic lock could be bypassed. The conditional bump is now the commit point.
3. **Import overwriting a live campaign.** Import reused entity ids, so importing a save onto a
   device that already held the source campaign silently overwrote its NPCs, locations and items.
   Import now regenerates every id and rewrites every reference.
4. **Lossy export.** The first export dropped all knowledge rows and most memories and items.
   Full-enumeration accessors were added to the store contract.
5. **A character vanished from her own conversation** — reported from play. An NPC introduced as
   arriving from elsewhere kept that location forever, so she could not be pictured, witnessed
   nothing said to her face, and had her knowledge rejected as `IMPOSSIBLE_KNOWLEDGE`. Presence is
   now resolved from the narrative and written back. Two further bugs surfaced underneath: the
   world simulator undid any move the story had just made, and characters were created *after*
   knowledge and memory were derived, so anyone introduced could never witness their own scene.
6. **Three subsystems were wired up and dead**, found by auditing the continuity complaint:
   `eventsInvolving` was implemented in the store contract and both implementations and never
   called; `upsertSummaries` was only ever reached by save/restore, so memory Layer D was inert and
   "earlier chapters" was permanently empty; and thread visibility was set to HIDDEN at world
   creation and never changed, so a world's own running situations never reached the journal.
   `NPC_INTRODUCED` was also emitted below the memorable threshold, so **first meetings were never
   remembered at all** — exactly the "how did we meet" case.
7. **The opening did not govern the opening.** The chosen situation was passed as the player's
   typed action, so it competed with the canonical clock and weather it was meant to override, and
   lost. It was also echoed into the log as a wall of GM instructions.
8. **Openings could dead-end.** A generated world ships no authored hooks, so a failed openings
   call left an empty list — and the retry button was drawn only when the list was non-empty.
9. **Rejection did not reject.** The validator reported impossible knowledge and dangling
   references, and the pipeline then applied the raw response anyway. `ValidationResult` now carries
   what survived, and the pipeline applies only that.
7. **Creation progress went idle too early** — reported by a player. The busy state was cleared
   *before* the opening-scene request, so the Begin button re-enabled while the call was still in
   flight and invited repeated taps. The orchestration was extracted into `GameCreator` so the
   emitted sequence could be pinned, and `GameCreatorTest` now asserts nothing goes idle until the
   last call returns.

## Bugs the audit pass found

A full production audit was run over the finished application. These were found by probes and by
the end-to-end journey test, not by inspection:

10. **Snapshot pruning deleted everything it was meant to keep**, so undo silently had nothing to
    roll back to every few captures. Snapshots also serialised the whole save, ledger included,
    on every capture, and undo rebuilt the game by deleting it first — destroying the player's
    usage and cost history, which no save bundle can restore.
11. **Memories grew without bound** (601 after 200 turns). The consolidator that was meant to
    prevent this was never called from anywhere, and only handled the importance band that does
    not actually grow.
12. **NPC hostility never reached the prompt or the journal**, because relationship writes used
    two orientations for the same pair and readers only queried one. Relationships also never
    decayed, despite the decay code existing.
13. **Two concurrent turns both committed.** The optimistic lock was sound; the in-memory store's
    transaction flag was not coroutine-scoped, and the mock provider never suspended, so the
    concurrency test had never actually raced anything.
14. **`listModels` blocked the main thread.** The blocking OkHttp call was wrapped in
    `Dispatchers.IO` by some callers and not others; the dispatch now lives inside the client.
15. **First meetings were never recorded for anyone the world started with.** Only characters the
    model invented were stamped as encountered, so a seeded NPC the player shared fifty scenes
    with stayed "unmet": absent from the journal, and never introduced to the model as someone
    already known. Found by the journey test on its first run.
16. **Importing a save was unreachable.** The button navigated to a screen with no import on it,
    and the view-model call it should have reached was never invoked from anywhere — and swallowed
    every failure when it was.
17. **Deleting a save stranded every picture it had generated** on disk, unreferenced.
18. **The usage screen's "total" stopped being a total** after 2,000 requests, understating what
    the player had spent on their own key.
19. **Character creation lost everything typed on a rotation.** The step number was saveable; the
    draft holding the character was not.

## Bugs the continuity upgrade found

Investigating *why* continuity was weak, rather than adding prompt, turned up five fields and one
whole channel that were accepted, validated and then never read — and one that was hard-coded
empty:

20. **The transcript was never sent.** `recentTurns` was read every turn and used only to detect
    repetition, so no prior narration and no prior player input ever reached the model. Every turn
    arrived as a fresh dossier with no conversation attached. The largest single cause.
21. **NPC interiority was stored and discarded.** Goals, desires, fears, values and secrets sat on
    every character; the context sent personality and a mood.
22. **`worldNotes = emptyList()`** was hard-coded in assembly, so the section the system prompt
    instructs the model to honour never rendered.
23. **`causedByEventId`** existed on every event and was never written, so nothing could explain
    why a current condition existed.
24. **`relationship_changes`** were discarded: a model reporting "this cost her trust" changed
    nothing, and all relationship movement came from event-type defaults.
25. **`world_changes`** was a channel duplicating `state_changes`, where only `state_changes` was
    wired. Removed rather than given a second path to the same place.
26. **Items held by people in the room** were never in the context, so the validator rejected a
    transfer of something it had no record of — the player could not take, be given, or be robbed
    of anything an NPC was holding.
27. **The Gemini schema translator** reached for `.jsonPrimitive` on the array form of `type`,
    which throws rather than returning null. Found by the first test written against it.

## What is *not* tested, and why

Stated plainly rather than omitted.

* **No instrumented UI test has been run.** No emulator and no device was available in the
  environment this was built in (no KVM, no attached device). The Compose layer compiles and is
  lint-clean, and the ViewModels are thin over `GameSession`, but **UI behaviour has not been
  observed running on a device.** This is the largest gap.
* **No live OpenAI call has been made.** There is no API key here, and §130 explicitly forbids tests
  that make network calls. The request and response shapes were verified against the official
  OpenAPI specification (v2.3.0) rather than guessed, but the integration has not been exercised
  against the live service.
* **Migrations are untested** because the schema is at version 1 and there is nothing to migrate
  from yet. `room-testing` is already on the test classpath for when there is.
* **Pricing accuracy.** Token counts come from OpenAI and are real; the money figure is a local
  multiplication by a table that will go stale. Labelled as an estimate everywhere it appears.
