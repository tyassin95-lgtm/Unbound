# OpenAI integration

All provider-specific knowledge lives in `app/data/ai/openai/`. The engine knows only
`AITextProvider`, `AIImageProvider`, `AIModelCatalog`, `AITextRequest/Response`, `AIUsage` and
`ModelProfile`.

**Verified against the official OpenAI OpenAPI specification (v2.3.0)** rather than from memory —
the spec was fetched and the exact request and response shapes read out of it before the client was
written.

## Endpoints used

### `POST /v1/responses`

```jsonc
{
  "model": "<player's choice>",
  "instructions": "<stable system prompt — byte-identical between turns>",
  "input": [{ "role": "user", "content": "<dynamic context>\n\n## PLAYER INPUT\n<action>" }],
  "max_output_tokens": 2400,
  "prompt_cache_key": "unbound-turn-v1",
  "store": false,
  "text": {
    "format": {
      "type": "json_schema",
      "name": "unbound_turn",
      "strict": true,
      "schema": { /* TurnSchema.schema() */ }
    }
  }
}
```

* `instructions` carries the stable half. That is what a prompt-prefix cache needs to hit.
* `prompt_cache_key` groups requests sharing that prefix. It is a constant, deliberately not derived
  from anything player-identifying.
* `store: false` — no conversation state is retained server-side. UNBOUND owns the state.

Response: `output_text` is preferred, with a fallback that walks `output[]` for `message` items and
their `output_text` content parts — both are documented, and the array form is what arrives when a
model also emits reasoning items. A `refusal` content part is surfaced as `CONTENT_REFUSED`. A
`status` of `incomplete` means the output hit the ceiling; the JSON will be truncated and the parse
fails cleanly rather than committing half a turn.

Usage is read from `input_tokens`, `output_tokens` and `input_tokens_details.cached_tokens`.

### `GET /v1/models`

Returns the ids this key can reach. See *Model discovery* below.

### `POST /v1/images/generations` and `POST /v1/images/edits`

When a canonical reference image exists, UNBOUND uses **`/images/edits` with
`input_fidelity: "high"`**, passing the stored reference as multipart. Editing the reference is what
actually preserves a face between portraits; generating from a text description alone does not.

GPT image models return `b64_json`. DALL·E models return a short-lived URL instead, which cannot be
cached on-device, so selecting one produces a clear `UNSUPPORTED_FEATURE` message rather than a
broken picture.

## Model discovery

Model IDs are not hard-coded as the source of truth. `GET /v1/models` with the player's key is the
truth about what they can reach.

What the endpoint *cannot* tell you is capability — it returns ids and nothing about structured
output, vision or pricing. So capability comes from a small, explicitly-versioned rule table over id
**families**, matched as prefixes:

```kotlin
STRUCTURED_OUTPUT_FAMILIES = ["gpt-6", "gpt-5", "gpt-4.1", "gpt-4o", "o4", "o3", "o1"]
REASONING_FAMILIES         = ["o4", "o3", "o1", "gpt-5-thinking", "gpt-6-thinking"]
```

A new dated or point release inside a known family classifies correctly with no code change. Audio,
embedding, moderation and legacy-completion models are filtered out entirely. Anything genuinely new
gets the conservative fallback and is shown to the player with its limitation stated.

Reasoning models reject a `temperature` parameter, so `TEMPERATURE` is withheld from their
capability set and the parameter is not sent.

### Choosing a model

A model must support **strict JSON-schema structured output** to be a story model. Models that
cannot are listed under *"Not usable for storytelling"* with the reason — never silently hidden, and
never silently substituted.

Each save stores its own `textModelId` and `imageModelId`. Changing the model affects future
requests only; history, state and memory are untouched.

## Credentials

The key is read from the Keystore per request, used to build one `Authorization` header, and never
cached in a field, never logged, and never placed in a URL or query parameter. See
[SECURITY.md](SECURITY.md).

## Error mapping

HTTP status plus the documented `error.code`/`error.type` map onto `AIErrorKind`:

| HTTP / code | Kind |
|---|---|
| 401 | `INVALID_KEY` |
| 403 `account_deactivated` | `REVOKED_KEY` |
| 429 `insufficient_quota` | `INSUFFICIENT_QUOTA` |
| 429 other | `RATE_LIMITED` (honours `Retry-After`) |
| 404 model | `MODEL_UNAVAILABLE` |
| 400 "does not support" | `UNSUPPORTED_FEATURE` |
| 400 content policy | `CONTENT_REFUSED` |
| 5xx | `SERVER_ERROR` |

Timeouts: 20 s connect, 180 s read. The read timeout is generous on purpose — a long narrative turn
on a reasoning model legitimately takes a while, and timing out early would fail a turn the player
has already been billed for.

## Adding another provider

Implement `AIProvider` in a new package and swap it in `AppContainer`. No engine change is required;
`MockAIProvider` already demonstrates that the seam holds.
