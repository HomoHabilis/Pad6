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
3. **Screenshot tests without AGP:** the sandbox below. This is how the UI was checked while porting.

## Screenshot-test sandbox (no Google Maven)

`tools/sandbox/` runs the app's Robolectric screenshot tests (`app/src/test/.../ScreenshotTest.kt`) in a plain
Kotlin/JVM Gradle project, with every Google-Maven-only piece taken from somewhere else:

| Normally from Google | Sandbox source |
|---|---|
| AGP (compiles resources, generates `R`, writes Robolectric's `test_config.properties`) | `aapt2` run by `run.sh`; the properties file is written by `setup.sh` |
| `aapt2`, `android.jar` (platform 36) | layers of the public Docker image `cimg/android` pulled with `curl` (no Docker needed) |
| AndroidX / Compose AARs | Xamarin.AndroidX NuGet packages, which embed the original AARs (latest versions, not the exact pinned ones) |
| `androidx.test:monitor` (needed by Robolectric) | compiled from source (`github.com/android/android-test`, sparse clone) |
| Robolectric's Android runtime download | `android-all-instrumented` jar from Maven Central, used with `robolectric.offline` |

Needs JDK 17+, `python3`, `curl`, `git`, `unzip`, and access to Docker Hub, NuGet, GitHub and Maven Central
(check with `curl -sI https://registry-1.docker.io/v2/ https://api.nuget.org/v3/index.json`).

```sh
android/tools/sandbox/setup.sh /opt/pyp6-sandbox     # once: ~3 GB download (mostly Docker layers), ~600 MB kept, ~4 min
android/tools/sandbox/run.sh   /opt/pyp6-sandbox     # all 22 screens, ~6 min -> /opt/pyp6-sandbox/screenshots/*.png
android/tools/sandbox/run.sh   /opt/pyp6-sandbox --tests '*PadsShot'   # one screen
```

`setup.sh` skips steps whose output exists (delete the folder to redo one) and rewrites the Gradle files every time;
`run.sh` recompiles the resources on every run, so edits to `app/src/main/res` are picked up. The project compiles the
repo's sources in place: edit the app, re-run `run.sh`, look at the PNGs.

What it does not do: build an APK, run R8, or check AAR metadata/`compileSdk` - that is still only the real build
(CI). Known quirks it works around:

- Robolectric downloads its runtime from Maven Central on first use and got HTTP 429 there; hence the offline jar.
- `androidx.test:monitor`'s two `ExposedInstrumentationApi` classes have the same simple name, so they are compiled
  as separate modules (`monitorhidden`, `monitorruntime`).
- Test classes are listed with `include("**/*Shot.class")` and class scanning off; otherwise Gradle finds no tests
  in the abstract base classes.
- `forkEvery = 1`, as in the real build (see Pitfalls).
