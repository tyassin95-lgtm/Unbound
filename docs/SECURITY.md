# Security

## Threat model

The realistic risks for a local-first BYOK app are: the credential leaking into a backup, a log, a
save file or the APK; and a copied database file exposing it. Not: an attacker with root on an
unlocked device, which no client-side measure addresses.

## Credential storage

`SecureCredentialStore` (`app/data/security/`):

* AES-256-GCM. The encryption key is generated **inside the Android Keystore** and is
  **not extractable** — the app can ask the Keystore to decrypt but can never read the key, so the
  ciphertext file is useless to anything that copies it off the device.
* Ciphertext lives in one app-private file, written atomically so an interrupted write cannot leave
  a half-file that decrypts to garbage.
* `minSdk 26` is chosen for this: it is the first API level where `AndroidKeyStore` supports AES/GCM
  symmetric keys. Going lower would force a materially weaker store.
* `setUserAuthenticationRequired(false)` — deliberate. Requiring a biometric prompt for every turn
  would make the game unusable, and the threat model is a copied file or a shared backup, not an
  unlocked device in the owner's hands.
* Excluded from cloud backup and device transfer via `res/xml/data_extraction_rules.xml`, plus
  `allowBackup="false"`.
* Clearing overwrites the file before unlinking, so ciphertext is not left recoverable in free
  blocks.
* If the Keystore entry is gone (data cleared, device restored), decryption fails, the store clears
  itself, and the UI prompts cleanly for a new key rather than failing every request with a
  decryption error.

## Where the key is not

Enumerated, and enforced:

| Location | Status |
|---|---|
| Room / SQLite | never — no column exists |
| DataStore / SharedPreferences | never — `AppSettings` holds choices only |
| Save exports | **structurally impossible** — `SaveBundle` has no field for one |
| Logs | `SafeLog` redacts key-shaped text before anything reaches logcat |
| `BuildConfig`, XML, assets, APK resources | verified by test |
| Source control | verified by test |
| Analytics | there is no analytics |

There is exactly **one** function that returns plaintext (`readKey()`), and exactly one caller: the
code that builds the `Authorization` header.

## Display

Once stored, the whole key is never shown again — not even to its owner. `mask()` yields
`sk-pro••••••••••••••••BBBB`: enough to tell two keys apart, not enough to reconstruct one. A test
asserts at most 12 non-mask characters survive.

## Logging

`SafeLog.redact` strips, before anything is emitted:

* `sk-…` keys
* `Bearer …` tokens
* `"authorization": …` and `"api_key": …` assignments

Debug logging is additionally compiled out of release builds. A test verifies redaction works on all
four forms and does not mangle ordinary prose.

## Automated verification

`SecretScanTest` — five tests, run in CI as part of `:app:testDebugUnitTest`:

1. **Source scan.** Walks every `.kt`, `.kts`, `.java`, `.xml`, `.json`, `.properties`, `.toml`,
   `.pro`, `.txt`, `.md` file under `app/src`, `core/src`, `gradle/` and the root build files for
   six secret patterns (OpenAI keys, project keys, bearer tokens, hardcoded key assignments, AWS
   keys, PEM private key blocks).
2. **BuildConfig scan.** Reflects over the generated class and fails on any field whose name
   contains key/secret/token/password/credential.
3. **APK scan.** Opens the built artifact and reads **every zip entry's bytes**, including
   `classes.dex` and `resources.arsc`, against the same patterns. Skips with a printed message if no
   APK has been built, rather than passing silently.
4. **Log redaction.**
5. **Masking.**

Test fixtures use obviously fake placeholders. No real key exists anywhere in this repository.

## Network

* HTTPS only, to `api.openai.com`. No certificate pinning: it would break corporate TLS inspection
  for no gain against this threat model, and the failure mode would be a game that mysteriously
  stops working.
* No cleartext traffic (`targetSdk 35` default).
* The key is only ever a request *header* — never a URL or query parameter, where it could reach a
  proxy log.
* Two permissions total: `INTERNET` and `ACCESS_NETWORK_STATE`.

## Content safeguards

Three enforced in code, not left to model behaviour (see `core/safety/ContentGuard.kt`):

1. The protagonist cannot be created under 18 — enforced at construction of the new-game request, so
   it cannot be bypassed by any UI path.
2. Every character carries an explicit integer age. A character introduced without one is discarded,
   so "ambiguous age" can never become "assumed adult".
3. If any character present in a scene is a minor, a hard constraint **naming them and their age**
   is injected into that turn's context — not a general reminder, which is far easier for a model to
   read past.

Deliberately not done: classifying the player's input or the model's prose as sexual. That would be
unreliable in both directions and would make the game refuse ordinary adult fiction.

## Data the player controls

* Removing the key erases it; campaigns are untouched and remain fully readable offline.
* Deleting a save cascades through every table.
* Export produces portable JSON with no credential.
* Nothing is uploaded anywhere. There is no server and no account.
