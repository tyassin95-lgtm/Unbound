# Testing

82 tests, 0 failures. No test makes a network call or needs an API key.

```bash
./gradlew :core:test            # 65 tests — engine, pure JVM, ~2s
./gradlew :app:testDebugUnitTest # 17 tests — Room on real SQLite + security
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
| `WorldTimeTest` | 5 | 0.0s | calendar, seasons, elapsed-time phrasing |
| `LongCampaignTest` | 4 | 0.4s | 150 turns, context bounds, cost |
| `ContentGuardTest` | 4 | 0.0s | age gating |
| `ImagePromptTest` | 4 | 0.0s | visual consistency |
| `ThreadEngineTest` | 4 | 0.0s | decay, deadlines, self-resolution |
| `WorldSimulatorTest` | 3 | 0.0s | off-screen world movement |
| `JournalTest` | 2 | 0.3s | derived views, no knowledge leaks |
| `KnowledgeScopeTest` | 2 | 0.0s | secrets and rumors |
| `LongTermMemoryTest` | 1 | 0.2s | the §94 scenario across 120 turns |

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
