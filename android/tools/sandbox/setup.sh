#!/usr/bin/env bash
# Sets up the screenshot-test sandbox: runs the app's Robolectric screenshot
# tests WITHOUT Google Maven (dl.google.com / maven.google.com), e.g. in a
# network-restricted container. Needs: JDK 17+, python3, curl, git, unzip,
# and access to Docker Hub, NuGet, GitHub and Maven Central.
#
# Where the pieces come from instead of Google Maven:
#   aapt2 + android.jar   -> the public Docker image cimg/android (layers pulled with curl, no Docker needed)
#   AndroidX classes/res  -> Xamarin.AndroidX NuGet packages (they embed the original AARs)
#   androidx.test monitor -> built from source (github.com/android/android-test)
#   Robolectric runtime   -> android-all-instrumented jar from Maven Central, used offline
#
# Usage: android/tools/sandbox/setup.sh [SANDBOX_DIR]     (default /opt/pyp6-sandbox)
# Then:  android/tools/sandbox/run.sh [SANDBOX_DIR]       -> SANDBOX_DIR/screenshots/*.png
# Each step is skipped when its output already exists; delete the directory to redo it.
set -euo pipefail

SB=${1:-/opt/pyp6-sandbox}
HERE=$(cd "$(dirname "$0")" && pwd)
ANDROID_DIR=$(cd "$HERE/../.." && pwd)

# Pinned inputs. The image tag decides the SDK; the platform/build-tools must exist in it.
IMAGE=cimg/android
IMAGE_TAG=2026.08.1
PLATFORM=android-36
BUILD_TOOLS=36.0.0
# Must match Robolectric's version (4.16) and the sdk in ScreenshotTest's @Config (35).
ANDROID_ALL=15-robolectric-13954326-i7
# Top-level NuGet packages; their Xamarin dependencies are pulled in too.
NUGET_PACKAGES="xamarin.androidx.compose.material3 xamarin.androidx.compose.ui xamarin.androidx.compose.foundation
  xamarin.androidx.activity.compose xamarin.androidx.lifecycle.viewmodel.compose xamarin.androidx.navigation.compose
  xamarin.androidx.compose.material.icons.extended xamarin.androidx.documentfile
  xamarin.androidx.lifecycle.runtime.compose xamarin.kotlinx.coroutines.android"

mkdir -p "$SB"
echo "Sandbox: $SB"

# --- 1. aapt2 + android.jar from the cimg/android image ----------------------
if [[ ! -f "$SB/sdk/platforms/$PLATFORM/android.jar" || ! -x "$SB/sdk/build-tools/$BUILD_TOOLS/aapt2" ]]; then
  echo "== SDK pieces from $IMAGE:$IMAGE_TAG (downloads the image layers, ~3 GB, keeps ~35 MB)"
  mkdir -p "$SB/sdk"
  python3 - "$IMAGE" "$IMAGE_TAG" "$SB/sdk" "$PLATFORM" "$BUILD_TOOLS" <<'EOF'
import json, subprocess, sys, urllib.request
image, tag, out, platform, bt = sys.argv[1:]
def token():
    u = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{image}:pull"
    return json.load(urllib.request.urlopen(u))["token"]
def get(path, accept):
    r = urllib.request.Request(f"https://registry-1.docker.io/v2/{image}/{path}",
                               headers={"Authorization": "Bearer " + token(), "Accept": accept})
    return json.load(urllib.request.urlopen(r))
idx = get(f"manifests/{tag}", "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json")
if "manifests" in idx:
    d = next(m["digest"] for m in idx["manifests"] if m.get("platform", {}).get("architecture") == "amd64")
    idx = get(f"manifests/{d}", "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json")
root = "home/circleci/android-sdk"
wanted = [f"{root}/platforms/{platform}", f"{root}/build-tools/{bt}"]
for layer in idx["layers"]:
    url = f"https://registry-1.docker.io/v2/{image}/blobs/{layer['digest']}"
    print(f"  layer {layer['digest'][7:19]} {layer['size'] / 1e6:.0f} MB", flush=True)
    # Stream each layer through tar; only the two wanted folders are written.
    curl = subprocess.Popen(["curl", "-sfL", "--retry", "5", "-H", "Authorization: Bearer " + token(), url], stdout=subprocess.PIPE)
    subprocess.run(["tar", "xzf", "-", "-C", out, "--strip-components=3", "--wildcards", "--no-anchored", "--ignore-failed-read",
                    *[w + "/*" for w in wanted]], stdin=curl.stdout, stderr=subprocess.DEVNULL)
    curl.wait()
EOF
  test -f "$SB/sdk/platforms/$PLATFORM/android.jar" || { echo "android.jar not found in $IMAGE:$IMAGE_TAG"; exit 1; }
fi
ANDROID_JAR="$SB/sdk/platforms/$PLATFORM/android.jar"
AAPT2="$SB/sdk/build-tools/$BUILD_TOOLS/aapt2"

# --- 2. AndroidX from NuGet ---------------------------------------------------
if [[ ! -d "$SB/androidx-jars" ]]; then
  echo "== AndroidX AARs from NuGet (~60 MB)"
  mkdir -p "$SB/nuget"
  (cd "$SB/nuget" && python3 - $NUGET_PACKAGES <<'EOF'
import io, json, os, re, sys, time, urllib.request, zipfile
BASE = "https://api.nuget.org/v3-flatcontainer"
seen = {}
def get(u):
    for i in range(6):
        try: return urllib.request.urlopen(u, timeout=60).read()
        except Exception: time.sleep(2 * (i + 1))
    raise RuntimeError(u)
def fetch(p):
    p = p.lower()
    if p in seen: return
    ver = [v for v in json.loads(get(f"{BASE}/{p}/index.json"))["versions"] if "-" not in v][-1]
    seen[p] = ver
    z = zipfile.ZipFile(io.BytesIO(get(f"{BASE}/{p}/{ver}/{p}.{ver}.nupkg")))
    deps = set()
    for n in z.namelist():
        if n.endswith(".nuspec"):
            deps |= set(re.findall(r'<dependency id="([^"]+)"', z.read(n).decode("utf-8", "replace")))
        if n.endswith(".aar") or (n.endswith(".jar") and ("/jar/" in n.lower() or n.startswith("jar"))):
            os.makedirs("libs", exist_ok=True)
            open(os.path.join("libs", p + "__" + os.path.basename(n)), "wb").write(z.read(n))
    for d in deps:
        if d.lower().startswith("xamarin."): fetch(d)
for p in sys.argv[1:]: fetch(p)
json.dump(seen, open("versions.json", "w"), indent=1, sort_keys=True)
print(f"  {len(seen)} packages")
EOF
  )
  mkdir -p "$SB/androidx-jars.tmp" "$SB/aar-res"
  for f in "$SB"/nuget/libs/*.aar; do
    n=$(basename "${f%.aar}")
    unzip -p "$f" classes.jar > "$SB/androidx-jars.tmp/$n.jar" 2>/dev/null || true
    mkdir -p "$SB/aar-res/$n" && unzip -q -o "$f" 'res/*' AndroidManifest.xml -d "$SB/aar-res/$n" 2>/dev/null || true
  done
  cp "$SB"/nuget/libs/*.jar "$SB/androidx-jars.tmp/" 2>/dev/null || true
  find "$SB/androidx-jars.tmp" -size 0 -delete
  mv "$SB/androidx-jars.tmp" "$SB/androidx-jars"
fi

# --- 3. androidx.test monitor from source ------------------------------------
if [[ ! -d "$SB/android-test" ]]; then
  echo "== androidx.test monitor sources (GitHub)"
  git clone -q --depth 1 --filter=blob:none --sparse https://github.com/android/android-test.git "$SB/android-test"
  git -C "$SB/android-test" sparse-checkout set runner/monitor espresso/idling_resource
fi

# --- 4. Robolectric's Android runtime (used offline: Robolectric's own download gets rate-limited) ---
if [[ ! -f "$SB/robolectric-deps/android-all-instrumented-$ANDROID_ALL.jar" ]]; then
  echo "== android-all-instrumented $ANDROID_ALL (~200 MB, Maven Central)"
  mkdir -p "$SB/robolectric-deps"
  curl -sfL --retry 5 -o "$SB/robolectric-deps/android-all-instrumented-$ANDROID_ALL.jar" \
    "https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$ANDROID_ALL/android-all-instrumented-$ANDROID_ALL.jar"
fi

# --- 5. The Gradle project (plain Kotlin/JVM, no AGP) ------------------------
P="$SB/project"
echo "== Gradle project in $P"
mkdir -p "$P/app/src/test/resources/com/android/tools" "$P/monitor" "$P/monitorhidden" "$P/monitorruntime"
cp -r "$ANDROID_DIR/gradlew" "$ANDROID_DIR/gradle" "$ANDROID_DIR/gradle.properties" "$P/"
AT="$SB/android-test/runner/monitor/java"
DEPS="    compileOnly(files(\"$ANDROID_JAR\"))
    compileOnly(fileTree(\"$SB/androidx-jars\") { include(\"*.jar\") })"

cat > "$P/settings.gradle.kts" <<EOF
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "pyp6-sandbox"
include(":core")
project(":core").projectDir = file("$ANDROID_DIR/core")
include(":app", ":monitor", ":monitorhidden", ":monitorruntime")
EOF

cat > "$P/build.gradle.kts" <<'EOF'
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
EOF

# androidx.test:monitor, split in three: the two ExposedInstrumentationApi
# classes share a simple name and are compiled on their own.
cat > "$P/monitorhidden/build.gradle.kts" <<EOF
plugins { java }
sourceSets["main"].java { srcDir("$AT"); include("androidx/test/internal/runner/hidden/ExposedInstrumentationApi.java") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
$DEPS
}
EOF
sed 's|internal/runner/hidden/|internal/runner/runtime/|' "$P/monitorhidden/build.gradle.kts" > "$P/monitorruntime/build.gradle.kts"

cat > "$P/monitor/build.gradle.kts" <<EOF
plugins { id("org.jetbrains.kotlin.jvm") }
val excluded = listOf("**/internal/runner/hidden/ExposedInstrumentationApi.java", "**/internal/runner/runtime/ExposedInstrumentationApi.java",
    "**/ActivityInvoker\\\$\\\$CC.java", "**/ActivityInvokerDesugar.java")
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("$AT")
}
sourceSets["main"].java { srcDirs("$AT", "$SB/android-test/espresso/idling_resource/java"); exclude(excluded) }
sourceSets["main"].kotlin.exclude(excluded)
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    compileOnly(project(":monitorhidden"))
$DEPS
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
EOF

cat > "$P/app/build.gradle.kts" <<EOF
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("$ANDROID_DIR/app/src/main/java")
    sourceSets["test"].kotlin.srcDir("$ANDROID_DIR/app/src/test/java")
}
// R.java, generated by run.sh with aapt2
sourceSets["main"].java.srcDir("$SB/res/gen")
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
val android = files("$ANDROID_JAR")
val androidx = fileTree("$SB/androidx-jars") { include("*.jar") }
dependencies {
    implementation(project(":core"))
    compileOnly(android)
    compileOnly(androidx)
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(android)
    testImplementation(androidx)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16") {
        exclude(group = "androidx.test")          // only on Google Maven: built from source instead
        exclude(group = "androidx.test.espresso")
    }
    testImplementation(project(":monitor"))
    testImplementation(project(":monitorruntime"))
    testImplementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
}
testing { suites { named<JvmTestSuite>("test") { useJUnit("4.13.2") } } }
tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
    forkEvery = 1                 // one JVM per screen, as in the real build
    maxParallelForks = 3
    isScanForTestClasses = false
    include("**/*Shot.class")
    systemProperty("pyp6.screenshots", "$SB/screenshots")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", "$SB/robolectric-deps")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
EOF

# Robolectric reads the merged manifest and compiled resources from here
# (AGP normally writes this file).
cat > "$P/app/src/test/resources/com/android/tools/test_config.properties" <<EOF
android_merged_manifest=$SB/res/AndroidManifest.xml
android_resource_apk=$SB/res/res.apk
android_merged_assets=$SB/res/assets
android_custom_package=io.github.pyp6.app
EOF

cat > "$SB/env.sh" <<EOF
ANDROID_DIR="$ANDROID_DIR"
ANDROID_JAR="$ANDROID_JAR"
AAPT2="$AAPT2"
EOF
echo "Done. Run: $HERE/run.sh $SB"
