# Database

Room, 21 tables, schema version 1, exported to `app/schemas/`.

## The storage decision

Each table carries **typed, indexed columns for every field the engine queries, filters or sorts
on**, plus a `payload` column holding the canonical record as JSON. Nothing is queried out of the
JSON; the JSON only round-trips a row the indexes already found, and it is always written from the
same object the columns were derived from, in one statement.

The alternative — one column per field across eighteen record types — would add several thousand
lines of hand-written mapping whose only benefit would be the ability to write queries the engine
does not issue, and whose cost would be a migration for every field added to a game model.
`WorldStore` defines exactly which reads exist, so the indexed set is knowable and closed.

The exception is queries that match against a **collection**. Those get real join tables with
composite indexes, because they are on the hot path of every turn:

* `memory_entities (memoryId, entityId, gameId)` — "memories involving any of these entities"
* `event_entities (eventId, entityId, gameId)` — "events involving any of these entities"
* `knowledge_subjects (knowledgeId, entityId, gameId)` — "what does X know about any of these?"

A LIKE over a packed string would have worked at ten NPCs and fallen over at a thousand.

## Tables

| Table | Indexed on | Notes |
|---|---|---|
| `games` | pk | `stateVersion` is the optimistic lock |
| `worlds`, `players` | gameId (pk) | one per game |
| `npcs` | gameId; +location; +tier; +name | tier lets ambient crowd be skipped |
| `locations` | gameId; +discovered | |
| `factions` | gameId | |
| `items` | gameId; +ownerId; +locationId | `destroyed` projected for filtering |
| `threads` | gameId; +terminal | `rank` = importance × 100 + momentum, so "open situations" is one indexed sort |
| `relationships` | (gameId, from); unique (gameId, from, to) | |
| `knowledge` | (gameId, knower); unique (gameId, knower, factKey) | uniqueness enforces one view per fact |
| `knowledge_subjects` | (gameId, entityId) | join |
| `rumors` | (gameId, virality) | |
| `memories` | gameId +owner/+importance/+location/+thread/+lastReinforced | |
| `memory_entities` | (gameId, entityId) | join — retrieval hot path |
| `summaries` | (gameId, coversToTurn) | |
| `events` | (gameId, sequence); (gameId, importance, worldMinutes); +actor; +target | the two prompt queries map onto the first two |
| `event_entities` | (gameId, entityId) | join |
| `turns` | (gameId, turnNumber); unique (gameId, idempotencyKey); +status | uniqueness is what makes retries safe |
| `snapshots` | (gameId, turnNumber) | mutable canonical state + the ledger position |
| `images` | (gameId, entityId); +canonical | metadata; bytes on disk |
| `usage_records` | gameId; timestamp | no FK — outlives the save it describes |

Every child table has a `CASCADE` foreign key to `games`, and `PRAGMA foreign_keys = ON` is set on
open (Room disables it by default). Deleting a save therefore cannot leave orphans — verified by a
test that deletes one campaign and asserts the other is untouched.

## Migrations

`MIGRATIONS` is currently empty because the schema is at version 1.

`fallbackToDestructiveMigration` is **deliberately not called anywhere**. A player's campaign is the
product; silently wiping it on a schema change would be worse than crashing, because at least a
crash is noticed. When the schema changes: bump `version`, add a `Migration`, and commit the
regenerated `app/schemas/*.json` so the change is reviewable in the diff.

## Query limits

SQLite binds at most 999 variables (fewer on older Android). `RoomWorldStore` chunks every
`IN (:ids)` query at 400, which matters during export when a large campaign can legitimately ask
about more ids than the limit.

An empty `IN ()` is a SQL *syntax error*, and Room binds the list verbatim. `memoryCandidates`
substitutes a sentinel id that matches nothing, so the first turn of a game — where nothing is yet
in focus — does not crash. Covered by a test.

## Verification

`RoomWorldStoreTest` runs on Robolectric against real SQLite: world creation, a full turn, the
atomic lock, idempotency across four retries, knowledge scoping through the join tables, certainty
never degrading, retrieval through `memory_entities`, empty filter sets, cascade deletion,
export/import id disjointness, a 120-turn campaign with bounded paging and unique sequences, and SQL
usage aggregation.
