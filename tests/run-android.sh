#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"
TEST_BUILD="$PROJECT_DIR/build/tests"
mkdir -p "$TEST_BUILD/classes" "$TEST_BUILD/dex"
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$PROJECT_DIR/tests/AndroidManifest.xml" -o "$TEST_BUILD/unsigned.apk"
javac --release 17 -cp "$ANDROID_JAR:$PROJECT_DIR/build/classes" \
    -d "$TEST_BUILD/classes" "$PROJECT_DIR/tests/GroupSummaryTests.java"
find "$TEST_BUILD/classes" -name '*.class' > "$TEST_BUILD/sources.txt"
"$TOOLS/d8" --min-api 34 --lib "$ANDROID_JAR" --classpath "$PROJECT_DIR/build/classes" \
    --output "$TEST_BUILD/dex" @"$TEST_BUILD/sources.txt"
cp "$TEST_BUILD/dex/classes.dex" "$TEST_BUILD/classes.dex"
(cd "$TEST_BUILD" && zip -q -u unsigned.apk classes.dex)
"$TOOLS/zipalign" -f -p 4 "$TEST_BUILD/unsigned.apk" "$TEST_BUILD/aligned.apk"
if [ -n "${RELEASE_KEYSTORE:-}" ]; then
    if [ -z "${HYPERAI_SIGNING_PASSWORD:-}" ]; then
        HYPERAI_SIGNING_PASSWORD="$(/usr/bin/security find-generic-password \
            -s "${RELEASE_KEYCHAIN_SERVICE:-hyper-ai-third-party-mcp.release-keystore}" -a "$USER" -w)"
    fi
    export HYPERAI_SIGNING_PASSWORD
    trap 'unset HYPERAI_SIGNING_PASSWORD' EXIT
    "$TOOLS/apksigner" sign --ks "$RELEASE_KEYSTORE" --ks-key-alias "${RELEASE_KEY_ALIAS:-finall}" \
        --ks-pass env:HYPERAI_SIGNING_PASSWORD --key-pass env:HYPERAI_SIGNING_PASSWORD \
        --out "$TEST_BUILD/tests.apk" "$TEST_BUILD/aligned.apk"
    unset HYPERAI_SIGNING_PASSWORD
else
    "$TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
        --key-pass pass:android --out "$TEST_BUILD/tests.apk" "$TEST_BUILD/aligned.apk"
fi
adb install --no-incremental -r "$TEST_BUILD/tests.apk"
adb shell pm grant io.github.finall1008.hyperainotif android.permission.POST_NOTIFICATIONS
adb shell am instrument -w "$@" io.github.finall1008.hyperainotif.tests/io.github.finall1008.hyperainotif.GroupSummaryTests \
    | tee "$TEST_BUILD/result.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$TEST_BUILD/result.txt"
