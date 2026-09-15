# Build

## Requirements

| Tool | Version used | Notes |
|---|---|---|
| JDK | 21 | 17+ works; toolchain targets 17 bytecode |
| Gradle | 8.14.3 | wrapper included |
| Android Gradle Plugin | 8.10.1 | |
| Kotlin | 2.1.21 | + KSP 2.1.21-2.0.1, Compose compiler plugin |
| Android SDK | platform 35, build-tools 35.0.0 | |
| minSdk / targetSdk | 26 / 35 | 26 is required for Keystore AES/GCM |

Every dependency version in `gradle/libs.versions.toml` was verified against its real POM before
being written into the catalog — not copied from a tutorial.

## Setup

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

If the SDK is not installed:

```bash
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip -d cmdline-tools
mv cmdline-tools/cmdline-tools cmdline-tools/latest
yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=$PWD --licenses
cmdline-tools/latest/bin/sdkmanager --sdk_root=$PWD \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## Commands

```bash
./gradlew :core:test              # engine tests, no SDK needed
./gradlew :app:testDebugUnitTest  # Room + security tests
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release APK
./gradlew :app:lintRelease        # static checks
```

## Outputs

| Artifact | Path | Size |
|---|---|---|
| Release APK | `app/build/outputs/apk/release/Unbound.apk` | ~1.9 MB |
| Debug APK | `app/build/outputs/apk/debug/Unbound-debug.apk` | ~20 MB |
| R8 mapping | `app/build/outputs/mapping/release/mapping.txt` | keep it to deobfuscate crashes |
| Room schema | `app/schemas/com.unbound.rpg.data.db.UnboundDatabase/1.json` | commit on every schema change |

Install:

```bash
adb install -r app/build/outputs/apk/release/Unbound.apk
```

## Release build

`isMinifyEnabled` and `isShrinkResources` are both on. The ProGuard rules keep what reflection needs:

* `kotlinx.serialization` generated `$$serializer` classes and `Companion` objects
* Room's generated `_Impl` classes
* OkHttp/Okio warnings suppressed

Verified in the produced artifact rather than assumed: 4,001 classes, 20,362 methods, 40 serializers
and 19 Room DAO implementations survive R8, and every engine class is present in the mapping file.

## Signing

The release build is signed with the **debug key**, so `assembleRelease` produces an installable
artifact without a keystore. **Replace this before distributing:**

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("UNBOUND_KEYSTORE"))
        storePassword = System.getenv("UNBOUND_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("UNBOUND_KEY_ALIAS")
        keyPassword = System.getenv("UNBOUND_KEY_PASSWORD")
    }
}
buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
```

Credentials come from the environment, never from a file in the repository — `SecretScanTest` fails
the build if anything key-shaped is committed.

## Verifying an artifact

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/release/Unbound.apk
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/Unbound.apk
./gradlew :app:testDebugUnitTest --tests '*SecretScanTest*'   # scans the built APK's bytes
```
