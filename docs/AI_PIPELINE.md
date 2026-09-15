# The AI turn pipeline

One player action, start to finish. Implemented in `core/engine/TurnPipeline.kt`.

## Shape, and why

The model call happens **outside** the transaction. It is slow and can fail, and holding a database
transaction open across a 30-second network request would be indefensible. Validation happens after
it and before any write. Every write lands inside one transaction guarded by the optimistic lock.

```
1.  Idempotency check       committed under this key already? return it unchanged
2.  Load game/world/player
3.  Persist a PENDING turn  ← before the network call, so a crash is resumable
4.  Assemble context        bounded retrieval, per-NPC knowledge, content guard clause
5.  Call the provider       ← outside the transaction
6.  Parse                   tolerant of fenced JSON
7.  Validate                reject impossible states
8.  ── transaction ──
      re-read and re-check the version
      bumpStateVersion  ← the commit point
      apply state ops · emit events · derive relationship deltas
      propagate knowledge · reconcile memories · update threads
      run the world simulator for the elapsed time
      write the completed turn and the new game row
      record usage
    ── end ──
9.  Return narrative + suggestions + diagnostics
```

Any failure before step 8 leaves the world byte-identical. The pending turn row keeps its
idempotency key so the retry is *the same turn*, not a new one.

## What the model may return

A strict JSON schema (`prompt/TurnSchema.kt`). The important design decision is what the schema
**omits**: there is no field by which the model can set an absolute value for currency, health or
ownership. It may only describe *deltas and transfers*. This is the structural reason the model
cannot narrate itself 500 gold — it has no way to say "the player now has 500", only "−500", which
is then checked against what the player actually holds.

```json
{
  "narrative": "...",
  "time_advance_minutes": 5,
  "scene_is_significant": false,
  "events": [...],
  "state_changes": [...],
  "knowledge_changes": [...],
  "relationship_changes": [...],
  "memory_candidates": [...],
  "thread_changes": [...],
  "npc_actions": [...],
  "world_changes": [...],
  "new_characters": [...],
  "suggested_actions": [...]
}
```

Strict mode requires every property in `required` and `additionalProperties: false` at every level,
so optionality is expressed by allowing null. The DTO layer therefore defaults every field: a model
that omits something produces a *smaller turn*, not a failed one.

## Validation

`StateValidator` is deterministic and has one governing principle: **prefer dropping one element
over failing the turn.** A single bad relationship target should not cost the player their action.

Only two things fail a turn outright, because committing them would corrupt state:

* an empty narrative
* negative time

Everything else drops the offending element, records a `ValidationIssue`, and commits the rest. The
issues surface in developer mode.

What it rejects:

| Code | Situation |
|---|---|
| `INSUFFICIENT_FUNDS` | spend exceeds holdings — including two spends that are individually affordable but jointly are not |
| `ITEM_DUPLICATION` | a unique item sent to two destinations in one turn |
| `WRONG_OWNER` | given away by someone who does not have it |
| `DUPLICATE_UNIQUE_ITEM` | a second unique item with an existing name |
| `DESTROYED_ITEM` | a destroyed item changing hands |
| `DEAD_NPC_ACTING` / `ALREADY_DEAD` | the dead acting, or dying twice |
| `TELEPORTATION` | a non-adjacent move with no time spent — unless the world's `specialRules` permit it |
| `IMPOSSIBLE_KNOWLEDGE` | an absent NPC learning something first-hand with no source named |
| `UNEXPLAINED_RELATIONSHIP_CHANGE` | a relationship moving with no stated reason |
| `IMPLAUSIBLE_RELATIONSHIP_SWING` | more than 45 points on a dimension in one turn |
| `IMPLAUSIBLE_HEALTH_SWING` | beyond the per-turn ceiling |
| `DANGLING_REFERENCE` | any reference to an entity that does not exist |
| `UNKNOWN_CHANGE_TYPE` / `UNKNOWN_EVENT_TYPE` | anything outside the permitted set |

World-specific rules can legitimately override physics: a setting whose `specialRules` mention
portals disables the teleportation check. Covered by test.

## What the engine does without the model

Deliberately deterministic, and therefore free:

* relationship consequences for unambiguous events (helping builds trust; theft costs it)
* knowledge propagation and rumor spread with distortion
* memory extraction, de-duplication and reinforcement
* thread momentum decay, deadlines and self-resolution
* weather, NPC schedules, faction action
* world time arithmetic
* the journal, status, inventory
* save, load, export, import, undo
* repetition detection (three same-verb turns injects a "change the pressure" note)

One model call per narrative turn. Asserted by test.

## The other two model calls

Besides the narrative turn, exactly two other operations reach a model, both only during world
creation and both at most once per campaign:

| Call | Schema | When |
|---|---|---|
| World generation | `WorldGenerationSchema.schema()` | Only when the player writes their own premise |
| Opening generation | `WorldGenerationSchema.openingsSchema()` | Once, after the character and world are both known |

Both return strict structured output, and both are sanitised before use. A note on schema shape:
strict mode requires `additionalProperties: false` at every level, which makes arbitrary object
keys impossible — so a location's exits travel as `[{label, to}]` rather than a `{label: to}` map.

Neither call can block play. A failed opening generation falls back to the world's authored hooks;
a failed *opening scene* still leaves a complete, playable world and tells the player only the
first paragraph is missing.

## Prompt structure

Split three ways so the stable part can actually be cached:

1. **`instructions`** — the static system prompt, assembled from ten modules
   (`PromptModules.kt`) in a fixed order. Byte-identical between turns for a given configuration.
   Nothing dynamic is permitted in that file.
2. **dynamic context** — labelled, near-tabular sections: world, player, scene, present, knowledge,
   memory, earlier chapters, important earlier events, recent events, what moved while you were
   busy, open situations, factions, in circulation, direction.
3. **player input** — appended last.

Terse and tabular rather than prose, because this block is sent on every turn and every token spent
restating the world is a token not spent on the story. The section order is fixed so diffs between
turns are small and legible when debugging.

## Failure handling

`AIErrorKind` classifies every failure, and the UI branches on the kind rather than on message text:

| Kind | Retryable | What the player sees |
|---|---|---|
| `INVALID_KEY` / `REVOKED_KEY` | no | the key was rejected; replace it |
| `INSUFFICIENT_QUOTA` | no | no credit; billing is in your OpenAI account |
| `RATE_LIMITED` | yes | try again shortly |
| `MODEL_UNAVAILABLE` | no | this account cannot use that model |
| `UNSUPPORTED_FEATURE` | no | names what the model cannot do |
| `TIMEOUT` / `NETWORK` / `SERVER_ERROR` | yes | try again |
| `MALFORMED_RESPONSE` | yes | could not be read; not applied |
| `CONTENT_REFUSED` | no | the provider declined |

Every error banner states *"Nothing in your world was changed"*, because after a failed turn that is
the only thing the player actually wants to know — and it is true, by construction.
