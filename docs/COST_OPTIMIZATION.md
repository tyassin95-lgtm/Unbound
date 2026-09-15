# Cost

The player pays for every token on their own key. Cost is treated as a correctness property.

## One model call per narrative turn

Everything below is deterministic local code and costs nothing:

| Operation | How |
|---|---|
| World creation | Authored seed worlds, expanded outward from the player |
| Journal, status, inventory | Derived from canonical state |
| Save, load, export, import, undo | Local |
| Time arithmetic, travel duration | `WorldTime` |
| NPC movement | Hourly schedules |
| Rumor spread and distortion | `KnowledgePropagator` |
| Faction action | `WorldSimulator`, day-scale ticks |
| Thread decay, deadlines, self-resolution | `ThreadEngine` |
| Weather | Seasonal tables |
| Relationship consequences for clear events | `RelationshipEngine.defaultDeltaFor` |
| Memory extraction, de-duplication, reinforcement | `MemoryExtractor` |
| Intent parsing for `status`, `journal`, `tone:`, `limits:`, `image of X` | `CommandParser` |
| Entity mention resolution | `CommandParser.resolveMentions` |

Asserted by test: creating a world costs **zero** model calls; ten turns cost exactly ten.

§91's worst case — fifty NPCs, one event — never happens. The simulator is bucketed by elapsed time
and skips ambient NPCs entirely, so a two-week skip costs the same as a two-hour one: nothing.

## Context does not grow with campaign age

The retrieval budget is a hard ceiling: 14 memories, 12 recent events, 6 older important events, 8
NPCs, 6 facts per NPC, 6 threads, 4 rumors, 3 summaries.

`LongCampaignTest` asserts that across 150 turns, late-game context stays under 1.6× early-game
context, no turn exceeds 30,000 characters, and fewer than 30 turns' prose appears in the final
context. Without those assertions, every claim on this page would be aspirational.

## Prompt caching

The prompt is split so the stable part can be cached: `instructions` is byte-identical between turns
for a given configuration, with nothing dynamic permitted in `PromptModules.kt`, and
`prompt_cache_key` groups requests that share it.

The usage screen reports the **cache-hit ratio** — cached input tokens over total input tokens — so
this is measurable rather than assumed.

## Output bounds

`max_output_tokens` is 2400. Narration targets 150–400 words by default, and the player can choose
Brief (90–180) or Long (300–650).

## Images

* Default is **on demand**. Nothing is generated unless asked.
* Automatic mode requires both the player's opt-in *and* the model flagging the scene significant.
* Cached by prompt plus appearance version; an identical request is never paid for twice.
* Disabled entirely is a first-class option.
* A failed image never fails or re-charges the turn.

## Task-appropriate models

`RequestType` distinguishes `NARRATIVE_TURN`, `MEMORY_EXTRACTION`, `SUMMARIZATION`, `INTENT_PARSE`,
`IMAGE`, `WORLD_GENERATION`, `MODEL_LIST` and `CONNECTION_TEST`, and usage is recorded per type.

In this build, memory extraction, summarisation and intent parsing are done **locally**, so their
token cost is zero rather than merely cheap. The taxonomy exists so that if a future feature needs a
model for one of them, it can be pointed at a cheap one and its cost reported separately.

## Reporting

`CostEstimator` multiplies real token counts (from OpenAI) by published prices. The UI labels every
money figure as an estimate and states that the OpenAI dashboard is authoritative — the app cannot
see discounts, tiers or free credits, and pretending otherwise would be a lie it cannot back up.

Usage rows are local-only. There is no upload path in the codebase.
