# Images

Optional, off by default, and never able to break a turn.

## Consistency

The problem: ask an image model twice for "a weather-worn smuggler" and you get two different
people. UNBOUND solves it with two mechanisms together.

### 1. A stable identity clause

`ImagePromptBuilder` assembles prompts from canonical structured data in a fixed order, with no
poetry:

```
Character portrait. A 31 year old man. broken nose, olive skin, black cropped hair,
dark brown eyes, wiry build, scar: burn on the left forearm, wearing oilskin coat,
carries a short gutting knife. Setting: Dark Industrial Fantasy, the eleventh year
of the Combine, Ashmarket. Realistic proportions, neutral lighting, plain background.
```

The identity clause is **byte-identical** between a character's first portrait and their fiftieth.
Only the situational clause changes. A test asserts this directly.

Age is stated first and explicitly, because the image model needs it as much as the text model does.

### 2. Reference-preserving edits

The first image of a subject becomes its **canonical reference**. Every later image passes those
stored bytes to `POST /v1/images/edits` with `input_fidelity: "high"`, so the model is modifying an
existing face rather than inventing a new one from a description.

Locations work identically: the first image fixes architecture, layout and visual identity; later
images depict the same place under different light, weather and condition.

## Appearance versions

When appearance genuinely changes — *"She cut her hair"* — the pipeline:

1. writes an `APPEARANCE_CHANGE` state op,
2. increments `Appearance.version`,
3. emits an event recording both the new and the previous description, so history is preserved
   rather than rewritten, and
4. invalidates the canonical reference for that version.

The next image creates a **new** canonical reference at the new version. Older images stay valid for
the version they depict. Covered by test: a haircut produces a different cache key and a bumped
version.

## Caching

Cached on `(prompt, appearanceVersion)` with the file present on disk. An identical request is never
paid for twice. Records store entity id, generation id, local path, prompt, model, timestamp,
canonical flag, appearance version and visual traits.

**Settings → Storage → Clear cached images** deletes the files but keeps the records, so the game
still knows which pictures existed and can make them again on request.

## Cost control

* `DISABLED` — no pictures at all.
* `ON_DEMAND` — the default. Only when the player types `image of X`.
* `AUTOMATIC_IMPORTANT` — also for scenes the model flags significant. Requires opt-in.

Never one image per turn, under any setting.

## Failure

An image failure **never** fails the turn. The narrative was already committed by the time
generation runs; a failure produces a note, records the failed usage row, and the request can be
retried independently.

## Resolution

`image of Mara` resolves against canonical entities *before* generating anything — the picture is of
the character the database knows, not a fresh invention. If nothing matches, the game says so rather
than drawing a stranger.
