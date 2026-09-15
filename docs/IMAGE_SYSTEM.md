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

## Asking for a picture

The image button sits beside the input bar — asking for a picture is something you do *about the
moment you are in*, so it belongs next to that moment rather than buried in a menu. It opens a
sheet built from canonical state **on every turn**, so what it offers is what is actually true now:

| Option | What it draws |
|---|---|
| This moment | The scene as narrated: this place, this light, everyone in it, doing what they are doing |
| What is passing between you | Close on the faces and hands of the people present |
| *(the location's name)* | The place itself, empty, as it stands right now |
| A face | Your own portrait, or any character currently in the room |

The "moment" text is the last narrative, trimmed to a sentence boundary — a whole page would cost
more and bury the thing actually happening.

Consistency works differently for a group shot than for a portrait. A scene cannot be an edit of
one canonical reference, so identity is carried by **text**: every character contributes the same
identity clause used for their own portrait, verbatim. That is why the clause is assembled from
structured facts in a fixed order — it has to survive being reused in three different prompts
without drifting. Scenes are never themselves canonical: they depict an event, not an identity.

Only one picture is generated at a time, and the button disables itself while a request is in
flight rather than queueing, because each one is a separate charge.

## Resolution

`image of Mara` resolves against canonical entities *before* generating anything — the picture is of
the character the database knows, not a fresh invention. If nothing matches, the game says so rather
than drawing a stranger.
