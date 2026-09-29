#!/usr/bin/env bash
# Runs the app's screenshot tests in the sandbox made by setup.sh.
# Usage: android/tools/sandbox/run.sh [SANDBOX_DIR] [extra gradle args, e.g. --tests '*PadsShot']
# Output: SANDBOX_DIR/screenshots/*.png
set -euo pipefail
SB=${1:-/opt/pyp6-sandbox}
shift || true
source "$SB/env.sh"

# Compile the app's and the AndroidX libraries' resources, link them into
# res.apk and generate R.java: the part AGP does in a real build. Redone on
# every run so changes to app/src/main/res are picked up.
R="$SB/res"
rm -rf "$R" && mkdir -p "$R/flat" "$R/gen" "$R/assets"
PKGS=""
for d in "$SB"/aar-res/*/; do
  d=${d%/}
  [[ -d "$d/res" && -n "$(find "$d/res" -type f | head -1)" ]] || continue
  "$AAPT2" compile --dir "$d/res" -o "$R/flat/$(basename "$d").zip"
  PKGS="$PKGS:$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | sed 's/package="//;s/"//')"
done
"$AAPT2" compile --dir "$ANDROID_DIR/app/src/main/res" -o "$R/flat/app.zip"
# The manifest as AGP would merge it: with the package and SDK levels.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.pyp6.app">\n    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />|' \
  "$ANDROID_DIR/app/src/main/AndroidManifest.xml" > "$R/AndroidManifest.xml"
"$AAPT2" link -o "$R/res.apk" -I "$ANDROID_JAR" --manifest "$R/AndroidManifest.xml" --java "$R/gen" \
  --extra-packages "${PKGS#:}" --auto-add-overlay "$R"/flat/*.zip

rm -rf "$SB/screenshots"
cd "$SB/project"
./gradlew :app:test --console=plain "$@"
ls "$SB/screenshots"
