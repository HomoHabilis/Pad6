# Building the Android app locally

## Requirements

| What | Version | Notes |
|---|---|---|
| JDK | 17 or newer (CI uses 21) | `java -version` |
| Android SDK | platform **37** (compileSdk), build-tools and platform-tools | targetSdk is 36, minSdk 30 |
| Network | `dl.google.com` / `maven.google.com` **and** Maven Central | AGP and AndroidX live only on Google Maven |

Gradle (9.7.1), AGP (9.3.2) and Kotlin (2.4.20) are fetched by the wrapper: do not install them.

## Setup

**Option A - Android Studio:** open the `android/` folder, let it install the SDK it asks for (API 37), done.

**Option B - command line only:**

```sh
# 1. SDK command-line tools: https://developer.android.com/studio#command-line-tools-only
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
unzip commandlinetools-*.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest

# 2. SDK packages + licenses (AGP refuses to build without accepted licenses)
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37" "platform-tools"

# 3. Tell Gradle where the SDK is (or keep ANDROID_HOME exported)
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

## Build, test, install

All from `android/`:

```sh
./gradlew :core:test                 # Kotlin core vs. desktop golden data (no SDK needed, see below)
./gradlew :app:assembleRelease       # -> app/build/outputs/apk/release/app-release.apk (signed, R8)
./gradlew :app:testDebugUnitTest     # Robolectric screenshots -> app/build/screenshots/*.png
./gradlew :app:installDebug          # phone with USB debugging on, via adb
```

APKs are signed with the checked-in `app/sideload.jks` (debug and release), so any build installs over any other.

Optional: `-Ppyp6.versionName=1.2.3 -Ppyp6.versionCode=42` stamps the version (CI takes it from `git describe`).

## Pitfalls (already handled in the build - don't "fix" them back)

- **AGP is on the root `buildscript` classpath** (`build.gradle.kts`), not in `plugins {}` of settings. Otherwise the Kotlin
  plugin and AGP load in different classloaders and configuration fails with
  `Could not generate a decorated class for type KotlinAndroidTarget > com/android/build/gradle/api/BaseVariant`.
- **`google()` is content-filtered** to `com.android.*`, `com.google.*`, `androidx.*` (`settings.gradle.kts`), so Kotlin
  artifacts are never looked up there.
- **compileSdk must be 37**: current Compose/Lifecycle/Navigation fail `checkReleaseAarMetadata` on 36.
- **Screenshot tests run one JVM per test class** (`forkEvery = 1`): Compose binds global state to the first test's
  main looper, so reusing a JVM gives stale or blank screenshots. They need ~3 GB heap each; about 5 min on CI.
- The first Robolectric run downloads `android-all-instrumented` (~200 MB) from Maven Central into `~/.m2`.

## Core only (no Android SDK, no Google Maven)

The `core/` module is plain Kotlin/JVM. This skips `:app` and AGP entirely:

```sh
./gradlew -Ppyp6.coreOnly=true :core:test
```

Reference data: `tools/make_golden.py` regenerates `core/src/test/resources/golden/`, `tools/extract_resources.py`
re-extracts the wavetables and blank pattern from the desktop script (both need the desktop app's Python deps).

## No access to Google Maven (sandboxes, restricted CI)

`:app` cannot be built without `maven.google.com`. Options, easiest first:

1. **Let GitHub build it:** push the branch; the **Android** workflow (`.github/workflows/android.yml`) runs all of the
   above and uploads the `android` (APK) and `android-screenshots` artifacts. Also runnable via *Run workflow*.
2. **Core only**, as above.
3. **Screenshots without AGP** (what was done while porting; fragile): a plain Kotlin/JVM Gradle project that compiles
   `app/src/main/java` + `app/src/test/java` against `platforms/android-37/android.jar` and AndroidX classes jars
   extracted from the AARs, with a hand-generated `R` class, Robolectric pointed at the resources through
   `test_config.properties`, and `-Drobolectric.offline=true -Drobolectric.dependency.dir=<dir with android-all jar>`.
   Only worth it when option 1 is impossible.
