# Architecture

## The one idea

The application owns canonical truth. The model narrates against it.

Everything structural in UNBOUND follows from that. The model never writes to the database; it
returns a *proposal* (prose plus a structured patch) which is validated against canonical state and
then applied — or rejected — by deterministic code.

```
Player input
   │
   ├─ CommandParser ──── local command? ──▶ answer from the database, no model call
   │
   ▼
Context assembly        current state · present NPCs · retrieved memories ·
   │                    per-NPC knowledge · recent + important events · open threads
   ▼
AITextProvider          one call, strict JSON schema
   │
   ▼
TurnResponseDto         parse (tolerant of fenced JSON)
   │
   ▼
StateValidator          reject the impossible; drop bad elements, keep the turn
   │
   ▼
WorldStore.transaction  ─ bumpStateVersion (atomic conditional UPDATE) ← the commit point
   │                    ─ apply ops · append events · propagate knowledge
   │                    ─ reconcile memories · update relationships/threads
   │                    ─ advance time · run the world simulator
   ▼
Narrative + suggestions
```

## Modules

```
core/  (pure Kotlin/JVM)
  model/         canonical records, world time, importance
  ledger/        GameEvent, EventType, KnowledgeScope
  memory/        MemoryRecord, scorer, extractor, consolidator
  knowledge/     KnowledgeRecord, Certainty, RumorRecord, KnowledgePropagator
  relationship/  RelationshipVector (11 dimensions), RelationshipEngine
  threads/       ThreadEngine — decay, deadlines, self-resolution
  simulation/    WorldSimulator — weather, NPC movement, rumors, factions
  validate/      TurnResponseDto, StateOp, StateValidator
  prompt/        PromptModules (static), ContextBuilder (dynamic), TurnSchema
  engine/        WorldStore, MemoryRetriever, TurnPipeline, GameFactory
  journal/       JournalBuilder — derived views
  save/          SaveSystem, SnapshotService
  images/        ImagePromptBuilder
  safety/        ContentGuard
  content/       authored characters and seed worlds
  command/       CommandParser
  ai/            AIProvider interfaces, ModelProfile, CostEstimator
  testing/       InMemoryWorldStore, MockAIProvider

app/  (Android)
  data/db/       Room entities, DAOs, mappers, RoomWorldStore
  data/security/ SecureCredentialStore, SafeLog
  data/ai/openai/ OpenAIClient, OpenAIProvider, OpenAIModelCatalog
  data/settings/ AppSettings (DataStore)
  data/images/   ImageService
  domain/        AppContainer (with database/provider seams for tests), GameSession, GameCreator
  ui/            Compose screens and ViewModels
```

### Why the boundary is there

`core` has **no Android dependency**. That is not architectural decoration — it is what makes the
hard parts testable. A 150-turn campaign, the long-term-memory scenario and every validator
rejection case run as ordinary JVM tests in seconds, with no emulator and no API key. In the
environment this was built in, no emulator existed at all; had the engine lived in the Android
module, none of it could have been verified.

`core` also has no knowledge of OpenAI. The engine talks to `AITextProvider`, `AIImageProvider` and
`AIModelCatalog`. `MockAIProvider` and `OpenAIProvider` are peers.

The UI never touches the store or the pipeline. It talks to `GameSession`, which is also where
local commands are resolved without a model call.

## World creation

```
character → world → opening → play
              │        │
              │        └─ OpeningGenerator   one call, 5-6 tailored openings
              └─ WorldGenerator              one call, only for a custom world
```

`GameCreator` (in `app/domain`) orchestrates the three stages and emits a `CreationState` at every
step. It is a plain class rather than logic inside the ViewModel for a specific reason: the
progress state must stay busy across *every* network call, and that ordering is only testable if
the orchestration can be driven without Android.

Both generators produce a `SeedWorld` — the same type the six authored settings are written in — so
`GameFactory` cannot tell an invented world from an authored one, and neither can anything
downstream of it. `WorldGenerator.sanitise` treats the model's answer as untrusted input on the
same principle as the state validator: repair what can be repaired, drop what cannot, and fail
loudly only when the result would be unplayable.

## The store contract

`WorldStore` (in `core/engine`) defines every read and write the engine can perform. Two properties
matter more than its size:

1. **Every read is bounded.** There is no `allEvents(gameId)` on the hot path. The full-table reads
   that do exist are named `allX` and documented as export-only, so their cost is obvious at the
   call site.
2. **`transaction` must be genuinely atomic.** A partially committed turn is the one failure that
   corrupts a save beyond repair.

Two implementations:

* `InMemoryWorldStore` (in `core/testing`) — the reference implementation. Its `transaction`
  snapshots every table and restores on throw, with transaction membership scoped to the calling
  coroutine so two concurrent turns cannot interleave. The engine test-suite runs against it, so an engine
  failure is never confused with a SQL failure.
* `RoomWorldStore` (in `app/data/db`) — production. A conformance suite runs the same scenarios
  against it on real SQLite via Robolectric.

## Concurrency and atomicity

Each turn carries the `stateVersion` it was built against. The commit point is
`bumpStateVersion(gameId, expected)`, which in SQL is:

```sql
UPDATE games SET stateVersion = stateVersion + 1
WHERE id = ? AND stateVersion = ?
```

One statement, so two concurrent turns cannot both observe the same version and both win — exactly
one UPDATE reports a changed row, the other turn throws `StaleStateException` and is abandoned
rather than merged.

This is called **before** the game row is written, so the guard cannot be bypassed. (An earlier
revision checked it afterwards; that was a genuine lost-update bug, fixed and now covered by a test.)

## Idempotency

An idempotency key identifies at most one turn row, ever. A retry *resumes* that row — it does not
open a second one. Without this, two rows share a key, the "already committed?" check can match the
stale one, and a retry charges the player twice. That was a real bug found by the test suite and is
now covered by `currency is never double-charged across a failed then retried turn`.

## Crash recovery

The turn row is written **before** the network call. A crash mid-turn therefore leaves a `PENDING`
row that is resumable, rather than a turn that silently never happened. Nothing outside the
transaction is ever mutated, so a failed turn leaves the world byte-identical.
