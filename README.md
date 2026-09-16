# UNBOUND

**Living World AI RPG** — a persistent open-world text RPG for Android where the world remembers
what you did, and the people in it only know what they actually learned.

You bring your own API key — **OpenAI or Google Gemini**, whichever you prefer. There is no UNBOUND
account, no UNBOUND server, and no application-owned key. Your campaign lives on your device.

---

## What it actually is

Most "AI RPGs" are a chat window with a system prompt. The model is asked to remember, and it
forgets. UNBOUND inverts that: **the application owns canonical truth and the model is the narrator
operating against it.**

```
Android app  ──HTTPS──▶  OpenAI  or  Google Gemini
     │
     └── owns: canonical state, an append-only event ledger, per-character
         knowledge, long-term memory, relationships, threads, obligations,
         world time
```

The provider is interchangeable and the world does not change when you switch: a test plays the
same campaign on both and requires identical state, journal and history. See
[docs/PROVIDERS.md](docs/PROVIDERS.md).

Concretely, that means:

* If the database says you have 112 crowns and the model narrates you spending 500, the turn's
  state patch is **rejected** and the narrative is reconciled. The model cannot mint gold.
* If an NPC was not present, was not told, and has no knowledge row for a fact, that fact is not in
  their section of the prompt — so they cannot mention it, however convenient it would be.
* Threads you ignore do not sit frozen. They lose momentum, pass deadlines, and resolve themselves
  without you — usually worse than if you had engaged.
* Returning after 120 turns, an NPC still remembers the theft, the apology, and the insult, and
  still does not know about the faction you joined across town.

## Worlds

Six authored settings ship with the app, each with its streets, people and quarrels already
running before you arrive. **Or describe your own**, in a sentence or a page, and UNBOUND builds
it: locations wired together, people with concrete faces and their own secrets, factions with
conflicting aims, and situations already in motion that you did not cause.

A generated world becomes an ordinary world. It goes through exactly the same door as the authored
ones, so nothing downstream — memory, knowledge, simulation, saves — knows or cares which it was.
Generated content is treated as untrusted: exits to places that were never defined are dropped,
homeless characters are placed, and anyone the model forgot to give an age gets an explicit one
rather than an inferred one.

Once the character and the world are both known, UNBOUND writes **five or six ways your story
could begin** — specific to this person in this place, differing in kind rather than wording. Take
one, write your own, or refuse all of them and start in the ordinary run of your life.

The same call judges what your character plausibly has on them — money and objects — from who they
are and what they do, rather than handing every protagonist the same purse. You see the amount and
can change it before you begin, and the possessions become real items you can spend, give away or
lose.

---

## Setup

1. Install the APK (see [Build](#build)).
2. Open it. The first screen explains the BYOK arrangement and asks which service you want to use.
   **Gemini has a free tier**, which is the quickest way to start; OpenAI is pay-as-you-go.
3. Paste the key and tap **Connect**. It is verified against your account and stored in this
   device's hardware-backed keystore. You can add the other provider later, and keep both.
4. Choose a story model. UNBOUND fetches the models *your key can actually reach* and shows which
   ones can and cannot be used, with the reason.
5. Optionally choose an image model. Image generation defaults to **on demand** — nothing is
   generated unless you ask.
6. Start a new game: character, setting, opening situation.

Your own account is billed for your usage. UNBOUND shows local estimates, per provider, and says
plainly that the provider's own console is the authority.

---

## Architecture

Two modules, with the boundary placed where it buys the most:

```
core/   pure Kotlin/JVM — no Android, no vendor, no Compose
        domain · event ledger · memory · knowledge · relationships · threads
        obligations · continuity engine · simulation · validator · prompt
        builder · turn pipeline · AI interfaces

app/    Android
        Room · Android Keystore · OpenAI and Gemini providers · Compose UI
```

`core` depends on no Android class, which is why the engine can be — and is — tested as ordinary
fast JVM tests: a 150-turn campaign, the long-term-memory scenario, the validator's rejection cases.
`core` has no knowledge of any vendor. The provider travels on each request as an opaque string
read from the save, so adding a third would mean writing one class in `app/data/ai/` and touching
nothing in the engine.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## How memory works

Four layers, because one would either forget or cost a fortune:

| Layer | What it is | Sent to the model? |
|---|---|---|
| **Canonical state** | Current truth: money, inventory, health, location, who is alive | Always, compactly |
| **Event ledger** | Append-only, never rewritten. Every meaningful thing that happened | Never wholesale — a bounded, scored slice |
| **Semantic memories** | Compact durable lines, reinforced when repeated | The top ~14 by relevance |
| **Summaries** | Chapter and history digests | Up to 3 |
| **Obligations** | Promises, debts and deals — true whether or not anyone remembers them | All that are open and relevant |

Retrieval is **structured filter first, scoring second, hard budget last**, and none of the three
stages is proportional to campaign age. A test asserts this directly: across 249 turns, early
history stays reachable *while* the context stays bounded — either alone is easy and useless.

On top of that, the model is given the last few turns verbatim. Structured state alone cannot carry
a conversation, and asking a model to reconstruct one from a summary of it is what made players
repeat themselves.

See [docs/MEMORY.md](docs/MEMORY.md) and [docs/CONTINUITY.md](docs/CONTINUITY.md).

---

## Development

```bash
# Engine tests — fast, no emulator, no API key
./gradlew :core:test

# Android tests — Room on real SQLite via Robolectric, plus the secret scan
./gradlew :app:testDebugUnitTest

# Everything
./gradlew test
```

Tests never make a network call and never need a key: a deterministic `MockAIProvider` reads the
context it is given and responds to the player's verb, so the real pipeline is exercised end to end.

Requirements: JDK 17+ (built with 21), Android SDK with platform 35 and build-tools 35.0.0.
Put `sdk.dir=/path/to/android-sdk` in `local.properties`.

---

## Build

```bash
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/Unbound.apk`

```bash
adb install -r app/build/outputs/apk/release/Unbound.apk
```

The release build is minified and resource-shrunk. It is signed with the debug key so that
`assembleRelease` produces an installable artifact without a keystore; **replace the signing config
before distributing.** See [docs/BUILD.md](docs/BUILD.md).

---

## Where your data lives

| Thing | Where | Leaves the device? |
|---|---|---|
| Campaigns, NPCs, memories, ledger | Room database, app-private | No |
| Generated images | App-private files | No |
| Usage and cost estimates | Room, app-private | No — there is no upload path in the codebase |
| Each provider's API key | AES-256-GCM, key non-extractable in Android Keystore, one file and one alias per provider | Only as a request header — `Authorization` for OpenAI, `x-goog-api-key` for Gemini, never in a URL |
| Your prose and world state | — | Only to the provider you chose, as prompt context |

Save exports are portable JSON and **cannot** contain a credential: `SaveBundle` has no field for
one, and a test asserts the exported bytes contain nothing key-shaped.

See [docs/SECURITY.md](docs/SECURITY.md).

---

## Model selection

Model IDs are not hard-coded as the source of truth. UNBOUND calls `GET /v1/models` with your key
and classifies what comes back by id *family*, so a new model in a known family is recognised
without a code change. What cannot be discovered — whether a model supports strict structured
output — comes from a small, explicit, versioned table.

A model that cannot do what the game needs is **shown and explained**, never silently hidden and
never silently substituted. Each save stores its own model, so switching models affects future
turns only and never rewrites history.

See [docs/OPENAI.md](docs/OPENAI.md).

---

## Reading it

Narration is styled from the story's own structure rather than by guessing at the prose:

* **What you say** appears in the narration in your own colour. The model returns the
  protagonist's spoken lines as a separate field as well as writing them into the prose, so the
  renderer matches them exactly instead of parsing "you said" — attribution parsing gets it wrong
  in precisely the cases that matter.
* **What everyone else says** is distinguished more quietly.
* **Texts, emails, calls, letters, broadcasts** — anything arriving from outside the room — are
  italic and orange.

Both conventions degrade harmlessly: if the model forgets one, the prose is simply shown unstyled.
A property test asserts that no word of the story is ever lost to formatting, whatever the markup.

Narration length is Brief (100-300 words), Normal (200-600) or Long (400-1200), and applies to
every campaign.

## Cost

This is treated as a product requirement, not an afterthought.

* One model call per narrative turn. World creation, journal, status, inventory, save, load, undo,
  time arithmetic, NPC scheduling, rumor spread, faction action and thread decay are all
  deterministic local code and cost **nothing**.
* The stable half of the prompt is byte-identical between turns so provider prompt caching can hit;
  the usage screen reports the cache-hit ratio so you can see whether it is.
* The full transcript is never replayed. Retrieval is capped.
* Images are off unless requested, cached by prompt and appearance version, and never regenerated
  for an identical request.

See [docs/COST_OPTIMIZATION.md](docs/COST_OPTIMIZATION.md).

---

## Troubleshooting

**"OpenAI rejected the API key."** The key is wrong, revoked, or from a deleted project. Settings →
Replace.

**"The OpenAI account has no remaining credit."** Billing is in your OpenAI account; UNBOUND cannot
see or change it.

**No models can be used for storytelling.** Your key may only have access to older models. A story
model must support strict JSON-schema structured output.

**A turn failed.** Nothing was applied — the world is exactly as it was. Tap *Try again*; the retry
uses the same idempotency key, so it cannot double-charge you or duplicate anything.

**The app forgot my key after a device restore.** Keystore entries do not survive a restore by
design. The campaigns are intact; add the key again.

**Images say "no longer cached on this device."** The cache was cleared. The record is kept, so ask
for the picture again.

---

## Known limitations

Stated plainly rather than omitted — see the [final section of docs/TESTING.md](docs/TESTING.md) for
exactly what is and is not verified.

* **No emulator was available where this was built**, so no instrumented UI test has been executed.
  The Compose layer is compiled and lint-clean; the Room layer is tested on real SQLite via
  Robolectric; the engine is covered by 129 JVM tests, and `PlayerJourneyTest` walks the whole
  player path — build a world, pick an opening, play, read the journal, ask for a picture, step
  back, export, reopen — over real SQLite and the real app wiring. **UI behaviour has still not
  been observed running on a device**, which remains the largest gap.
* Prices in the model table are estimates and go stale. Token counts come from OpenAI and are real;
  the money figure is a local multiplication and is labelled as an estimate throughout.
* Semantic retrieval is lexical overlap, not embeddings. A `SemanticIndex` seam exists and is
  genuinely wired — supplying an index feeds similarity into the score — but no index ships,
  because embeddings add a per-turn cost on the player's own key.
* Worlds are seeded from six authored settings, or described by the player and built by the model,
  and then grow outward from the player.
* The release APK is signed with the debug key.
