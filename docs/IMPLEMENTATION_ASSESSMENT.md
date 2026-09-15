# UNBOUND — Implementation Assessment

Written before implementation, per the build specification's "environment audit first" requirement.

## 1. Environment audit (measured, not assumed)

| Item | Finding |
|---|---|
| Repository | `/home/user/Unbound`, git initialised, **completely empty** (no commits, no files). Greenfield. |
| OS | Linux 6.18 x86_64 |
| JDK | OpenJDK 21.0.10 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Gradle | 8.14.3 installed system-wide |
| Android SDK | **Not present initially.** Installed during audit to `/home/user/android-sdk`: cmdline-tools 11076708, platform-tools, `platforms;android-35`, `build-tools;35.0.0`, all licences accepted. |
| Network | HTTPS works through an agent proxy. `dl.google.com`, Google Maven and Maven Central all reachable and verified by probing actual artifact POMs. |
| Emulator / device | **None available.** No KVM, no attached device. Therefore *instrumented* Android tests cannot be executed here. |
| Disk | ~30 GB free — sufficient. |

### Consequence of the emulator gap
Everything that must be *proved* to work is placed where it can actually be executed: a pure
Kotlin/JVM module. Android-only code (Room, Keystore, Compose) is kept thin and is compiled and
lint-checked, with Room's schema exported and verified at build time. This is stated plainly
rather than papered over; see `docs/TESTING.md` for exactly what is and is not executed.

## 2. Version selection (verified reachable before being written into the build)

* Android Gradle Plugin 8.10.1, Gradle 8.14.3 (wrapper pinned)
* Kotlin 2.1.21, KSP 2.1.21-2.0.1, Compose compiler plugin (bundled with Kotlin 2.x)
* compileSdk/targetSdk 35, minSdk 26
* Room 2.7.1, Compose BOM 2025.05.00, Navigation Compose 2.8.9, Lifecycle 2.8.7
* OkHttp 4.12.0, kotlinx-serialization-json 1.8.1, kotlinx-coroutines 1.10.2
* androidx.security:security-crypto 1.1.0-alpha06 (API 26–32 fallback path only)

minSdk 26 is chosen deliberately: it is the first API level where `AndroidKeyStore` supports
AES/GCM symmetric keys, which is what the BYOK key storage is built on. Going lower would force a
materially weaker credential store.

## 3. Module decomposition and why

```
core/   pure Kotlin/JVM   domain, ledger, memory, knowledge, relationships,
                          threads, simulation, validator, prompt builder,
                          AI provider interfaces, mock provider, turn pipeline
app/    Android           Room implementation of core's store interfaces,
                          Keystore-backed secret store, OpenAI provider, Compose UI
```

The engine is deliberately **not** an Android module. It depends on no Android class, so the
100-turn campaign test, the long-term-memory test, the validator tests and the retrieval-bounds
tests all run as ordinary fast JVM tests with no emulator. `core` also has no knowledge of OpenAI
and no knowledge of Compose, satisfying the specification's clean-boundary rules structurally
rather than by convention.

## 4. Decisions taken where the specification left room

| Ambiguity | Decision | Rationale |
|---|---|---|
| Vector DB for memory retrieval | **Not used.** Deterministic relational filter + weighted scoring. | Spec §27/§139 explicitly prefer this; it is debuggable and testable, and embeddings would add per-turn API cost. A `SemanticIndex` seam exists for later. |
| OpenAI API surface | **Responses API** (`POST /v1/responses`) with `text.format = json_schema`, `strict: true`. Models via `GET /v1/models`, images via `POST /v1/images/generations`. | Current OpenAI surface; all provider assumptions are isolated in `app/ai/openai` behind `AIProvider`. |
| Key storage | Android Keystore AES-256-GCM, ciphertext in a private-mode file. | Raw key never reaches Room, DataStore, SharedPreferences, saves, or logs. |
| Structured output vs tool calls | Structured output (JSON schema). | Deterministic to validate; no round-trip cost. |
| Turn atomicity | Single Room transaction with optimistic locking on `stateVersion` + idempotency key on the turn. | Satisfies §44/§45/§46 without a queue. |
| Background NPC simulation | Deterministic local Kotlin, zero AI calls. | §39/§91 — cost is a primary requirement. |
| Model catalogue | Fetched live from the account, filtered by a local capability table, with a static fallback list when offline. | §6 forbids hard-coding today's model names as the only source of truth. |

## 5. Build order

Audit → Gradle skeleton that compiles → domain + ledger → memory + knowledge → relationships +
threads → validator → simulation → prompt + pipeline (JVM-tested against the mock provider) →
Room → Keystore → OpenAI provider → Compose UI → usage/diagnostics → security + tests → release APK.

Compilation is run after each subsystem rather than once at the end.
