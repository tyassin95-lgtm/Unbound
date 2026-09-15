# Memory

The requirement is contradictory on its face: a campaign must stay consistent across thousands of
turns, and the per-turn cost must not grow with campaign age. Four layers resolve it.

## Layer A — Canonical state

Current truth: currency, inventory, health, location, who is alive, relationship vectors, weather,
faction standing. Lives in the entity tables. Always sent, always compact.

This layer is *authoritative*. Where it disagrees with anything else, it wins.

## Layer B — The event ledger

Append-only. Every meaningful event, with actor, target, location, in-world time, importance,
knowledge scope, witnesses, and a one-sentence factual summary.

The ledger is **never rewritten and never casually pruned**. Everything else — memories, summaries,
relationship totals, the journal — is derived and could be rebuilt from it.

The ledger is also **never sent wholesale**. Two separate, separately-budgeted queries reach the
prompt:

* the most recent N events, and
* the most important events from *before* that window.

The second query is what lets turn 200 reference turn 5. It is separate precisely so recency can
never crowd out significance.

## Layer C — Semantic memories

Compact durable lines — "The player promised to settle the Kettle's note before the month turns" —
owned by a specific entity (an NPC, the player, a faction, or `world`).

Two mechanisms keep this layer from exploding:

* **Importance gating.** Only `MEDIUM` and above are eligible for a memory at all. Trivia stays in
  the ledger.
* **Reinforcement.** A new memory that restates something the owner already remembers does not
  insert a near-duplicate; it bumps `reinforcementCount` on the existing row. Paying your tab eight
  times produces one memory that knows it happened eight times. (Verified by test.)

Periodic consolidation groups an owner's old low-importance memories about the same subject into
one line, keeping the source event ids. Canonical events are never touched.

## Layer D — Summaries

Chapter, session, NPC-history, location-history and faction-history digests. Convenience only —
never authoritative over state or the ledger.

---

## Retrieval

Three stages, none of which is proportional to campaign age.

### 1. Structured filter (indexed SQL)

`memoryCandidates` matches on owner, involved entity, location or thread, above a minimum
importance, capped at `budget × 6`. Entity matching goes through a real join table
(`memory_entities`) with a composite index, not a LIKE over a packed string — this is the hot path
of every turn.

### 2. Scoring (deterministic, local, free)

Only the surviving candidates are scored. Every term is bounded and every weight is an explicit
constant:

| Term | Weight | Notes |
|---|---|---|
| involves the focus of this action | 4.0 | strongest signal |
| involves someone present | 3.0 | |
| importance | 2.5 | × the importance weight |
| recency | 2.0 | `exp(-ageDays / 7)` — a half-life, not a cliff |
| belongs to an active thread | 2.0 | |
| wording overlap with the input | 2.0 | lexical, no API call |
| happened here | 1.5 | |
| involves a relevant item | 1.2 | |
| reinforcement | 1.0 | `ln(1+n)/ln(10)`, capped |
| relevant faction | 1.0 | |
| confidence | 0.5 | centred on 0.5 |

The weights are constants rather than a learned model on purpose: a designer must be able to look at
a retrieved set and explain it. Every `ScoredMemory` carries the human-readable reasons it scored,
and developer mode shows the retrieved ids.

### 3. Budget

Hard caps, applied regardless: 14 memories, 12 recent events, 6 older important events, 8 NPCs, 6
knowledge facts per NPC, 6 threads, 4 rumors, 3 summaries.

### Why not embeddings

A vector index would add a per-turn embedding call — a recurring cost on the player's own key, for a
retrieval stage that already works. §27 and §139 of the specification both prefer relational
filtering first. A `SemanticIndex` interface exists so the lexical term can be swapped for cosine
similarity without touching the contract.

---

## Per-character knowledge

This is the mechanism that makes the world feel alive rather than omniscient, and it is
**structural**, not a prompt instruction.

There is no global "known facts" table. `KnowledgeRecord` only ever answers *"what does X think?"*,
with a certainty grade:

| Certainty | Meaning | How the narrator may use it |
|---|---|---|
| `KNOWN` | witnessed or verified | may state as fact |
| `BELIEVED` | told by someone trusted | may assert with conviction |
| `RUMORED` | heard secondhand | must voice as hearsay |
| `SUSPECTED` | inferred, no evidence | a guess |
| `MISTAKEN` | sincerely held, and false | play it straight |
| `UNKNOWN` | explicitly aware of not knowing | |

Knowledge is written by exactly one component: `KnowledgePropagator`. An event's `KnowledgeScope`
decides who learns it:

* `PRIVATE` — only the actor
* `SECRET` — only named witnesses
* `WITNESSED` — everyone at the location
* `LOCAL_RUMOR` — witnesses now, then spreads over time with growing distortion
* `FACTION` — the faction's membership
* `PUBLIC` — everyone in the region

An NPC who was not present, not told, and not reached by a rumor has **no row**. No row means
nothing in their prompt section. Nothing in their prompt section means the model cannot have them
mention it. The §24 worked example — Mara knows the player stole the ring, the guard knows only
that a ring went missing, the neighbour blames the dock gang — arises from this machinery rather
than from being scripted.

A knower holds exactly one view per fact, and a later weaker report never downgrades a stronger one:
hearing a rumor about something you saw yourself does not make you unsure. (Verified against real
SQL.)

## What is verified

`LongTermMemoryTest` plays the §94 scenario literally across 120 turns and asserts what Mara
remembers, what the watch only half-heard, what Mara must *not* know, and that the context actually
handed to the model at turn 120 carries the history.

`LongCampaignTest` runs 150 turns and asserts that late-game context does not exceed 1.6× early-game
context, that no single turn sends more than 30,000 characters, that fewer than 30 turns appear in
the final context, and that a `CRITICAL` memory from turn 3 is still retrievable at turn 151.
