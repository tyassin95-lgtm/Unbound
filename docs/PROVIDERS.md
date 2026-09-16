# Providers

UNBOUND runs on OpenAI or on Google Gemini. You need one key, not both.

## The shape

```
TurnPipeline  ──  holds a single AIProvider, and has never heard of a vendor
     │
     │  request carries providerId: an opaque string read from the save
     ▼
ProviderRegistry.routing()
     ├── OpenAIProvider   (Responses API, json_schema strict)
     └── GeminiProvider   (generateContent, responseSchema)
```

The engine never interprets the provider id. It reads one off the save and passes it through. That
is what keeps the database, memories, events, knowledge, saves and world logic independent of whose
model is running — and it is asserted directly: `PlayerJourneyTest` plays the same world on both
and requires the resulting state, journal and event count to be identical.

## Parity

Both providers get the **same assembled context, the same schema and the same budget**. Only the
envelope differs. A provider that received a thinner context would produce a worse world and the
player would reasonably blame the model rather than the app.

The engine owns one turn schema and it is sent to both providers essentially unchanged. Gemini's
`responseSchema` takes a subset of standard JSON Schema — lowercase `type`, `properties`,
`required`, `items`, `enum`, `description`, `additionalProperties` — which is what the engine
already produces.

This was got wrong first time. The original implementation translated into the older
OpenAPI-flavoured `Schema` proto (uppercase `"STRING"`, `propertyOrdering`, `format: "enum"`,
`nullable`), which is no longer the documented dialect. That turned a valid schema into one the
service would not compile, and **world generation failed with a 500 before a single world could be
built**. Only two things are adjusted now: `$schema` and friends are dropped, and a
`["string","null"]` union collapses to `"string"` — the field is already absent from `required`, so
the union carries no meaning here.

The lesson is in the code as a comment: translating a schema is a liability, and the less of it
done the better.

If a schema is rejected anyway — the docs warn that "very large or deeply nested schemas may be
rejected", and world generation sends the largest one this app has — the request is retried once
with the schema moved into the prompt and `responseMimeType` still set to JSON. Deliberately narrow:
only for failures that indicate the schema was the problem, never for a refusal, a rejected key or
a rate limit, which would only fail again differently. What is *accepted* does not loosen — the
response goes through the same parser and the same validator.

## Credentials

Each provider has its **own ciphertext file and its own non-extractable Keystore key**, so revoking
or replacing one cannot disturb or reveal the other. OpenAI keeps the original file name and alias
so an existing player's key survives the upgrade.

Gemini's key is sent in the `x-goog-api-key` **header**. Google's own quickstarts show `?key=...`
in the URL; this app does not, because a query parameter ends up in proxy logs, history and crash
reports. `SecretScanTest` enforces that for both clients.

## Model discovery

Both catalogues are fetched from the account rather than hard-coded. Model names change faster than
any table in this repository could, so a baked-in list would be stale before it shipped. Gemini's
capabilities come from `supportedGenerationMethods` where the API states them, and from the model
family only where it does not. The static tables exist solely so the settings screen can show
something honest while offline, and models that cannot return a strict structured response are
shown as unusable with the reason — never silently hidden, never silently substituted.

## Fallback

Opt-in, and never silent. It covers only a provider being **unreachable**: rate limits, outages,
timeouts, dropped connections. A refusal, a rejected key, an exhausted account or a missing model
surfaces instead — sending content one vendor refused to another is laundering, not resilience, and
the rest would fail identically twice. When a stand-in does write a turn, the player is told in the
log, because a different narrator changes how the game reads.

## Cost

Usage is recorded and priced **per provider**. Two vendors ship models with overlapping names, so
pooling on the bare model id would price one with the other's rates. Prices are estimates, labelled
as such; each vendor's own console is authoritative.

Gemini's free tier is treated as a real option and a real limit: `RESOURCE_EXHAUSTED` is told apart
from an exhausted billing quota, and the message says which it was and what to do about it.
