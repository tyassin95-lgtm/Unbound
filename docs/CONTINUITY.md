# Continuity

How UNBOUND keeps a world coherent across hundreds of turns, and what was wrong with the first
attempt.

## The audit

Continuity was the application's weakest property, but not for the reason it looked. The obvious
diagnosis — "not enough context" — was wrong. The database was already rich. Almost none of it
reached the model.

| Kept in the database | Reached the model |
|---|---|
| NPC goals, desires, fears, values, secrets | personality and a mood |
| `causedByEventId` on every event | never written, never read |
| Item provenance (written on every transfer) | never rendered |
| Location history | never written, never rendered |
| The last N turns, read every turn | used only to detect repetition |
| World-simulation notes | `worldNotes = emptyList()` — hard-coded |
| `relationship_changes` from the model | accepted, validated, discarded |
| `world_changes` from the model | a whole channel wired to nothing |

The single largest gap was the **transcript**. `recentTurns` was read on every turn and used only to
decide whether the player was repeating themselves. No prior narration and no prior player input
ever reached the model, so every turn arrived as a fresh dossier with no conversation attached.
That is why a player had to keep restating what had just been said — and no amount of extra prompt
would have fixed it.

Items held by people in the room were also never in the context, so the validator saw a transfer of
something it had no record of and rejected it: the player could not take, be given, or be robbed of
anything an NPC was holding.

## The architecture

```
Game database (canonical)
        │
ContinuityEngine        ← decides what matters right now
        │
ContextBuilder          ← renders it as a dossier
        │
AIProvider (routing)    ← OpenAI or Gemini, identical input
```

`ContinuityEngine` is deliberately separate from the turn pipeline. Deciding what the model should
know is a different job from committing state, and it is the part most worth being able to test
alone — `ContinuityScenarioTest` drives it directly.

## Four kinds of statement

The context distinguishes four things, because confusing them is what produces a world that
contradicts itself:

* **CURRENT TRUTH** — canonical state. The database says so, so it is so.
* **HISTORY** — what happened, and where current truth came from. Fixed, past tense.
* **MEMORY** — what a mind retains. Partial by nature; silence is not evidence of absence.
* **BELIEF** — what someone holds to be true, which is allowed to be wrong.

Plus **THE SCENE SO FAR**, the transcript, explicitly subordinate to CURRENT TRUTH: prose is not
the record.

Sections are ordered stable-first — the world, the protagonist, the rules — so consecutive turns
share a long prefix for a provider's prompt cache to hit. `LongCampaignContinuityTest` asserts the
stable half is byte-identical across thirty turns, which is the only thing that makes that claim
true rather than aspirational.

## Obligations

Promises, debts, deals, threats and oaths are **canonical state**, not memory. Before this, the
world could only remember that a relationship carried some numeric `obligation` and that an event
of type `PLAYER_MADE_PROMISE` had occurred. Neither answers the question a player actually asks
three hundred turns later: *what did I promise, to whom, and is it still outstanding?* A number
cannot be broken, and an event summary is not state — nothing consults it.

A commitment is true whether or not anyone in the scene remembers it, which is exactly what lets a
creditor turn up unannounced. A debt narrated as settled but not reported stays owed, and a turn
cannot discharge one the world never recorded.

## Causality

The first event of a turn is what the player did; every consequence the turn emits points back at
it. So "she will not serve you" arrives attached to the night it started, rather than the model
having to invent a reason on the spot — which is how fabricated history gets in.

## Cost

More context is not free, and the work above made a turn send considerably more. Per-turn query
cost went **down**, because the additions were paid for by removing two N+1 reads:

* NPC knowledge was two queries per character in the scene, so assembling a turn scaled with how
  crowded the room was. It is now one windowed query for the whole cast.
* What people in the room are carrying was one query per person; now one for the room.

`AuditProbe2Test` measures the per-turn query count and asserts it does not grow with campaign age.

## What is verified

`ContinuityScenarioTest` runs the scenarios end to end and asserts **persisted state or the context
actually sent** — never whether a paragraph sounds right. A prose check would pass on a world that
had forgotten everything and was improvising well.

* forgotten encounter — someone met once is still known 200 turns later
* secret — told to one person, does not reach an unrelated one
* betrayal — the relationship moves, and the world can say why afterwards
* ownership — an item changes hands and keeps its history
* location — leave after something happened, return, find it changed
* faction — helping one is still felt by the other 150 turns later
* time — weeks pass and the world moved rather than paused
* obligations — outlive their scene; cannot be invented or silently discharged
* transcript — the conversation in progress reaches the model

`LongCampaignContinuityTest` holds the two properties in tension at 51, 100 and 249 turns: early
history reachable, context bounded.
